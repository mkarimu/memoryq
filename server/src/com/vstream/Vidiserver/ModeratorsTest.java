package com.vstream.Vidiserver;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Moderators: the super-admin gives members rights area by area; a moderator adds users, changes and removes only the
 * ones they added (unless "modify any user"), edits emails and gives game IDs only with those rights, never touches the
 * super-admin or other moderators; the other parts of the server let a moderator in only for the rights given; all logged.
 */
public final class ModeratorsTest {

    private static int failures = 0;
    private static int port;

    public static void main(String[] args) {
        try {
            run();
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAILED: " + t);
            System.exit(1);
        }
    }

    static String[] call(String method, String path, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (token != null) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", body.startsWith("{") ? "application/json" : "application/x-www-form-urlencoded");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return new String[] {String.valueOf(code), new String(b.toByteArray(), StandardCharsets.UTF_8)};
    }

    static String[] get(String path, String token) throws Exception {
        return call("GET", path, null, token);
    }

    static String[] post(String path, String body, String token) throws Exception {
        return call("POST", path, body, token);
    }

    private static void run() throws Exception {
        Path dir = Files.createTempDirectory("memoryq-mods");
        long[] now = {1792900000000L};
        UserStore users = new UserStore(dir.resolve("users.txt"), new LiveChannelRegistry(), x -> { });
        users.init(dir.resolve("none.txt"));
        users.createUser("gad", "password123", UserStore.Role.CHATTER);
        users.createUser("baraka", "password123", UserStore.Role.CHATTER);
        users.createUser("neema", "password123", UserStore.Role.CHATTER);
        users.createUser("zawadi", "password123", UserStore.Role.CHATTER);
        SessionStore sessions = new SessionStore(() -> now[0]);
        String admin = sessions.create("admin", UserStore.Role.ADMIN);
        String gad = sessions.create("gad", UserStore.Role.CHATTER);
        String baraka = sessions.create("baraka", UserStore.Role.CHATTER);
        final Map<String, String> emails = new HashMap<String, String>();
        final Map<String, String> games = new HashMap<String, String>();
        final Map<String, String> firstNames = new HashMap<String, String>();
        emails.put("neema", "neema@example.com");
        Moderators.Members fake = new Moderators.Members() {
            public String profileJson(UserStore.Info i) {
                return "{\"username\":\"" + i.username + "\",\"firstName\":\"" + (firstNames.containsKey(i.username) ? firstNames.get(i.username) : "") + "\"}";
            }

            public String edit(String actor, UserStore.Info i, String form, boolean emailOk) throws Exception {
                String f = MediaAuthHttpServer.queryParam(form, "firstName");
                if (f != null) {
                    firstNames.put(i.username, f);
                }
                String e = MediaAuthHttpServer.queryParam(form, "email");
                if (emailOk && e != null && !e.isEmpty()) {
                    emails.put(i.username, e);
                }
                return profileJson(i);
            }

            public String gameId(String actor, String username, String code) throws Exception {
                if (!emails.containsKey(username)) {
                    throw new Exception(username + " has no email - add one first (a game ID needs an email)");
                }
                String c = code.isEmpty() ? String.valueOf(10 + games.size()) : code;
                games.put(username, c);
                return c;
            }

            public String gameIdOf(String username) {
                return games.containsKey(username) ? games.get(username) : "";
            }

            public void delete(String actor, String username) throws Exception {
                users.deleteAccount(username);
            }

            public String emailOf(String username) {
                return emails.get(username);
            }

            public String nameOf(String username) {
                return firstNames.containsKey(username) ? firstNames.get(username) : "";
            }

            public void setEmail(String username, String email) throws Exception {
                if (!email.contains("@")) {
                    throw new Exception("that is not an email address");
                }
                emails.put(username, email);
            }
        };
        Moderators mods = new Moderators(dir.resolve("moderators.txt"), users, sessions, () -> now[0]).members(fake).init();
        MissingGame game = new MissingGame(dir.resolve("missing"), sessions, users, null, () -> now[0]);
        game.init();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mods.register(http);
        game.register(http);
        http.start();
        port = http.getAddress().getPort();
        try {
            System.out.println("-- The super-admin chooses moderators and their rights --");
            checkTrue("members cannot see or give rights", get("/api/admin/moderators", gad)[0].equals("403")
                    && post("/api/admin/moderators/save", "username=gad&rights=users.modifyAny", gad)[0].equals("403"));
            String[] r = post("/api/admin/moderators/save", "username=gad&rights=users.add,users.modifyOwn,users.removeOwn,users.gameId,games.missing,nonsense", admin);
            checkTrue("Gad: add users, modify and remove the ones he adds, the old 'game IDs' right is now the Vistr word game, Spot What's Missing pictures (unknown rights ignored)",
                    r[0].equals("200") && r[1].contains("\"username\":\"gad\"") && r[1].contains("\"players.vistr\"") && !r[1].contains("\"users.gameId\"")
                    && !r[1].contains("nonsense"));
            checkTrue("the super-admin cannot be made a moderator", post("/api/admin/moderators/save", "username=admin&rights=users.add", admin)[0].equals("400"));
            checkTrue("nor a name nobody has", post("/api/admin/moderators/save", "username=ghost&rights=users.add", admin)[0].equals("404"));
            r = get("/api/mod/me", gad);
            String own = r[1].substring(r[1].indexOf("\"rights\":"), r[1].indexOf("\"catalogue\""));
            checkTrue("Gad's admin page knows his rights", r[1].contains("\"superAdmin\":false") && own.contains("\"users.add\"") && !own.contains("\"users.modifyAny\""));
            checkTrue("a member without rights has none", get("/api/mod/me", baraka)[1].contains("\"rights\":[]")
                    && get("/api/mod/users", baraka)[0].equals("403"));

            System.out.println("-- Gad adds and looks after his own users --");
            r = post("/api/mod/users/create", "username=juma_k&password=goodpass123&email=juma%40example.com&game=1", gad);
            checkTrue("Gad adds juma_k with an email and a game ID", r[0].equals("200") && users.info("juma_k") != null && r[1].contains("\"mine\":true")
                    && r[1].contains("\"gameId\":\"10\"") && r[1].contains("\"email\":\"juma@example.com\""));
            r = get("/api/mod/users", gad);
            checkTrue("his list: his own users (others he adds to his game from its players page)", r[0].equals("200") && r[1].contains("juma_k")
                    && !r[1].contains("\"username\":\"neema\"") && !r[1].contains("\"username\":\"admin\""));
            checkTrue("other members' emails are not shown to him", !r[1].contains("neema@example.com") && r[1].contains("\"hasEmail\":true"));
            r = post("/api/mod/users/edit", "u=juma_k&firstName=Juma&email=juma.k%40example.com", gad);
            checkTrue("he changes juma_k (his) - email too", r[0].equals("200") && "Juma".equals(firstNames.get("juma_k")) && "juma.k@example.com".equals(emails.get("juma_k")));
            r = post("/api/mod/users/edit", "u=neema&firstName=Hacked", gad);
            checkTrue("but not neema (someone else's)", r[0].equals("403") && !firstNames.containsKey("neema") && r[1].contains("only modify users you added"));
            checkTrue("he cannot remove neema", post("/api/mod/users/remove", "u=neema", gad)[0].equals("403") && users.info("neema") != null);
            r = post("/api/mod/users/gameid", "u=neema&code=77", gad);
            checkTrue("he gives neema (she has an email) game ID 77", r[0].equals("200") && "77".equals(games.get("neema")));
            r = post("/api/mod/users/gameid", "u=baraka", gad);
            checkTrue("no email, no game ID (said clearly)", r[0].equals("400") && r[1].contains("no email"));
            checkTrue("he cannot touch the super-admin", post("/api/mod/users/edit", "u=admin&firstName=x", gad)[0].equals("403")
                    && post("/api/mod/users/gameid", "u=admin", gad)[0].equals("404"));
            r = post("/api/mod/users/remove", "u=juma_k", gad);
            checkTrue("he removes juma_k (his own)", r[0].equals("200") && users.info("juma_k") == null);

            System.out.println("-- More rights, fewer rights --");
            post("/api/admin/moderators/save", "username=baraka&rights=users.email", admin);
            r = post("/api/mod/users/edit", "u=neema&email=neema.new%40example.com&firstName=No", baraka);
            checkTrue("Baraka may edit emails only: neema's email changes, nothing else", r[0].equals("200") && "neema.new@example.com".equals(emails.get("neema"))
                    && !firstNames.containsKey("neema"));
            checkTrue("moderators never change each other", post("/api/mod/users/edit", "u=gad&email=g%40x.com", baraka)[0].equals("403"));
            checkTrue("nor add users without the right", post("/api/mod/users/create", "username=x_kid&password=goodpass123", baraka)[0].equals("403"));
            post("/api/admin/moderators/save", "username=baraka&rights=users.modifyAny", admin);
            r = post("/api/mod/users/edit", "u=neema&firstName=Neema", baraka);
            checkTrue("with 'modify any user' Baraka changes neema", r[0].equals("200") && "Neema".equals(firstNames.get("neema")));
            checkTrue("but may not remove her (only users he added, with that right)", post("/api/mod/users/remove", "u=neema", baraka)[0].equals("403"));

            System.out.println("-- Other parts of the server: only with the right --");
            checkTrue("Gad (games.missing) opens Spot What's Missing's admin", get("/api/admin/missing", gad)[0].equals("200"));
            checkTrue("Baraka (no games right) does not", get("/api/admin/missing", baraka)[0].equals("403"));
            UserStore.Info gi = users.info("gad"), bi = users.info("baraka");
            checkTrue("rights are checked one by one", Moderators.may(gi, "games.missing") && !Moderators.may(gi, "streams.end") && Moderators.may(bi, "users.modifyAny")
                    && !Moderators.may(bi, "market.approve") && Moderators.may(users.info("admin"), "market.approve"));
            users.setDisabled("gad", true);
            checkTrue("a switched-off moderator has no rights", !Moderators.may(users.info("gad"), "games.missing"));
            users.setDisabled("gad", false);

            System.out.println("-- Game IDs: one per game, kept here even while a game cannot be reached --");
            final boolean[] up = {false};
            final Moderators.Members fk = fake;
            final java.util.Set<String> pausedCodes = new java.util.HashSet<String>();
            final java.util.List<String[]> outsiders = new java.util.ArrayList<String[]>();
            outsiders.add(new String[] {"501", "Phone player", "phone@example.com", "", "0", "120", "1500", "", "3"});
            final Map<String, Map<String, Integer>> reportData = new java.util.HashMap<String, Map<String, Integer>>();
            GameIds gids = new GameIds(dir.resolve("gameids.txt"), () -> now[0]).emails(u -> emails.get(u))
                    .knownVistr(u -> games.containsKey(u) ? games.get(u) : "")
                    .sync("vistr", new GameIds.Sync() {
                        public String send(String actor, String u, String code, boolean change) throws Exception {
                            if (!up[0]) {
                                throw new java.io.IOException("the game could not be reached");
                            }
                            if (games.containsKey(u) && !code.isEmpty() && !code.equals(games.get(u)) && !change) { // like Vistr: their player stays
                                VistrImport.Bad b = new VistrImport.Bad(409, "Vistr: " + u + " already has Vistr Player ID " + games.get(u) + " - their points are on it.");
                                b.existing = games.get(u);
                                b.owner = u;
                                throw b;
                            }
                            return fk.gameId(actor, u, code.isEmpty() && games.containsKey(u) ? games.get(u) : code);
                        }

                        public String peek(String actor, String u) throws Exception {
                            if (!up[0]) {
                                throw new java.io.IOException("the game could not be reached");
                            }
                            return games.containsKey(u) ? games.get(u) : "";
                        }

                        public void pause(String actor, String id, boolean on) {
                            if (on) {
                                pausedCodes.add(id);
                            } else {
                                pausedCodes.remove(id);
                            }
                        }

                        public void drop(String actor, String id) {
                            games.values().remove(id);
                        }

                        public java.util.List<String[]> roster(String actor) throws Exception {
                            if (!up[0]) {
                                throw new java.io.IOException("the game could not be reached");
                            }
                            java.util.List<String[]> l = new java.util.ArrayList<String[]>(outsiders);
                            for (Map.Entry<String, String> e : games.entrySet()) {
                                l.add(new String[] {e.getValue(), "", emails.containsKey(e.getKey()) ? emails.get(e.getKey()) : "", e.getKey(),
                                    pausedCodes.contains(e.getValue()) ? "1" : "0", "10", "1500", "", "1"});
                            }
                            return l;
                        }

                        public Map<String, Map<String, Integer>> report(String actor, String from, String to) throws Exception {
                            if (!up[0]) {
                                throw new java.io.IOException("the game could not be reached");
                            }
                            Map<String, Map<String, Integer>> out = new java.util.HashMap<String, Map<String, Integer>>();
                            for (Map.Entry<String, Map<String, Integer>> e : reportData.entrySet()) {
                                Map<String, Integer> days = new java.util.TreeMap<String, Integer>();
                                for (Map.Entry<String, Integer> d : e.getValue().entrySet()) {
                                    if (d.getKey().compareTo(from) >= 0 && d.getKey().compareTo(to) <= 0) {
                                        days.put(d.getKey(), d.getValue());
                                    }
                                }
                                out.put(e.getKey(), days);
                            }
                            return out;
                        }
                    }).init();
            r = get("/api/mod/users?q=neema", admin);
            checkTrue("the list shows every game with the member's IDs", r[1].contains("\"game\":\"vistr\",\"name\":\"Vistr word game\",\"id\":\"77\"")
                    && r[1].contains("\"game\":\"missing\",\"name\":\"Spot What's Missing\",\"id\":\"\""));
            r = post("/api/mod/users/gameid", "u=neema&game=vistr&code=4321", admin);
            checkTrue("the Vistr game is down: the ID is still saved here (and said so)", r[0].equals("200") && r[1].contains("\"waiting\":\"the game could not be reached\"")
                    && "4321".equals(gids.idOf("neema", "vistr")) && r[1].contains("\"state\":\"waiting\""));
            up[0] = true;
            checkTrue("when the game is back, the player she already had stays hers (77, her points) - no second number",
                    gids.retry() == 1 && "77".equals(games.get("neema")) && get("/api/mod/users?q=neema", admin)[1].contains("\"id\":\"77\",\"state\":\"ok\""));
            r = post("/api/mod/users/gameid", "u=neema&game=missing", admin);
            String mid = gids.idOf("neema", "missing");
            checkTrue("Spot What's Missing: an ID is picked when the box is empty", r[0].equals("200") && mid.matches("\\d{4}"));
            checkTrue("one ID per person in each game", post("/api/mod/users/gameid", "u=baraka&game=missing&code=" + mid, admin)[0].equals("409"));
            checkTrue("the Vistr word game needs an email (said clearly)", post("/api/mod/users/gameid", "u=baraka&game=vistr", admin)[1].contains("needs an email"));
            checkTrue("Spot What's Missing does not", post("/api/mod/users/gameid", "u=baraka&game=missing&code=55", admin)[0].equals("200")
                    && "55".equals(gids.idOf("baraka", "missing")));
            checkTrue("a wrong ID is refused", post("/api/mod/users/gameid", "u=baraka&game=missing&code=1234567", admin)[0].equals("400")
                    && post("/api/mod/users/gameid", "u=baraka&game=chess&code=12", admin)[0].equals("400"));
            checkTrue("an ID can be taken away", post("/api/mod/users/gameid", "u=baraka&game=missing&remove=1", admin)[0].equals("200")
                    && gids.idOf("baraka", "missing").isEmpty());
            checkTrue("members without the right cannot give IDs", post("/api/mod/users/gameid", "u=neema&game=missing&code=99", baraka)[0].equals("403"));
            String neemaS = sessions.create("neema", UserStore.Role.CHATTER);
            System.out.println("-- Public access, game by game --");
            r = get("/api/games", neemaS);
            checkTrue("Spot What's Missing is not public until the super-admin opens it; Vistr is", r[1].contains("\"key\":\"missing\",\"name\":\"Spot What's Missing\",\"public\":false,\"open\":false")
                    && r[1].contains("\"key\":\"vistr\",\"name\":\"Vistr word game\",\"public\":true,\"open\":true"));
            checkTrue("... a carer is told it is not open yet", get("/api/missing/players", neemaS)[0].equals("403"));
            checkTrue("... the super-admin can use it", get("/api/missing/players", admin)[0].equals("200")
                    && get("/api/games", admin)[1].contains("\"key\":\"missing\",\"name\":\"Spot What's Missing\",\"public\":false,\"open\":true"));
            checkTrue("... and only the super-admin switches it", post("/api/admin/games/save", "game=missing&public=1", gad)[0].equals("403"));
            r = get("/api/admin/games", admin);
            checkTrue("the super-admin's overview lists every game, also the one not public, with its moderators", r[0].equals("200")
                    && r[1].contains("\"key\":\"missing\",\"name\":\"Spot What's Missing\",\"public\":false") && r[1].contains("\"moderators\":[\"gad\"]"));
            r = post("/api/admin/games/save", "game=missing&public=1", admin);
            checkTrue("Public access on: open to everyone", r[0].equals("200") && GameIds.isPublic("missing") && get("/api/games", null)[1].contains("\"key\":\"missing\",\"name\":\"Spot What's Missing\",\"public\":true,\"open\":true"));
            checkTrue("the carer sees her Spot What's Missing ID", get("/api/missing/players", neemaS)[1].contains("\"gameId\":\"" + mid + "\""));
            checkTrue("IDs survive a restart", mid.equals(new GameIds(dir.resolve("gameids.txt"), () -> now[0]).init().idOf("neema", "missing")));

            gids.init(); // the copy made for the restart check took over: this one (with the game) again
            checkTrue("public access survives a restart", GameIds.isPublic("missing"));
            post("/api/admin/games/save", "game=missing&public=0", admin);
            checkTrue("... and can be switched off again", !GameIds.isPublic("missing") && get("/api/missing/players", neemaS)[0].equals("403"));
            System.out.println("-- One person, one player --");
            r = post("/api/mod/users/gameid", "u=neema&game=vistr", admin);
            checkTrue("Give one for someone who has a Player ID: the same ID, nothing new", r[0].equals("200") && r[1].contains("\"gameId\":\"77\"") && "77".equals(games.get("neema")));
            r = post("/api/mod/users/gameid", "u=neema&game=vistr&code=88", admin);
            checkTrue("a different number is not given without asking (the answer says which one she has)", r[0].equals("409")
                    && r[1].contains("\"existing\":\"77\"") && "77".equals(games.get("neema")));
            r = post("/api/mod/users/gameid", "u=neema&game=vistr&code=88&change=1", admin);
            checkTrue("... and is when the admin confirms (same player, points stay)", r[0].equals("200") && "88".equals(games.get("neema")) && "88".equals(gids.idOf("neema", "vistr")));
            emails.put("baraka", "baraka@example.com");
            up[0] = false;
            r = post("/api/mod/users/gameid", "u=baraka&game=vistr", admin);
            checkTrue("game not reachable and no number typed: nothing is made up (try again)", r[0].equals("503") && gids.idOf("baraka", "vistr").isEmpty());
            up[0] = true;
            games.put("baraka", "61"); // Baraka had a player in the game already (linked there)
            r = get("/api/mod/users/one?u=baraka", admin);
            checkTrue("opening a member asks the game: the ID they already have shows", r[1].contains("\"game\":\"vistr\",\"name\":\"Vistr word game\",\"id\":\"61\""));

            System.out.println("-- Suggestions: pick the person who is already here --");
            firstNames.put("neema", "Neema Atieno");
            r = get("/api/mod/suggest?q=nee", admin);
            checkTrue("typing part of a username finds them (with their game IDs)", r[1].contains("\"username\":\"neema\"") && r[1].contains("\"vistr\":\"88\""));
            checkTrue("... part of the name too", get("/api/mod/suggest?q=atieno", admin)[1].contains("\"username\":\"neema\""));
            checkTrue("... and the email", get("/api/mod/suggest?q=neema.new%40example", admin)[1].contains("\"username\":\"neema\""));
            r = post("/api/mod/users/create", "username=neema2&password=goodpass123&email=neema.new%40example.com", admin);
            checkTrue("a second account with someone's email is not made (it says whose it is)", r[0].equals("409") && r[1].contains("\"existingUser\":\"neema\"")
                    && users.info("neema2") == null);

            System.out.println("-- Moderators per game --");
            post("/api/admin/moderators/save", "username=zawadi&rights=players.vistr", admin);
            String zaw = sessions.create("zawadi", UserStore.Role.CHATTER);
            checkTrue("Zawadi looks after the Vistr word game", get("/api/mod/game?game=vistr", zaw)[0].equals("200"));
            checkTrue("... not Spot What's Missing (not ticked)", get("/api/mod/game?game=missing", zaw)[0].equals("403")
                    && post("/api/mod/users/gameid", "u=baraka&game=missing&code=56", zaw)[0].equals("403"));
            checkTrue("Spot What's Missing (not public) is shut for her", get("/api/games", zaw)[1].contains("\"key\":\"missing\",\"name\":\"Spot What's Missing\",\"public\":false,\"open\":false")
                    && get("/api/missing/players", zaw)[0].equals("403"));
            post("/api/admin/moderators/save", "username=zawadi&rights=players.vistr,players.missing", admin);
            checkTrue("ticked for it: she sees it and can use it before it is public", get("/api/games", zaw)[1].contains("\"key\":\"missing\",\"name\":\"Spot What's Missing\",\"public\":false,\"open\":true")
                    && get("/api/missing/players", zaw)[0].equals("200") && get("/api/mod/game?game=missing", zaw)[0].equals("200"));
            post("/api/admin/moderators/save", "username=zawadi&rights=players.vistr", admin);
            r = post("/api/mod/users/create", "username=kid_z&password=goodpass123&email=kidz%40example.com&game=vistr", zaw);
            checkTrue("she adds a new player to her game (account and Player ID)", r[0].equals("200") && games.containsKey("kid_z"));
            r = post("/api/mod/users/gameid", "u=gad&game=vistr", zaw);
            emails.put("gad", "gad@example.com");
            r = post("/api/mod/users/gameid", "u=gad&game=vistr", zaw);
            checkTrue("... and an existing member (picked from the suggestions)", r[0].equals("200") && games.containsKey("gad"));
            r = get("/api/mod/game?game=vistr", zaw);
            checkTrue("her game page: every player, hers ticked as hers", r[1].contains("\"username\":\"kid_z\"") && r[1].contains("\"username\":\"neema\"")
                    && r[1].matches("(?s).*\"username\":\"kid_z\".*?\"mine\":true.*") && r[1].matches("(?s).*\"username\":\"neema\".*?\"mine\":false.*")
                    && r[1].contains("\"email\":\"n***@example.com\""));
            checkTrue("... players who are not members only for the super-admin", r[1].contains("\"others\":[]")
                    && get("/api/mod/game?game=vistr", admin)[1].contains("\"name\":\"Phone player\""));
            checkTrue("she pauses her player (the game is told)", post("/api/mod/game/pause", "game=vistr&u=kid_z&on=1", zaw)[0].equals("200")
                    && pausedCodes.contains(games.get("kid_z")) && gids.paused("kid_z", "vistr"));
            checkTrue("... and lets them play again", post("/api/mod/game/pause", "game=vistr&u=kid_z&on=0", zaw)[0].equals("200") && !pausedCodes.contains(games.get("kid_z")));
            checkTrue("she may not pause, change or remove players someone else added", post("/api/mod/game/pause", "game=vistr&u=neema&on=1", zaw)[0].equals("403")
                    && post("/api/mod/users/gameid", "u=neema&game=vistr&code=89&change=1", zaw)[0].equals("403")
                    && post("/api/mod/game/remove", "game=vistr&u=neema", zaw)[0].equals("403") && "88".equals(games.get("neema")));
            checkTrue("... nor act on players who are not members", post("/api/mod/game/pause", "game=vistr&code=501&on=1", zaw)[0].equals("403"));
            r = post("/api/mod/game/remove", "game=vistr&u=gad&account=1", zaw);
            checkTrue("removing a member she added to the game leaves the account she did not make", r[0].equals("200") && r[1].contains("\"account\":false")
                    && r[1].contains("account stays") && !games.containsKey("gad")
                    && users.info("gad") != null);
            r = post("/api/mod/game/remove", "game=vistr&u=kid_z&account=1", zaw);
            checkTrue("removing her own player: out of the game, and the account she made", r[0].equals("200") && !games.containsKey("kid_z")
                    && users.info("kid_z") == null && gids.idOf("kid_z", "vistr").isEmpty());
            checkTrue("the super-admin pauses a player who is not a member", post("/api/mod/game/pause", "game=vistr&code=501&on=1", admin)[0].equals("200")
                    && pausedCodes.contains("501"));
            String neemaP = sessions.create("neema", UserStore.Role.CHATTER);
            checkTrue("a moderator for one game sees the Users page for her own players only", get("/api/mod/users", zaw)[0].equals("200"));

            System.out.println("-- Dummy bot users --");
            users.createUser("helper_bot", "password123", UserStore.Role.CHATTER);
            users.createUser("bot_7", "password123", UserStore.Role.CHATTER);
            users.createUser("robbie", "password123", UserStore.Role.CHATTER);
            users.createUser("quiet", "password123", UserStore.Role.CHATTER);
            emails.put("quiet", "bot-12@bots.memoryq.com");
            users.createUser("mod_bot", "password123", UserStore.Role.CHATTER);
            post("/api/admin/moderators/save", "username=mod_bot&rights=players.vistr", admin);
            checkTrue("a moderator cannot see or remove bot users", post("/api/admin/moderators/botusers", "", zaw)[0].equals("403")
                    && post("/api/admin/moderators/botusers/remove", "all=1", zaw)[0].equals("403"));
            r = post("/api/admin/moderators/botusers", "", admin);
            checkTrue("the list shows the bot-looking users and why", r[0].equals("200") && r[1].contains("\"username\":\"helper_bot\"") && r[1].contains("\"username\":\"bot_7\"")
                    && r[1].contains("\"username\":\"quiet\"") && r[1].contains("bots.memoryq.com"));
            checkTrue("... but not real people, a moderator or the super-admin", !r[1].contains("robbie") && !r[1].contains("mod_bot") && !r[1].contains("\"username\":\"admin\"") && !r[1].contains("neema"));
            r = post("/api/admin/moderators/botusers/remove", "names=helper_bot,robbie", admin);
            checkTrue("ticked ones go; a real person asked for by mistake is left alone", r[1].contains("\"removed\":1") && users.info("helper_bot") == null && users.info("robbie") != null && users.info("bot_7") != null);
            checkTrue("nothing ticked is refused", post("/api/admin/moderators/botusers/remove", "names=", admin)[0].equals("400"));
            r = post("/api/admin/moderators/botusers/remove", "all=1", admin);
            checkTrue("remove all takes the rest of the bot users and nobody else", r[1].contains("\"removed\":2") && users.info("bot_7") == null && users.info("quiet") == null
                    && users.info("robbie") != null && users.info("mod_bot") != null && users.info("neema") != null);
            checkTrue("the removals are logged", get("/api/admin/moderators", admin)[1].contains("removed bot user"));

            System.out.println("-- Performance report: games per day and week --");
            post("/api/admin/moderators/save", "username=zawadi&rights=players.vistr", admin);
            r = post("/api/mod/users/create", "username=kid_r&password=goodpass123&email=kidr%40example.com&game=vistr", zaw);
            checkTrue("Zawadi adds a player for the report", r[0].equals("200") && games.containsKey("kid_r"));
            Map<String, Integer> kd = new java.util.TreeMap<String, Integer>();
            kd.put("2026-10-26", 3);
            kd.put("2026-10-27", 2);
            kd.put("2026-11-02", 4);
            reportData.put(games.get("kid_r"), kd);
            Map<String, Integer> nd = new java.util.TreeMap<String, Integer>();
            nd.put("2026-10-26", 5);
            reportData.put(games.get("neema"), nd);
            Map<String, Integer> od = new java.util.TreeMap<String, Integer>();
            od.put("2026-10-28", 7);
            reportData.put("501", od);
            String rq = "/api/mod/report?game=vistr&from=2026-10-26&to=2026-11-02";
            checkTrue("switched off for her: no report", get(rq, zaw)[0].equals("403") && get(rq + "&format=csv", zaw)[0].equals("403"));
            post("/api/admin/moderators/save", "username=zawadi&rights=players.vistr,reports.vistr", admin);
            checkTrue("the right is in the catalogue under the game", get("/api/mod/me", admin)[1].contains("\"key\":\"reports.vistr\"")
                    && get("/api/mod/me", admin)[1].matches("(?s).*\"key\":\"reports.vistr\".*?\"game\":\"vistr\".*"));
            r = get(rq, zaw);
            checkTrue("switched on: her own player only, with day, week and total", r[0].equals("200") && r[1].contains("\"username\":\"kid_r\"") && r[1].contains("\"gameId\":\"" + games.get("kid_r") + "\"")
                    && r[1].contains("\"email\":\"kidr@example.com\"") && r[1].contains("\"weekTotals\":[5,4]") && r[1].contains("\"total\":9")
                    && !r[1].contains("\"username\":\"neema\"") && !r[1].contains("Phone player"));
            checkTrue("... the days are listed from the first date to the last", r[1].contains("\"dates\":[\"2026-10-26\",\"2026-10-27\"") && r[1].contains("\"weeks\":[\"2026-10-26\",\"2026-11-02\"]")
                    && r[1].contains("\"days\":[3,2,0,0,0,0,0,4]"));
            r = get(rq + "&format=csv", zaw);
            checkTrue("the CSV: columns, her player, day and week numbers", r[0].equals("200") && r[1].startsWith("\uFEFFEmail,Username,Game ID,Moderator,2026-10-26,2026-10-27")
                    && r[1].contains(",Week of 2026-10-26,Week of 2026-11-02,Total games") && r[1].contains("kidr@example.com,kid_r," + games.get("kid_r") + ",zawadi,3,2,0,0,0,0,0,4,5,4,9")
                    && r[1].contains("All players,,,,3,2,0,0,0,0,0,4,5,4,9") && !r[1].contains("neema"));
            r = get(rq + "&format=pdf", zaw);
            checkTrue("the PDF is a real PDF with the title and her player", r[0].equals("200") && r[1].startsWith("%PDF-1.4") && r[1].trim().endsWith("%%EOF")
                    && r[1].contains("Performance report - Vistr word game") && r[1].contains("kid_r") && r[1].contains("Weekly totals") && r[1].contains("Day by day"));
            r = get(rq, admin);
            checkTrue("the super-admin sees every player, with who added each, and players who are not members",
                    r[1].contains("\"username\":\"kid_r\"") && r[1].contains("\"username\":\"neema\"") && r[1].contains("\"username\":\"Phone player\"")
                    && r[1].contains("\"moderator\":\"(not a Memoryq member)\"") && r[1].contains("\"moderator\":\"zawadi\"") && r[1].contains("\"total\":21"));
            String todayIso = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
            kd.put(todayIso, 6);
            String rqPast = "/api/mod/report?game=vistr&from=2026-10-26&to=2026-10-27";
            r = get(rqPast, zaw);
            checkTrue("today is shown even when the period is in the past, and is not added to its total",
                    r[1].contains("\"today\":\"" + todayIso + "\"") && r[1].contains("\"todayTotal\":6") && r[1].contains("\"total\":5,\"today\":6") && !r[1].contains("\"total\":11"));
            r = get(rqPast + "&live=1", zaw);
            checkTrue("the page that asks every few seconds gets today too", r[0].equals("200") && r[1].contains("\"todayTotal\":6"));
            kd.put(todayIso, 8);
            r = get(rqPast + "&live=1", zaw);
            checkTrue("... and a few seconds old is accepted (cache)", r[1].contains("\"todayTotal\":6"));
            r = get(rqPast, zaw);
            checkTrue("... but a normal request is always fresh", r[1].contains("\"todayTotal\":8"));
            kd.remove(todayIso);
            r = get(rq, zaw);
            checkTrue("the report carries the pay rule (default: 6 games = 20.00 USD)", r[1].contains("\"pay\":{\"threshold\":6,\"cents\":2000,\"currency\":\"USD\"}"));
            checkTrue("a moderator cannot change the pay rule", post("/api/admin/moderators/pay", "game=vistr&threshold=3&amount=5&currency=KES", zaw)[0].equals("403"));
            r = post("/api/admin/moderators/pay", "game=vistr&threshold=8&amount=12.5&currency=KES", admin);
            checkTrue("the super-admin sets games a day, amount and currency", r[0].equals("200") && r[1].contains("\"cents\":1250"));
            checkTrue("... and the report shows it", get(rq, zaw)[1].contains("\"pay\":{\"threshold\":8,\"cents\":1250,\"currency\":\"KES\"}"));
            checkTrue("nonsense is refused", post("/api/admin/moderators/pay", "game=vistr&threshold=0&amount=5&currency=KES", admin)[0].equals("400")
                    && post("/api/admin/moderators/pay", "game=vistr&threshold=6&amount=abc&currency=KES", admin)[0].equals("400")
                    && post("/api/admin/moderators/pay", "game=vistr&threshold=6&amount=5&currency=%3Cb%3E", admin)[0].equals("400")
                    && post("/api/admin/moderators/pay", "game=nope&threshold=6&amount=5&currency=KES", admin)[0].equals("400"));
            post("/api/admin/moderators/pay", "game=vistr&threshold=6&amount=20&currency=USD", admin);
            checkTrue("a bad or too long period is refused", get("/api/mod/report?game=vistr&from=2026-11-05&to=2026-11-01", admin)[0].equals("400")
                    && get("/api/mod/report?game=vistr&from=nonsense&to=2026-11-01", admin)[0].equals("400")
                    && get("/api/mod/report?game=vistr&from=2020-01-01&to=2026-11-01", admin)[0].equals("400"));
            checkTrue("a game that keeps no daily record, and a member with no rights", get("/api/mod/report?game=missing&from=2026-10-26&to=2026-11-02", admin)[0].equals("400")
                    && get(rq, neemaP)[0].equals("403"));
            up[0] = false;
            checkTrue("the game is down: said so, nothing made up", get(rq, admin)[0].equals("502"));
            up[0] = true;
            post("/api/admin/moderators/save", "username=zawadi&rights=players.vistr", admin);
            checkTrue("switched off again: the report is gone", get(rq, zaw)[0].equals("403"));
            r = get("/api/admin/moderators", admin);
            checkTrue("downloads are logged", r[1].contains("\"action\":\"report\"") && r[1].contains("\"target\":\"vistr\""));

            System.out.println("-- Kept and logged --");
            Moderators again = new Moderators(dir.resolve("moderators.txt"), users, sessions, () -> now[0]).members(fake).init();
            checkTrue("rights survive a restart", again.has("gad", "players.vistr") && again.has("baraka", "users.modifyAny") && !again.has("baraka", "users.email"));
            r = get("/api/admin/moderators", admin);
            checkTrue("the super-admin sees what moderators did", r[1].contains("\"action\":\"added user\",\"target\":\"juma_k\"")
                    && r[1].contains("\"action\":\"removed user\",\"target\":\"juma_k\"") && r[1].contains("\"action\":\"game ID\",\"target\":\"neema\""));
            post("/api/admin/moderators/remove", "username=gad", admin);
            checkTrue("rights taken away: Gad is a member again", get("/api/mod/me", gad)[1].contains("\"rights\":[]")
                    && post("/api/mod/users/create", "username=y_kid&password=goodpass123", gad)[0].equals("403"));
        } finally {
            http.stop(0);
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void checkTrue(String name, boolean ok) {
        System.out.println((ok ? "  ok    " : "  FAIL  ") + name);
        if (!ok) {
            failures++;
        }
    }
}