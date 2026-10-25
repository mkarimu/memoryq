package com.vstream.Vidiserver;

import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts everything on one shared LiveChannelRegistry:
 *   8080  viewer channel list, /watch, sign-in API, channel pool, chat, news and the admin page
 *   9120  authorization for MediaMTX (127.0.0.1 only)
 * Settings (all optional):
 *   -Dvstream.usersFile=users.txt   -Dvstream.claimsFile=claims.txt   -Dvstream.chatFile=chat.txt
 *   -Dvstream.newsFile=news.txt     -Dvstream.poolSize=10             -Dvstream.claimTtlHours=48
 *   -Dvstream.claimWarnHours=12     -Dvstream.guestIdleDays=30        -Dvstream.newsMinutes=30
 *   -Dvstream.newsDailyCap=200      -Dvstream.newsPerSource=5         -Dvstream.newsModel=gpt-5-nano
 *   -Dvstream.profilesFile=profiles.txt  -Dvstream.profilerFirst=5  -Dvstream.profilerMax=50
 *   -Dvstream.profilerMinutes=60    -Dvstream.adsFile=ads.txt         -Dvstream.adsDir=ads
 *   -Dvstream.wallFile=wall.txt     -Dvstream.wallDir=wall            -Dvstream.testPostMinutes=60
 * The OpenAI key is read from the OPENAI_API_KEY environment variable.
 */
public class VideoServerMain {

