package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The assistant end to end, with a scripted model in place of a provider. The script decides which
 * tools the "model" calls; the tests then inspect exactly what would have been sent to the provider.
 */
@WithJenkins
class AssistantTest {

    static final String API_KEY = "sk-ant-test-0123456789abcdefghij";
    static final String GITHUB_TOKEN = "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789ab";

    private JenkinsRule r;

    /** Everything the fake model received, one entry per call. */
    private final List<List<Llm.Turn>> received = Collections.synchronizedList(new ArrayList<>());

    private final List<Function<List<Llm.Turn>, Llm.Reply>> script = Collections.synchronizedList(new ArrayList<>());
    private final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    private final Handler auditHandler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            audit.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        MockFolder team = r.createFolder("team");
        MockFolder secret = r.createFolder("secret");
        FreeStyleProject web = team.createProject(FreeStyleProject.class, "web-deploy");
        web.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(
                    hudson.model.AbstractBuild<?, ?> build,
                    hudson.Launcher launcher,
                    hudson.model.BuildListener listener) {
                listener.getLogger().println("Connecting with DB_PASSWORD=hunter2");
                listener.getLogger().println("Using key " + API_KEY);
                // Secrets at the start of a line and quoted values, which JSON escaping used to hide
                // from the redactor.
                listener.getLogger().println(GITHUB_TOKEN);
                listener.getLogger().println("AKIAIOSFODNN7EXAMPLE");
                listener.getLogger().println("deploy_password=\"correct horse\"");
                listener.getLogger().println("ERROR: connection refused");
                return false;
            }
        });
        r.assertBuildStatus(Result.FAILURE, web.scheduleBuild2(0));
        FreeStyleProject payroll = secret.createProject(FreeStyleProject.class, "payroll-deploy");
        payroll.getBuildersList().add(new FailureBuilder());
        r.assertBuildStatus(Result.FAILURE, payroll.scheduleBuild2(0));

        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(CredentialsScope.GLOBAL, "ai-key", "AI", Secret.fromString(API_KEY)));
        SystemCredentialsProvider.getInstance().save();

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, ConciergePermissions.ASSISTANT)
                .everywhere()
                .to("alice")
                .grant(Item.READ)
                .onFolders(team)
                .to("alice")
                .grant(Jenkins.READ)
                .everywhere()
                .to("bob")
                .grant(Item.READ)
                .onFolders(team)
                .to("bob"));

        AssistantConfiguration config = AssistantConfiguration.get();
        config.setEnabled(true);
        config.setProvider(Provider.ANTHROPIC);
        config.setModel("claude-test");
        config.setCredentialsId("ai-key");
        config.setShareLogs(true);
        config.save();

        AssistantEndpoint.clientFactory = (provider, baseUrl, model, key) -> (system, turns, tools) -> {
            received.add(List.copyOf(turns));
            if (script.isEmpty()) {
                return new Llm.Reply("Done.", List.of());
            }
            return script.remove(0).apply(turns);
        };
        Assistant.AUDIT.addHandler(auditHandler);
        AssistantEndpoint.RATE_LIMITER.reset();
    }

    @AfterEach
    void tearDown() {
        AssistantEndpoint.clientFactory = HttpLlmClient::new;
        Assistant.AUDIT.removeHandler(auditHandler);
        Assistant.AUDIT.setLevel(null);
    }

    private static Function<List<Llm.Turn>, Llm.Reply> callTool(String name, Map<String, Object> args) {
        return turns -> new Llm.Reply("", List.of(new Llm.ToolCall("id-" + name, name, args)));
    }

    private Page ask(JenkinsRule.WebClient wc, String question) throws Exception {
        WebRequest request = new WebRequest(new URL(r.getURL(), "concierge/assistant/ask"), HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(List.of(
                new NameValuePair("question", question), new NameValuePair("page", "job/team/job/web-deploy/"))));
        wc.addCrumb(request);
        return wc.getPage(request);
    }

    /** All tool results sent to the model, as one string. */
    private String toolResultsSent() {
        StringBuilder out = new StringBuilder();
        for (List<Llm.Turn> turns : received) {
            for (Llm.Turn turn : turns) {
                if (turn instanceof Llm.ToolResultsTurn results) {
                    results.results()
                            .forEach(result -> out.append(result.content()).append('\n'));
                }
            }
        }
        return out.toString();
    }

    private <T> T as(String user, Callable<T> what) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return what.call();
        }
    }

    @Test
    void answersUsingToolsThatOnlySeeWhatTheUserCanSee() throws Exception {
        script.add(callTool("list_jobs", Map.of("status", "failure")));
        script.add(callTool("overview", Map.of()));
        script.add(turns -> new Llm.Reply("team/web-deploy is failing: connection refused.", List.of()));

        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        Map<String, Object> body =
                Json.parseObject(ask(wc, "What is failing?").getWebResponse().getContentAsString());
        assertEquals("team/web-deploy is failing: connection refused.", body.get("answer"));
        assertEquals(List.of("list_jobs", "overview"), body.get("tools"));

        String sent = toolResultsSent();
        assertTrue(sent.contains("team/web-deploy"));
        assertFalse(sent.contains("payroll"), "a hidden job reached the model: " + sent);
        assertFalse(sent.contains("secret"), "a hidden folder reached the model: " + sent);
    }

    @Test
    void hiddenJobsLookExactlyLikeMissingOnes() throws Exception {
        script.add(callTool("get_job", Map.of("job", "secret/payroll-deploy")));
        script.add(callTool("get_job", Map.of("job", "team/no-such-job")));
        script.add(turns -> new Llm.Reply("Not found.", List.of()));
        ask(r.createWebClient().login("alice"), "Tell me about payroll");
        String sent = toolResultsSent();
        assertTrue(sent.contains("There is no job called secret/payroll-deploy that the user can see."));
        assertTrue(sent.contains("There is no job called team/no-such-job that the user can see."));
    }

    @Test
    void logsAreRedactedBeforeTheyLeave() throws Exception {
        script.add(callTool("get_build_log", Map.of("job", "team/web-deploy")));
        script.add(turns -> new Llm.Reply("The database refused the connection.", List.of()));
        ask(r.createWebClient().login("alice"), "Why did it fail?");
        String sent = toolResultsSent();
        assertTrue(sent.contains("connection refused"), sent);
        assertFalse(sent.contains("hunter2"), "a password reached the model");
        assertFalse(sent.contains(API_KEY), "the API key reached the model");
        assertFalse(sent.contains(GITHUB_TOKEN), "a token at the start of a line reached the model");
        assertFalse(sent.contains("AKIAIOSFODNN7EXAMPLE"), "an access key at the start of a line reached the model");
        assertFalse(sent.contains("correct horse"), "a quoted password reached the model");
        assertTrue(sent.contains(Redactor.MASK));
    }

    @Test
    void logToolsAreNotOfferedUnlessAnAdminSharesLogs() throws Exception {
        AssistantConfiguration.get().setShareLogs(false);
        AssistantTools tools = new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()));
        assertFalse(tools.specs().stream().anyMatch(s -> s.name().contains("log")));
        // And asking anyway is refused, in case a model calls a tool it was not offered.
        Llm.ToolResult result =
                as("alice", () -> tools.run(new Llm.ToolCall("1", "get_build_log", Map.of("job", "team/web-deploy"))));
        assertTrue(result.error());
        assertTrue(result.content().contains("has not allowed sharing logs"));
        assertFalse(result.content().contains("connection refused"));
    }

    @Test
    void configurationNeedsExtendedReadAndAnOptIn() throws Exception {
        AssistantConfiguration.get().setShareConfiguration(true);
        AssistantTools tools = new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()));
        Llm.ToolResult alice = as(
                "alice",
                () -> tools.run(new Llm.ToolCall("1", "get_job_configuration", Map.of("job", "team/web-deploy"))));
        assertTrue(alice.error(), "alice has Item/Read but not Item/ExtendedRead");
        Llm.ToolResult admin = as(
                "admin",
                () -> tools.run(new Llm.ToolCall("1", "get_job_configuration", Map.of("job", "team/web-deploy"))));
        assertFalse(admin.error(), admin.content());
    }

    @Test
    void theProviderListNeedsSystemRead() throws Exception {
        assertTrue(as("alice", () -> AssistantConfiguration.get().doFillProviderItems())
                .isEmpty());
        assertEquals(
                Provider.values().length,
                as("admin", () -> AssistantConfiguration.get().doFillProviderItems())
                        .size());
    }

    @Test
    void pluginsNeedSystemRead() throws Exception {
        AssistantTools tools = new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()));
        assertTrue(as("alice", () -> tools.run(new Llm.ToolCall("1", "list_plugins", Map.of())))
                .error());
        assertFalse(as("admin", () -> tools.run(new Llm.ToolCall("1", "list_plugins", Map.of())))
                .error());
    }

    @Test
    void unknownToolsAndBrokenArgumentsAreErrorsForTheModel() throws Exception {
        AssistantTools tools = new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()));
        for (String forbidden : List.of("script_console", "get_credentials", "replay_build", "run_groovy")) {
            Llm.ToolResult result = as("admin", () -> tools.run(new Llm.ToolCall("1", forbidden, Map.of())));
            assertTrue(result.error(), forbidden);
        }
        assertTrue(as("alice", () -> tools.run(new Llm.ToolCall("1", "get_job", Map.of("_invalid_arguments", "{"))))
                .error());
    }

    @Test
    void needsTheAssistantPermission() throws Exception {
        JenkinsRule.WebClient bob = r.createWebClient().login("bob").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, ask(bob, "hello").getWebResponse().getStatusCode());
        assertEquals(403, bob.goTo("concierge/assistant/").getWebResponse().getStatusCode());
        assertTrue(received.isEmpty(), "nothing may be sent for a user without the permission");
    }

    @Test
    void isInvisibleWhileDisabled() throws Exception {
        AssistantConfiguration.get().setEnabled(false);
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(404, ask(alice, "hello").getWebResponse().getStatusCode());
        String page = alice.goTo("concierge/").getWebResponse().getContentAsString();
        assertFalse(page.contains("assistant.js"), "no panel while the assistant is off");
    }

    @Test
    void panelIsAddedToPagesForPermittedUsersOnly() throws Exception {
        String alice =
                r.createWebClient().login("alice").goTo("").getWebResponse().getContentAsString();
        assertTrue(alice.contains("/plugin/concierge/js/assistant.js"));
        String bob = r.createWebClient().login("bob").goTo("").getWebResponse().getContentAsString();
        assertFalse(bob.contains("/plugin/concierge/js/assistant.js"));
    }

    @Test
    void askingIsPostOnly() throws Exception {
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        Page get = alice.goTo("concierge/assistant/ask?question=hello", null);
        assertNotEquals(200, get.getWebResponse().getStatusCode());
        assertTrue(received.isEmpty());
    }

    @Test
    void rateLimitIsPerUser() throws Exception {
        AssistantConfiguration.get().setRequestsPerHour(2);
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(200, ask(alice, "one").getWebResponse().getStatusCode());
        assertEquals(200, ask(alice, "two").getWebResponse().getStatusCode());
        Page third = ask(alice, "three");
        assertEquals(429, third.getWebResponse().getStatusCode());
        assertTrue(third.getWebResponse().getContentAsString().contains("limit"));
        JenkinsRule.WebClient admin = r.createWebClient().login("admin");
        assertEquals(200, ask(admin, "mine").getWebResponse().getStatusCode());
    }

    @Test
    void conversationIsKeptInTheSessionAndCanBeReset() throws Exception {
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");
        ask(alice, "first question");
        ask(alice, "second question");
        List<Llm.Turn> second = received.get(received.size() - 1);
        assertTrue(second.stream()
                .anyMatch(t -> t instanceof Llm.UserTurn u && u.text().contains("first question")));

        String history = alice.goTo("concierge/assistant/history", "application/json")
                .getWebResponse()
                .getContentAsString();
        assertTrue(history.contains("second question"));
        Map<String, Object> parsed = Json.parseObject(history);
        assertEquals("alice", parsed.get("user"), "the chat shows the person's name and initials");
        for (Object turn : Json.array(parsed, "turns")) {
            Object at = ((Map<?, ?>) turn).get("at");
            assertTrue(at instanceof Number && ((Number) at).longValue() > 0, "every turn carries its time: " + turn);
        }

        WebRequest reset = new WebRequest(new URL(r.getURL(), "concierge/assistant/reset"), HttpMethod.POST);
        alice.addCrumb(reset);
        alice.getPage(reset);
        assertFalse(alice.goTo("concierge/assistant/history", "application/json")
                .getWebResponse()
                .getContentAsString()
                .contains("second question"));
    }

    @Test
    void auditLogRecordsQuestionsAndToolsButNeverTheKey() throws Exception {
        script.add(callTool("get_build_log", Map.of("job", "team/web-deploy")));
        script.add(turns -> new Llm.Reply("ok", List.of()));
        ask(r.createWebClient().login("alice"), "Why did web-deploy fail?");
        String log = String.join("\n", audit);
        assertTrue(log.contains("question user=alice"), log);
        assertTrue(log.contains("name=get_build_log"), log);
        assertFalse(log.contains(API_KEY), "the API key reached the audit log");
        assertFalse(log.contains("hunter2"), "tool results must not be audited");
        // The system log is readable with Overall/SystemRead, so what was asked stays out of it...
        assertFalse(log.contains("Why did web-deploy fail?"), log);

        // ...and is there for an administrator who adds a log recorder at FINE.
        audit.clear();
        Assistant.AUDIT.setLevel(java.util.logging.Level.FINE);
        script.add(turns -> new Llm.Reply("ok", List.of()));
        ask(r.createWebClient().login("alice"), "And the one before?");
        assertTrue(String.join("\n", audit).contains("text=And the one before?"), String.join("\n", audit));
    }

    @Test
    void stopsAfterTheToolCallLimit() throws Exception {
        AssistantConfiguration.get().setMaxToolCalls(2);
        for (int i = 0; i < 10; i++) {
            script.add(callTool("overview", Map.of()));
        }
        Map<String, Object> body = Json.parseObject(ask(r.createWebClient().login("alice"), "loop forever")
                .getWebResponse()
                .getContentAsString());
        assertEquals(3, received.size(), "two rounds with tools, then one without");
        assertTrue(body.get("answer") instanceof String);
    }

    @Test
    void answersThatLookLikeJsonArriveUnchanged() throws Exception {
        script.add(turns -> new Llm.Reply("[1, 2, 3]", List.of()));
        Map<String, Object> body = Json.parseObject(ask(r.createWebClient().login("alice"), "numbers")
                .getWebResponse()
                .getContentAsString());
        assertEquals("[1, 2, 3]", body.get("answer"));
    }

    @Test
    void deepSeekStaysOffUntilAllowed() {
        FormValidation blocked =
                AssistantConfiguration.validate(Provider.DEEPSEEK, "deepseek-chat", "ai-key", "", false);
        assertEquals(FormValidation.Kind.ERROR, blocked.kind);
        FormValidation allowed =
                AssistantConfiguration.validate(Provider.DEEPSEEK, "deepseek-chat", "ai-key", "", true);
        assertEquals(FormValidation.Kind.OK, allowed.kind);
    }

    @Test
    void baseUrlsAreChecked() {
        assertEquals(FormValidation.Kind.OK, AssistantConfiguration.checkUrl("https://gateway.example.com/v1").kind);
        assertEquals(FormValidation.Kind.OK, AssistantConfiguration.checkUrl("http://localhost:11434/v1").kind);
        assertEquals(FormValidation.Kind.WARNING, AssistantConfiguration.checkUrl("http://models.internal/v1").kind);
        assertEquals(FormValidation.Kind.ERROR, AssistantConfiguration.checkUrl("file:///etc/passwd").kind);
        assertEquals(FormValidation.Kind.ERROR, AssistantConfiguration.checkUrl("https://user:pw@host/v1").kind);
        assertEquals(
                FormValidation.Kind.ERROR,
                AssistantConfiguration.validate(Provider.LOCAL, "llama", null, "", false).kind,
                "a local model needs a base URL");
    }

    @Test
    void testConnectionIsAdminOnly() throws Exception {
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        WebRequest request = new WebRequest(
                new URL(
                        r.getURL(),
                        "descriptorByName/io.jenkins.plugins.concierge.AssistantConfiguration/testConnection"),
                HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(List.of(
                new NameValuePair("provider", "OPENAI"),
                new NameValuePair("selectedModel", "m"),
                new NameValuePair("credentialsId", "ai-key"),
                new NameValuePair("baseUrl", "https://attacker.example.com"))));
        alice.addCrumb(request);
        assertEquals(403, alice.getPage(request).getWebResponse().getStatusCode());
    }

    @Test
    void settingsSurviveTheConfigurationForm() throws Exception {
        AssistantConfiguration config = AssistantConfiguration.get();
        config.setShareScm(true);
        config.setBaseUrl("https://gateway.example.com/anthropic");
        config.setRequestsPerHour(12);
        JenkinsRule.WebClient admin = r.createWebClient().login("admin");
        org.htmlunit.html.HtmlPage page = admin.goTo("configure");
        // The credentials list is filled by a background request; submit only once it has arrived.
        admin.waitForBackgroundJavaScript(5000);
        r.submit(page.getFormByName("config"));
        config = AssistantConfiguration.get();
        assertTrue(config.isEnabled());
        assertEquals(Provider.ANTHROPIC, config.getProvider());
        assertEquals("claude-test", config.getModel());
        assertEquals("ai-key", config.getCredentialsId());
        assertTrue(config.isShareLogs());
        assertTrue(config.isShareScm());
        assertFalse(config.isShareConfiguration());
        assertEquals("https://gateway.example.com/anthropic", config.getEffectiveBaseUrl());
        assertEquals(12, config.getRequestsPerHour());
    }

    @Test
    void usersCanBringTheirOwnKeyOnlyWhenAllowed() throws Exception {
        User alice = User.getById("alice", true);
        alice.addProperty(new UserKeyProperty(Secret.fromString("sk-alice-own-key-123456789")));
        List<String> keysUsed = Collections.synchronizedList(new ArrayList<>());
        AssistantEndpoint.clientFactory = (provider, baseUrl, model, key) -> {
            keysUsed.add(key);
            return (system, turns, tools) -> new Llm.Reply("ok", List.of());
        };
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        ask(wc, "one");
        AssistantConfiguration.get().setAllowUserKeys(true);
        ask(wc, "two");
        assertEquals(List.of(API_KEY, "sk-alice-own-key-123456789"), keysUsed);
    }
}
