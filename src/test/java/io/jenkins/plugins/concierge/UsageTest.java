package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.model.User;
import hudson.util.Secret;
import java.math.BigDecimal;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.util.NameValuePair;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/** Tokens, estimated cost, the monthly ledger and the budget. */
@WithJenkins
class UsageTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        this.r = r;
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER)
                .everywhere()
                .to("admin")
                .grant(Jenkins.READ, ConciergePermissions.ASSISTANT)
                .everywhere()
                .to("alice"));
        SystemCredentialsProvider.getInstance()
                .getCredentials()
                .add(new StringCredentialsImpl(
                        CredentialsScope.GLOBAL, "ai-key", "", Secret.fromString("sk-instance-key-1")));
        AssistantConfiguration config = AssistantConfiguration.get();
        config.setEnabled(true);
        config.setProvider(Provider.ANTHROPIC);
        config.setModel("claude-sonnet-5-5");
        config.setCredentialsId("ai-key");
        config.save();
        AssistantEndpoint.RATE_LIMITER.reset();
        UsageLedger.reload();
        // Two rounds: a tool call, then the answer. 3,000 input and 300 output tokens in all.
        AssistantEndpoint.clientFactory = (provider, baseUrl, model, key) -> (system, turns, tools) -> {
            boolean answered = turns.stream().anyMatch(t -> t instanceof Llm.ToolResultsTurn);
            return answered
                    ? new Llm.Reply("Done.", List.of(), 2000, 200)
                    : new Llm.Reply("", List.of(new Llm.ToolCall("1", "overview", Map.of())), 1000, 100);
        };
    }

    @AfterEach
    void tearDown() {
        AssistantEndpoint.clientFactory = HttpLlmClient::new;
    }

    private Page ask(JenkinsRule.WebClient wc) throws Exception {
        WebRequest request = new WebRequest(new URL(r.getURL(), "concierge/assistant/ask"), HttpMethod.POST);
        request.setRequestParameters(new ArrayList<>(List.of(new NameValuePair("question", "how are things?"))));
        wc.addCrumb(request);
        return wc.getPage(request);
    }

    @Test
    void providersReportTokensInTheirOwnFormat() throws Exception {
        Llm.Reply anthropic = HttpLlmClient.anthropicReply(
                Json.parseObject(
                        "{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
                                + "\"usage\":{\"input_tokens\":100,\"cache_read_input_tokens\":50,\"cache_creation_input_tokens\":10,\"output_tokens\":20}}"));
        assertEquals(160, anthropic.inputTokens(), "cached input is counted, to keep the estimate safe");
        assertEquals(20, anthropic.outputTokens());
        Llm.Reply openAi = HttpLlmClient.openAiReply(
                Json.parseObject(
                        "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":70,\"completion_tokens\":9}}"));
        assertEquals(70, openAi.inputTokens());
        assertEquals(9, openAi.outputTokens());
        Llm.Reply silent =
                HttpLlmClient.openAiReply(Json.parseObject("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
        assertEquals(0, silent.inputTokens());
    }

    @Test
    void eachQuestionIsCountedWithItsTokensAndCost() throws Exception {
        Page answer = ask(r.createWebClient().login("alice"));
        assertFalse(
                Json.parseObject(answer.getWebResponse().getContentAsString()).containsKey("usage"),
                "cost is for the usage page, not shown with every answer");
        UsageLedger.Entry alice =
                UsageLedger.get().month(UsageLedger.currentMonth()).get("alice");
        assertEquals(3000, alice.getInputTokens(), "tokens are summed across tool rounds");
        assertEquals(300, alice.getOutputTokens());
        // claude-sonnet-5-5 at $2 / $10 per 1M: 3000 * 2 / 1M + 300 * 10 / 1M = $0.009
        assertEquals(0, new BigDecimal("0.009").compareTo(alice.getCost()));
        assertEquals(0, alice.getOwnKeyQuestions());
    }

    @Test
    void theLedgerKeepsCountsAcrossRestarts() throws Exception {
        ask(r.createWebClient().login("alice"));
        ask(r.createWebClient().login("alice"));
        UsageLedger.reload();
        UsageLedger.Entry alice =
                UsageLedger.get().month(UsageLedger.currentMonth()).get("alice");
        assertEquals(2, alice.getQuestions());
        assertEquals(6000, alice.getInputTokens());
        assertEquals(0, new BigDecimal("0.018").compareTo(alice.getCost()));
    }

    @Test
    void theBudgetStopsQuestionsOnceReached() throws Exception {
        AssistantConfiguration.get().setMonthlyBudget("0.01");
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(200, ask(alice).getWebResponse().getStatusCode());
        assertEquals(200, ask(alice).getWebResponse().getStatusCode(), "$0.009 spent is still under $0.01");
        Page refused = ask(alice);
        assertEquals(429, refused.getWebResponse().getStatusCode());
        assertTrue(refused.getWebResponse().getContentAsString().contains("budget of $0.01"));
    }

    @Test
    void unknownPricesAreCountedButNotCosted() throws Exception {
        AssistantConfiguration config = AssistantConfiguration.get();
        config.setProvider(Provider.OPENAI);
        config.setModel("some-model-without-a-price");
        config.setMonthlyBudget("0.001");
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");
        ask(alice);
        ask(alice);
        UsageLedger.Entry entry =
                UsageLedger.get().month(UsageLedger.currentMonth()).get("alice");
        assertEquals(2, entry.getUnpricedQuestions());
        assertEquals(0, BigDecimal.ZERO.compareTo(entry.getCost()));
    }

    @Test
    void ownKeysNeverCountTowardsTheBudget() throws Exception {
        AssistantConfiguration config = AssistantConfiguration.get();
        config.setAllowUserKeys(true);
        config.setMonthlyBudget("0.001");
        User.getById("alice", true).addProperty(new UserKeyProperty(Secret.fromString("sk-alice-own-key-1")));
        JenkinsRule.WebClient alice = r.createWebClient().login("alice");
        for (int i = 0; i < 3; i++) {
            assertEquals(
                    200, ask(alice).getWebResponse().getStatusCode(), "the instance budget does not apply to her key");
        }
        UsageLedger.Entry entry =
                UsageLedger.get().month(UsageLedger.currentMonth()).get("alice");
        assertEquals(3, entry.getOwnKeyQuestions());
        assertEquals(0, BigDecimal.ZERO.compareTo(UsageLedger.get().instanceCostThisMonth()));
    }

    @Test
    void usagePageIsForAdministrators() throws Exception {
        ask(r.createWebClient().login("alice"));
        AssistantConfiguration.get().setMonthlyBudget("20");
        JenkinsRule.WebClient alice = r.createWebClient().login("alice").withThrowExceptionOnFailingStatusCode(false);
        assertEquals(403, alice.goTo("concierge/usage/").getWebResponse().getStatusCode());
        String page = r.createWebClient()
                .login("admin")
                .goTo("concierge/usage/")
                .getWebResponse()
                .getContentAsString();
        assertTrue(page.contains("alice"));
        assertTrue(page.contains("$0.009"), "costs under a cent keep their digits");
        assertTrue(page.contains("of the $20.00 monthly budget"));
        assertFalse(page.contains("how are things"), "questions are never stored or shown");
    }

    @Test
    void costsAreFormattedForPeople() {
        assertEquals("$12.35", ModelPrice.dollars(new BigDecimal("12.345678")));
        assertEquals("$0.0042", ModelPrice.dollars(new BigDecimal("0.004213")));
        assertEquals("$0.00", ModelPrice.dollars(BigDecimal.ZERO));
    }

    @Test
    void budgetMustBeAPositiveAmount() {
        AssistantConfiguration config = AssistantConfiguration.get();
        assertEquals(hudson.util.FormValidation.Kind.OK, config.doCheckMonthlyBudget("$12.50").kind);
        assertEquals(hudson.util.FormValidation.Kind.OK, config.doCheckMonthlyBudget("").kind);
        assertEquals(hudson.util.FormValidation.Kind.ERROR, config.doCheckMonthlyBudget("lots").kind);
        assertEquals(hudson.util.FormValidation.Kind.ERROR, config.doCheckMonthlyBudget("-5").kind);
        config.setMonthlyBudget("nonsense");
        assertNull(config.getBudget(), "an unparseable budget is no budget, not zero");
    }
}
