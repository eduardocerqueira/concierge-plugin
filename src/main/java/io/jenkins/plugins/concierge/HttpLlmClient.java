package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two wire formats: Anthropic's Messages API and OpenAI-style chat completions. Uses the JDK
 * HTTP client through Jenkins' proxy settings, and {@link Json} rather than a third-party library.
 *
 * <p>The API key is only ever put in a request header. It never appears in an exception message,
 * a log line or an audit entry.
 */
final class HttpLlmClient implements Llm.Client {

    static final Duration TIMEOUT = Duration.ofSeconds(120);
    static final int MAX_OUTPUT_TOKENS = 2048;

    private final Provider provider;
    private final String baseUrl;
    private final String model;

    @CheckForNull
    private final String apiKey;

    private final HttpClient http;

    HttpLlmClient(
            @NonNull Provider provider, @NonNull String baseUrl, @NonNull String model, @CheckForNull String apiKey) {
        this.provider = provider;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.apiKey = apiKey;
        this.http = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /** The endpoint requests are sent to. */
    @NonNull
    String endpoint() {
        return provider.getFlavor() == Provider.Flavor.ANTHROPIC
                ? baseUrl + "/v1/messages"
                : baseUrl + "/chat/completions";
    }

    @NonNull
    @Override
    public Llm.Reply complete(@NonNull String system, @NonNull List<Llm.Turn> turns, @NonNull List<Llm.ToolSpec> tools)
            throws IOException, InterruptedException {
        boolean anthropic = provider.getFlavor() == Provider.Flavor.ANTHROPIC;
        String body = Json.write(anthropic ? anthropicBody(system, turns, tools) : openAiBody(system, turns, tools));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (anthropic) {
            request.header("anthropic-version", "2023-06-01");
            if (apiKey != null) {
                request.header("x-api-key", apiKey);
            }
        } else if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new Llm.ProviderException(response.statusCode(), errorMessage(response.body()));
        }
        Map<String, Object> json;
        try {
            json = Json.parseObject(response.body());
        } catch (IllegalArgumentException e) {
            throw new Llm.ProviderException(0, "The provider's response was not valid JSON.");
        }
        return anthropic ? anthropicReply(json) : openAiReply(json);
    }

    /** The provider's own error message, which never contains our key, trimmed to a sensible size. */
    static String errorMessage(String body) {
        try {
            Map<String, Object> json = Json.parseObject(body);
            Object error = json.get("error");
            String message = error instanceof Map ? Json.string(error, "message", null) : null;
            if (message == null && error instanceof String s) {
                message = s;
            }
            if (message != null) {
                return message.length() > 300 ? message.substring(0, 300) : message;
            }
        } catch (RuntimeException e) {
            // Not JSON: fall through.
        }
        return "The provider returned an error.";
    }

    // ------------------------------------------------------------------ Anthropic