    public static void main(String[] args) throws Exception {
        LiveChannelRegistry registry = new LiveChannelRegistry();

        UserStore users = new UserStore(
                Paths.get(System.getProperty("vstream.usersFile", "users.txt")),
                registry, MediaMtxClient::kickPublisherAsync);
        String adminPassword = users.init(Paths.get("streamkeys.txt"));

        long ttlHours = Long.getLong("vstream.claimTtlHours", 48L);
        long warnHours = Long.getLong("vstream.claimWarnHours", 12L);
        int poolSize = Integer.getInteger("vstream.poolSize", 10);
        long guestIdleDays = Long.getLong("vstream.guestIdleDays", 30L);
        ClaimStore claims = new ClaimStore(
                Paths.get(System.getProperty("vstream.claimsFile", "claims.txt")),
                registry, MediaMtxClient::kickPublisherAsync,
                ttlHours * 3600000L, warnHours * 3600000L, System::currentTimeMillis);
        claims.init(101, poolSize);

        ChatStore chat = new ChatStore(
                Paths.get(System.getProperty("vstream.chatFile", "chat.txt")), System::currentTimeMillis);
        chat.init();
        Runtime.getRuntime().addShutdownHook(new Thread(chat::flushIfDirty));

        OpenAiSummarizer openAi = OpenAiSummarizer.fromEnvironment();
        NewsService.Summarizer summarizer = new ChainSummarizer(openAi, new ExtractiveSummarizer(),
                System::currentTimeMillis, Integer.getInteger("vstream.newsAiDailyCap", 200));
        long newsMinutes = Long.getLong("vstream.newsMinutes", 30L);
        NewsService news = new NewsService(
                Paths.get(System.getProperty("vstream.newsFile", "news.txt")), System::currentTimeMillis,
                new SafeFetcher(), summarizer,
                Integer.getInteger("vstream.newsDailyCap", 1000), Integer.getInteger("vstream.newsPerSource", 5));
        news.init();

        SessionStore sessions = new SessionStore();
        // sign-ins survive restarts and updates ("remember me for 30 days"); only fingerprints are kept
        sessions.persistTo(Paths.get(System.getProperty("vstream.sessionsFile", "sessions.txt")));
        Runtime.getRuntime().addShutdownHook(new Thread(sessions::flushIfDirty));
        Restreamer restreamer = new Restreamer(Paths.get(System.getProperty("vstream.restreamFile", "restream.txt")),
                sessions, users, claims);
        restreamer.init();
        MediaAuthHttpServer authServer = new MediaAuthHttpServer(registry);
        // who went live where, with what title: the "Recent streams" list names the person behind each recording
        StreamHistory streamHistory = new StreamHistory(Paths.get(System.getProperty("vstream.streamHistoryFile", "stream-history.txt")),
                System::currentTimeMillis);
        streamHistory.init();
        // every stream with its length (kept for good): streamers' totals and loyalty, moderation, news, e-mails
        StreamLog streamLog = new StreamLog(Paths.get(System.getProperty("vstream.streamLogFile", "stream-log.txt")),
                System::currentTimeMillis);
        streamLog.init();
        authServer.setPublishListener(channel -> {
            claims.touch(channel);
            restreamer.onPublish(channel);
            try {
                String who = null;
                for (ClaimStore.PoolEntry pe : claims.pool()) {
                    if (pe.channelId.equals(channel)) {
                        who = pe.holder;
                    }
                }
                LiveChannel ch = registry.getChannel(channel);
                if (who == null && ch != null && ch.getBroadcasterId() != null && !ch.getBroadcasterId().startsWith("preset-")) {
                    who = ch.getBroadcasterId();
                }
                streamHistory.started(channel, who == null ? "" : who, ch == null || ch.getTitle() == null ? "" : ch.getTitle());
                streamLog.started(channel, who == null ? "" : who, ch == null || ch.getTitle() == null ? "" : ch.getTitle());
            } catch (RuntimeException e) {
                System.out.println("[streams] " + e);
            }
        });
        authServer.start();

        ChannelListHttpServer listServer = new ChannelListHttpServer(registry);
        listServer.setChannelFilter(channel -> MediaMtxClient.isChannelLive(channel.getChannelId()));
        AccountApi api = new AccountApi(users, sessions, new Limiter(), registry,
                Paths.get(System.getProperty("vstream.adminPage", "admin.html")),
                MediaMtxClient::kickPublisherAsync, MediaMtxClient::isChannelLive);
        api.setClaims(claims);
        api.setRestreamer(restreamer);
        TvApi tv = new TvApi(listServer::liveChannels, listServer.getViewerTracker()::count, listServer::extraFor,
                System::currentTimeMillis);
        api.setTv(tv);
        tv.start();
        EmailService emails = new EmailService(Paths.get(System.getProperty("vstream.emailsFile", "emails.txt")),
                users, Mailer.fromSystemProperties(), System::currentTimeMillis);
        emails.init();
        Runtime.getRuntime().addShutdownHook(new Thread(emails::flushIfDirty));
        api.setEmail(emails, !"false".equals(System.getProperty("vstream.requireEmail", "true")));
        listServer.setAccountApi(api);
        ChatApi chatApi = new ChatApi(chat, users, sessions, registry, MediaMtxClient::isChannelLive);
        ModerationService moderation = new ModerationService(
                Paths.get(System.getProperty("vstream.moderationFile", "moderation.txt")), System::currentTimeMillis,
                chat, users, sessions, (name, reason) -> claims.releaseByUser(name, reason));
        moderation.init();
        chatApi.setModeration(moderation);
        api.setModeration(moderation);
        listServer.setChatApi(chatApi);
        NewsApi newsApi = new NewsApi(news, users, sessions);
        listServer.setNewsApi(newsApi);
        CallService callService = new CallService(
                Paths.get(System.getProperty("vstream.callsFile", "calls.txt")), System::currentTimeMillis,
                chat, users, Boolean.getBoolean("vstream.callsGuestsMayStart"));
        callService.init();
        authServer.setCallAuth(callService::authorize);
        listServer.setCallApi(new CallApi(callService, chat, users, sessions));
        ProfileStore profiles = new ProfileStore(
                Paths.get(System.getProperty("vstream.profilesFile", "profiles.txt")));
        profiles.init();
        Runtime.getRuntime().addShutdownHook(new Thread(profiles::flushIfDirty));
        ProfilerService profiler = new ProfilerService(profiles, users, System::currentTimeMillis,
                new java.security.SecureRandom(), Integer.getInteger("vstream.profilerFirst", 5),
                Integer.getInteger("vstream.profilerMax", 50),
                Long.getLong("vstream.profilerMinutes", 60L) * 60000L);
        NotificationStore notes = new NotificationStore(
                Paths.get(System.getProperty("vstream.notificationsFile", "notifications.txt")));
        notes.init();
        Runtime.getRuntime().addShutdownHook(new Thread(notes::flushIfDirty));
        ProfileApi profileApi = new ProfileApi(profiles, profiler, users, sessions, new Limiter(),
                name -> moderation.deleteAccount("admin", name), System::currentTimeMillis);
        profileApi.setNotifications(notes, callService);
        listServer.setProfileApi(profileApi);
        AdStore adStore = new AdStore(Paths.get(System.getProperty("vstream.adsFile", "ads.txt")),
                Paths.get(System.getProperty("vstream.adsDir", "ads")));
        adStore.init();
        Runtime.getRuntime().addShutdownHook(new Thread(adStore::flushIfDirty));
        api.setAdFree(adStore::adFree); // the team's accounts never get Google ads
        listServer.setFeedApi(new FeedApi(adStore, profiles, users, sessions, new Limiter(),
                System::currentTimeMillis, new java.security.SecureRandom()));
        WallStore wall = new WallStore(Paths.get(System.getProperty("vstream.wallFile", "wall.txt")),
                Paths.get(System.getProperty("vstream.wallDir", "wall")));
        wall.init();
        Runtime.getRuntime().addShutdownHook(new Thread(wall::flushIfDirty));
        WallService wallService = new WallService(wall, profiles, users, System::currentTimeMillis,
                new java.security.SecureRandom(), Long.getLong("vstream.testPostMinutes", 60L) * 60000L);
        WallApi wallApi = new WallApi(wall, profiles, users, sessions, chat, new Limiter(),
                System::currentTimeMillis, new java.security.SecureRandom());
        wallApi.setBlocks(callService);
        wallApi.setNotifications(notes);
        wallService.setNotifications(notes);
        listServer.setNotifyApi(new NotifyApi(notes, profiles, users, sessions));
        listServer.setWallApi(wallApi);
        RadioStore radioStore = new RadioStore(Paths.get(System.getProperty("vstream.radioFile", "radio.txt")),
                Paths.get(System.getProperty("vstream.radioDir", "radio-music")));
        radioStore.init();
        Runtime.getRuntime().addShutdownHook(new Thread(radioStore::flushIfDirty));
        AudioTools audioTools = new AudioTools(Paths.get(System.getProperty("vstream.toolsDir", "tools")),
                Paths.get(System.getProperty("vstream.radioTmp", "radio-tmp")));
        audioTools.setOpenAi(System.getenv("OPENAI_API_KEY"), System.getProperty("vstream.radioVoiceModel", "gpt-4o-mini-tts"));
        audioTools.detect();
        restreamer.setFfmpeg(() -> audioTools.ready() ? audioTools.ffmpegPath() : null);
        tv.setFfmpeg(() -> audioTools.ready() ? audioTools.ffmpegPath() : null);
        RadioService radio = new RadioService(radioStore, audioTools, news, chat, profiles,
                RadioWriter.OpenAi.fromEnvironment(), System::currentTimeMillis, new java.security.SecureRandom());
        RadioStories radioStories = new RadioStories(Paths.get(System.getProperty("vstream.radioStoriesDir", "radio-stories")));
        try {
            radioStories.init();
        } catch (java.io.IOException e) {
            System.out.println("Radio stories could not be read: " + e.getMessage());
        }
        radio.setStories(radioStories);
        // what songs are about: Alma tells a song's message before or after it (OpenAI listens to each song once)
        SongMessages songMessages = new SongMessages(Paths.get(System.getProperty("vstream.songMessagesFile", "radio-messages.txt")),
                radioStore, audioTools, RadioWriter.OpenAi.fromEnvironment(), System::currentTimeMillis,
                Integer.getInteger("vstream.songMessagesPerDay", 300));
        songMessages.init();
        radio.setSongMessages(songMessages);
        RadioStats radioStats = new RadioStats(Paths.get(System.getProperty("vstream.radioStatsFile", "radio-stats.txt")),
                System::currentTimeMillis);
        radioStats.init();
        Runtime.getRuntime().addShutdownHook(new Thread(radioStats::flushIfDirty));
        radio.setStats(radioStats);
        GeoIp geoIp = new GeoIp(Paths.get(System.getProperty("vstream.geoFile", "geo/dbip-country-lite.csv.gz")));
        geoIp.start();
        RadioListenLog listenLog = new RadioListenLog(Paths.get(System.getProperty("vstream.radioLogDir", "radio-logs")),
                System::currentTimeMillis, geoIp);
        listenLog.init();
        Runtime.getRuntime().addShutdownHook(new Thread(listenLog::closeAll));
        radio.setListenLog(listenLog);
        radio.setLiveEnabled(!Boolean.getBoolean("vstream.noLiveDj"));
        RadioApi radioApi = new RadioApi(radio, users, sessions, System::currentTimeMillis,
                Paths.get(System.getProperty("vstream.radioTmp", "radio-tmp")));
        String publicHost = System.getProperty("vstream.publicHost", System.getProperty("vstream.mediaHost", "memoryq.com"));
        radioApi.setLiveHost(publicHost);
        radioApi.setMail(Mailer.fromSystemProperties(), user -> {
            EmailService.Entry e = emails.get(user);
            return e != null && e.verified ? e.email : null;
        }, System.getProperty("vstream.adminEmail", "support@memoryq.com"));
        RadioBilling radioBilling = new RadioBilling(Paths.get(System.getProperty("vstream.billingFile", "billing.txt")));
        radioBilling.init();
        radioApi.setBilling(radioBilling);
        radioApi.setEmails(emails);
        radioApi.oldLinks().start(); // old Centova / Azuracast listening addresses play the moved stations
        radioApi.setClaims(claims);
        radioApi.djPro().setSettingsFile(Paths.get(System.getProperty("vstream.djproFile", "djpro.txt")));
        radioApi.djPro().setDjTokenFile(Paths.get(System.getProperty("vstream.djproSessionsFile", "djpro-sessions.txt")));
        radioApi.startBillingTimer();
        listServer.setRadioApi(radioApi);
        // "Try the Studio": a radio station anyone may run with a public login; back to its starting point every 2 hours
        DemoRadio demoRadio = new DemoRadio(radio, users, sessions, System::currentTimeMillis,
                Paths.get(System.getProperty("vstream.demoFile", "demo-radio.txt")),
                Paths.get(System.getProperty("vstream.demoDir", "demo-radio")));
        demoRadio.init();
        radioApi.setDemo(demoRadio);
        api.addModule(demoRadio::register);
        listServer.setGate(demoRadio::wrap);
        demoRadio.start();
        // Vstream "Try it now - no account needed" for Play Store testers (admin switches it on, with an end date)
        QuickStart quickStart = new QuickStart(users, sessions, System::currentTimeMillis,
                Paths.get(System.getProperty("vstream.quickFile", "vstream-quick.txt")));
        quickStart.init();
        api.addModule(quickStart::register);
        ScheduledExecutorService callTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "call-timer");
            t.setDaemon(true);
            return t;
        });
        callTimer.scheduleWithFixedDelay(() -> {
            try {
                callService.tick();
            } catch (RuntimeException e) {
                System.out.println("[calls] timer error: " + e);
            }
        }, 5, 5, TimeUnit.SECONDS);
        // videos live on the video disk (/srv/mkarimu, made by setup-disks.sh) when it is there
        java.nio.file.Path videoDir = MediaDisks.videoDir();
        MediaDisks.moveVideos(Paths.get("videos"), videoDir);
        VideoStore videoStore = new VideoStore(Paths.get(System.getProperty("vstream.videosFile", "videos.txt")),
                Paths.get(System.getProperty("vstream.videoCommentsFile", "video-comments.txt")), videoDir);
        videoStore.init();
        VideoApi videoApi = new VideoApi(videoStore, users, sessions, System::currentTimeMillis);
        videoApi.setTools(() -> audioTools.ready() ? audioTools.ffmpegPath() : null,
                () -> audioTools.ready() ? audioTools.ffprobePath() : null);
        videoApi.startCleanup();
        api.setVideos(videoApi);
        Recordings recordings = new Recordings(MediaDisks.recordingsDir(), videoApi, users, sessions, System::currentTimeMillis);
        recordings.setHistory(streamHistory);
        recordings.setLog(streamLog);
        Mailer streamMailer = Mailer.fromSystemProperties();
        StreamersApi streamers = new StreamersApi(streamLog, users, sessions, System::currentTimeMillis)
                .recordings(recordings).videos(videoStore).history(streamHistory).emails(emails)
                .kick(MediaMtxClient::kickPublisherAsync)
                .mail((to, subject, text) -> streamMailer.send(to, subject, text), user -> {
                    EmailService.Entry e = emails.get(user);
                    return e != null && e.verified ? e.email : null;
                }, System.getProperty("vstream.adminEmail", "support@memoryq.com"),
                        System.getProperty("vstream.publicHost", System.getProperty("vstream.mediaHost", "memoryq.com")))
                .alertsFile(Paths.get(System.getProperty("vstream.streamAlertsFile", "stream-alerts.txt")));
        api.addModule(streamers::register);
        streamers.start(MediaMtxClient::isChannelLive);
        newsApi.setPinned(streamers::liveNews);
        // the members' marketplace: listings wait for the admin's approval; buyers and sellers talk in messages
        MarketStore market = new MarketStore(Paths.get(System.getProperty("vstream.marketFile", "market.txt")),
                Paths.get(System.getProperty("vstream.marketPhotos", "market-photos")));
        try {
            market.init();
        } catch (java.io.IOException e) {
            System.out.println("[market] could not read the marketplace: " + e.getMessage());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(market::flushIfDirty));
        MarketApi marketApi = new MarketApi(market, users, sessions, System::currentTimeMillis).notifications(notes)
                .mail((to, subject, text) -> streamMailer.send(to, subject, text), user -> {
                    EmailService.Entry e = emails.get(user);
                    return e != null && e.verified ? e.email : null;
                }, System.getProperty("vstream.adminEmail", "support@memoryq.com"),
                        System.getProperty("vstream.publicHost", System.getProperty("vstream.mediaHost", "memoryq.com")));
        api.addModule(marketApi::register);
        // the super-admin edits the website's words in place (the pencil on each part of the page)
        java.nio.file.Path pagesDir = Paths.get(System.getProperty("vstream.adminPage", "admin.html")).toAbsolutePath().getParent();
        SiteContent siteContent = new SiteContent(Paths.get(System.getProperty("vstream.siteContentFile", "site-content.txt")),
                pagesDir == null ? Paths.get(".") : pagesDir, sessions, users);
        siteContent.init();
        api.setSiteContent(siteContent);
        api.addModule(siteContent::register);
        // which services each member sees (Admin -> Profiler -> Services)
        ServiceAccess services = new ServiceAccess(Paths.get(System.getProperty("vstream.servicesFile", "services.txt")), users, sessions);
        services.init();
        api.setServices(services::json);
        api.addModule(services::register);
        // one sign-in everywhere: Google, and handing the sign-in to the Vistr game and the Memoryq app
        SignInApi signIn = new SignInApi(Paths.get(System.getProperty("vstream.signinFile", "signin.txt")), users, sessions, emails, api.issuer(),
                System::currentTimeMillis);
        signIn.init();
        api.setGoogleOf(signIn::googleOf);
        api.addModule(signIn::register);
        // Profiler -> Vistr players (every game player gets a Memoryq account) and editing any member's whole profile
        VistrImport vistrImport = new VistrImport(Paths.get(System.getProperty("vstream.vistrImportFile", "vistr-import.txt")), users, sessions,
                profiles, emails, services, signIn, System::currentTimeMillis);
        vistrImport.init();
        api.addModule(vistrImport::register);
        // Admin -> Mail: email to @memoryq.com (Postfix hands it over on 127.0.0.1:2525), what Memoryq sends, and the Marketplace
        MailCenter mailCenter = new MailCenter(Paths.get(System.getProperty("vstream.mailDir", "mail")), sessions, System::currentTimeMillis)
                .mailer(streamMailer).market(market, notes);
        try {
            mailCenter.init();
        } catch (java.io.IOException e) {
            System.out.println("[mail] could not read the mail centre: " + e.getMessage());
        }
        Mailer.sentLog = m -> mailCenter.logSystem(m[0], m[1], m[2]);
        mailCenter.startSmtp(Integer.getInteger("vstream.mailInPort", 2525));
        api.addModule(mailCenter::register);
        // World Radio (memoryq.com/world): stations from all over the world on a spinning globe; members add their own
        WorldRadio world = new WorldRadio(Paths.get(System.getProperty("vstream.worldFile", "world-radio.txt")), users, sessions,
                System::currentTimeMillis);
        world.init();
        world.setAi(RadioWriter.OpenAi.fromEnvironment());
        world.setOwnHosts(System.getProperty("vstream.publicHost", ""), System.getProperty("vstream.mediaHost", ""), "memoryq.com", "www.memoryq.com",
                "mkarimu.net", "www.mkarimu.net");
        Runtime.getRuntime().addShutdownHook(new Thread(world::flushIfDirty));
        api.addModule(world::register);
        // the station finder bot: Radio Browser + Wikidata daily, the Icecast directory weekly, every stream checked nightly
        world.setMemoryqStations(radioStore::stations, "https://" + System.getProperty("vstream.publicHost", "memoryq.com"));
        radioApi.setWorldSync(world::syncMemoryq);
        WorldBot worldBot = new WorldBot(world, geoIp::country);
        worldBot.startSchedule();
        ScheduledExecutorService marketTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-save");
            t.setDaemon(true);
            return t;
        });
        marketTimer.scheduleWithFixedDelay(market::flushIfDirty, 1, 1, TimeUnit.MINUTES);
        api.addModule(recordings::register);
        recordings.start();
        BackupJob backup = new BackupJob(Paths.get("."), MediaDisks.backupRoot(), videoDir,
                Paths.get(System.getProperty("vstream.radioDir", "radio-music")), System::currentTimeMillis);
        api.addModule(server -> backup.register(server, sessions, users, () -> {
            java.util.List<Recordings.Rec> rs = recordings.list();
            long b = 0;
            for (Recordings.Rec r : rs) {
                b += r.bytes;
            }
            return rs.size() + " live recording(s), " + MediaDisks.gb(b) + " (kept 30 days; the oldest go first when the video disk is "
                    + Recordings.FULL + "% full)";
        }));
        if (backup.available()) {
            backup.start();
        }
        final long backupStarted = System.currentTimeMillis();
        api.setUserView(new AdminUserView(users, sessions).profiles(profiles).emails(emails).moderation(moderation)
                .claims(claims, MediaMtxClient::isChannelLive).restreamer(restreamer).radio(radioStore)
                .videos(videoStore).wall(wall).chat(chat));
        // ---- Alma (the website assistant), downloads, and the SuperAgent ----
        DownloadApi downloads = new DownloadApi(Paths.get(System.getProperty("vstream.downloadsDir", "downloads")), sessions,
                System::currentTimeMillis);
        Alma alma = new Alma(sessions, Paths.get(System.getProperty("vstream.almaLog", "alma-log.txt")), System::currentTimeMillis);
        alma.setOpenAi(System.getenv("OPENAI_API_KEY"), System.getProperty("vstream.almaModel", "gpt-5-nano"), null);
        alma.setLiveFacts(() -> {
            StringBuilder f = new StringBuilder();
            f.append("- Live on Memoryq TV right now: ").append(listServer.liveChannels().size()).append(" stream(s).\n");
            StringBuilder on = new StringBuilder();
            int n = 0;
            for (RadioStore.Station st : radioStore.stations()) {
                if (st.enabled && n < 10) {
                    on.append(n++ > 0 ? ", " : "").append(st.name);
                }
            }
            f.append("- Radio stations: ").append(n == 0 ? "none on air" : on).append(".\n");
            f.append("- Apps ready to download at https://memoryq.com/download: ").append(downloads.available()).append(".\n");
            return f.toString();
        });
        java.util.function.Supplier<java.util.Map<String, Object>> vistr = SuperAgent.remoteStatus(
                System.getProperty("vstream.vistrStatus", "https://vistr.memoryq.com/api/agent/status"));
        SuperAgent agent = new SuperAgent(Paths.get("."), Paths.get(System.getProperty("vstream.assetsFile", "SERVERASSETS.md")),
                Paths.get(System.getProperty("vstream.agentSnapshot", "superagent-snapshot.txt")), sessions, System::currentTimeMillis);
        agent.fact("Accounts", () -> {
            int all = 0, off = 0, bc = 0;
            for (UserStore.Info i : users.listInfos()) {
                all++;
                if (i.disabled) {
                    off++;
                }
                if (i.role == UserStore.Role.BROADCASTER) {
                    bc++;
                }
            }
            return all + " (" + bc + " broadcasters, " + off + " disabled or suspended)";
        }).fact("Videos", () -> {
            int all = 0, tvn = 0, failed = 0;
            for (VideoStore.Video v : videoStore.list(v -> true)) {
                all++;
                if (v.featured) {
                    tvn++;
                }
                if (VideoStore.FAILED.equals(v.status)) {
                    failed++;
                }
            }
            return all + " (" + tvn + " on Memoryq TV" + (failed > 0 ? ", " + failed + " failed to convert" : "") + ")";
        }).fact("Radio stations", () -> {
            int all = 0, on = 0;
            for (RadioStore.Station st : radioStore.stations()) {
                all++;
                if (st.enabled) {
                    on++;
                }
            }
            return all + " (" + on + " switched on)";
        }).fact("Chat groups", () -> String.valueOf(chat.listGroups().size()))
                .fact("Alma", () -> alma.askedToday() + " questions today, answers with " + (alma.aiReady() ? "OpenAI" : "built-in answers (no OpenAI key)"))
                .fact("Downloads", downloads::available)
                .fact("FFmpeg", () -> audioTools.ready() ? "ready" : "NOT installed")
                .fact("Video disk (Mkarimu TV, mkarimu.net)", () -> MediaDisks.spaceLine(videoDir) + " - " + videoDir.toAbsolutePath())
                .fact("Backup disk", () -> backup.available() ? MediaDisks.spaceLine(MediaDisks.backupRoot()) + "; last backup "
                        + BackupJob.when(backup.lastRun()) + ": " + backup.lastMessage() : "not there (setup-disks.sh)")
                .fact("Live recordings", () -> {
                    java.util.List<Recordings.Rec> rs = recordings.list();
                    long b = 0;
                    for (Recordings.Rec r : rs) {
                        b += r.bytes;
                    }
                    return MediaDisks.recordingsDir() == null ? "off (no video disk)" : rs.size() + " (" + MediaDisks.gb(b) + ", kept 30 days)";
                })
                .fact("Word game (vistr.memoryq.com)", () -> SuperAgent.vistrLine(vistr.get()))
                .counter("Live channels", () -> listServer.liveChannels().size())
                .counter("People watching live", () -> {
                    int v = 0;
                    for (LiveChannel c : listServer.liveChannels()) {
                        v += listServer.getViewerTracker().count(c.getChannelId());
                    }
                    return v;
                })
                .counter("Videos being prepared", () -> videoStore.list(v -> VideoStore.PROCESSING.equals(v.status)).size())
                .check(() -> audioTools.ready() ? null : "FFmpeg is not installed - radio, video uploads and thumbnails cannot work")
                .check(() -> MediaDisks.usedPercent(videoDir) > Recordings.FULL ? "The video disk is " + MediaDisks.usedPercent(videoDir)
                        + "% full - old live recordings are being removed; grow the disk or delete videos" : null)
                .check(() -> backup.available() && MediaDisks.usedPercent(MediaDisks.backupRoot()) > 90 ? "The backup disk is "
                        + MediaDisks.usedPercent(MediaDisks.backupRoot()) + "% full - videos or songs may not be backed up" : null)
                .check(() -> backup.available() && System.currentTimeMillis() - Math.max(backup.lastOk(), backupStarted) > 36L * 3600000L
                        ? "No good backup for more than a day (last: " + BackupJob.when(backup.lastOk()) + ") - " + backup.lastMessage() : null)
                .check(() -> vistr.get() == null ? "The word game (vistr.memoryq.com) does not answer - on the VPS: systemctl status vistr" : null)
                .check(() -> {
                    long failed = videoStore.list(v -> VideoStore.FAILED.equals(v.status) && v.created > System.currentTimeMillis() - 86400000L).size();
                    return failed > 0 ? failed + " video upload(s) failed to convert today - see Admin -> Videos -> All videos" : null;
                });
        api.addModule(downloads::register);
        api.addModule(alma::register);
        api.addModule(agent::register);
        agent.start();
        listServer.start();

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                claims.tick(MediaMtxClient::isChannelLive);
            } catch (RuntimeException e) {
                System.out.println("[claims] timer error: " + e);
            }
        }, 1, 1, TimeUnit.MINUTES);
        timer.scheduleWithFixedDelay(() -> {
            try {
                int lifted = moderation.tick();
                if (lifted > 0) {
                    System.out.println("[moderation] lifted " + lifted + " suspension(s) whose time was up");
                }
            } catch (RuntimeException e) {
                System.out.println("[moderation] timer error: " + e);
            }
        }, 1, 1, TimeUnit.MINUTES);
        timer.scheduleWithFixedDelay(() -> {
            try {
                profiler.tick();
                adStore.flushIfDirty();
                wallService.tick();
            } catch (RuntimeException e) {
                System.out.println("[profiler] timer error: " + e);
            }
        }, 10, 60, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> {
            try {
                radio.tick();
            } catch (RuntimeException e) {
                System.out.println("[radio] timer error: " + e);
            }
        }, 3, 30, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> {
            try {
                chat.flushIfDirty();
            } catch (RuntimeException e) {
                System.out.println("[chat] timer error: " + e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> {
            try {
                java.util.List<String> gone = users.purgeIdleGuests(
                        System.currentTimeMillis(), guestIdleDays * 24L * 3600000L);
                for (String g : gone) {
                    chat.removeUserEverywhere(g);
                }
                if (!gone.isEmpty()) {
                    System.out.println("[accounts] Removed " + gone.size() + " idle guest account(s)");
                }
            } catch (RuntimeException e) {
                System.out.println("[accounts] timer error: " + e);
            }
        }, 1, 60, TimeUnit.MINUTES);
        // News runs on its own thread because a refresh can take minutes.
        ScheduledExecutorService newsTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "news-timer");
            t.setDaemon(true);
            return t;
        });
        newsTimer.scheduleWithFixedDelay(() -> {
            try {
                String result = news.refreshAll();
                if (result.startsWith("added") && !result.startsWith("added 0")) {
                    System.out.println("[news] " + result);
                }
            } catch (RuntimeException e) {
                System.out.println("[news] timer error: " + e);
            }
        }, 2, newsMinutes, TimeUnit.MINUTES);

        System.out.println();
        if (adminPassword != null) {
            System.out.println("=== FIRST START: ADMIN ACCOUNT ===");
            System.out.println("Username: admin");
            System.out.println("Password: " + adminPassword);
            System.out.println("Copy it now. It is not shown again (AdminPasswordReset makes a new one).");
            System.out.println("==================================");
        }
        System.out.println("Channel pool:  " + poolSize + " channels, unused for " + ttlHours
                + " hours -> back to the pool");
        System.out.println("Chat:          rooms for every live channel + public groups");
        System.out.println(summarizer.aiReady()
                ? "News:          AI summaries ON (model " + summarizer.model() + "), every " + newsMinutes + " min"
                : "News:          built-in summarizer ON (set OPENAI_API_KEY to add AI summaries)");
        System.out.println("Profiler:      " + (profiles.profilerOn()
                ? "ON - test people: " + profiles.testCount() + " of " + profiler.max()
                : "paused") + " (admin page: pause, resume or add one)");
        System.out.println("Radio:         " + audioTools.note() + ", " + radioStore.stations().size()
                + " station(s) (admin page: Radio)");
        System.out.println("Admin page:    http://localhost:" + listServer.getPort() + "/admin");
        System.out.println("Channel list:  http://localhost:" + listServer.getPort() + "/channels");
        System.out.println("Press Ctrl+C to stop.");

        Thread.currentThread().join();
    }
}