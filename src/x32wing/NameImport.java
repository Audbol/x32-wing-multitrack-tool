package x32wing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads channel names from an X32/M32 scene (.scn), a WING snapshot (.snap / .json) or a plain text list. */
final class NameImport {
    private NameImport() {}

    private static final Pattern X32_CH = Pattern.compile("^/ch/(\\d{2})/config\\s+\"([^\"]*)\"", Pattern.MULTILINE);

    /** Returns names indexed from 0 (input channel 1). Missing names are null. */
    static List<String> read(Path p) throws IOException {
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        String fn = p.getFileName().toString().toLowerCase();
        if (fn.endsWith(".scn") || X32_CH.matcher(text).find()) return x32(text);
        String t = text.trim();
        if (t.startsWith("{")) return wing(t);
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.trim();
            if (l.isEmpty() && out.isEmpty()) continue;
            out.add(l.replaceFirst("^\\d+\\s*[.:\\t-]\\s*", ""));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    private static List<String> x32(String text) {
        TreeMap<Integer, String> m = new TreeMap<>();
        Matcher mt = X32_CH.matcher(text);
        while (mt.find()) m.put(Integer.parseInt(mt.group(1)), mt.group(2));
        return toList(m);
    }

    /** Finds the first object "ch" whose numeric children have a "name" (WING snapshot layout). */
    private static List<String> wing(String text) throws IOException {
        Object root;
        try { root = new Json(text).value(); } catch (RuntimeException e) { throw new IOException("Could not parse snapshot JSON: " + e.getMessage()); }
        TreeMap<Integer, String> m = new TreeMap<>();
        findChannels(root, m);
        if (m.isEmpty()) throw new IOException("No channel names found in snapshot.");
        return toList(m);
    }

    @SuppressWarnings("unchecked")
    private static boolean findChannels(Object o, TreeMap<Integer, String> out) {
        if (o instanceof Map<?, ?> map) {
            Object ch = map.get("ch");
            if (ch instanceof Map<?, ?> chm) {
                for (Map.Entry<?, ?> e : chm.entrySet()) {
                    if (e.getKey().toString().matches("\\d+") && e.getValue() instanceof Map<?, ?> c && c.get("name") instanceof String n)
                        out.put(Integer.parseInt(e.getKey().toString()), n);
                }
                if (!out.isEmpty()) return true;
            }
            for (Object v : ((Map<String, Object>) map).values()) if (findChannels(v, out)) return true;
        } else if (o instanceof List<?> l) {
            for (Object v : l) if (findChannels(v, out)) return true;
        }
        return false;
    }

    private static List<String> toList(TreeMap<Integer, String> m) {
        List<String> out = new ArrayList<>();
        if (m.isEmpty()) return out;
        for (int i = 1; i <= m.lastKey(); i++) {
            String n = m.get(i);
            out.add(n == null || n.isBlank() ? null : n.trim());
        }
        return out;
    }

    /** Minimal JSON parser (objects, arrays, strings, numbers, literals). */
    private static final class Json {
        private final String s;
        private int i;
        Json(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws(); String k = str(); ws(); expect(':'); m.put(k, value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect('}'); return m;
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value()); ws();
                    if (s.charAt(i) == ',') { i++; continue; }
                    expect(']'); return l;
                }
            }
            if (c == '"') return str();
            int st = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
            String tok = s.substring(st, i);
            return switch (tok) { case "true" -> Boolean.TRUE; case "false" -> Boolean.FALSE; case "null" -> null; default -> tok; };
        }

        private String str() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private void expect(char c) { if (s.charAt(i) != c) throw new IllegalArgumentException("expected '" + c + "' at " + i); i++; }
    }
}
