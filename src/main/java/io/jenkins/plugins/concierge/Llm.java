package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The provider-neutral shape of a conversation with a model that can call tools. The clients
 * translate it to and from each provider's wire format.
 */
final class Llm {

    private Llm() {}

    /** Talks to one provider. */
    interface Client {
        @NonNull
        Reply complete(@NonNull String system, @NonNull List<Turn> turns, @NonNull List<ToolSpec> tools)
                throws IOException, InterruptedException;
    }

    /** A tool the model may call, with a JSON Schema for its input. */
    record ToolSpec(String name, String description, Map<String, Object> inputSchema) {}

    /** A tool call the model asked for. */
    record ToolCall(String id, String name, Map<String, Object> arguments) {}

    /** The outcome of one tool call, as text for the model. */
    record ToolResult(String callId, String name, String content, boolean error) {}

    /** One entry in the conversation. Serializable because past turns are kept in the HTTP session. */
    sealed interface Turn extends java.io.Serializable permits UserTurn, AssistantTurn, ToolResultsTurn {}

    /** A question; {@code at} is when it was asked, in epoch milliseconds, or 0 when unknown. */
    record UserTurn(String text, long at) implements Turn {
        UserTurn(String text) {
            this(text, 0);
        }
    }

    /** A reply; {@code at} is when it was given, in epoch milliseconds, or 0 when unknown. */
    record AssistantTurn(String text, List<ToolCall> toolCalls, long at) implements Turn {
        AssistantTurn(String text, List<ToolCall> toolCalls) {
            this(text, toolCalls, 0);
        }
    }

    record ToolResultsTurn(List<ToolResult> results) implements Turn {}

    /**
     * What the model said back: some text, tool calls, or both, and the tokens the provider says
     * the request used (0 when it did not say).
     */
    record Reply(String text, List<ToolCall> toolCalls, long inputTokens, long outputTokens) {
        Reply(String text, List<ToolCall> toolCalls) {
            this(text, toolCalls, 0, 0);
        }
    }

    /** A provider answered with an error, or something that was not a valid answer. */
    static final class ProviderException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        ProviderException(int status, String message) {
            super(message);
            this.status = status;
        }

        /** The HTTP status, or 0 when the response could not be understood. */
        int getStatus() {
            return status;
        }
    }
}
