package com.varikaagency.download.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Petit lecteur/écrivain JSON sans dépendance (Map, List, String, Number, Boolean, null). */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ écriture
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable) {
            sb.append('[');
            Iterator<Object> it = ((Iterable<Object>) o).iterator();
            while (it.hasNext()) {
                write(sb, it.next());
                if (it.hasNext()) sb.append(',');
            }
            sb.append(']');
        } else if (o instanceof double[]) {
            sb.append('[');
            double[] a = (double[]) o;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                write(sb, a[i]);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ lecture
    public static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("JSON : caractères en trop");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    public static long num(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).longValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("JSON : fin inattendue");
            return s.charAt(i);
        }

        Object value() {
            char c = peek();
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (peek() != ':') throw new IllegalArgumentException("JSON : ':' attendu");
                i++;
                ws();
                m.put(k, value());
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("JSON : ',' attendu");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = peek();
                i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("JSON : ',' attendu");
            }
        }

        String string() {
            if (peek() != '"') throw new IllegalArgumentException("JSON : texte attendu");
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }

        Number number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw new IllegalArgumentException("JSON : valeur invalide à " + st);
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) return Double.parseDouble(t);
            return Long.parseLong(t);
        }
    }
}
