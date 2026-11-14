package com.vstream.Vidiserver;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A game's performance report: for each player, the games played every day and every week of a period (UTC dates, weeks start on
 * Monday). Built for paying moderators for the players they bring in. Comes out as a table on screen (JSON), CSV or PDF.
 */
final class PerformanceReport {

    static final int MAX_DAYS = 366;

    /** The time zone a game's days are cut in when the game does not say (Vistr counts in Los Angeles time, like Weekly Champions). */
    static String tzOf(String game) {
        return "vistr".equals(game) ? "America/Los_Angeles" : "UTC";
    }

    /** Today's date in a time zone (IANA name); UTC when the name is not known. */
    static String todayIn(String tz) {
        try {
            return LocalDate.now(java.time.ZoneId.of(tz)).toString();
        } catch (RuntimeException e) {
            return LocalDate.now(java.time.ZoneOffset.UTC).toString();
        }
    }

    /** One player. days: date (YYYY-MM-DD) -> games that day. */
    static final class Row {
        final String email;
        final String username;
        final String gameId;
        final String moderator;
        final Map<String, Integer> days = new HashMap<String, Integer>();
        /** date -> {games, points, last game time (ms)}: what the game kept for that day. */
        final Map<String, long[]> daily = new HashMap<String, long[]>();
        /** Each game from the day the game started its play log: {time (ms), word length, points, "solo"/"duo", "app"/"web", date}. */
        final List<String[]> plays = new ArrayList<String[]>();

        Row(String email, String username, String gameId, String moderator) {
            this.email = email == null ? "" : email;
            this.username = username == null ? "" : username;
            this.gameId = gameId == null ? "" : gameId;
            this.moderator = moderator == null ? "" : moderator;
        }
    }

    final String gameName;
    final String scope;
    final String from;
    final String to;
    final String generated;
    /** Today's UTC date: the report always shows what each player played today, whatever the period. */
    String today = "";
    /** The time zone the days are cut in (IANA name; "UTC" for a game that counts in UTC). */
    String tz = "UTC";
    /** The first day the game listed single games from (empty: none yet). Days before it only have totals. */
    String logFrom = "";
    /** What a day is worth: at least payThreshold games in a day pays payCents once (no more for extra games, nothing for fewer). */
    int payThreshold = 6;
    long payCents = 2000;
    String payCurrency = "USD";
    final List<String> dates;
    /** The Monday of each week the period touches, in order. */
    final List<String> weeks = new ArrayList<String>();
    final List<Row> rows = new ArrayList<Row>();

    PerformanceReport(String gameName, String scope, String from, String to, String generated) {
        this.gameName = gameName;
        this.scope = scope;
        this.from = from;
        this.to = to;
        this.generated = generated;
        this.dates = dates(from, to);
        for (String d : dates) {
            String w = weekOf(d);
            if (!weeks.contains(w)) {
                weeks.add(w);
            }
        }
    }

