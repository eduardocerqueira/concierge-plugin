package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Cause;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.PasswordParameterValue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Builds proposed by the assistant: nothing runs until the person confirms, and confirming checks
 * everything again.
 */
@WithJenkins
class ActionsTest {

    private JenkinsRule r;
    private FreeStyleProject api;
    private MockFolder team;
    private final List<Function<List<Llm.Turn>, Llm.Reply>> script = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        team = r.createFolder("team");
        api = team.createProject(FreeStyleProject.class, "api-build");
        api.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("BRANCH", "main", "Branch to build"),
                new PasswordParameterDefinition("TOKEN", "s3cret-default", "Deploy token")));
        team.createProject(FreeStyleProject.class, "web-deploy");
        MockFolder secret = r.createFolder("secret");
        secret.createProject(FreeStyleProject.class, "payroll");

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        grant(true);

        AssistantConfiguration config = AssistantConfiguration.get();
        config.setEnabled(true);
        config.setProvider(Provider.LOCAL);
        config.setModel("test");
        config.setBaseUrl("http://localhost:1/v1");
        config.setAllowActions(true);
        config.setActionJobs("team/*");
        config.save();

        AssistantEndpoint.RATE_LIMITER.reset();
        AssistantEndpoint.clientFactory = (provider, baseUrl, model, key) -> (system, turns, tools) -> {
            if (script.isEmpty()) {
                return new Llm.Reply("I proposed it; check the card.", List.of());
            }
            return script.remove(0).apply(turns);
        };
    }

    private void grant(boolean aliceCanBuild) {
        MockAuthorizationStrategy strategy = new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, ConciergePermissions.ASSISTANT)
                .everywhere()
                .to("alice", "bob")
                .grant(Item.READ)
                .onFolders(team)
                .to("alice", "bob");
        if (aliceCanBuild) {
            strategy.grant(Item.BUILD).onFolders(team).to("alice");
        }
        r.jenkins.setAuthorizationStrategy(strategy);
    }

    @AfterEach
    void tearDown() {
        AssistantEndpoint.clientFactory = HttpLlmClient::new;
    }

    private static Function<List<Llm.Turn>, Llm.Reply> call(String name, Map<String, Object> args) {
        return turns -> new Llm.Reply("", List.of(new Llm.ToolCall("id-" + name, name, args)));
    }

    private Page post(JenkinsRule.WebClient wc, String path, String... params) throws Exception {
        WebRequest request = new WebRequest(new URL(r.getURL(), path), HttpMethod.POST);
        List<NameValuePair> pairs = new ArrayList<>();
        for (int i = 0; i < params.length; i += 2) {
            pairs.add(new NameValuePair(params[i], params[i + 1]));
        }
        request.setRequestParameters(pairs);
        wc.addCrumb(request);
        return wc.getPage(request);
    }

    private Map<String, Object> ask(JenkinsRule.WebClient wc) throws Exception {
        return Json.parseObject(post(wc, "concierge/assistant/ask", "question", "please run it")
                .getWebResponse()
                .getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyProposal(Map<String, Object> body) {
        List<?> proposals = (List<?>) body.get("proposals");
        assertEquals(1, proposals.size(), "proposals: " + proposals);
        return (Map<String, Object>) proposals.get(0);
    }

    private <T> T as(String user, Callable<T> what) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return what.call();
        }
    }

    @Test
    void nothingRunsUntilThePersonConfirms() throws Exception {
        script.add(call("propose_build", Map.of("job", "team/api-build", "parameters", Map.of("BRANCH", "feature/x"))));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");
        Map<String, Object> proposal = onlyProposal(ask(alice));
        assertEquals("team/api-build", proposal.get("job"));
        assertEquals(
                List.of(
                        Map.of("name", "BRANCH", "value", "feature/x"),
                        Map.of("name", "TOKEN", "value", "(default, hidden)")),
                proposal.get("parameters"));
        assertFalse(proposal.toString().contains("s3cret"), "a password default reached the browser");

        r.waitUntilNoActivity();
        assertNull(api.getLastBuild(), "proposing must not start anything");
        assertTrue(r.jenkins.getQueue().isEmpty());

        Page confirmed = post(alice, "concierge/assistant/confirm", "id", (String) proposal.get("id"));
        assertEquals(200, confirmed.getWebResponse().getStatusCode());
        r.waitUntilNoActivity();
        FreeStyleBuild build = api.getLastBuild();
        assertNotNull(build);
        ParametersAction parameters = build.getAction(ParametersAction.class);
        assertEquals("feature/x", ((StringParameterValue) parameters.getParameter("BRANCH")).getValue());
        assertEquals(
                "s3cret-default",
                ((PasswordParameterValue) parameters.getParameter("TOKEN"))
                        .getValue()
                        .getPlainText());
        Cause.UserIdCause cause = build.getCause(Cause.UserIdCause.class);
        assertEquals("alice", cause.getUserId(), "the build is started by the person, not by the plugin");
    }

    @Test
    void aProposalWorksOnce() throws Exception {
        script.add(call("propose_build", Map.of("job", "team/api-build")));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        String id = (String) onlyProposal(ask(alice)).get("id");
        assertEquals(
                200,
                post(alice, "concierge/assistant/confirm", "id", id)
                        .getWebResponse()
                        .getStatusCode());
        assertEquals(
                409,
                post(alice, "concierge/assistant/confirm", "id", id)
                        .getWebResponse()
                        .getStatusCode());
        r.waitUntilNoActivity();
        assertEquals(1, api.getBuilds().size());
    }

    @Test
    void aProposalBelongsToItsUser() throws Exception {
        BuildProposals proposals = new BuildProposals();
        BuildProposals.Proposal proposal =
                as("alice", () -> proposals.proposeBuild(AssistantConfiguration.get(), "team/api-build", Map.of()));
        assertThrows(
                BuildProposals.ProposalException.class,
                () -> as("admin", () -> proposals.confirm(AssistantConfiguration.get(), proposal.id())));
        // Another browser session has no proposals at all.
        JenkinsRule.WebClient admin = r.createWebClient().login("admin").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(
                404,
                post(admin, "concierge/assistant/confirm", "id", proposal.id())
                        .getWebResponse()
                        .getStatusCode());
        r.waitUntilNoActivity();
        assertNull(api.getLastBuild());
    }

    @Test
    void cancellingDropsTheProposal() throws Exception {
        script.add(call("propose_build", Map.of("job", "team/api-build")));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        String id = (String) onlyProposal(ask(alice)).get("id");
        post(alice, "concierge/assistant/cancel", "id", id);
        assertEquals(
                409,
                post(alice, "concierge/assistant/confirm", "id", id)
                        .getWebResponse()
                        .getStatusCode());
        r.waitUntilNoActivity();
        assertNull(api.getLastBuild());
    }

    @Test
    void permissionIsCheckedAgainOnConfirm() throws Exception {
        script.add(call("propose_build", Map.of("job", "team/api-build")));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        String id = (String) onlyProposal(ask(alice)).get("id");
        grant(false);
        Page refused = post(alice, "concierge/assistant/confirm", "id", id);
        assertEquals(409, refused.getWebResponse().getStatusCode());
        assertTrue(refused.getWebResponse().getContentAsString().contains("permission"));
        r.waitUntilNoActivity();
        assertNull(api.getLastBuild());
    }

    @Test
    void turningActionsOffStopsPendingProposals() throws Exception {
        script.add(call("propose_build", Map.of("job", "team/api-build")));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        String id = (String) onlyProposal(ask(alice)).get("id");
        AssistantConfiguration.get().setAllowActions(false);
        assertEquals(
                409,
                post(alice, "concierge/assistant/confirm", "id", id)
                        .getWebResponse()
                        .getStatusCode());
        r.waitUntilNoActivity();
        assertNull(api.getLastBuild());
    }

    @Test
    void onlyAllowedJobsAndOnlyForPeopleWhoMayBuild() throws Exception {
        AssistantTools tools =
                new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()), new BuildProposals());
        AssistantConfiguration.get().setActionJobs("team/web-*");
        Llm.ToolResult notListed =
                as("alice", () -> tools.run(new Llm.ToolCall("1", "propose_build", Map.of("job", "team/api-build"))));
        assertTrue(notListed.error());
        assertTrue(notListed.content().contains("not allowed to start"));

        AssistantConfiguration.get().setActionJobs("team/*");
        Llm.ToolResult bob =
                as("bob", () -> tools.run(new Llm.ToolCall("1", "propose_build", Map.of("job", "team/api-build"))));
        assertTrue(bob.error(), "bob may read but not build");

        Llm.ToolResult hidden =
                as("alice", () -> tools.run(new Llm.ToolCall("1", "propose_build", Map.of("job", "secret/payroll"))));
        assertTrue(hidden.content().contains("There is no job called secret/payroll that the user can see."));
    }

    @Test
    void passwordAndUnknownParametersAreRefused() throws Exception {
        AssistantTools tools =
                new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()), new BuildProposals());
        Llm.ToolResult password = as(
                "alice",
                () -> tools.run(new Llm.ToolCall(
                        "1", "propose_build", Map.of("job", "team/api-build", "parameters", Map.of("TOKEN", "x")))));
        assertTrue(password.error());
        assertTrue(password.content().contains("password parameter"));
        Llm.ToolResult unknown = as(
                "alice",
                () -> tools.run(new Llm.ToolCall(
                        "1", "propose_build", Map.of("job", "team/api-build", "parameters", Map.of("NOPE", "x")))));
        assertTrue(unknown.error());
    }

    @Test
    void rebuildsCopyOrdinaryValuesButNeverSecrets() throws Exception {
        r.assertBuildStatusSuccess(api.scheduleBuild2(
                0,
                new ParametersAction(
                        new StringParameterValue("BRANCH", "release/2"),
                        new PasswordParameterValue("TOKEN", "previous-run-secret"))));
        script.add(call("propose_rebuild", Map.of("job", "team/api-build")));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");
        Map<String, Object> proposal = onlyProposal(ask(alice));
        assertEquals("rebuild", proposal.get("kind"));
        assertEquals(1, proposal.get("fromBuild"));
        assertFalse(proposal.toString().contains("previous-run-secret"));
        post(alice, "concierge/assistant/confirm", "id", (String) proposal.get("id"));
        r.waitUntilNoActivity();
        ParametersAction parameters = api.getLastBuild().getAction(ParametersAction.class);
        assertEquals("release/2", ((StringParameterValue) parameters.getParameter("BRANCH")).getValue());
        assertEquals(
                "s3cret-default",
                ((PasswordParameterValue) parameters.getParameter("TOKEN"))
                        .getValue()
                        .getPlainText(),
                "a repeated build uses the default, not the previous run's secret");
    }

    @Test
    void proposalToolsAreOnlyOfferedWhenActionsAreAllowed() throws Exception {
        AssistantConfiguration.get().setAllowActions(false);
        AssistantTools tools =
                new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()), new BuildProposals());
        assertFalse(tools.specs().stream().anyMatch(s -> s.name().startsWith("propose_")));
        assertTrue(as("alice", () -> tools.run(new Llm.ToolCall("1", "propose_build", Map.of("job", "team/api-build"))))
                .error());
        assertFalse(Assistant.systemPrompt(false).contains("propose_build"));
        assertTrue(Assistant.systemPrompt(true).contains("propose_build"));
    }

    @Test
    void confirmIsPostOnlyAndNeedsTheAssistantPermission() throws Exception {
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        assertNotEquals(
                200,
                alice.goTo("concierge/assistant/confirm?id=x", null)
                        .getWebResponse()
                        .getStatusCode());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .to("alice")
                .grant(Item.READ, Item.BUILD)
                .onFolders(team)
                .to("alice"));
        assertEquals(
                403,
                post(alice, "concierge/assistant/confirm", "id", "x")
                        .getWebResponse()
                        .getStatusCode());
    }

    /** A job like the demo's release-train: every common parameter type at once. */
    private FreeStyleProject releaseJob() throws Exception {
        FreeStyleProject release = team.createProject(FreeStyleProject.class, "release-train");
        release.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("VERSION", "2.15.0", ""),
                new hudson.model.ChoiceParameterDefinition(
                        "ENVIRONMENT", new String[] {"staging", "production", "sandbox"}, ""),
                new hudson.model.ChoiceParameterDefinition(
                        "CANARY_PERCENT", new String[] {"5", "10", "25", "50", "100"}, ""),
                new hudson.model.BooleanParameterDefinition("RUN_SMOKE_TESTS", true, ""),
                new hudson.model.TextParameterDefinition("RELEASE_NOTES", "Routine release.", ""),
                new PasswordParameterDefinition("DEPLOY_TOKEN", hudson.util.Secret.fromString("tok-default"), "")));
        return release;
    }

    @Test
    void complexParametersRoundTripThroughTheCard() throws Exception {
        FreeStyleProject release = releaseJob();
        Map<String, Object> asked = new java.util.LinkedHashMap<>();
        asked.put("VERSION", "2.16.0");
        asked.put("ENVIRONMENT", "production");
        asked.put("CANARY_PERCENT", "10");
        asked.put("RUN_SMOKE_TESTS", "false");
        asked.put("RELEASE_NOTES", "Faster refunds.\nNew retry logic.");
        script.add(call("propose_build", Map.of("job", "team/release-train", "parameters", asked)));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");
        Map<String, Object> proposal = onlyProposal(ask(alice));
        assertEquals(
                List.of(
                        Map.of("name", "VERSION", "value", "2.16.0"),
                        Map.of("name", "ENVIRONMENT", "value", "production"),
                        Map.of("name", "CANARY_PERCENT", "value", "10"),
                        Map.of("name", "RUN_SMOKE_TESTS", "value", "false"),
                        Map.of("name", "RELEASE_NOTES", "value", "Faster refunds.\nNew retry logic."),
                        Map.of("name", "DEPLOY_TOKEN", "value", "(default, hidden)")),
                proposal.get("parameters"),
                "every value the card shows, in the job's order, with the password hidden");

        post(alice, "concierge/assistant/confirm", "id", (String) proposal.get("id"));
        r.waitUntilNoActivity();
        ParametersAction values = release.getLastBuild().getAction(ParametersAction.class);
        assertEquals("production", values.getParameter("ENVIRONMENT").getValue());
        assertEquals("10", values.getParameter("CANARY_PERCENT").getValue());
        assertEquals(Boolean.FALSE, values.getParameter("RUN_SMOKE_TESTS").getValue());
        assertEquals(
                "Faster refunds.\nNew retry logic.",
                values.getParameter("RELEASE_NOTES").getValue());
        assertEquals(
                "tok-default",
                ((PasswordParameterValue) values.getParameter("DEPLOY_TOKEN"))
                        .getValue()
                        .getPlainText());
    }

    @Test
    void choicesOutsideTheListAreRefused() throws Exception {
        releaseJob();
        AssistantTools tools =
                new AssistantTools(AssistantConfiguration.get(), new Redactor(List.of()), new BuildProposals());
        Llm.ToolResult result = as(
                "alice",
                () -> tools.run(new Llm.ToolCall(
                        "1",
                        "propose_build",
                        Map.of("job", "team/release-train", "parameters", Map.of("ENVIRONMENT", "moon")))));
        assertTrue(result.error(), result.content());
        assertTrue(result.content().contains("ENVIRONMENT"), result.content());
    }

    @Test
    void jobPatterns() {
        JobPatterns patterns = new JobPatterns("team/*\n# comment\nops/**, exact-job");
        assertTrue(patterns.matches("team/api-build"));
        assertFalse(patterns.matches("team/sub/api-build"), "* stays within one folder");
        assertTrue(patterns.matches("ops/a/b/c"));
        assertTrue(patterns.matches("exact-job"));
        assertFalse(patterns.matches("exact-job-2"));
        assertFalse(patterns.matches("team.x/y"), "dots are literal");
        assertTrue(new JobPatterns("").isEmpty());
        assertFalse(new JobPatterns(null).matches("anything"));
    }
}
