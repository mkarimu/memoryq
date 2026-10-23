package com.vstream.broadcaster;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads people's drawn avatars and profile pictures from the server, with a small memory cache. */
final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(60);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    /** Shows the person's avatar (avatar = true) or profile picture in the view. */
    static void load(final ImageView view, final String host, final String username, final boolean avatar) {
        if (host == null || host.isEmpty() || username == null || username.isEmpty()) {
            return;
        }
        final String key = (avatar ? "a|" : "p|") + username.toLowerCase(java.util.Locale.ROOT);
        view.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap b = fetch(host, username, avatar);
            if (b == null) {
                return;
            }
            CACHE.put(key, b);
            MAIN.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(b);
                }
            });
        });
    }

    /** Shows any picture the server serves at path (for example an ad picture), cached under key. */
    static void loadPath(final ImageView view, final String host, final String path, final String key) {
        if (host == null || host.isEmpty() || path == null || !path.startsWith("/")) {
            return;
        }
        view.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        POOL.execute(() -> {
            Bitmap b = fetchUrl(Api.base(host) + path);
            if (b == null) {
                return;
            }
            CACHE.put(key, b);
            MAIN.post(() -> {
                if (key.equals(view.getTag())) {
                    view.setImageBitmap(b);
                }
            });
        });
    }

    /** Forget a person's pictures (after they edit their name or country). */
    static void forget(String username) {
        String u = username == null ? "" : username.toLowerCase(java.util.Locale.ROOT);
        CACHE.remove("a|" + u);
        CACHE.remove("p|" + u);
    }

    private static Bitmap fetch(String host, String username, boolean avatar) {
        try {
            String path = avatar ? "/api/profiles/avatar?u=" : "/api/profiles/picture?u=";
            return fetchUrl(Api.base(host) + path + URLEncoder.encode(username, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    private static Bitmap fetchUrl(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            InputStream in = c.getInputStream();
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            return b;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}