    /** Every date from..to inclusive; throws IllegalArgumentException for a bad or too long period. */
    static List<String> dates(String from, String to) {
        LocalDate a;
        LocalDate b;
        try {
            a = LocalDate.parse(from);
            b = LocalDate.parse(to);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("dates must look like 2026-10-31");
        }
        if (b.isBefore(a)) {
            throw new IllegalArgumentException("the first date is after the last one");
        }
        if (a.plusDays(MAX_DAYS).isBefore(b)) {
            throw new IllegalArgumentException("a report covers at most " + MAX_DAYS + " days");
        }
        List<String> out = new ArrayList<String>();
        for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
            out.add(d.toString());
        }
        return out;
    }

    /** The Monday on or before this date. */
    static String weekOf(String date) {
        LocalDate d = LocalDate.parse(date);
        return d.minusDays(d.getDayOfWeek().getValue() - 1).toString();
    }

    int day(Row r, String date) {
        Integer n = r.days.get(date);
        return n == null ? 0 : n.intValue();
    }

    int week(Row r, String monday) {
        int t = 0;
        for (String d : dates) {
            if (weekOf(d).equals(monday)) {
                t += day(r, d);
            }
        }
        return t;
    }

    int todayOf(Row r) {
        return today.isEmpty() ? 0 : day(r, today);
    }

    int todayTotal() {
        int t = 0;
        for (Row r : rows) {
            t += todayOf(r);
        }
        return t;
    }

    int total(Row r) {
        int t = 0;
        for (String d : dates) {
            t += day(r, d);
        }
        return t;
    }

    int dayTotal(String date) {
        int t = 0;
        for (Row r : rows) {
            t += day(r, date);
        }
        return t;
    }

    int weekTotal(String monday) {
        int t = 0;
        for (Row r : rows) {
            t += week(r, monday);
        }
        return t;
    }

    int grandTotal() {
        int t = 0;
        for (Row r : rows) {
            t += total(r);
        }
        return t;
    }

    // ---- CSV: one file with the day-by-day columns, then the weekly totals, then the total ----

    String csv() {
        StringBuilder sb = new StringBuilder("\uFEFF");
        List<String> h = new ArrayList<String>();
        h.add("Email");
        h.add("Username");
        h.add("Game ID");
        h.add("Moderator");
        for (String d : dates) {
            h.add(d);
        }
        for (String w : weeks) {
            h.add("Week of " + w);
        }
        h.add("Total games");
        line(sb, h);
        for (Row r : rows) {
            List<String> c = new ArrayList<String>();
            c.add(r.email);
            c.add(r.username);
            c.add(r.gameId);
            c.add(r.moderator);
            for (String d : dates) {
                c.add(String.valueOf(day(r, d)));
            }
            for (String w : weeks) {
                c.add(String.valueOf(week(r, w)));
            }
            c.add(String.valueOf(total(r)));
            line(sb, c);
        }
        List<String> t = new ArrayList<String>();
        t.add("All players");
        t.add("");
        t.add("");
        t.add("");
        for (String d : dates) {
            t.add(String.valueOf(dayTotal(d)));
        }
        for (String w : weeks) {
            t.add(String.valueOf(weekTotal(w)));
        }
        t.add(String.valueOf(grandTotal()));
        line(sb, t);
        return sb.toString();
    }

    private static void line(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            sb.append(i > 0 ? "," : "").append(cell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** A CSV cell; text that a spreadsheet would run as a formula gets a leading apostrophe. */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+")) {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---- JSON for the page ----

    String json() {
        StringBuilder sb = new StringBuilder("{\"game\":").append(q(gameName)).append(",\"scope\":").append(q(scope)).append(",\"from\":").append(q(from))
                .append(",\"to\":").append(q(to)).append(",\"generated\":").append(q(generated)).append(",\"today\":").append(q(today))
                .append(",\"tz\":").append(q(tz)).append(",\"logFrom\":").append(q(logFrom))
                .append(",\"pay\":{\"threshold\":").append(payThreshold).append(",\"cents\":").append(payCents).append(",\"currency\":").append(q(payCurrency)).append("},\"dates\":[");
        for (int i = 0; i < dates.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(dates.get(i)));
        }
        sb.append("],\"weeks\":[");
        for (int i = 0; i < weeks.size(); i++) {
            sb.append(i > 0 ? "," : "").append(q(weeks.get(i)));
        }
        sb.append("],\"rows\":[");
        for (int k = 0; k < rows.size(); k++) {
            Row r = rows.get(k);
            sb.append(k > 0 ? "," : "").append("{\"email\":").append(q(r.email)).append(",\"username\":").append(q(r.username)).append(",\"gameId\":")
                    .append(q(r.gameId)).append(",\"moderator\":").append(q(r.moderator)).append(",\"days\":[");
            for (int i = 0; i < dates.size(); i++) {
                sb.append(i > 0 ? "," : "").append(day(r, dates.get(i)));
            }
            sb.append("],\"weekTotals\":[");
            for (int i = 0; i < weeks.size(); i++) {
                sb.append(i > 0 ? "," : "").append(week(r, weeks.get(i)));
            }
            sb.append("],\"total\":").append(total(r)).append(",\"today\":").append(todayOf(r)).append(",\"daily\":{");
            int nd = 0;
            for (Map.Entry<String, long[]> e : new java.util.TreeMap<String, long[]>(r.daily).entrySet()) {
                sb.append(nd++ > 0 ? "," : "").append(q(e.getKey())).append(":[").append(e.getValue()[0]).append(',').append(e.getValue()[1]).append(',').append(e.getValue()[2]).append(']');
            }
            sb.append("},\"plays\":[");
            for (int i = 0; i < r.plays.size(); i++) {
                String[] p = r.plays.get(i);
                sb.append(i > 0 ? "," : "").append('[').append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(',').append(q(p[3])).append(',').append(q(p[4])).append(',').append(q(p[5])).append(']');
            }
            sb.append("]}");
        }
        sb.append("],\"dayTotals\":[");
        for (int i = 0; i < dates.size(); i++) {
            sb.append(i > 0 ? "," : "").append(dayTotal(dates.get(i)));
        }
        sb.append("],\"weekTotalsAll\":[");
        for (int i = 0; i < weeks.size(); i++) {
            sb.append(i > 0 ? "," : "").append(weekTotal(weeks.get(i)));
        }
        return sb.append("],\"total\":").append(grandTotal()).append(",\"todayTotal\":").append(todayTotal()).append('}').toString();
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---- PDF: a title, the weekly table, then the day-by-day table (split across pages when it is wide) ----

    byte[] pdf() {
        SimplePdf pdf = new SimplePdf();
        pdf.newPage();
        float y = SimplePdf.H - 40;
        pdf.text(28, y, 15, true, "Performance report - " + gameName);
        y -= 16;
        pdf.text(28, y, 9, false, "Period: " + from + " to " + to + "   (dates are " + (tz.equals("UTC") ? "UTC" : tz + " time") + "; each week starts on Monday)");
        y -= 12;
        pdf.text(28, y, 9, false, "Players: " + scope);
        y -= 12;
        pdf.text(28, y, 9, false, "Games = online games the player finished.  Generated " + generated + " UTC.  Total: " + grandTotal() + " games, " + rows.size() + " players.");
        y -= 22;

        String[] fixedH = {"Email", "Username", "Game ID", "Moderator"};
        float[] fixedW = {134, 100, 42, 100};
        String[] weekH = new String[weeks.size()];
        for (int i = 0; i < weekH.length; i++) {
            weekH[i] = "Wk " + weeks.get(i).substring(5);
        }
        String[] dayH = new String[dates.size()];
        for (int i = 0; i < dayH.length; i++) {
            dayH[i] = dates.get(i).substring(5);
        }
        pdf.text(28, y, 11, true, "Weekly totals");
        y -= 20;
        grid(pdf, y, fixedH, fixedW, weekH, 56, true);
        pdf.newPage();
        pdf.text(28, SimplePdf.H - 40, 11, true, "Day by day");
        grid(pdf, SimplePdf.H - 62, fixedH, fixedW, dayH, 27, false);
        pdf.footer("Vistr performance report " + from + " to " + to);
        return pdf.bytes();
    }

    /** Draws the table from y downwards: the identifying columns, as many of the var columns as fit, and the total, then the next block of columns. */
    private void grid(SimplePdf pdf, float startY, String[] fixedH, float[] fixedW, String[] varH, float varW, boolean weekly) {
        float usable = SimplePdf.W - 56;
        float fixedSum = 0;
        for (float f : fixedW) {
            fixedSum += f;
        }
        float totalW = 36;
        int per = Math.max(1, (int) ((usable - fixedSum - totalW) / varW));
        boolean first = true;
        for (int from0 = 0; from0 < varH.length; from0 += per) {
            int to0 = Math.min(varH.length, from0 + per);
            if (!first) {
                pdf.newPage();
                startY = SimplePdf.H - 40;
            }
            first = false;
            float y = startY;
            int row = 0;
            y = header(pdf, y, fixedH, fixedW, varH, from0, to0, varW, totalW);
            for (int k = 0; k <= rows.size(); k++) {
                if (y < 52) {
                    pdf.newPage();
                    y = header(pdf, SimplePdf.H - 40, fixedH, fixedW, varH, from0, to0, varW, totalW);
                }
                boolean totalRow = k == rows.size();
                Row r = totalRow ? null : rows.get(k);
                if (totalRow) {
                    pdf.line(28, y + 10, 28 + usable, y + 10);
                }
                float x = 28;
                String[] fixed = totalRow ? new String[] {"All players", "", "", ""} : new String[] {r.email, r.username, r.gameId, r.moderator};
                for (int c = 0; c < fixed.length; c++) {
                    pdf.text(x + 2, y, 7.5f, totalRow, SimplePdf.fit(fixed[c], fixedW[c] - 4, 7.5f));
                    x += fixedW[c];
                }
                for (int c = from0; c < to0; c++) {
                    int v;
                    if (totalRow) {
                        v = weekly ? weekTotal(weeks.get(c)) : dayTotal(dates.get(c));
                    } else {
                        v = weekly ? week(r, weeks.get(c)) : day(r, dates.get(c));
                    }
                    pdf.text(x + varW - 4 - SimplePdf.width(String.valueOf(v), 7.5f), y, 7.5f, totalRow, String.valueOf(v));
                    x += varW;
                }
                int tv = totalRow ? grandTotal() : total(r);
                pdf.text(x + totalW - 4 - SimplePdf.width(String.valueOf(tv), 7.5f), y, 7.5f, true, String.valueOf(tv));
                y -= 13;
                row++;
            }
        }
    }

    private float header(SimplePdf pdf, float y, String[] fixedH, float[] fixedW, String[] varH, int from0, int to0, float varW, float totalW) {
        float usable = SimplePdf.W - 56;
        pdf.fillGrey(28, y - 4, usable, 14);
        float x = 28;
        for (int c = 0; c < fixedH.length; c++) {
            pdf.text(x + 2, y, 7.5f, true, fixedH[c]);
            x += fixedW[c];
        }
        for (int c = from0; c < to0; c++) {
            pdf.text(x + varW - 4 - SimplePdf.width(varH[c], 7.5f), y, 7.5f, true, varH[c]);
            x += varW;
        }
        pdf.text(x + totalW - 4 - SimplePdf.width("Total", 7.5f), y, 7.5f, true, "Total");
        return y - 16;
    }

    /** A very small PDF writer: A4 landscape pages of Helvetica text, lines and grey bars. */
    static final class SimplePdf {
        static final float W = 842;
        static final float H = 595;
        private static final int[] HELV = {278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};

        private final List<StringBuilder> pages = new ArrayList<StringBuilder>();
        private StringBuilder cur;

        void newPage() {
            cur = new StringBuilder();
            pages.add(cur);
        }

        static float width(String s, float size) {
            float w = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                w += c >= 32 && c <= 126 ? HELV[c - 32] : 556;
            }
            return w * size / 1000f;
        }

        /** The text cut with ".." so that it fits the width. */
        static String fit(String s, float maxW, float size) {
            if (width(s, size) <= maxW) {
                return s;
            }
            String t = s;
            while (t.length() > 1 && width(t + "..", size) > maxW) {
                t = t.substring(0, t.length() - 1);
            }
            return t + "..";
        }

        void text(float x, float y, float size, boolean bold, String s) {
            cur.append("BT /").append(bold ? "F2" : "F1").append(' ').append(num(size)).append(" Tf ").append(num(x)).append(' ').append(num(y))
                    .append(" Td (").append(esc(s)).append(") Tj ET\n");
        }

        void line(float x1, float y1, float x2, float y2) {
            cur.append("0.6 G 0.5 w ").append(num(x1)).append(' ').append(num(y1)).append(" m ").append(num(x2)).append(' ').append(num(y2)).append(" l S 0 G\n");
        }

        void fillGrey(float x, float y, float w, float h) {
            cur.append("0.9 g ").append(num(x)).append(' ').append(num(y)).append(' ').append(num(w)).append(' ').append(num(h)).append(" re f 0 g\n");
        }

        /** "text - page i of n" at the bottom of every page. */
        void footer(String text) {
            for (int i = 0; i < pages.size(); i++) {
                StringBuilder keep = cur;
                cur = pages.get(i);
                text(28, 24, 7.5f, false, text + " - page " + (i + 1) + " of " + pages.size());
                cur = keep;
            }
        }

        private static String num(float f) {
            return String.format(java.util.Locale.ROOT, "%.2f", f);
        }

        private static String esc(String s) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(' || c == ')' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 32) {
                    sb.append(' ');
                } else if (c > 255) {
                    sb.append('?');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        byte[] bytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<Integer> offs = new ArrayList<Integer>();
            put(out, "%PDF-1.4\n");
            int n = pages.size();
            // objects: 1 catalog, 2 pages, 3 and 4 fonts, then (page, content) for each page
            offs.add(out.size());
            put(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            offs.add(out.size());
            StringBuilder kids = new StringBuilder();
            for (int i = 0; i < n; i++) {
                kids.append(5 + 2 * i).append(" 0 R ");
            }
            put(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>\nendobj\n");
            offs.add(out.size());
            put(out, "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");
            offs.add(out.size());
            put(out, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");
            for (int i = 0; i < n; i++) {
                int pageObj = 5 + 2 * i;
                int contentObj = pageObj + 1;
                byte[] content = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
                offs.add(out.size());
                put(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) W + " " + (int) H + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                        + contentObj + " 0 R >>\nendobj\n");
                offs.add(out.size());
                put(out, contentObj + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
                out.write(content, 0, content.length);
                put(out, "\nendstream\nendobj\n");
            }
            int xref = out.size();
            put(out, "xref\n0 " + (offs.size() + 1) + "\n0000000000 65535 f \n");
            for (int o : offs) {
                put(out, String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o));
            }
            put(out, "trailer\n<< /Size " + (offs.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
            return out.toByteArray();
        }

        private static void put(ByteArrayOutputStream out, String s) {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            out.write(b, 0, b.length);
        }
    }
}