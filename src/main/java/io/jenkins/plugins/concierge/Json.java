package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, strict JSON reader and writer for talking to AI providers.
 *
 * <p>Not json-lib, which Jenkins core ships: json-lib turns any string that looks like JSON
 * ({@code "[1]"}, {@code "{}"}, {@code "null"}) into an object or null, both when building and when
 * parsing. Build logs and model answers look like that all the time, and a prompt or an answer must
 * arrive exactly as written. Values here are plain Java: {@link Map}, {@link List}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}.
 */
public final class Json {

    /** Deepest nesting accepted when parsing, so a hostile response cannot exhaust the stack. */
    static final int MAX_DEPTH = 64;

    private Json() {}

    // ------------------------------------------------------------------ writing

    @NonNull
    public static String write(@CheckForNull Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                out.append("null");
            } else {
                out.append(n instanceof BigDecimal b ? b.toPlainString() : n.toString());
            }
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Escape control characters and the line separators JavaScript chokes on.
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    /** Parses one JSON value; anything after it other than whitespace is an error. */
    @CheckForNull
    public static Object parse(@NonNull String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("Unexpected text after the JSON value");
        }
        return value;
    }

    /** Parses a JSON object, failing if the text is anything else. */
    @NonNull
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(@NonNull String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    /** The named member as a map, or an empty map. */
    @NonNull
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(@CheckForNull Object map, @NonNull String key) {
        Object value = map instanceof Map<?, ?> m ? m.get(key) : null;
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** The named member as a list, or an empty list. */
    @NonNull
    public static List<?> array(@CheckForNull Object map, @NonNull String key) {
        Object value = map instanceof Map<?, ?> m ? m.get(key) : null;
        return value instanceof List<?> list ? list : List.of();
    }

    /** The named member as a string, or the default. */
    public static String string(@CheckForNull Object map, @NonNull String key, String defaultValue) {
        Object value = map instanceof Map<?, ?> m ? m.get(key) : null;
        return value instanceof String s ? s : defaultValue;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("JSON nested too deeply");
            }
            if (pos >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("Unexpected character '" + c + "'");
            }
        }

        private void literal(String word) {
            if (!text.startsWith(word, pos)) {
                throw error("Expected " + word);
            }
            pos += word.length();
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected a member name");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw error("Unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw error("Unterminated escape");
                    }
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("Bad unicode escape");
                            }
                            try {
                                out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("Control character in string");
                } else {
                    out.append(c);
                }
            }
        }

        private Number number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && "0123456789.eE+-".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String literal = text.substring(start, pos);
            try {
                if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
                    long l = Long.parseLong(literal);
                    return l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE ? (Number) (int) l : (Number) l;
                }
                return new BigDecimal(literal);
            } catch (NumberFormatException e) {
                throw error("Bad number " + literal);
            }
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("Unexpected end of JSON");
            }
            return text.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                pos--;
                throw error("Expected '" + c + "'");
            }
        }
    }
}
