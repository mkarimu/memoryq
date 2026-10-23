package com.vstream.broadcaster;

import java.util.regex.Pattern;

/** Keeps server addresses out of messages shown on screen. */
final class Mask {

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");

    private Mask() {
    }

    static String ips(String text) {
        return text == null ? "" : IPV4.matcher(text).replaceAll("[server]");
    }
}