# Memoryq - SERVERASSETS

The development log and inventory of everything that runs under memoryq.com. The parts above the
"Live status" line are kept by people (edit them in Admin -> Server -> SERVERASSETS). The parts below it
are kept by the SuperAgent: it checks every deploy for what changed, writes it into the change log, and
refreshes the live status every 5 minutes.

## Assets

### memoryq.com - main web server
- VPS 162.253.130.252 (Ubuntu), HTTPS by Caddy, Java 8+ server (Vidiserver), MediaMTX for live video
  (RTMP 1935, HLS at /hls, WebRTC/WHIP at /webrtc), FFmpeg, Piper voices.
- Deployed from the PC with scripts\deploy-vps.ps1 (update scripts in scripts\apply-*.ps1).
- Public pages: home (/), Memoryq TV (/tv), web app (/me), Studio (/studio), DJ Pro (/djpro),
  downloads (/download), terms, privacy, Vstream legal (/vstream-legal).

### Mkarimu TV (mkarimu.net) - the video site, on the same server
- Its home page is the TV wall ("Mkarimu TV"); memoryq.com/tv sends people there once mkarimu.net is in use.
- Video disk /srv/mkarimu (400 GB on datastore2): videos, thumbnails, live recordings (every live channel, kept 30
  days; Admin - Videos - Live recordings: watch, keep as a video, delete; the oldest go first above 80% full).
- Backup disk /srv/backup (100 GB on datastore1, the other drive): every night at 03:30 the data (last 14 nights),
  every uploaded video and every song. Admin - Server - Disks and backups.
- The VPS is a VM (79) on the VMware host with two 1 TB drives (datastore1 = system + backups, datastore2 = videos);
  8 GB memory, 4 CPUs. Old Centova address 184.75.212.211 is a second address of this server (old radio links).

### Memoryq app (Android, com.vstream.broadcaster) and web app (/me)
- News feed (AI-summarised stories), wall posts with photos, likes and comments, people and friends,
  profiles with photos, chats (rooms, public and private groups, invites), calls, notifications,
  radio player, watching live channels, going live, e-mail sign-up with a 6-digit code.
- Web app only so far: Videos (watch, comment, upload, YouTube links, My videos).

### Memoryq Studio (/studio) - our own audio/video streaming, subscription based
- Radio stations with AutoDJ, playlists (rotation, scheduled, interval, immediate), music library,
  live DJs (own port per station), Alma the presenter (news breaks, shout-outs, song titles - artists' names only
  when switched on, the station's name over songs, and story time: books read a paragraph at a time between
  songs), listener statistics and reports
  (countries, players, tracks, CSV), widgets, plans and billing (Stripe), free trials.
- Centova Cast import: accounts, songs and playlists copied in with a new live port and a one-week trial;
  keeps suspended / switched-off accounts that way, never takes over someone else's username (same e-mail only),
  copies the listening history (Studio - Report - Historical), lists what works differently, and can send the
  old Centova listening links to the new Memoryq links (mq-export.sh redirect, undo with redirect-off).
  Running the export or the import again asks first: which accounts to send again, and per existing station skip /
  overwrite chosen parts (songs, playlists, settings, history) / new copy.
