package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an email (headers, the text and HTML parts, attachments - multipart, base64, quoted-printable, any charset,
 * =?UTF-8?B?...?= headers) and writes one (text, attachments, a reply's In-Reply-To). No libraries needed.
 */
final class MimeMail {

    static final class Attachment {
        String name = "attachment";
        String type = "application/octet-stream";
        byte[] data = new byte[0];
        String cid = "";
    }

    Map<String, String> headers = new LinkedHashMap<String, String>(); // lower-case name -> decoded value (first one)
    String text = "";
    String html = "";
    List<Attachment> attachments = new ArrayList<Attachment>();

    String header(String name) {
        String v = headers.get(name.toLowerCase(Locale.ROOT));
        return v == null ? "" : v;
    }

    // ---- reading ----

    static MimeMail parse(byte[] raw) {
        MimeMail m = new MimeMail();
        Part p = Part.of(raw);
        m.headers = p.headers;
        m.walk(p, 0);
        if (m.text.isEmpty() && !m.html.isEmpty()) {
            m.text = htmlToText(m.html);
        }
        return m;
    }

    private void walk(Part p, int depth) {
        String ct = p.contentType();
        String disp = p.raw("content-disposition").toLowerCase(Locale.ROOT);
        if (ct.startsWith("multipart/") && depth < 12) {
            String boundary = param(p.raw("content-type"), "boundary");
            if (!boundary.isEmpty()) {
                for (byte[] child : split(p.body, boundary)) {
                    walk(Part.of(child), depth + 1);
                }
                return;
            }
        }
        if (ct.equals("message/rfc822") && depth < 12) {
            Attachment a = new Attachment();
            a.name = "forwarded-message.eml";
            a.type = ct;
            a.data = p.decoded();
            attachments.add(a);
            return;
        }
        String fileName = param(p.raw("content-disposition"), "filename");
        if (fileName.isEmpty()) {
            fileName = param(p.raw("content-type"), "name");
        }
        boolean isAttachment = disp.startsWith("attachment") || (!fileName.isEmpty() && !ct.startsWith("text/"));
        if (!isAttachment && ct.equals("text/plain") && text.isEmpty()) {
            text = p.text();
        } else if (!isAttachment && ct.equals("text/html") && html.isEmpty()) {
            html = p.text();
        } else if (!ct.startsWith("multipart/")) {
            Attachment a = new Attachment();
            a.name = fileName.isEmpty() ? (ct.startsWith("image/") ? "image." + ct.substring(6) : "attachment") : decodeWords(fileName);
            a.name = a.name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
            a.type = ct.isEmpty() ? "application/octet-stream" : ct;
            a.data = p.decoded();
            a.cid = p.raw("content-id").replaceAll("[<>\\s]", "");
            if (a.data.length > 0) {
                attachments.add(a);
            }
        }
    }

    /** One part: its headers and its (still encoded) body. */
    static final class Part {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        Map<String, String> rawHeaders = new LinkedHashMap<String, String>();
        byte[] body = new byte[0];

        static Part of(byte[] raw) {
            Part p = new Part();
            int end = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), 0);
            int skip = 4;
            int lf = indexOf(raw, "\n\n".getBytes(StandardCharsets.US_ASCII), 0);
            if (end < 0 || (lf >= 0 && lf < end)) {
                end = lf;
                skip = 2;
            }
            String head = end < 0 ? new String(raw, StandardCharsets.ISO_8859_1) : new String(raw, 0, end, StandardCharsets.ISO_8859_1);
            p.body = end < 0 ? new byte[0] : java.util.Arrays.copyOfRange(raw, end + skip, raw.length);
            String name = null;
            StringBuilder val = new StringBuilder();
            for (String line : head.split("\r?\n")) {
                if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t') && name != null) {
                    val.append(' ').append(line.trim());
                    continue;
                }
                if (name != null) {
                    p.put(name, val.toString());
                }
                int c = line.indexOf(':');
                if (c > 0) {
                    name = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    val.setLength(0);
                    val.append(line.substring(c + 1).trim());
                } else {
                    name = null;
                }
            }
            if (name != null) {
                p.put(name, val.toString());
            }
            return p;
        }

        private void put(String name, String v) {
            if (!rawHeaders.containsKey(name)) {
                rawHeaders.put(name, v);
                headers.put(name, decodeWords(utf8Guess(v)));
            }
        }

        String raw(String name) {
            String v = rawHeaders.get(name);
            return v == null ? "" : v;
        }

        String contentType() {
            String ct = raw("content-type").toLowerCase(Locale.ROOT);
            int semi = ct.indexOf(';');
            return (semi < 0 ? ct : ct.substring(0, semi)).trim().isEmpty() ? "text/plain" : (semi < 0 ? ct : ct.substring(0, semi)).trim();
        }

        byte[] decoded() {
            String enc = raw("content-transfer-encoding").toLowerCase(Locale.ROOT).trim();
            if (enc.equals("base64")) {
                try {
                    return Base64.getMimeDecoder().decode(new String(body, StandardCharsets.US_ASCII).replaceAll("[^A-Za-z0-9+/=]", ""));
                } catch (IllegalArgumentException e) {
                    return new byte[0];
                }
            }
            if (enc.equals("quoted-printable")) {
                return quotedPrintable(body);
            }
            return body;
        }

        String text() {
            String cs = param(raw("content-type"), "charset");
            Charset charset = StandardCharsets.UTF_8;
            try {
                if (!cs.isEmpty()) {
                    charset = Charset.forName(cs.trim());
                }
            } catch (Exception e) {
                charset = StandardCharsets.UTF_8;
            }
            return new String(decoded(), charset).replace("\r\n", "\n");
        }
    }

    static String utf8Guess(String latin1) {
        // headers are read as ISO-8859-1; raw UTF-8 in them (common) is turned back into proper text
        byte[] b = latin1.getBytes(StandardCharsets.ISO_8859_1);
        for (byte x : b) {
            if (x < 0) {
                String u = new String(b, StandardCharsets.UTF_8);
                return u.indexOf('\uFFFD') >= 0 ? latin1 : u;
            }
        }
        return latin1;
    }

    static int indexOf(byte[] a, byte[] b, int from) {
        outer:
        for (int i = Math.max(0, from); i <= a.length - b.length; i++) {
            for (int j = 0; j < b.length; j++) {
                if (a[i + j] != b[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static List<byte[]> split(byte[] body, String boundary) {
        List<byte[]> out = new ArrayList<byte[]>();
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int pos = indexOf(body, delim, 0);
        while (pos >= 0) {
            int start = pos + delim.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') {
                break; // the closing boundary
            }
            while (start < body.length && (body[start] == '\r' || body[start] == '\n' || body[start] == ' ' || body[start] == '\t')) {
                if (body[start] == '\n') {
                    start++;
                    break;
                }
                start++;
            }
            int next = indexOf(body, delim, start);
            int end = next < 0 ? body.length : next;
            if (end > start && body[end - 1] == '\n') {
                end--;
            }
            if (end > start && body[end - 1] == '\r') {
                end--;
            }
            out.add(java.util.Arrays.copyOfRange(body, start, Math.max(start, end)));
            pos = next;
        }
        return out;
    }

    static byte[] quotedPrintable(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < in.length; i++) {
            byte b = in[i];
            if (b == '=') {
                if (i + 1 < in.length && (in[i + 1] == '\r' || in[i + 1] == '\n')) { // a soft line break
                    i++;
                    if (in[i] == '\r' && i + 1 < in.length && in[i + 1] == '\n') {
                        i++;
                    }
                    continue;
                }
                if (i + 2 < in.length) {
                    int h = Character.digit(in[i + 1], 16), l = Character.digit(in[i + 2], 16);
                    if (h >= 0 && l >= 0) {
                        out.write(h * 16 + l);
                        i += 2;
                        continue;
                    }
                }
            }
            out.write(b);
        }
        return out.toByteArray();
    }

    static String param(String header, String name) {
        Matcher m = Pattern.compile("(?i)(?:^|;)\\s*" + Pattern.quote(name) + "\\*?\\s*=\\s*(\"([^\"]*)\"|([^;\\s]*))").matcher(header == null ? "" : header);
        if (!m.find()) {
            return "";
        }
        String v = m.group(2) != null ? m.group(2) : m.group(3);
        if (v != null && v.matches("(?i)utf-8''.*")) { // RFC 2231: filename*=UTF-8''na%20me.pdf
            try {
                return java.net.URLDecoder.decode(v.substring(7).replace("+", "%2B"), "UTF-8");
            } catch (Exception e) {
                return v.substring(7);
            }
        }
        return v == null ? "" : v;
    }

    private static final Pattern WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");

    /** =?UTF-8?B?...?= and =?ISO-8859-1?Q?...?= in a header. */
    static String decodeWords(String s) {
        if (s == null || s.indexOf("=?") < 0) {
            return s == null ? "" : s;
        }
        Matcher m = WORD.matcher(s.replaceAll("\\?=\\s+=\\?", "?==?"));
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String out;
            try {
                Charset cs = Charset.forName(m.group(1));
                byte[] b;
                if (m.group(2).equalsIgnoreCase("B")) {
                    b = Base64.getDecoder().decode(m.group(3));
                } else {
                    b = quotedPrintable(m.group(3).replace('_', ' ').getBytes(StandardCharsets.US_ASCII));
                }
                out = new String(b, cs);
            } catch (Exception e) {
                out = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String htmlToText(String html) {
        String t = html.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").trim();
    }

    /** The plain address in "Name <a@b.c>". */
    static String address(String h) {
        Matcher m = Pattern.compile("<([^<>\\s]+@[^<>\\s]+)>").matcher(h == null ? "" : h);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        Matcher m2 = Pattern.compile("([A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+)").matcher(h == null ? "" : h);
        return m2.find() ? m2.group(1).toLowerCase(Locale.ROOT) : "";
    }

    static String displayName(String h) {
        String s = h == null ? "" : h.trim();
        int lt = s.indexOf('<');
        String n = lt > 0 ? s.substring(0, lt).trim().replaceAll("^\"|\"$", "") : "";
        return n.isEmpty() ? address(s) : n;
    }

    // ---- writing ----

    /** A whole message: text, optional HTML, attachments; headers for a reply. Returns the raw message and its Message-ID. */
    static String[] build(String fromName, String from, List<String> to, List<String> cc, String replyTo, String subject, String text,
                          List<Attachment> files, String inReplyTo, String references) {
        String domain = from.substring(from.indexOf('@') + 1);
        String id = "<" + UUID.randomUUID() + "@" + domain + ">";
        SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(Mailer.header(fromName)).append(" <").append(from).append(">\r\n");
        sb.append("To: ").append(join(to)).append("\r\n");
        if (cc != null && !cc.isEmpty()) {
            sb.append("Cc: ").append(join(cc)).append("\r\n");
        }
        if (replyTo != null && !replyTo.isEmpty()) {
            sb.append("Reply-To: <").append(replyTo).append(">\r\n");
        }
        sb.append("Subject: ").append(Mailer.header(subject)).append("\r\n");
        sb.append("Date: ").append(date.format(new Date())).append("\r\n");
        sb.append("Message-ID: ").append(id).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isEmpty()) {
            sb.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
            sb.append("References: ").append((references == null || references.isEmpty() ? "" : references + " ") + inReplyTo).append("\r\n");
        }
        sb.append("MIME-Version: 1.0\r\n");
        String body = text.replace("\r\n", "\n").replace("\n", "\r\n");
        String b64 = mimeB64(body.getBytes(StandardCharsets.UTF_8));
        if (files == null || files.isEmpty()) {
            sb.append("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n").append(b64).append("\r\n");
        } else {
            String b = "mq-" + UUID.randomUUID().toString().replace("-", "");
            sb.append("Content-Type: multipart/mixed; boundary=\"").append(b).append("\"\r\n\r\n");
            sb.append("--").append(b).append("\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n").append(b64).append("\r\n");
            for (Attachment a : files) {
                String n = a.name.replaceAll("[\"\\r\\n]", "_");
                boolean ascii = n.matches("[\\x20-\\x7e]*");
                sb.append("--").append(b).append("\r\nContent-Type: ").append(a.type.replaceAll("[\\r\\n;\"]", "")).append(ascii ? "; name=\"" + n + "\"" : "")
                        .append("\r\nContent-Disposition: attachment; ").append(ascii ? "filename=\"" + n + "\"" : "filename*=UTF-8''" + pct(n))
                        .append("\r\nContent-Transfer-Encoding: base64\r\n\r\n").append(mimeB64(a.data)).append("\r\n");
            }
            sb.append("--").append(b).append("--\r\n");
        }
        return new String[]{sb.toString(), id};
    }

    private static String pct(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String a : list) {
            sb.append(sb.length() > 0 ? ", " : "").append('<').append(a).append('>');
        }
        return sb.toString();
    }

    private static String mimeB64(byte[] b) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(b);
    }
}