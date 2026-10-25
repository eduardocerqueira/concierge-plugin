package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Both wire formats against a local HTTP server, so nothing leaves the machine. Needs Jenkins only
 * because the client honours Jenkins' proxy settings.
 */
@WithJenkins
class HttpLlmClientTest {

    private HttpServer server;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void start(JenkinsRule r) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            headers.set(Map.copyOf(exchange.getRequestHeaders()));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String header(String name) {
        for (Map.Entry<String, List<String>> e : headers.get().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().get(0);
            }
        }
        return null;
    }

    private static List<Llm.Turn> conversation() {
        return List.of(
                new Llm.UserTurn("Why is [build] failing?"),
                new Llm.AssistantTurn(
                        "", List.of(new Llm.ToolCall("call_1", "list_jobs", Map.of("status", "failure")))),
                new Llm.ToolResultsTurn(List.of(new Llm.ToolResult("call_1", "list_jobs", "[\"team/web\"]", false))));
    }

    private static List<Llm.ToolSpec> tools() {
        return List.of(new Llm.ToolSpec("list_jobs", "List jobs", Map.of("type", "object", "properties", Map.of())));
    }

    @Test
    void anthropicFormat() throws Exception {
        reply = "{\"content\":[{\"type\":\"text\",\"text\":\"Looking.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"tu_2\",\"name\":\"get_build_log\",\"input\":{\"job\":\"team/web\"}}],"
                + "\"stop_reason\":\"tool_use\"}";
        Llm.Reply result = new HttpLlmClient(Provider.ANTHROPIC, base() + "/", "claude-test", "secret-key")
                .complete("system prompt", conversation(), tools());

        assertEquals("/v1/messages", path.get());
        assertEquals("secret-key", header("x-api-key"));
        assertEquals("2023-06-01", header("anthropic-version"));
        Map<String, Object> sent = Json.parseObject(body.get());
        assertEquals("claude-test", sent.get("model"));
        assertEquals("system prompt", sent.get("system"));
        List<?> messages = Json.array(sent, "messages");
        assertEquals(3, messages.size());
        // The tool result went back as a string, exactly as given, even though it looks like JSON.
        Map<String, Object> toolResult =
                (Map<String, Object>) Json.array(messages.get(2), "content").get(0);
        assertEquals("tool_result", toolResult.get("type"));
        assertEquals("[\"team/web\"]", toolResult.get("content"));
        assertEquals("list_jobs", Json.string(Json.array(sent, "tools").get(0), "name", null));

        assertEquals("Looking.", result.text());
        assertEquals(1, result.toolCalls().size());
        assertEquals("get_build_log", result.toolCalls().get(0).name());
        assertEquals("team/web", result.toolCalls().get(0).arguments().get("job"));
    }

    @Test
    void openAiCompatibleFormat() throws Exception {
        reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"c9\",\"type\":\"function\",\"function\":{\"name\":\"get_job\",\"arguments\":\"{\\\"job\\\":\\\"a\\\"}\"}}]}}]}";
        Llm.Reply result = new HttpLlmClient(Provider.XAI, base() + "/v1", "grok-test", "xai-key")
                .complete("system prompt", conversation(), tools());

        assertEquals("/v1/chat/completions", path.get());
        assertEquals("Bearer xai-key", header("Authorization"));
        Map<String, Object> sent = Json.parseObject(body.get());
        List<?> messages = Json.array(sent, "messages");
        assertEquals("system", Json.string(messages.get(0), "role", null));
        assertEquals("tool", Json.string(messages.get(3), "role", null));
        assertEquals("call_1", Json.string(messages.get(3), "tool_call_id", null));
        assertEquals("function", Json.string(Json.array(sent, "tools").get(0), "type", null));

        assertEquals("", result.text());
        assertEquals("get_job", result.toolCalls().get(0).name());
        assertEquals("a", result.toolCalls().get(0).arguments().get("job"));
    }

    @Test
    void localModelsNeedNoKey() throws Exception {
        reply = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}";
        new HttpLlmClient(Provider.LOCAL, base() + "/v1", "llama", null)
                .complete("s", List.of(new Llm.UserTurn("hi")), List.of());
        assertEquals(null, header("Authorization"));
        assertFalse(Json.parseObject(body.get()).containsKey("tools"), "no tools offered means no tools key");
    }

    @Test
    void errorsCarryTheProviderMessageButNeverTheKey() {
        status = 401;
        reply = "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";
        Llm.ProviderException e = assertThrows(
                Llm.ProviderException.class,
                () -> new HttpLlmClient(Provider.ANTHROPIC, base(), "m", "the-real-key")
                        .complete("s", List.of(new Llm.UserTurn("hi")), List.of()));
        assertEquals(401, e.getStatus());
        assertEquals("invalid x-api-key", e.getMessage());
        assertFalse(e.getMessage().contains("the-real-key"));
    }

    @Test
    void garbageResponsesAreReportedNotThrownAsSomethingElse() {
        reply = "<html>not json</html>";
        Llm.ProviderException e = assertThrows(
                Llm.ProviderException.class,
                () -> new HttpLlmClient(Provider.OPENAI, base(), "m", "k")
                        .complete("s", List.of(new Llm.UserTurn("hi")), List.of()));
        assertEquals(0, e.getStatus());
        assertTrue(e.getMessage().contains("not valid JSON"));
    }

    @Test
    void brokenToolArgumentsAreFlaggedForTheTool() throws Exception {
        reply =
                "{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"x\",\"function\":{\"name\":\"get_job\",\"arguments\":\"{oops\"}}]}}]}";
        Llm.Reply result = new HttpLlmClient(Provider.OPENAI, base(), "m", "k")
                .complete("s", List.of(new Llm.UserTurn("hi")), tools());
        assertTrue(result.toolCalls().get(0).arguments().containsKey("_invalid_arguments"));
    }
}
