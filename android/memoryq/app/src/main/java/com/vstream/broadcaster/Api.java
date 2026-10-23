package com.vstream.broadcaster;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Small client for the server's account and channel-pool API (port 8080). Call it off the main thread. */
final class Api {

    static final class Result {
        final int code;
        final String body;

        Result(int code, String body) {
            this.code = code;
            this.body = body;
        }

        JSONObject json() {
            try {
                return new JSONObject(body);
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        String error() {
            String e = json().optString("error", "");
            if (!e.isEmpty()) {
                return e;
            }
            return code == 0 ? "Cannot reach the server" : "Error " + code;
        }
    }

    private Api() {
    }

    static Result get(String host, String path, String token) {
        return call("GET", host, path, null, token);
    }

    static Result post(String host, String path, String form, String token) {
        return call("POST", host, path, form == null ? "" : form, token);
    }

    static String form(String... pairs) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=')
                        .append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always available
        }
        return sb.toString();
    }

    /** The public server: a new phone uses it until someone picks another server in Settings. */
    static final String DEFAULT_HOST = "memoryq.com";

    /**
     * True for an internet name such as memoryq.com: the app then talks to it over HTTPS (port 443, through the
     * web server) and plays live video from /hls. Wi-Fi addresses (192.168.x.x, a PC name) keep http on 8080/8888.
     */
    static boolean isDomain(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(java.util.Locale.US);
        if (h.isEmpty() || h.indexOf(':') >= 0 || h.indexOf('.') < 0 || h.endsWith(".local") || h.endsWith(".lan")) {
            return false;
        }
        for (int i = 0; i < h.length(); i++) {
            char ch = h.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                return true;
            }
        }
        return false;
    }

    /** The server's web address: https://memoryq.com, or http://192.168.1.20:8080 for a PC on Wi-Fi. */
    static String base(String host) {
        return isDomain(host) ? "https://" + host.trim() : "http://" + host.trim() + ":8080";
    }

    /** Where live video is played from: https://memoryq.com/hls, or http://192.168.1.20:8888 on Wi-Fi. */
    static String hls(String host) {
        return isDomain(host) ? "https://" + host.trim() + "/hls" : "http://" + host.trim() + ":8888";
    }

    /** Turns a live-video address from the server (http://name:8888/...) into one that works over HTTPS. */
    static String fixHls(String address, String host) {
        if (address == null || !isDomain(host)) {
            return address;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https?://[^/]+:8888(/.*)?$").matcher(address);
        return m.matches() ? hls(host) + (m.group(1) == null ? "" : m.group(1)) : address;
    }
    private static Result call(String method, String host, String path, String body, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base(host) + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(3000);
            c.setReadTimeout(6000);
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && sb.length() < 65536) {
                    sb.append(line);
                }
                reader.close();
            }
            return new Result(code, sb.toString());
        } catch (Exception e) {
            return new Result(0, "");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}