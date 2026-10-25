package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The jobs an administrator lets the assistant propose running, as full-name patterns, one per
 * line: {@code team/api-build}, {@code team/*} (one level) or {@code team/**} (any depth). An empty
 * list matches nothing.
 */
final class JobPatterns {

    static final int MAX_PATTERNS = 100;

    private final List<Pattern> patterns;

    JobPatterns(@CheckForNull String text) {
        List<Pattern> compiled = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("[\\r\\n,]+")) {
                String pattern = line.trim();
                if (pattern.isEmpty() || pattern.startsWith("#") || compiled.size() >= MAX_PATTERNS) {
                    continue;
                }
                compiled.add(toRegex(pattern));
            }
        }
        this.patterns = compiled;
    }

    boolean isEmpty() {
        return patterns.isEmpty();
    }

    boolean matches(@NonNull String fullName) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(fullName).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
