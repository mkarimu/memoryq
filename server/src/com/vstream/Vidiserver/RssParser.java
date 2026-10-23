package com.vstream.Vidiserver;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads RSS 2.0, RSS 1.0 and Atom feeds. Only the title, the link, the short description, an id and
 * the date are kept. HTML in the description is turned into plain text. DOCTYPE declarations and
 * external entities are refused, so a hostile feed cannot read files or reach other servers.
 */
public final class RssParser {

    public static final int MAX_DESCRIPTION = 1200;
    private static final int MAX_ITEMS = 50;
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");

    public static final class FeedItem {
        public final String guid;
        public final String title;
        public final String link;
        public final String description;
        public final long published;

        FeedItem(String guid, String title, String link, String description, long published) {
            this.guid = guid;
            this.title = title;
            this.link = link;
            this.description = description;
            this.published = published;
        }
    }

    private RssParser() {
    }

    public static List<FeedItem> parse(byte[] xml) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new DefaultHandler());
            doc = b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("that is not a readable RSS or Atom feed");
        }
        List<FeedItem> result = new ArrayList<FeedItem>();
        collect(doc.getElementsByTagName("item"), result);
        collect(doc.getElementsByTagName("entry"), result);
        return result;
    }

    private static void collect(NodeList nodes, List<FeedItem> result) {
        for (int i = 0; i < nodes.getLength() && result.size() < MAX_ITEMS; i++) {
            Node n = nodes.item(i);
            if (n instanceof Element) {
                FeedItem item = toItem((Element) n);
                if (item != null) {
                    result.add(item);
                }
            }
        }
    }

    private static FeedItem toItem(Element e) {
        String title = plain(childText(e, "title"), 300);
        String link = linkOf(e);
        if (title.isEmpty() || link == null) {
            return null;
        }
        String raw = firstNonEmpty(childText(e, "description"), childText(e, "summary"),
                childText(e, "content:encoded"), childText(e, "content"));
        String description = plain(raw, MAX_DESCRIPTION);
        String guid = firstNonEmpty(childText(e, "guid"), childText(e, "id")).trim();
        if (guid.length() > 300) {
            guid = guid.substring(0, 300);
        }
        long when = parseDate(firstNonEmpty(childText(e, "pubDate"), childText(e, "published"),
                childText(e, "updated"), childText(e, "dc:date")));
        return new FeedItem(guid, title, link, description, when);
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static String childText(Element e, String name) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element && c.getNodeName().equals(name)) {
                String t = c.getTextContent();
                if (t != null && !t.trim().isEmpty()) {
                    return t.trim();
                }
            }
        }
        return "";
    }

    private static String linkOf(Element e) {
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element) || !c.getNodeName().equals("link")) {
                continue;
            }
            Element l = (Element) c;
            String candidate = "";
            String href = l.getAttribute("href");
            if (href != null && !href.isEmpty()) {
                String rel = l.getAttribute("rel");
                if (rel == null || rel.isEmpty() || rel.equals("alternate")) {
                    candidate = href;
                }
            } else if (l.getTextContent() != null) {
                candidate = l.getTextContent().trim();
            }
            if (isWebLink(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static boolean isWebLink(String s) {
        if (s == null || s.length() > 1000 || s.length() < 8) {
            return false;
        }
        String l = s.toLowerCase(Locale.ROOT);
        if (!(l.startsWith("http://") || l.startsWith("https://"))) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i)) || s.charAt(i) < 0x20) {
                return false;
            }
        }
        return true;
    }

    static long parseDate(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0;
        }
        String v = s.trim();
        try {
            return ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // try the next format
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // try the next format
        }
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** HTML to plain text: tags removed, entities decoded, spaces collapsed, cut to max characters. */
    static String plain(String html, int max) {
        if (html == null) {
            return "";
        }
        String s = TAGS.matcher(html).replaceAll(" ");
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = decode(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(rep != null ? rep : m.group(0)));
        }
        m.appendTail(sb);
        StringBuilder out = new StringBuilder(sb.length());
        boolean space = true;
        for (int i = 0; i < sb.length(); i++) {
            char ch = sb.charAt(i);
            if (ch < 0x20 || ch == 0x7f || Character.isWhitespace(ch)) {
                if (!space) {
                    out.append(' ');
                    space = true;
                }
            } else {
                out.append(ch);
                space = false;
            }
        }
        String text = out.toString().trim();
        return text.length() > max ? text.substring(0, max).trim() : text;
    }

    private static String decode(String name) {
        if (name.startsWith("#")) {
            try {
                int cp = (name.charAt(1) == 'x' || name.charAt(1) == 'X')
                        ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                if (cp < 0x20 || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
                    return " ";
                }
                return new String(Character.toChars(cp));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "nbsp": return " ";
            case "amp": return "&";
            case "lt": return "<";
            case "gt": return ">";
            case "quot": return "\"";
            case "apos": return "'";
            case "rsquo": case "lsquo": return "'";
            case "ldquo": case "rdquo": return "\"";
            case "ndash": case "mdash": return "-";
            case "hellip": return "...";
            default: return null;
        }
    }
}