- Old listening links: the old Centova address 184.75.212.211 is moved onto this server (oldlinks.sh on the VPS), and
  Memoryq plays each moved station on its old port (http://184.75.212.211:PORT/stream). Admin - Radio - Import -
  Old listening links. The listings (Radio.garden, TuneIn) still need the new https://memoryq.com/radio/ID.mp3 links.
- Playlists: rotation by weight (optionally only at set hours), scheduled shows (weekdays, monthly, yearly or once;
  fill the time, play the list once or one song), interval playlists every N songs / minutes playing one song or a
  whole ad break in order (before requests; optionally not during shows), immediate playlists.

### DJ Pro (/djpro) - live DJ console, tied to each Studio account
- Two decks with club-size spinning records, scratch, tempo/BPM, cues, loops, effects; device library,
  Dropbox and music services; live on air with optional video; works next to the Studio.

### Vistr word game (vistr.memoryq.com) - first of the Memoryq games
- Change one letter at a time; solo or two players taking turns; 4-7 letters; English, Spanish, French,
  Portuguese, Swahili; ads, reviews, contact form; app for phones (Capacitor).
- Code: GitHub mkarimu/vistr. Runs on the same VPS: Node.js service "vistr", installed and updated with
  deploy/setup-server.sh (update = newest code from GitHub). Caddy sends vistr.memoryq.com to it; Memoryq deploys
  keep that site (and any other game's) in the Caddy settings.
- SuperAgent bots: each lone player gets a bot partner (plays at a human pace, never on the score boards);
  when the player quits, the bot waits 2 hours, then is removed.

### Vstream (Android, com.vstream.live) - remote live-streaming for anchors
- Streaming only (no chat). Picks a free Memoryq channel automatically, or streams to your own RTMP server.
- Right-side menu: Start / Pause / Stop, Live streams (3-way split screen), switch camera, mute, settings.
- "Also stream to" X, Facebook, Twitch, YouTube, TikTok or any RTMP server (copied by the VPS).
- First-run agreement: terms, streaming rules, privacy, copyright, other platforms, disclaimers.

## Web administration (/admin) - accomplished
- Overview summary; Users (all users, create, suspended, deletion requests, profile photos, Account details:
  everything one account has - profile, streams, videos, posts, comments, chats - with suspend / reinstate / delete).
- Videos: Memoryq TV playlist (YouTube links and uploads, order), all members' videos (on TV, pause, delete).
- Streaming: live channels, channel pool. Chat: rooms, reports, group admins, mail. Wall: posts and reports.
- News sources and stories. Ads: slider settings and paid ads. Calls add-on. Profiler (test people).
- Radio: on air, stations (accounts list, limits, billing, login as owner), music library, Centova import.
- Server: SERVERASSETS (this file), downloads (APK files), Alma's questions.

## Video interface - accomplished
- Memoryq TV (/tv): big screen + live thumbnails re-ordered by prominence (viewers, peak, growth, time
  on air, new-stream boost, ratings when they exist); lists (Top live, Most watched, Rising, Just started,
  Live DJs, Top rated, Marathons, Videos); search; Prev / Play-Pause / Next; sound; Full screen;
  controls slide away 3 s after play starts. Featured videos play when nobody is live.
- Videos for members: upload (converted to MP4), YouTube links, comments, edit, pause, delete.

## Video interface - missing
- Star ratings for channels and videos (the TV score is ready for them).
- Payments for channels/videos; video categories chosen by broadcasters.
- Videos in the Android apps (web only today).

## Assistants
- Alma - the chat assistant on the website (sign-up, downloads, radio, videos, going live). Uses OpenAI when
  the server has a key, built-in answers otherwise.
- SuperAgent - keeps this file, watches server activity and warns about problems (disk space, FFmpeg missing).
- Game bots for the word game (Vistr): one bot for each lone player, removed 2 hours after its player leaves.
  The live status below shows players, games and bots; a warning appears if vistr.memoryq.com stops answering.

## XXX NEXT XXX - not accomplished yet
- Studio Master: a video mixer for live events. Up to 5 Vstream feeds cued at the same time; the console shows
  the cued streams; the Master-DJ clicks one and its border changes colour, a slider appears on the main screen
  with the slot number of the incoming stream to show it is ready; transitions (cut, crossfades of different
  kinds) and a side-by-side debate mode.
- Advertisers and the ads algorithm (advertiser accounts, targeting, pacing, reports).
- Our own search engine (people, channels, stations, videos, posts).
- More games next to Vistr (each gets its own site line in Caddy, kept by every Memoryq deploy).
- More to come.