    private Map<String, Object> anthropicBody(String system, List<Llm.Turn> turns, List<Llm.ToolSpec> tools) {
        List<Object> messages = new ArrayList<>();
        for (Llm.Turn turn : turns) {
            if (turn instanceof Llm.UserTurn user) {
                messages.add(Map.of("role", "user", "content", List.of(text(user.text()))));
            } else if (turn instanceof Llm.AssistantTurn assistant) {
                List<Object> content = new ArrayList<>();
                if (assistant.text() != null && !assistant.text().isEmpty()) {
                    content.add(text(assistant.text()));
                }
                for (Llm.ToolCall call : assistant.toolCalls()) {
                    Map<String, Object> use = new LinkedHashMap<>();
                    use.put("type", "tool_use");
                    use.put("id", call.id());
                    use.put("name", call.name());
                    use.put("input", call.arguments());
                    content.add(use);
                }
                messages.add(Map.of("role", "assistant", "content", content));
            } else if (turn instanceof Llm.ToolResultsTurn results) {
                List<Object> content = new ArrayList<>();
                for (Llm.ToolResult result : results.results()) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", result.callId());
                    block.put("content", result.content());
                    if (result.error()) {
                        block.put("is_error", true);
                    }
                    content.add(block);
                }
                messages.add(Map.of("role", "user", "content", content));
            }
        }
        List<Object> toolList = new ArrayList<>();
        for (Llm.ToolSpec tool : tools) {
            toolList.add(
                    Map.of("name", tool.name(), "description", tool.description(), "input_schema", tool.inputSchema()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        body.put("system", system);
        body.put("messages", messages);
        if (!toolList.isEmpty()) {
            body.put("tools", toolList);
        }
        return body;
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text == null ? "" : text);
    }

    @SuppressWarnings("unchecked")
    static Llm.Reply anthropicReply(Map<String, Object> json) throws Llm.ProviderException {
        Object content = json.get("content");
        if (!(content instanceof List<?> blocks)) {
            throw new Llm.ProviderException(0, "The provider's response had no content.");
        }
        StringBuilder text = new StringBuilder();
        List<Llm.ToolCall> calls = new ArrayList<>();
        for (Object block : blocks) {
            String type = Json.string(block, "type", "");
            if (type.equals("text")) {
                text.append(Json.string(block, "text", ""));
            } else if (type.equals("tool_use")) {
                Object input = ((Map<String, Object>) block).get("input");
                calls.add(new Llm.ToolCall(
                        Json.string(block, "id", ""),
                        Json.string(block, "name", ""),
                        input instanceof Map ? (Map<String, Object>) input : Map.of()));
            }
        }
        return new Llm.Reply(text.toString(), calls);
    }

    // ------------------------------------------------------------------ OpenAI-compatible

    private Map<String, Object> openAiBody(String system, List<Llm.Turn> turns, List<Llm.ToolSpec> tools) {
        List<Object> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        for (Llm.Turn turn : turns) {
            if (turn instanceof Llm.UserTurn user) {
                messages.add(Map.of("role", "user", "content", user.text()));
            } else if (turn instanceof Llm.AssistantTurn assistant) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", assistant.text() == null ? "" : assistant.text());
                if (!assistant.toolCalls().isEmpty()) {
                    List<Object> calls = new ArrayList<>();
                    for (Llm.ToolCall call : assistant.toolCalls()) {
                        calls.add(Map.of(
                                "id",
                                call.id(),
                                "type",
                                "function",
                                "function",
                                Map.of("name", call.name(), "arguments", Json.write(call.arguments()))));
                    }
                    message.put("tool_calls", calls);
                }
                messages.add(message);
            } else if (turn instanceof Llm.ToolResultsTurn results) {
                for (Llm.ToolResult result : results.results()) {
                    messages.add(Map.of("role", "tool", "tool_call_id", result.callId(), "content", result.content()));
                }
            }
        }
        List<Object> toolList = new ArrayList<>();
        for (Llm.ToolSpec tool : tools) {
            toolList.add(Map.of(
                    "type",
                    "function",
                    "function",
                    Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        body.put("messages", messages);
        if (!toolList.isEmpty()) {
            body.put("tools", toolList);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    static Llm.Reply openAiReply(Map<String, Object> json) throws Llm.ProviderException {
        List<?> choices = Json.array(json, "choices");
        if (choices.isEmpty()) {
            throw new Llm.ProviderException(0, "The provider's response had no choices.");
        }
        Map<String, Object> message = Json.object(choices.get(0), "message");
        Object content = message.get("content");
        String text = content instanceof String s ? s : "";
        List<Llm.ToolCall> calls = new ArrayList<>();
        for (Object call : Json.array(message, "tool_calls")) {
            Map<String, Object> function = Json.object(call, "function");
            String arguments = Json.string(function, "arguments", "{}");
            Map<String, Object> parsed;
            try {
                parsed = arguments.isBlank() ? Map.of() : Json.parseObject(arguments);
            } catch (IllegalArgumentException e) {
                // A model that sent broken arguments gets told so by the tool, not by a crash.
                parsed = Map.of("_invalid_arguments", arguments);
            }
            calls.add(new Llm.ToolCall(Json.string(call, "id", ""), Json.string(function, "name", ""), parsed));
        }
        return new Llm.Reply(text, calls);
    }
}
