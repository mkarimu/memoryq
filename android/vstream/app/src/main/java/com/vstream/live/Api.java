package com.vstream.live;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Small client for the Memoryq account API. Call it off the main thread. */
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
                return Character.toUpperCase(e.charAt(0)) + e.substring(1);
            }
            return code == 0 ? "Cannot reach the server - check your internet connection" : "Server error " + code;
        }
    }

    private Api() {
    }

    /** memoryq.com is reached over https; a PC address on Wi-Fi (for testing) over http on 8080. */
    static boolean isDomain(String host) {
        String h = host.trim();
        return h.matches(".*[a-zA-Z].*\\.[a-zA-Z]{2,}$") && !h.contains(":");
    }

    static String base(String host) {
        return isDomain(host) ? "https://" + host.trim() : "http://" + host.trim() + ":8080";
    }

    /** Where live video is played from: https://memoryq.com/hls, or http://PC:8888 on Wi-Fi. */
    static String hls(String host) {
        return isDomain(host) ? "https://" + host.trim() + "/hls" : "http://" + host.trim() + ":8888";
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
                sb.append(URLEncoder.encode(pairs[i], "UTF-8")).append('=').append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
            }
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 is always there
        }
        return sb.toString();
    }

    private static Result call(String method, String host, String path, String body, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base(host) + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
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
                while ((line = reader.readLine()) != null && sb.length() < 131072) {
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