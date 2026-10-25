package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.User;
import hudson.util.FormValidation;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.GET;
import org.kohsuke.stapler.verb.POST;

/**
 * The assistant at {@code /concierge/assistant}: a chat page, and the endpoints the chat panel on
 * every page talks to.
 *
 * <p>{@link #getTarget()} requires {@link ConciergePermissions#ASSISTANT} and an enabled
 * configuration before anything here is reached. Asking and resetting are POST-only, so a link or
 * an image on another site cannot spend the instance's AI budget (docs/security-design.md, rule 2).
 */
public class AssistantEndpoint implements StaplerProxy {

    private static final Logger LOGGER = Logger.getLogger(AssistantEndpoint.class.getName());

    /** Session attribute holding the conversation so far. */
    static final String HISTORY = AssistantEndpoint.class.getName() + ".history";

    /** Session attribute holding builds proposed and not yet confirmed. */
    static final String PROPOSALS = AssistantEndpoint.class.getName() + ".proposals";

    /** How many past turns are sent back to the model with each question. */
    static final int HISTORY_TURNS = 12;

    static final RateLimiter RATE_LIMITER = new RateLimiter(System::currentTimeMillis);

    /** Replaced in tests so no request leaves the machine. */
    static ClientFactory clientFactory = HttpLlmClient::new;

