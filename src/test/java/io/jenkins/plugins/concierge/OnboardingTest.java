package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * The onboarding pages, mostly from the point of view of someone who must not see everything.
 *
 * <p>The instance has a team folder alice can read and a secret folder she cannot. Every test that
 * shows instance data checks that nothing from the secret folder leaks, by name or by count
 * (docs/security-design.md, rule 1).
 */
@WithJenkins
class OnboardingTest {

    private JenkinsRule r;
    private MockFolder team;
    private FreeStyleProject teamBuild;
    private FreeStyleProject teamBroken;
    private FreeStyleProject secretBuild;
    private FreeStyleProject secretBroken;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        team = r.createFolder("team");
        MockFolder secret = r.createFolder("secret");

        teamBuild = team.createProject(FreeStyleProject.class, "api-build");
        r.buildAndAssertSuccess(teamBuild);
        teamBroken = team.createProject(FreeStyleProject.class, "web-deploy");
        teamBroken.getBuildersList().add(new FailureBuilder());
        r.assertBuildStatus(Result.FAILURE, teamBroken.scheduleBuild2(0));

        secretBuild = secret.createProject(FreeStyleProject.class, "payroll-build");
        r.buildAndAssertSuccess(secretBuild);
        secretBroken = secret.createProject(FreeStyleProject.class, "payroll-deploy");
        secretBroken.getBuildersList().add(new FailureBuilder());
        r.assertBuildStatus(Result.FAILURE, secretBroken.scheduleBuild2(0));

        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ)
                .everywhere()
                .to("alice", "lead")
                .grant(Item.READ)
                .onFolders(team)
                .to("alice", "lead")
                .grant(ConciergePermissions.MANAGE_TOURS)
                .everywhere()
                .to("lead"));
    }

    private <T> T as(String user, Callable<T> what) throws Exception {
        try (ACLContext ignored = ACL.as2(User.getById(user, true).impersonate2())) {
            return what.call();
        }
    }

    @Test
    void useIsImpliedByReadButCurationAndTheAssistantAreNot() throws Exception {
        assertTrue(as("alice", () -> r.jenkins.hasPermission(ConciergePermissions.USE)));
        assertFalse(as("alice", () -> r.jenkins.hasPermission(ConciergePermissions.MANAGE_TOURS)));
        assertFalse(as("alice", () -> r.jenkins.hasPermission(ConciergePermissions.ASSISTANT)));
        assertTrue(as("admin", () -> r.jenkins.hasPermission(ConciergePermissions.ASSISTANT)));
    }

    @Test
    void overviewCountsOnlyWhatTheUserCanRead() throws Exception {
        InstanceOverview admin = as("admin", InstanceOverview::forCurrentUser);
        assertEquals(4, admin.getJobCount());
        assertEquals(2, admin.getCount("failure"));

        InstanceOverview alice = as("alice", InstanceOverview::forCurrentUser);
        assertEquals(2, alice.getJobCount(), "the secret folder's jobs are not even counted");
        assertEquals(1, alice.getCount("failure"));
        assertEquals(List.of("team/web-deploy"), names(alice.getFailing()));
        assertFalse(names(alice.getRecent()).stream().anyMatch(n -> n.startsWith("secret/")));
        assertEquals(
                List.of("team"),
                alice.getFolders().stream().map(f -> f.getName()).collect(Collectors.toList()),
                "a folder the user cannot read is not listed");
        assertFalse(alice.isPluginsVisible(), "installed plugins are system information");
        assertTrue(admin.isPluginsVisible());
    }

    @Test
    void welcomePageNeverMentionsTheSecretFolder() throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        String page = wc.goTo("concierge/").getWebResponse().getContentAsString();
        assertTrue(page.contains("web-deploy"), "alice sees her team's failing job");
        for (String hidden : List.of("payroll-build", "payroll-deploy", "secret")) {
            assertFalse(page.contains(hidden), "the welcome page leaked " + hidden);
        }
    }

    @Test
    void pageNeedsOverallRead() throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().withThrowExceptionOnFailingStatusCode(false);
        // Anonymous has no Overall/Read under this strategy, so Jenkins sends them to log in.
        HtmlPage page = wc.goTo("concierge/");
        assertTrue(
                page.getUrl().toString().contains("login")
                        || page.getWebResponse().getStatusCode() == 403,
                "anonymous users must not reach the Concierge page");
    }

    @Test
    void startHereDropsPinnedJobsTheUserCannotRead() throws Exception {
        ConciergeConfiguration.get()
                .setStartHere(List.of(
                        new PinnedJob("secret/payroll-build", "Top secret"),
                        new PinnedJob("team/api-build", "Our main build"),
                        new PinnedJob("does-not-exist", "Gone")));

        List<StartHereEntry> alice = as("alice", StartHereEntry::forCurrentUser);
        assertEquals(1, alice.size());
        assertEquals("team/api-build", alice.get(0).getJob().getFullName());
        assertEquals("Our main build", alice.get(0).getNote());

        assertEquals(2, as("admin", StartHereEntry::forCurrentUser).size());

        JSONObject tour =
                as("alice", () -> TourCatalog.steps(TourCatalog.START_HERE, Examples.forCurrentUser(), alice));
        assertNotNull(tour);
        String json = tour.toString();
        assertFalse(json.contains("payroll"), "the start-here tour leaked a hidden job");
        assertFalse(json.contains("Top secret"), "the start-here tour leaked a hidden job's note");
    }

    @Test
    void tourEndpointOnlyPointsAtReadableJobs() throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        for (String id : List.of(TourCatalog.DASHBOARD, TourCatalog.JOB)) {
            Page page = wc.goTo("concierge/tour?id=" + id, "application/json");
            String json = page.getWebResponse().getContentAsString();
            assertFalse(json.contains("secret"), id + " tour leaked the secret folder");
            JSONArray steps = JSONObject.fromObject(json).getJSONObject("data").getJSONArray("steps");
            assertFalse(steps.isEmpty());
        }
        Page missing = wc.withThrowExceptionOnFailingStatusCode(false).goTo("concierge/tour?id=nope", null);
        assertEquals(404, missing.getWebResponse().getStatusCode());
    }

    @Test
    void jobTourUsesAHealthyJobWhenThereIsOne() throws Exception {
        Examples examples = as("alice", Examples::forCurrentUser);
        assertNotNull(examples.builtJob);
        assertEquals("team/api-build", examples.builtJob.getFullName());
        assertEquals("team/web-deploy", examples.failingJob.getFullName());
        assertNull(examples.pipelineJob, "no Pipeline jobs here, and the paths must cope");
    }

    @Test
    void learningPathsLeaveOutPagesTheUserCannotOpen() throws Exception {
        Set<String> aliceSteps = stepIds(as("alice", () -> LearningPath.forCurrentUser(Set.of())));
        assertFalse(aliceSteps.contains("devops.plugins"));
        assertFalse(aliceSteps.contains("devops.log"));
        assertFalse(aliceSteps.contains("devops.security"));

        Set<String> adminSteps = stepIds(as("admin", () -> LearningPath.forCurrentUser(Set.of())));
        assertTrue(adminSteps.containsAll(Set.of("devops.plugins", "devops.log", "devops.security")));

        for (String id : adminSteps) {
            assertTrue(LearningPath.isKnownStep(id), id + " must be accepted by the progress endpoint");
        }
    }

    @Test
    void progressIsPostOnlyAndPerUser() throws Exception {
        JenkinsRule.WebClient wc = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);

        Page get = wc.goTo("concierge/progress?step=newcomer.job&done=true", null);
        assertNotEquals(200, get.getWebResponse().getStatusCode(), "GET must not be accepted");
        assertEquals(Set.of(), ProgressProperty.of(User.getById("alice", true)), "GET must not change anything");

        assertEquals(200, post(wc, "concierge/progress?step=newcomer.job&done=true"));
        assertEquals(Set.of("newcomer.job"), ProgressProperty.of(User.getById("alice", false)));
        assertEquals(Set.of(), ProgressProperty.of(User.getById("lead", true)), "progress is per user");

        assertEquals(400, post(wc, "concierge/progress?step=made.up&done=true"));

        assertEquals(200, post(wc, "concierge/progress?step=newcomer.job&done=false"));
        assertEquals(Set.of(), ProgressProperty.of(User.getById("alice", false)));
    }

    @Test
    void curationNeedsManageTours() throws Exception {
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, alice.goTo("concierge/manage").getWebResponse().getStatusCode());
        WebRequest save = new WebRequest(new URL(r.getURL(), "concierge/saveTours"), HttpMethod.POST);
        save.setRequestParameters(new java.util.ArrayList<>(
                List.of(new org.htmlunit.util.NameValuePair("json", "{\"welcomeMessage\":\"hacked\"}"))));
        alice.addCrumb(save);
        assertEquals(403, alice.getPage(save).getWebResponse().getStatusCode());
        assertEquals("", ConciergeConfiguration.get().getWelcomeMessage());

        JenkinsRule.WebClient lead = r.createWebClient().login("lead");
        assertEquals(200, lead.goTo("concierge/manage").getWebResponse().getStatusCode());
    }

    @Test
    void curatedTextIsShownAsTextNotMarkup() throws Exception {
        ConciergeConfiguration.get().setWelcomeMessage("Hello <script>alert(1)</script> <b>team</b>");
        ConciergeConfiguration.get()
                .setStartHere(List.of(new PinnedJob("team/api-build", "<img src=x onerror=alert(2)>")));

        JenkinsRule.WebClient wc = r.createWebClient().login("alice");
        String page = wc.goTo("concierge/").getWebResponse().getContentAsString();
        assertFalse(page.contains("<script>alert(1)"));
        assertFalse(page.contains("<b>team</b>"));
        assertFalse(page.contains("<img src=x"));
        assertTrue(page.contains("&lt;b>team&lt;/b>") || page.contains("&lt;b&gt;team&lt;/b&gt;"));
    }

    @Test
    void systemConfigurationPageStillRendersAndSaves() throws Exception {
        // ConciergeConfiguration has no config.jelly; if Jenkins tried to include one, the System
        // page would break part-way through for every administrator.
        JenkinsRule.WebClient admin = r.createWebClient().login("admin");
        HtmlPage configure = admin.goTo("configure");
        assertEquals(200, configure.getWebResponse().getStatusCode());
        assertNotNull(configure.getFormByName("config"));
        r.submit(configure.getFormByName("config"));
    }

    @Test
    void configurationKeepsItsLimits() {
        ConciergeConfiguration config = ConciergeConfiguration.get();
        config.setWelcomeMessage("x".repeat(ConciergeConfiguration.MAX_WELCOME_LENGTH + 50));
        assertEquals(
                ConciergeConfiguration.MAX_WELCOME_LENGTH,
                config.getWelcomeMessage().length());

        List<PinnedJob> many = new java.util.ArrayList<>();
        for (int i = 0; i < ConciergeConfiguration.MAX_PINNED + 5; i++) {
            many.add(new PinnedJob("job-" + i, "n".repeat(ConciergeConfiguration.MAX_NOTE_LENGTH + 10)));
        }
        config.setStartHere(many);
        assertEquals(ConciergeConfiguration.MAX_PINNED, config.getStartHere().size());
        assertEquals(
                ConciergeConfiguration.MAX_NOTE_LENGTH,
                config.getStartHere().get(0).getNote().length());
    }

    @Test
    void curationFormRowsAreParsedWhetherOneOrMany() {
        assertEquals(
                List.of(new PinnedJob("a", "note")),
                ConciergeAction.pinnedFrom(JSONObject.fromObject("{\"fullName\":\" a \",\"note\":\"note\"}")));
        assertEquals(
                2,
                ConciergeAction.pinnedFrom(JSONArray.fromObject(
                                "[{\"fullName\":\"a\"},{\"fullName\":\"\"},{\"fullName\":\"b\",\"note\":\"x\"}]"))
                        .size(),
                "blank rows are dropped");
        assertTrue(ConciergeAction.pinnedFrom(null).isEmpty());
    }

    private int post(JenkinsRule.WebClient wc, String path) throws Exception {
        WebRequest request = new WebRequest(new URL(r.getURL(), path), HttpMethod.POST);
        wc.addCrumb(request);
        return wc.getPage(request).getWebResponse().getStatusCode();
    }

    private static List<String> names(List<JobRef> refs) {
        return refs.stream().map(JobRef::getFullName).collect(Collectors.toList());
    }

    private static Set<String> stepIds(List<LearningPath> paths) {
        return paths.stream()
                .flatMap(p -> p.getSteps().stream())
                .map(LearningPath.Step::getId)
                .collect(Collectors.toSet());
    }
}
