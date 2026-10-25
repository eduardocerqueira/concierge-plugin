package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The AI providers the assistant can use. Anthropic has its own API; everything else here speaks the
 * OpenAI chat-completions format, so two small clients cover all of them (docs/security-design.md,
 * rule 8).
 */
public enum Provider {
    ANTHROPIC("Anthropic", Flavor.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5", true),
    OPENAI("OpenAI", Flavor.OPENAI, "https://api.openai.com/v1", "", true),
    XAI("xAI Grok", Flavor.OPENAI, "https://api.x.ai/v1", "", true),
    /** Hosted in China. Disabled until an administrator explicitly allows it. */
    DEEPSEEK("DeepSeek", Flavor.OPENAI, "https://api.deepseek.com", "", true),
    LOCAL("Local model (OpenAI-compatible, such as Ollama)", Flavor.OPENAI, "", "", false);

    /** Which wire format a provider uses. */
    public enum Flavor {
        ANTHROPIC,
        OPENAI
    }

    private final String displayName;
    private final Flavor flavor;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final boolean keyRequired;

    Provider(String displayName, Flavor flavor, String defaultBaseUrl, String defaultModel, boolean keyRequired) {
        this.displayName = displayName;
        this.flavor = flavor;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.keyRequired = keyRequired;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public Flavor getFlavor() {
        return flavor;
    }

    /** Where requests go unless an administrator sets a base URL, for example an AI gateway. */
    @NonNull
    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    @NonNull
    public String getDefaultModel() {
        return defaultModel;
    }

    public boolean isKeyRequired() {
        return keyRequired;
    }
}
