package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes things that look like secrets from text before it is sent to an AI provider
 * (docs/security-design.md, rule 4).
 *
 * <p>This is a second line of defence. Jenkins already masks bound credentials in build logs, but a
 * secret printed in another form (base64'd, split, echoed by a tool) or committed into a
 * configuration slips past that. Patterns are deliberately bounded so that a hostile log line
 * cannot make them run for long.
 */
final class Redactor {

    static final String MASK = "[REDACTED]";

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            // Whole PEM blocks: private keys and certificates with keys.
            new Rule(
                    Pattern.compile(
                            "-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY-----[\\s\\S]{0,10000}?-----END [A-Z0-9 ]{0,40}PRIVATE KEY-----"),
                    MASK),
            // Credentials embedded in URLs: https://user:password@host
            new Rule(
                    Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]{1,20}://)[^/\\s:@]{1,200}:[^/\\s@]{1,200}@"),
                    "$1" + MASK + "@"),
            // A token alone as the user part: https://ghp_abc...@github.com/org/repo
            new Rule(Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]{1,20}://)[A-Za-z0-9_-]{20,200}@"), "$1" + MASK + "@"),
            // Authorization headers.
            new Rule(Pattern.compile("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}"), "$1 " + MASK),
            // Well-known token formats.
            new Rule(Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"), MASK),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,255}\\b"), MASK),
            new Rule(Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{30,255}\\b"), MASK),
            new Rule(Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"), MASK),
            new Rule(Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b"), MASK),
            new Rule(Pattern.compile("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}\\b"), MASK),
            new Rule(Pattern.compile("\\bxai-[A-Za-z0-9]{20,}\\b"), MASK),
            new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), MASK),
            new Rule(Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b"), MASK),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"), MASK),
            // key=value and key: value where the key says it is a secret. The key is kept so the
            // model can still say "the password is wrong".
            new Rule(
                    Pattern.compile("(?i)\\b([A-Za-z0-9_.-]{0,40}(?:password|passwd|pwd|secret|token|api[_-]?key"
                            + "|access[_-]?key|private[_-]?key|client[_-]?secret|credentials?)[A-Za-z0-9_.-]{0,40})"
                            + "(\\s{0,3}[:=]\\s{0,3})(\"[^\"\\r\\n]{1,500}\"|'[^'\\r\\n]{1,500}'|[^\\s\"',;]{1,500})"),
                    "$1$2" + MASK));

    private final List<String> literals;

    /**
     * @param literals exact values to remove wherever they appear, such as the provider API key in
     *     use. Values shorter than 6 characters are ignored, since removing them would mangle text.
     */
    Redactor(@CheckForNull Collection<String> literals) {
        List<String> kept = new ArrayList<>();
        if (literals != null) {
            for (String literal : literals) {
                if (literal != null && literal.length() >= 6) {
                    kept.add(literal);
                }
            }
        }
        this.literals = kept;
    }

    @NonNull
    String redact(@CheckForNull String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        for (String literal : literals) {
            out = out.replace(literal, MASK);
        }
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(out);
            if (matcher.find()) {
                out = matcher.replaceAll(rule.replacement());
            }
        }
        return out;
    }

    /** Redacts every string in a value about to go through {@link Json#write}: map values and list items, at any depth. */
    Object redactValues(@CheckForNull Object value) {
        if (value instanceof String text) {
            return redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((key, item) -> out.put(key, redactValues(item)));
            return out;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            items.forEach(item -> out.add(redactValues(item)));
            return out;
        }
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        // Json writes anything else as its string form, so redact that.
        return redact(value.toString());
    }
}
