package com.vstream.Vidiserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sends email through the mail server on this machine (Postfix on the VPS, 127.0.0.1:25), which signs it (DKIM)
 * and delivers it. Settings (Java system properties):
 *   vstream.smtpHost (127.0.0.1)  vstream.smtpPort (25)
 *   vstream.mailFrom (no-reply@memoryq.com)  vstream.mailName (Memoryq)  vstream.mailReplyTo (support@memoryq.com)
 * On a Windows PC without a mail server, the message is printed in the server window instead, so sign-ups can
 * still be tested there.
 */
public final class Mailer {

    /** A plain, safe email address: no spaces, quotes, angle brackets or line breaks. */
    public static final Pattern ADDRESS =
            Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)+");

    private final String host;
    private final int port;
    private final String from;
    private final String fromName;
    private final String replyTo;
    private final boolean consoleFallback;

    public Mailer(String host, int port, String from, String fromName, String replyTo, boolean consoleFallback) {
        this.host = host;
        this.port = port;
        this.from = from;
        this.fromName = fromName;
        this.replyTo = replyTo;
        this.consoleFallback = consoleFallback;
    }

    public static Mailer fromSystemProperties() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return new Mailer(System.getProperty("vstream.smtpHost", "127.0.0.1"),
                Integer.getInteger("vstream.smtpPort", 25),
                System.getProperty("vstream.mailFrom", "no-reply@memoryq.com"),
                System.getProperty("vstream.mailName", "Memoryq"),
                System.getProperty("vstream.mailReplyTo", "support@memoryq.com"),
                Boolean.parseBoolean(System.getProperty("vstream.mailToConsole", String.valueOf(windows))));
    }

    public static boolean validAddress(String a) {
        return a != null && a.length() <= 200 && ADDRESS.matcher(a).matches();
    }

    /** Sends a plain-text email. Returns null when it was handed to the mail server, otherwise what went wrong. */
    public String send(String to, String subject, String text) {
        if (!validAddress(to)) {
            return "that email address does not look right";
        }
        try {
            deliver(to, subject, text);
            java.util.function.Consumer<String[]> log = sentLog;
            if (log != null) {
                try {
                    log.accept(new String[]{to, subject, text});
                } catch (RuntimeException e) {
                    // the mail centre's copy is not important enough to fail a send
                }
            }
            return null;
        } catch (IOException e) {
            if (consoleFallback && (e instanceof java.net.ConnectException)) {
                System.out.println("[mail] no mail server on this PC - the email is shown here instead:");
                System.out.println("[mail]   To: " + to);
                System.out.println("[mail]   Subject: " + subject);
                for (String line : text.split("\n")) {
                    System.out.println("[mail]   " + line);
                }
                return null;
            }
            System.out.println("[mail] could not send to " + to + ": " + e.getMessage());
            return "the email could not be sent right now - try again in a few minutes";
        }
    }

    /** Every email Memoryq sends by itself (sign-up codes, alerts...) is copied to the admin's mail centre (System folder). */
    static volatile java.util.function.Consumer<String[]> sentLog;

    public String from() {
        return from;
    }

    /** Sends a whole message made elsewhere (the mail centre's replies, forwards, attachments). Null = handed over. */
    public String sendRaw(String envelopeFrom, java.util.List<String> rcpts, String raw) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = s.getOutputStream();
            expect(in, 220);
            cmd(out, "EHLO " + envelopeFrom.substring(envelopeFrom.indexOf('@') + 1));
            expect(in, 250);
            cmd(out, "MAIL FROM:<" + envelopeFrom + ">");
            expect(in, 250);
            for (String r : rcpts) {
                if (!validAddress(r)) {
                    return "\"" + r + "\" is not an email address";
                }
                cmd(out, "RCPT TO:<" + r + ">");
                expect(in, 250);
            }
            cmd(out, "DATA");
            expect(in, 354);
            StringBuilder sb = new StringBuilder();
            for (String line : raw.split("\r\n", -1)) {
                sb.append(line.startsWith(".") ? "." + line : line).append("\r\n");
            }
            String data = sb.toString();
            if (data.endsWith("\r\n\r\n")) {
                data = data.substring(0, data.length() - 2);
            }
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            expect(in, 250);
            cmd(out, "QUIT");
            return null;
        } catch (IOException e) {
            if (consoleFallback && (e instanceof java.net.ConnectException)) {
                System.out.println("[mail] no mail server on this PC - not sent: " + rcpts);
                return null;
            }
            return "the mail server did not take it: " + e.getMessage();
        }
    }

    private void deliver(String to, String subject, String text) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = s.getOutputStream();
            expect(in, 220);
            String helo = from.substring(from.indexOf('@') + 1);
            cmd(out, "EHLO " + helo);
            expect(in, 250);
            cmd(out, "MAIL FROM:<" + from + ">");
            expect(in, 250);
            cmd(out, "RCPT TO:<" + to + ">");
            expect(in, 250);
            cmd(out, "DATA");
            expect(in, 354);
            out.write(message(to, subject, text).getBytes(StandardCharsets.US_ASCII));
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            expect(in, 250);
            cmd(out, "QUIT");
        }
    }

    String message(String to, String subject, String text) {
        SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        String domain = from.substring(from.indexOf('@') + 1);
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(header(fromName)).append(" <").append(from).append(">\r\n");
        sb.append("To: <").append(to).append(">\r\n");
        if (replyTo != null && !replyTo.isEmpty()) {
            sb.append("Reply-To: <").append(replyTo).append(">\r\n");
        }
        sb.append("Subject: ").append(header(subject)).append("\r\n");
        sb.append("Date: ").append(date.format(new Date())).append("\r\n");
        sb.append("Message-ID: <").append(UUID.randomUUID()).append('@').append(domain).append(">\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        String body = text.replace("\r\n", "\n").replace("\n", "\r\n");
        sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        return sb.toString();
    }

    /** A header value: plain ASCII as it is, anything else as UTF-8 base64 (RFC 2047). No line breaks. */
    static String header(String v) {
        String clean = v.replace('\r', ' ').replace('\n', ' ');
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c < 32 || c > 126) {
                return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.getBytes(StandardCharsets.UTF_8)) + "?=";
            }
        }
        return clean;
    }

    private static void cmd(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** Reads one (possibly multi-line) reply and checks its code. */
    private static void expect(BufferedReader in, int code) throws IOException {
        String line;
        do {
            line = in.readLine();
            if (line == null) {
                throw new IOException("the mail server closed the connection");
            }
        } while (line.length() > 3 && line.charAt(3) == '-');
        int got;
        try {
            got = Integer.parseInt(line.substring(0, 3));
        } catch (RuntimeException e) {
            throw new IOException("unexpected answer from the mail server: " + line);
        }
        if (got != code) {
            throw new IOException("the mail server said: " + line);
        }
    }
}