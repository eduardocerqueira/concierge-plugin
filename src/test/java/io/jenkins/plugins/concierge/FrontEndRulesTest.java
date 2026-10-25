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
 * docs/security-design.md, rule 6, checked against the source: no {@code innerHTML} in our scripts,
 * no inline script in our views, and every view escapes by default.
 */
class FrontEndRulesTest {

    private static final Path WEBAPP = Path.of("src/main/webapp");
    private static final Path VIEWS = Path.of("src/main/resources");

    @Test
    void scriptsNeverParseHtml() throws IOException {
        for (Path js : files(WEBAPP, ".js")) {
            String source = Files.readString(js, StandardCharsets.UTF_8);
            for (String banned : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(")) {
                assertFalse(source.contains(banned), js + " uses " + banned);
            }
        }
    }

    @Test
    void viewsHaveNoInlineScriptAndEscapeByDefault() throws IOException {
        Pattern inlineScript = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern handler = Pattern.compile("\\son[a-z]+=\"", Pattern.CASE_INSENSITIVE);
        List<Path> views = files(VIEWS, ".jelly");
        assertFalse(views.isEmpty());
        for (Path view : views) {
            String source = Files.readString(view, StandardCharsets.UTF_8);
            assertTrue(source.startsWith("<?jelly escape-by-default='true'?>"), view + " must escape by default");
            assertFalse(inlineScript.matcher(source).find(), view + " has an inline script");
            assertFalse(handler.matcher(source).find(), view + " has an inline event handler");
        }
    }

    private static List<Path> files(Path root, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(suffix)).collect(Collectors.toList());
        }
    }
}
