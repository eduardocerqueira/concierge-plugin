package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Model lists from each provider's API, against a local server, and the prices shown with them. */
@WithJenkins
class ModelCatalogTest {

    private JenkinsRule r;
    private HttpServer server;
    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private final Map<String, String> authSeen = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void start(JenkinsRule r) throws IOException {
        this.r = r;
        ModelCatalog.clearCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("x-api-key");
            authSeen.put(
                    path,
                    auth != null
                            ? "x-api-key " + auth
                            : String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String body = replies.get(path);
            int status = body == null ? 404 : 200;
            byte[] bytes =
                    (body == null ? "{\"error\":{\"message\":\"not here\"}}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "key", "", Secret.fromString("sk-test-key")));
    }

    @AfterEach
    void stop() {
        server.stop(0);
        ModelCatalog.clearCache();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static List<String> ids(List<ModelCatalog.Model> models) {
        return models.stream().map(ModelCatalog.Model::id).collect(Collectors.toList());
    }

    @Test
    void anthropicListsItsModels() throws Exception {
        replies.put(
                "/v1/models",
                "{\"data\":[{\"id\":\"claude-sonnet-5-5\",\"display_name\":\"Claude Sonnet 5.5\",\"type\":\"model\"},"
                        + "{\"id\":\"claude-opus-5-5\",\"display_name\":\"Claude Opus 5.5\"}],\"has_more\":false}");
        List<ModelCatalog.Model> models = ModelCatalog.list(Provider.ANTHROPIC, base(), "sk-test-key");
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5-5"), ids(models));
        assertEquals("Claude Sonnet 5.5", models.get(1).displayName());
        assertEquals("x-api-key sk-test-key", authSeen.get("/v1/models"));
    }

    @Test
    void openAiListLeavesOutModelsThatCannotChat() throws Exception {
        replies.put(
                "/v1/models",
                "{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"text-embedding-3-large\"},{\"id\":\"whisper-1\"},"
                        + "{\"id\":\"dall-e-3\"},{\"id\":\"gpt-4o-mini-tts\"},{\"id\":\"omni-moderation-latest\"},{\"id\":\"o4-mini\"}]}");
        List<ModelCatalog.Model> models = ModelCatalog.list(Provider.OPENAI, base() + "/v1", "sk-test-key");
        assertEquals(List.of("gpt-5", "o4-mini"), ids(models));
        assertEquals("Bearer sk-test-key", authSeen.get("/v1/models"));
    }

    @Test
    void xaiReportsPricesInCentsPerHundredMillionTokens() throws Exception {
        replies.put(
                "/v1/language-models",
                "{\"models\":[{\"id\":\"grok-4\",\"prompt_text_token_price\":30000,\"completion_text_token_price\":150000}]}");
        ModelCatalog.Model grok =
                ModelCatalog.list(Provider.XAI, base() + "/v1", "xai-key").get(0);
        assertNotNull(grok.livePrice());
        assertEquals(0, new BigDecimal("3").compareTo(grok.livePrice().inputPerMillion()));
        assertEquals(0, new BigDecimal("15").compareTo(grok.livePrice().outputPerMillion()));
        assertEquals("reported by xAI", grok.livePrice().source());
    }

    @Test
    void xaiFallsBackToThePlainListBehindAGateway() throws Exception {
        replies.put("/v1/models", "{\"data\":[{\"id\":\"grok-4\"}]}");
        List<ModelCatalog.Model> models = ModelCatalog.list(Provider.XAI, base() + "/v1", "xai-key");
        assertEquals(List.of("grok-4"), ids(models));
        assertNull(models.get(0).livePrice());
    }

    @Test
    void localModelsNeedNoKey() throws Exception {
        replies.put("/v1/models", "{\"data\":[{\"id\":\"qwen2.5:7b\"},{\"id\":\"llama3.1:8b\"}]}");
        assertEquals(
                List.of("llama3.1:8b", "qwen2.5:7b"), ids(ModelCatalog.list(Provider.LOCAL, base() + "/v1", null)));
        assertEquals("null", authSeen.get("/v1/models"));
    }

    @Test
    void listsAreCachedPerProviderAddressAndKey() throws Exception {
        replies.put("/v1/models", "{\"data\":[{\"id\":\"m\"}]}");
        ModelCatalog.list(Provider.LOCAL, base() + "/v1", null);
        ModelCatalog.list(Provider.LOCAL, base() + "/v1", null);
        assertEquals(1, requests.get());
        ModelCatalog.list(Provider.LOCAL, base() + "/v1", "another-key");
        assertEquals(2, requests.get(), "a different key is a different cache entry");
    }

    @Test
    void pricesComeFromTheAdminThenTheProviderThenTheShippedList() {
        ModelPrice live = new ModelPrice(BigDecimal.ONE, BigDecimal.TEN, "reported by xAI");
        Map<String, ModelPrice> overrides = ModelPrice.parseOverrides("claude-sonnet-5-5 = 1.50 / 7.50", null);

        ModelPrice admin = ModelPrice.find(Provider.ANTHROPIC, "claude-sonnet-5-5", live, overrides);
        assertEquals("set by an administrator", admin.source());
        assertEquals(live, ModelPrice.find(Provider.XAI, "grok-4", live, Map.of()));

        ModelPrice listed = ModelPrice.find(Provider.ANTHROPIC, "claude-sonnet-5-5", null, Map.of());
        assertEquals("$2.00 in / $10.00 out per 1M tokens", listed.describe());
        assertTrue(listed.source().contains("as of " + ModelPrice.bundledAsOf()), listed.source());

        assertEquals(
                "$0.10 in / $0.50 out per 1M tokens",
                ModelPrice.bundled(Provider.ANTHROPIC, "claude-haiku-5-5").describe());
        assertNull(ModelPrice.find(Provider.OPENAI, "gpt-5", null, Map.of()), "no trusted source, no price");
    }

    @Test
    void costIsComputedFromTokens() {
        ModelPrice price = new ModelPrice(new BigDecimal("2.00"), new BigDecimal("10.00"), "x");
        assertEquals(0, new BigDecimal("0.012").compareTo(price.cost(1000, 1000)));
        assertEquals(0, BigDecimal.ZERO.compareTo(price.cost(0, 0)));
    }

    @Test
    void badPriceLinesAreReportedNotGuessed() {
        List<String> problems = new ArrayList<>();
        Map<String, ModelPrice> parsed = ModelPrice.parseOverrides(
                "# comment\nok-model = $1 / $2\nbroken = one / two\nno-slash = 3\n = 1 / 2\nneg = -1 / 2", problems);
        assertEquals(List.of("ok-model"), new ArrayList<>(parsed.keySet()));
        assertEquals(4, problems.size(), String.valueOf(problems));
        assertEquals(FormValidation.Kind.ERROR, AssistantConfiguration.get().doCheckModelPrices("broken = x").kind);
    }

    @Test
    void modelDropdownShowsPricesAndKeepsTheCurrentChoice() throws Exception {
        replies.put("/v1/models", "{\"data\":[{\"id\":\"claude-sonnet-5-5\",\"display_name\":\"Claude Sonnet 5.5\"}]}");
        AssistantConfiguration config = AssistantConfiguration.get();
        config.setModel("claude-old-model");
        ListBoxModel items = config.doFillSelectedModelItems("ANTHROPIC", "key", base(), "");
        List<String> labels = items.stream().map(o -> o.name).collect(Collectors.toList());
        assertTrue(
                labels.contains(
                        "Claude Sonnet 5.5 (claude-sonnet-5-5) — $2.00 in / $10.00 out per 1M tokens (Anthropic first-party API list prices, as of "
                                + ModelPrice.bundledAsOf() + ")"),
                String.valueOf(labels));
        ListBoxModel.Option current = items.stream()
                .filter(o -> o.value.equals("claude-old-model"))
                .findFirst()
                .orElseThrow();
        assertTrue(current.selected, "a saved model missing from the list stays selected rather than being lost");
        assertTrue(current.name.contains("not in the provider"));
    }

    @Test
    void modelDropdownExplainsFailures() {
        AssistantConfiguration config = AssistantConfiguration.get();
        List<String> labels = config.doFillSelectedModelItems("OPENAI", "key", base() + "/missing", "").stream()
                .map(o -> o.name)
                .collect(Collectors.toList());
        assertTrue(
                labels.stream().anyMatch(l -> l.startsWith("Could not list models (HTTP 404)")),
                String.valueOf(labels));
        labels = config.doFillSelectedModelItems("OPENAI", "", base(), "").stream()
                .map(o -> o.name)
                .collect(Collectors.toList());
        assertTrue(labels.stream().anyMatch(l -> l.contains("API key first")), String.valueOf(labels));
    }

    @Test
    void onlyAdministratorsCanMakeThePluginFetchModels() throws Exception {
        replies.put("/v1/models", "{\"data\":[{\"id\":\"claude-sonnet-5-5\"}]}");
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("alice"));
        AssistantConfiguration config = AssistantConfiguration.get();
        try (ACLContext ignored = ACL.as2(User.getById("alice", true).impersonate2())) {
            config.doFillSelectedModelItems("ANTHROPIC", "key", base(), "");
        }
        assertEquals(0, requests.get(), "a non-administrator must not be able to use the stored key");
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            config.doFillSelectedModelItems("ANTHROPIC", "key", base(), "");
        }
        assertEquals(1, requests.get());
    }

    @Test
    void customModelWinsOverTheList() {
        AssistantConfiguration config = AssistantConfiguration.get();
        config.setProvider(Provider.ANTHROPIC);
        config.setModel("claude-sonnet-5-5");
        assertEquals("claude-sonnet-5-5", config.getModel());
        config.setCustomModel("my-gateway-alias");
        assertEquals("my-gateway-alias", config.getModel());
        config.setCustomModel("  ");
        config.setModel("");
        assertEquals("claude-sonnet-5-5", config.getModel(), "the provider default when nothing is chosen");
        assertFalse(config.getModel().isBlank());
    }
}
