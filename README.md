# Memoryq

The Memoryq platform (memoryq.com, mkarimu.net): server, web pages and the Android apps.

- `server/src` - the Java server (Java 8, no libraries), with its tests (*Test.java)
- `server/pages` - the web pages (home, me, admin, studio, djpro, tv, market...)
- `server/scripts/deploy-vps.ps1` - sends the server to the VPS
- `android/memoryq` - the Memoryq app (com.vstream.broadcaster)
- `android/vstream` - the Vstream app (com.vstream.live)

Not in here (on purpose): users and their data, passwords, Stripe and OpenAI keys, the Play upload key, recordings, music.
Updated by vistr-and-git.ps1 from the working folders on the PC.
