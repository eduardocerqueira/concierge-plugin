package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The models a provider offers, fetched from its own API with the configured key.
 *
 * <ul>
 *   <li>Anthropic: {@code GET /v1/models}
 *   <li>xAI: {@code GET /language-models}, which also reports prices; {@code /models} as a fallback
 *   <li>OpenAI, DeepSeek and local models: {@code GET /models}
 * </ul>
 *
 * Only administrators trigger a fetch, from the assistant settings, so the key is never used on a
 * user's behalf here. Results are cached for ten minutes per provider, address and key.
 */
final class ModelCatalog {

    static final long CACHE_MILLIS = 10L * 60 * 1000;
    static final int MAX_MODELS = 300;

    /**
     * OpenAI lists embedding, image, audio and moderation models next to chat models. None of
     * them can hold a conversation with tools.
     */
    private static final Pattern NOT_CHAT = Pattern.compile(
            "(?i).*(embedding|tts|whisper|dall-e|gpt-image|image|audio|moderation|realtime|transcribe|search|"
                    + "davinci|babbage|computer-use|sora).*");

    /** One model, as the settings page lists it. */
    record Model(
            String id, String displayName, @CheckForNull ModelPrice livePrice) {}

    private record Cached(long at, List<Model> models) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    static LongSupplier clock = System::currentTimeMillis;

    private ModelCatalog() {}

    /** The provider's models, newest data first from cache when fresh. Throws when it cannot ask. */
    @NonNull
    static List<Model> list(@NonNull Provider provider, @NonNull String baseUrl, @CheckForNull String apiKey)
            throws IOException, InterruptedException {
        String key = provider.name() + "|" + baseUrl + "|" + fingerprint(apiKey);
        Cached cached = CACHE.get(key);
        long now = clock.getAsLong();
        if (cached != null && now - cached.at() < CACHE_MILLIS) {
            return cached.models();
        }
        List<Model> models = fetch(provider, baseUrl.replaceAll("/+$", ""), apiKey);
        CACHE.put(key, new Cached(now, models));
        return models;
    }

    /**
     * A live price for a model, if a recent list from this provider reported one. Never fetches:
     * answering a question must not trigger a model-list request.
     */
    @CheckForNull
    static ModelPrice cachedLivePrice(@NonNull Provider provider, @NonNull String model) {
        long now = clock.getAsLong();
        for (Map.Entry<String, Cached> entry : CACHE.entrySet()) {
            if (entry.getKey().startsWith(provider.name() + "|")
                    && now - entry.getValue().at() < CACHE_MILLIS) {
                for (Model m : entry.getValue().models()) {
                    if (m.id().equals(model) && m.livePrice() != null) {
                        return m.livePrice();
                    }
                }
            }
        }
        return null;
    }

    static void clearCache() {
        CACHE.clear();
    }

    private static List<Model> fetch(Provider provider, String base, @CheckForNull String apiKey)
            throws IOException, InterruptedException {
        List<Model> models = new ArrayList<>();
        switch (provider) {
            case ANTHROPIC -> {
                Map<String, Object> json = get(provider, base + "/v1/models?limit=1000", apiKey);
                for (Object item : Json.array(json, "data")) {
                    String id = Json.string(item, "id", "");
                    if (!id.isEmpty()) {
                        models.add(new Model(id, Json.string(item, "display_name", id), null));
                    }
                }
            }
            case XAI -> {
                try {
                    Map<String, Object> json = get(provider, base + "/language-models", apiKey);
                    for (Object item : Json.array(json, "models")) {
                        String id = Json.string(item, "id", "");
                        if (!id.isEmpty()) {
                            models.add(new Model(id, id, xaiPrice(item)));
                        }
                    }
                } catch (Llm.ProviderException e) {
                    // A gateway in front of xAI may only offer the plain list.
                    models.addAll(openAiList(provider, base, apiKey, false));
                }
            }
            case OPENAI -> models.addAll(openAiList(provider, base, apiKey, true));
            default -> models.addAll(openAiList(provider, base, apiKey, false));
        }
        models.sort(Comparator.comparing(Model::id));
        return models.size() > MAX_MODELS ? List.copyOf(models.subList(0, MAX_MODELS)) : List.copyOf(models);
    }

    private static List<Model> openAiList(Provider provider, String base, @CheckForNull String apiKey, boolean filter)
            throws IOException, InterruptedException {
        List<Model> models = new ArrayList<>();
        Map<String, Object> json = get(provider, base + "/models", apiKey);
        for (Object item : Json.array(json, "data")) {
            String id = Json.string(item, "id", "");
            if (!id.isEmpty() && !(filter && NOT_CHAT.matcher(id).matches())) {
                models.add(new Model(id, id, null));
            }
        }
        return models;
    }

    /**
     * xAI reports prices in US cents per 100 million tokens. Dividing by 10,000 gives dollars per
     * million.
     */
    @CheckForNull
    static ModelPrice xaiPrice(Object item) {
        Object in = item instanceof Map<?, ?> map ? map.get("prompt_text_token_price") : null;
        Object out = item instanceof Map<?, ?> map ? map.get("completion_text_token_price") : null;
        if (!(in instanceof Number) || !(out instanceof Number)) {
            return null;
        }
        BigDecimal divisor = BigDecimal.valueOf(10_000);
        return new ModelPrice(
                new BigDecimal(in.toString()).divide(divisor),
                new BigDecimal(out.toString()).divide(divisor),
                "reported by xAI");
    }

    private static Map<String, Object> get(Provider provider, String url, @CheckForNull String apiKey)
            throws IOException, InterruptedException {
        HttpClient http = ProxyConfiguration.newHttpClientBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (provider.getFlavor() == Provider.Flavor.ANTHROPIC) {
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
            throw new Llm.ProviderException(response.statusCode(), HttpLlmClient.errorMessage(response.body()));
        }
        try {
            return Json.parseObject(response.body());
        } catch (IllegalArgumentException e) {
            throw new Llm.ProviderException(0, "The provider's model list was not valid JSON.");
        }
    }

    /** A hash of the key, so the cache never holds the key itself. */
    private static String fingerprint(@CheckForNull String apiKey) {
        if (apiKey == null) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(apiKey.hashCode());
        }
    }
}
