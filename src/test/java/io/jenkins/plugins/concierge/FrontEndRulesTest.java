package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * docs/security-design.md, rule 6, checked against the source: nothing in our scripts parses HTML or
 * evaluates code, no inline script, handler or style in our views, and every view escapes by default.
 */
class FrontEndRulesTest {

    private static final Path WEBAPP = Path.of("src/main/webapp");
    private static final Path VIEWS = Path.of("src/main/resources");

    @Test
    void scriptsNeverParseHtml() throws IOException {
        for (Path js : files(WEBAPP, ".js")) {
            String source = Files.readString(js, StandardCharsets.UTF_8);
            for (String banned : List.of(
                    "innerHTML",
                    "outerHTML",
                    "insertAdjacentHTML",
                    "document.write",
                    "eval(",
                    "new Function",
                    "DOMParser",
                    "createContextualFragment",
                    "javascript:")) {
                assertFalse(source.contains(banned), js + " uses " + banned);
            }
        }
    }

    @Test
    void viewsHaveNoInlineScriptAndEscapeByDefault() throws IOException {
        Pattern inlineScript = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern handler = Pattern.compile("\\son[a-z]+\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE);
        // Inline styles are blocked by a strict Content-Security-Policy too.
        Pattern style = Pattern.compile("\\sstyle\\s*=\\s*[\"']", Pattern.CASE_INSENSITIVE);
        List<Path> views = files(VIEWS, ".jelly");
        assertFalse(views.isEmpty());
        for (Path view : views) {
            String source = Files.readString(view, StandardCharsets.UTF_8);
            assertTrue(source.startsWith("<?jelly escape-by-default='true'?>"), view + " must escape by default");
            assertFalse(inlineScript.matcher(source).find(), view + " has an inline script");
            assertFalse(handler.matcher(source).find(), view + " has an inline event handler");
            assertFalse(style.matcher(source).find(), view + " has an inline style");
            assertFalse(source.contains("escape-by-default='false'"), view + " turns escaping off");
            assertFalse(source.contains("rawHtml"), view + " writes raw HTML");
        }
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(suffix)).collect(Collectors.toList());
        }
    }
}
