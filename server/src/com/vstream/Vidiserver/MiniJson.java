package com.vstream.Vidiserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for answers from other services (Stripe): objects become Maps, arrays Lists, numbers Doubles
 * (or Longs when whole), plus String, Boolean and null.
 */
final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        MiniJson p = new MiniJson(text == null ? "" : text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("extra text after JSON");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return (Map<String, Object>) o;
    }

    /** Follows a path of keys ("subscription", "id"); null if any step is missing. */
    @SuppressWarnings("unchecked")
    static Object at(Object root, Object... path) {
        Object cur = root;
        for (Object k : path) {
            if (k instanceof Integer && cur instanceof List) {
                List<Object> l = (List<Object>) cur;
                int n = (Integer) k;
                cur = n >= 0 && n < l.size() ? l.get(n) : null;
            } else if (cur instanceof Map) {
                cur = ((Map<String, Object>) cur).get(String.valueOf(k));
            } else {
                return null;
            }
        }
        return cur;
    }

    static String str(Object root, Object... path) {
        Object v = at(root, path);
        return v == null ? "" : (v instanceof Double && ((Double) v) == Math.rint((Double) v)
                ? String.valueOf(((Double) v).longValue()) : String.valueOf(v));
    }

    static long num(Object root, Object... path) {
        Object v = at(root, path);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("JSON ended early");
        }
        char c = s.charAt(i);
        if (c == '{') {
            return obj();
        }
        if (c == '[') {
            return arr();
        }
        if (c == '"') {
            return string();
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') {
                throw new IllegalArgumentException("expected a name in JSON");
            }
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<Object>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (i < s.length() && s.charAt(i) == ',') {
                i++;
                continue;
            }
            expect(']');
            return l;
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new IllegalArgumentException("expected '" + c + "' in JSON");
        }
        i++;
    }

    private String string() {
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("bad escape in JSON");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        throw new IllegalArgumentException("unfinished text in JSON");
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String t = s.substring(start, i);
        if (t.isEmpty()) {
            throw new IllegalArgumentException("bad JSON");
        }
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                // too big - fall through
            }
        }
        return Double.parseDouble(t);
    }
}