    /** Builds the client for a provider; a seam for tests. */
    interface ClientFactory {
        Llm.Client create(Provider provider, String baseUrl, String model, String apiKey);
    }

    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(ConciergePermissions.ASSISTANT);
        if (!AssistantConfiguration.get().isEnabled()) {
            throw HttpResponses.notFound();
        }
        return this;
    }

    /** True when the current user may use the assistant and it is switched on. */
    static boolean isAvailable() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins != null
                && AssistantConfiguration.get().isEnabled()
                && jenkins.hasPermission(ConciergePermissions.ASSISTANT);
    }

    @NonNull
    public AssistantConfiguration getConfig() {
        return AssistantConfiguration.get();
    }

    /** The conversation so far, for the chat page. */
    @NonNull
    public List<Map<String, Object>> getConversation(StaplerRequest2 req) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Llm.Turn turn : history(req.getSession(false))) {
            if (turn instanceof Llm.UserTurn user) {
                out.add(Map.of("role", "user", "text", user.text(), "at", user.at()));
            } else if (turn instanceof Llm.AssistantTurn assistant) {
                out.add(Map.of("role", "assistant", "text", assistant.text(), "at", assistant.at()));
            }
        }
        return out;
    }

    /** The conversation so far, for the chat panel when it opens. Read-only, so GET is fine. */
    @GET
    public HttpResponse doHistory(StaplerRequest2 req) {
        return json(
                200,
                Map.of(
                        "status", "ok",
                        "provider", AssistantConfiguration.get().getProvider().getDisplayName(),
                        "user", currentUserName(),
                        "turns", getConversation(req)));
    }

    @POST
    public HttpResponse doAsk(StaplerRequest2 req, @QueryParameter String question, @QueryParameter String page)
            throws IOException {
        Jenkins.get().checkPermission(ConciergePermissions.ASSISTANT);
        AssistantConfiguration config = AssistantConfiguration.get();
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) {
            return error(400, Messages.Assistant_emptyQuestion());
        }
        if (text.length() > Assistant.MAX_QUESTION_LENGTH) {
            return error(400, Messages.Assistant_questionTooLong(Assistant.MAX_QUESTION_LENGTH));
        }
        FormValidation problem = config.validate();
        if (problem.kind == FormValidation.Kind.ERROR) {
            return error(503, Messages.Assistant_notConfigured());
        }
        User user = User.current();
        String userId = user == null ? "anonymous" : user.getId();
        if (!RATE_LIMITER.tryAcquire(userId, config.getRequestsPerHour())) {
            Assistant.AUDIT.info(() -> "rate-limited user=" + userId);
            return error(429, Messages.Assistant_rateLimited(config.getRequestsPerHour()));
        }

        String userKey = UserKeyProperty.forCurrentUser();
        java.math.BigDecimal budget = config.getBudget();
        if (userKey == null
                && budget != null
                && UsageLedger.get().instanceCostThisMonth().compareTo(budget) >= 0) {
            Assistant.AUDIT.info(() -> "budget-reached user=" + userId);
            return error(429, Messages.Usage_budgetReached(ModelPrice.money(budget)));
        }
        String key = userKey != null ? userKey : config.lookupApiKey();
        if (config.getProvider().isKeyRequired() && key == null) {
            return error(503, Messages.Assistant_notConfigured());
        }
        List<String> literals = new ArrayList<>();
        if (key != null) {
            literals.add(key);
        }
        HttpSession session = req.getSession(true);
        long askedAt = System.currentTimeMillis();
        List<Llm.Turn> history = history(session);
        BuildProposals proposals = config.isAllowActions() ? proposals(session) : null;
        Redactor redactor = new Redactor(literals);
        Llm.Client client =
                clientFactory.create(config.getProvider(), config.getEffectiveBaseUrl(), config.getModel(), key);
        Assistant assistant = new Assistant(
                config,
                client,
                new AssistantTools(config, redactor, proposals),
                config.getProvider().name() + "/" + config.getModel() + (userKey != null ? " (user key)" : ""));

        try {
            Assistant.Answer answer = assistant.ask(redactor.redact(text), cleanPage(page), history);
            List<Llm.Turn> updated = new ArrayList<>(history);
            updated.add(new Llm.UserTurn(redactor.redact(text), askedAt));
            updated.add(new Llm.AssistantTurn(answer.text(), List.of(), System.currentTimeMillis()));
            while (updated.size() > HISTORY_TURNS) {
                updated.remove(0);
            }
            session.setAttribute(HISTORY, new ArrayList<>(updated));

            // Our own JSON writer, not json-lib, which would turn an answer like "[1, 2]" into an array.
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("answer", answer.text());
            result.put("askedAt", askedAt);
            result.put("at", System.currentTimeMillis());
            result.put("tools", answer.toolsUsed());
            // Counted for the usage page and the budget; not shown with the answer.
            recordUsage(config, userId, answer.inputTokens(), answer.outputTokens(), userKey != null, true);
            List<Object> proposed = new ArrayList<>();
            if (proposals != null) {
                proposals.takeMadeNow().forEach(p -> proposed.add(p.toJson()));
                session.setAttribute(PROPOSALS, proposals);
            }
            result.put("proposals", proposed);
            return json(200, result);
        } catch (Llm.ProviderException e) {
            recordUsage(config, userId, assistant.inputTokens(), assistant.outputTokens(), userKey != null, false);
            LOGGER.log(Level.WARNING, "AI provider error {0}: {1}", new Object[] {e.getStatus(), e.getMessage()});
            Assistant.AUDIT.info(() -> "provider-error user=" + userId + " status=" + e.getStatus());
            return error(502, Messages.Assistant_providerError(e.getStatus()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(503, Messages.Assistant_providerError(0));
        } catch (IOException e) {
            recordUsage(config, userId, assistant.inputTokens(), assistant.outputTokens(), userKey != null, false);
            LOGGER.log(
                    Level.WARNING,
                    "Could not reach the AI provider: {0}",
                    e.getClass().getSimpleName());
            return error(502, Messages.Assistant_providerError(0));
        } finally {
            client.close();
        }
    }

    /**
     * Counts what a question spent, including the rounds before a provider error, so a question that
     * fails part-way still counts against the budget.
     */
    private static void recordUsage(
            AssistantConfiguration config,
            String userId,
            long inputTokens,
            long outputTokens,
            boolean ownKey,
            boolean answered) {
        ModelPrice price = config.getPrice();
        java.math.BigDecimal cost = price == null ? null : price.cost(inputTokens, outputTokens);
        if (answered || inputTokens > 0 || outputTokens > 0) {
            UsageLedger.get().record(userId, inputTokens, outputTokens, cost, ownKey);
        }
    }

    /**
     * Starts a build the assistant proposed, once the person clicks "Run it". Everything is checked
     * again here; see {@link BuildProposals#confirm}.
     */
    @POST
    public HttpResponse doConfirm(StaplerRequest2 req, @QueryParameter String id) {
        Jenkins.get().checkPermission(ConciergePermissions.ASSISTANT);
        HttpSession session = req.getSession(false);
        BuildProposals proposals = session == null ? null : (BuildProposals) session.getAttribute(PROPOSALS);
        String userId = BuildProposals.currentUserId();
        if (proposals == null) {
            return error(404, Messages.Action_unknownProposal());
        }
        try {
            hudson.model.Queue.Item item = proposals.confirm(AssistantConfiguration.get(), id);
            session.setAttribute(PROPOSALS, proposals);
            String job =
                    item.task instanceof hudson.model.Job<?, ?> j ? j.getFullName() : item.task.getFullDisplayName();
            Assistant.AUDIT.info(() -> "build-confirmed user=" + userId + " job=" + job);
            String url = item.task instanceof hudson.model.Job<?, ?> j ? JobRef.url(j) : "";
            return json(200, Map.of("status", "ok", "job", job, "url", url));
        } catch (BuildProposals.ProposalException e) {
            Assistant.AUDIT.info(() -> "build-refused user=" + userId + " reason=" + e.getMessage());
            return error(409, e.getMessage());
        }
    }

    /** Drops a proposed build. */
    @POST
    public HttpResponse doCancel(StaplerRequest2 req, @QueryParameter String id) {
        Jenkins.get().checkPermission(ConciergePermissions.ASSISTANT);
        HttpSession session = req.getSession(false);
        BuildProposals proposals = session == null ? null : (BuildProposals) session.getAttribute(PROPOSALS);
        if (proposals != null && proposals.cancel(id)) {
            session.setAttribute(PROPOSALS, proposals);
            Assistant.AUDIT.info(() -> "build-cancelled user=" + BuildProposals.currentUserId());
        }
        return json(200, Map.of("status", "ok"));
    }

    @NonNull
    static BuildProposals proposals(@NonNull HttpSession session) {
        Object stored = session.getAttribute(PROPOSALS);
        return stored instanceof BuildProposals p ? p : new BuildProposals();
    }

    /** The signed-in user's name for the chat's avatar, or empty for anonymous. */
    @NonNull
    static String currentUserName() {
        User user = User.current();
        return user == null ? "" : user.getDisplayName();
    }

    /** Forgets the conversation. */
    @POST
    public HttpResponse doReset(StaplerRequest2 req) {
        Jenkins.get().checkPermission(ConciergePermissions.ASSISTANT);
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.removeAttribute(HISTORY);
        }
        return json(200, Map.of("status", "ok"));
    }

    @NonNull
    @SuppressWarnings("unchecked")
    static List<Llm.Turn> history(@CheckForNull HttpSession session) {
        if (session == null) {
            return List.of();
        }
        Object stored = session.getAttribute(HISTORY);
        return stored instanceof List ? List.copyOf((List<Llm.Turn>) stored) : List.of();
    }

    /** The page the user is on, as a path relative to the Jenkins root, or null. */
    @CheckForNull
    static String cleanPage(@CheckForNull String page) {
        if (page == null) {
            return null;
        }
        String trimmed = page.trim();
        if (trimmed.isEmpty() || trimmed.length() > 300 || !trimmed.matches("[A-Za-z0-9_./%~:@!$&'()*+,;=-]*")) {
            return null;
        }
        return trimmed.replaceFirst("^/+", "");
    }

    private static HttpResponse error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("message", message);
        return json(status, body);
    }

    static HttpResponse json(int status, Map<String, ?> body) {
        String text = Json.write(body);
        return new HttpResponse() {
            @Override
            public void generateResponse(
                    org.kohsuke.stapler.StaplerRequest2 req, org.kohsuke.stapler.StaplerResponse2 rsp, Object node)
                    throws IOException {
                rsp.setStatus(status);
                rsp.setContentType("application/json;charset=UTF-8");
                rsp.setHeader("Cache-Control", "no-store");
                rsp.getWriter().print(text);
            }
        };
    }
}
