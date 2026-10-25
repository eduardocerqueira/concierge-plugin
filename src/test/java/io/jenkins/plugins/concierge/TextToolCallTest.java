package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextToolCallTest {

    private static final List<Llm.ToolSpec> OFFERED =
            List.of(new Llm.ToolSpec("get_build_log", "log", Map.of("type", "object")));

    private static Llm.Reply text(String text) {
        return Assistant.textToolCalls(new Llm.Reply(text, List.of()), OFFERED);
    }

    @Test
    void acceptsAToolCallWrittenAsTheWholeReply() {
        for (String reply : List.of(
                "{\"name\": \"get_build_log\", \"arguments\": {\"job\": \"web-deploy\"}}",
                "```json\n{\"name\":\"get_build_log\",\"arguments\":{\"job\":\"web-deploy\"}}\n```",
                "<tool_call>\n{\"name\":\"get_build_log\",\"parameters\":{\"job\":\"web-deploy\"}}\n</tool_call>")) {
            Llm.Reply converted = text(reply);
            assertEquals(1, converted.toolCalls().size(), reply);
            assertEquals("get_build_log", converted.toolCalls().get(0).name());
            assertEquals("web-deploy", converted.toolCalls().get(0).arguments().get("job"));
        }
    }

    @Test
    void leavesEverythingElseAlone() {
        for (String reply : List.of(
                "The build failed because the database refused the connection.",
                "Here you go: {\"name\":\"get_build_log\",\"arguments\":{}}",
                "{\"name\":\"script_console\",\"arguments\":{\"script\":\"x\"}}",
                "{\"name\":\"get_build_log\"",
                "{\"total\": 3, \"jobs\": []}")) {
            assertTrue(text(reply).toolCalls().isEmpty(), reply);
        }
    }
}
