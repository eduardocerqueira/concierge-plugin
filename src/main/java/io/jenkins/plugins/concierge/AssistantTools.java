package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.PluginWrapper;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Computer;
import hudson.model.HealthReport;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.User;
import hudson.scm.ChangeLogSet;
import hudson.triggers.Trigger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import org.springframework.security.access.AccessDeniedException;

/**
 * The read-only tools the assistant offers the model.
 *
 * <p>Every tool runs on the request thread, as the user who asked, and checks Jenkins permissions
 * itself (docs/security-design.md, rule 1). Results are built from an allowlist of fields; nothing
 * is serialised wholesale (rule 4). Logs, configuration and SCM details are only returned when an
 * administrator has allowed that kind of data. All text is redacted before it leaves.
 *
 * <p>Some tools are never offered, whatever the model asks for: Script Console, credentials,
 * replay, workspaces, plugin installation and configuration changes (rule 5).
 */
final class AssistantTools {

    static final int LIST_LIMIT = 50;
    static final int RESULT_CHAR_LIMIT = 12_000;
    static final int SEARCH_MATCH_LIMIT = 40;

    private final AssistantConfiguration config;
    private final Redactor redactor;

    @CheckForNull
    private final BuildProposals proposals;

    AssistantTools(@NonNull AssistantConfiguration config, @NonNull Redactor redactor) {
        this(config, redactor, null);
    }

    /**
     * @param proposals where proposed builds go, or null to offer no actions. Even with a store,
     *     the proposal tools are only offered when an administrator has allowed actions.
     */
    AssistantTools(
            @NonNull AssistantConfiguration config,
            @NonNull Redactor redactor,
            @CheckForNull BuildProposals proposals) {
        this.config = config;
        this.redactor = redactor;
        this.proposals = proposals;
    }

    boolean actionsOffered() {
        return proposals != null && config.isAllowActions();
    }

    /** The tool definitions sent to the model. */
    @NonNull
    List<Llm.ToolSpec> specs() {
        List<Llm.ToolSpec> specs = new ArrayList<>();
        specs.add(spec(
                "whoami",
                "Who is asking, and what they are allowed to do on this Jenkins. Call this before suggesting actions.",
                Map.of()));
        specs.add(spec(
                "overview",
                "A summary of the instance as the user sees it: job counts by status, what is building, queue length, agents.",
                Map.of()));
        specs.add(spec(
                "list_jobs",
                "Jobs the user can see, newest build first. Optionally filter by part of the name and by status.",
                Map.of(
                        "query", prop("string", "Part of the job's full name, case-insensitive."),
                        "status",
                                enumProp(
                                        "Only jobs with this status.",
                                        List.of("failure", "unstable", "success", "aborted", "notbuilt", "disabled")),
                        "limit", prop("integer", "At most this many jobs, up to " + LIST_LIMIT + "."))));
        specs.add(spec(
                "get_job",
                "Details of one job: description, status, health, parameters and recent builds.",
                Map.of("job", prop("string", "The job's full name, for example team/api-build.")),
                "job"));
        specs.add(spec(
                "get_build",
                "Details of one build: result, duration, what started it, test results"
                        + (config.isShareScm() ? " and the changes it included." : "."),
                Map.of(
                        "job", prop("string", "The job's full name."),
                        "number", prop("integer", "The build number. Omit for the last build.")),
                "job"));
        if (config.isShareLogs()) {
            specs.add(spec(
                    "get_build_log",
                    "The last lines of a build's console log, with secrets removed.",
                    Map.of(
                            "job", prop("string", "The job's full name."),
                            "number", prop("integer", "The build number. Omit for the last build."),
                            "lines",
                                    prop(
                                            "integer",
                                            "How many lines from the end, up to " + config.getMaxLogLines() + ".")),
                    "job"));
            specs.add(spec(
                    "search_build_log",
                    "Lines of a build's console log containing some text (not a regular expression), with line numbers.",
                    Map.of(
                            "job", prop("string", "The job's full name."),
                            "number", prop("integer", "The build number. Omit for the last build."),
                            "text", prop("string", "Text to look for, case-insensitive.")),
                    "job",
                    "text"));
        }
        if (config.isShareConfiguration()) {
            specs.add(spec(
                    "get_job_configuration",
                    "How a job is set up: type, parameters with defaults, triggers"
                            + (config.isShareScm() ? ", source repository" : "")
                            + ". Needs permission to see the job's configuration.",
                    Map.of("job", prop("string", "The job's full name.")),
                    "job"));
        }
        if (actionsOffered()) {
            specs.add(spec(
                    "propose_build",
                    "Propose starting a build. Nothing runs: the person sees a confirmation card and decides."
                            + " Only for jobs the administrator allows and the person may build. Omitted"
                            + " parameters use the job's defaults; password parameters cannot be set.",
                    Map.of(
                            "job", prop("string", "The job's full name."),
                            "parameters",
                                    Map.of(
                                            "type", "object",
                                            "description", "Parameter values by name, as strings.",
                                            "additionalProperties", Map.of("type", "string"))),
                    "job"));
            specs.add(spec(
                    "propose_rebuild",
                    "Propose running a build again with the same parameters (sensitive values revert to"
                            + " defaults). Nothing runs until the person confirms.",
                    Map.of(
                            "job", prop("string", "The job's full name."),
                            "number", prop("integer", "The build to repeat. Omit for the last build.")),
                    "job"));
        }
        specs.add(spec("get_queue", "Builds waiting to start, and why they are waiting.", Map.of()));
        specs.add(spec("list_agents", "Agents, whether they are online, their executors and labels.", Map.of()));
        specs.add(spec(
                "list_plugins",
                "Installed plugins with versions and whether an update is available. Needs Overall/SystemRead.",
                Map.of()));
        return specs;
    }

    /** Runs one tool call and returns its result as text for the model. Never throws. */
    @NonNull
    Llm.ToolResult run(@NonNull Llm.ToolCall call) {
        Object result;
        boolean error = false;
        try {
            Map<String, Object> args = call.arguments();
            if (args.containsKey("_invalid_arguments")) {
                throw new ToolException("The arguments were not valid JSON.");
            }
            result = switch (call.name()) {
                case "whoami" -> whoami();
                case "overview" -> overview();
                case "list_jobs" -> listJobs(args);
                case "get_job" -> getJob(args);
                case "get_build" -> getBuild(args);
                case "get_build_log" -> requireShared(config.isShareLogs(), "logs", () -> getBuildLog(args));
                case "search_build_log" -> requireShared(config.isShareLogs(), "logs", () -> searchBuildLog(args));
                case "get_job_configuration" ->
                    requireShared(config.isShareConfiguration(), "job configuration", () -> getJobConfiguration(args));
                case "get_queue" -> queue();
                case "list_agents" -> agents();
                case "list_plugins" -> plugins();
                case "propose_build" -> propose(args, false);
                case "propose_rebuild" -> propose(args, true);
                default -> throw new ToolException("There is no tool called " + call.name() + ".");
            };
        } catch (ToolException e) {
            result = Map.of("error", e.getMessage());
            error = true;
        } catch (AccessDeniedException e) {
            result = Map.of("error", "The user is not allowed to see that.");
            error = true;
        } catch (IOException | RuntimeException e) {
            result = Map.of("error", "The tool failed: " + e.getClass().getSimpleName());
            error = true;
        }
        String text = redactor.redact(Json.write(result));
        if (text.length() > RESULT_CHAR_LIMIT) {
            text = text.substring(0, RESULT_CHAR_LIMIT) + "\n[truncated]";
        }
        return new Llm.ToolResult(call.id(), call.name(), text, error);
    }

    // ------------------------------------------------------------------ tools

    private Map<String, Object> whoami() {
        Jenkins jenkins = Jenkins.get();
        User user = User.current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user == null ? "anonymous" : user.getId());
        out.put("displayName", user == null ? "anonymous" : user.getDisplayName());
        out.put("administrator", jenkins.hasPermission(Jenkins.ADMINISTER));
        out.put("canSeePlugins", jenkins.hasPermission(Jenkins.SYSTEM_READ));
        out.put("logsShared", config.isShareLogs());
        out.put("configurationShared", config.isShareConfiguration());
        out.put("scmShared", config.isShareScm());
        return out;
    }

    private Map<String, Object> overview() {
        InstanceOverview overview = InstanceOverview.forCurrentUser();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", overview.getJobCount());
        out.put("byStatus", overview.getStatusCounts());
        out.put("building", names(overview.getBuilding()));
        out.put("failing", names(overview.getFailing()));
        out.put("queued", overview.getQueueLength());
        out.put("agentsOnline", overview.getAgentsOnline() + " of " + overview.getAgentCount());
        out.put("executorsBusy", overview.getBusyExecutors() + " of " + overview.getExecutors());
        out.put(
                "folders",
                overview.getFolders().stream()
                        .map(f -> Map.of("name", f.getName(), "jobs", f.getJobCount()))
                        .toList());
        return out;
    }

    private Map<String, Object> listJobs(Map<String, Object> args) {
        String query = string(args, "query").toLowerCase(Locale.ROOT);
        String status = string(args, "status");
        int limit = clamp(integer(args, "limit", 20), 1, LIST_LIMIT);
        List<JobRef> refs = new ArrayList<>();
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (!job.hasPermission(Item.READ)) {
                continue;
            }
            if (!query.isEmpty() && !job.getFullName().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            JobRef ref = new JobRef(job);
            if (!status.isEmpty() && !status.equals(ref.getStatus())) {
                continue;
            }
            refs.add(ref);
        }
        refs.sort((a, b) -> Long.compare(b.getLastBuildTime(), a.getLastBuildTime()));
        List<Object> jobs = new ArrayList<>();
        for (JobRef ref : refs.subList(0, Math.min(limit, refs.size()))) {
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("job", ref.getFullName());
            job.put("status", ref.getStatus());
            job.put("building", ref.isBuilding());
            job.put("url", ref.getUrl());
            jobs.add(job);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", refs.size());
        out.put("jobs", jobs);
        return out;
    }

    private Map<String, Object> getJob(Map<String, Object> args) throws ToolException {
        Job<?, ?> job = job(args);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job.getFullName());
        out.put("displayName", job.getFullDisplayName());
        out.put("url", JobRef.url(job));
        out.put("type", job.getClass().getSimpleName());
        out.put("description", plain(job.getDescription()));
        out.put("status", JobRef.status(job.getIconColor()));
        out.put("building", job.isBuilding());
        out.put("buildable", job.isBuildable());
        out.put("canBuild", job.hasPermission(Item.BUILD));
        HealthReport health = job.getBuildHealth();
        if (health != null) {
            out.put("health", health.getScore() + "%: " + health.getDescription());
        }
        out.put("lastSuccessfulBuild", number(job.getLastSuccessfulBuild()));
        out.put("lastFailedBuild", number(job.getLastFailedBuild()));
        List<Object> parameters = new ArrayList<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property != null) {
            for (ParameterDefinition definition : property.getParameterDefinitions()) {
                Map<String, Object> parameter = new LinkedHashMap<>();
                parameter.put("name", definition.getName());
                parameter.put("type", definition.getClass().getSimpleName());
                parameter.put("description", plain(definition.getDescription()));
                parameters.add(parameter);
            }
        }
        out.put("parameters", parameters);
        List<Object> builds = new ArrayList<>();
        int count = 0;
        for (Run<?, ?> run : job.getBuilds()) {
            if (count++ >= 10) {
                break;
            }
            builds.add(buildSummary(run));
        }
        out.put("recentBuilds", builds);
        return out;
    }

    private Map<String, Object> getBuild(Map<String, Object> args) throws ToolException {
        Run<?, ?> run = run(args);
        Map<String, Object> out = buildSummary(run);
        out.put("job", run.getParent().getFullName());
        out.put("url", JobRef.url(run.getParent()) + run.getNumber() + "/");
        List<String> causes = new ArrayList<>();
        CauseAction causeAction = run.getAction(CauseAction.class);
        if (causeAction != null) {
            for (Cause cause : causeAction.getCauses()) {
                causes.add(cause.getShortDescription());
            }
        }
        out.put("causes", causes);
        Map<String, Object> tests = testSummary(run);
        if (tests != null) {
            out.put("tests", tests);
        }
        if (config.isShareScm()) {
            List<Object> changes = new ArrayList<>();
            for (ChangeLogSet<? extends ChangeLogSet.Entry> set : changeSets(run)) {
                for (ChangeLogSet.Entry entry : set) {
                    if (changes.size() >= 20) {
                        break;
                    }
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("commit", entry.getCommitId());
                    change.put("author", entry.getAuthor().getDisplayName());
                    change.put("message", entry.getMsg());
                    changes.add(change);
                }
            }
            out.put("changes", changes);
        }
        return out;
    }

    private Map<String, Object> getBuildLog(Map<String, Object> args) throws ToolException, IOException {
        Run<?, ?> run = run(args);
        int lines = clamp(integer(args, "lines", 100), 1, config.getMaxLogLines());
        List<String> log = run.getLog(lines);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", run.getParent().getFullName());
        out.put("number", run.getNumber());
        out.put("building", run.isBuilding());
        out.put("lines", String.join("\n", log));
        return out;
    }

    private Map<String, Object> searchBuildLog(Map<String, Object> args) throws ToolException, IOException {
        Run<?, ?> run = run(args);
        String text = string(args, "text");
        if (text.isEmpty() || text.length() > 200) {
            throw new ToolException("Give between 1 and 200 characters to search for.");
        }
        String needle = text.toLowerCase(Locale.ROOT);
        List<Object> matches = new ArrayList<>();
        int lineNumber = 0;
        int total = 0;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(run.getLogInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String plainLine = hudson.console.ConsoleNote.removeNotes(line);
                if (plainLine.toLowerCase(Locale.ROOT).contains(needle)) {
                    total++;
                    if (matches.size() < SEARCH_MATCH_LIMIT) {
                        matches.add(Map.of(
                                "line",
                                lineNumber,
                                "text",
                                plainLine.length() > 400 ? plainLine.substring(0, 400) : plainLine));
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", run.getParent().getFullName());
        out.put("number", run.getNumber());
        out.put("matches", total);
        out.put("shown", matches);
        return out;
    }

    private Map<String, Object> getJobConfiguration(Map<String, Object> args) throws ToolException {
        Job<?, ?> job = job(args);
        if (!job.hasPermission(Item.EXTENDED_READ)) {
            throw new ToolException("The user is not allowed to see this job's configuration.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("job", job.getFullName());
        out.put("type", job.getClass().getSimpleName());
        out.put("disabled", !job.isBuildable());
        List<Object> parameters = new ArrayList<>();
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (property != null) {
            for (ParameterDefinition definition : property.getParameterDefinitions()) {
                Map<String, Object> parameter = new LinkedHashMap<>();
                parameter.put("name", definition.getName());
                parameter.put("type", definition.getClass().getSimpleName());
                // Password defaults are secrets and never leave; other defaults are configuration.
                hudson.model.ParameterValue defaultValue = definition.getDefaultParameterValue();
                if (definition instanceof SimpleParameterDefinition
                        && !(definition instanceof PasswordParameterDefinition)
                        && defaultValue != null) {
                    Object value = defaultValue.getValue();
                    parameter.put("default", value == null ? null : String.valueOf(value));
                }
                parameters.add(parameter);
            }
        }
        out.put("parameters", parameters);
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> parameterized) {
            List<Object> triggers = new ArrayList<>();
            for (Trigger<?> trigger : parameterized.getTriggers().values()) {
                triggers.add(Map.of(
                        "type", trigger.getDescriptor().getDisplayName(), "spec", String.valueOf(trigger.getSpec())));
            }
            out.put("triggers", triggers);
        }
        if (config.isShareScm() && job instanceof SCMTriggerItem scmItem) {
            List<Object> scms = new ArrayList<>();
            scmItem.getSCMs().forEach(scm -> scms.add(Map.of("type", scm.getType(), "key", scm.getKey())));
            out.put("scm", scms);
        }
        out.put(
                "note",
                "Inline Pipeline scripts and secrets are never shared. Parameters, triggers"
                        + (config.isShareScm() ? " and repositories" : "")
                        + " are.");
        return out;
    }

    private Map<String, Object> queue() {
        List<Object> items = new ArrayList<>();
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof Item task && task.hasPermission(Item.READ)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("job", task.getFullName());
                entry.put("why", item.getWhy());
                entry.put("waitingSinceMillis", item.getInQueueSince());
                items.add(entry);
            }
        }
        return Map.of("items", items);
    }

    private Map<String, Object> agents() {
        List<Object> agents = new ArrayList<>();
        for (Computer computer : Jenkins.get().getComputers()) {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("name", computer.getDisplayName());
            agent.put("online", computer.isOnline());
            agent.put("executors", computer.countExecutors());
            agent.put("busy", computer.countBusy());
            hudson.model.Node node = computer.getNode();
            if (node != null) {
                List<String> labels = new ArrayList<>();
                for (Label label : node.getAssignedLabels()) {
                    labels.add(label.getName());
                }
                agent.put("labels", labels);
            }
            agents.add(agent);
        }
        return Map.of("agents", agents);
    }

    private Map<String, Object> plugins() throws ToolException {
        Jenkins jenkins = Jenkins.get();
        if (!jenkins.hasPermission(Jenkins.SYSTEM_READ)) {
            throw new ToolException("The user is not allowed to see installed plugins.");
        }
        List<Object> plugins = new ArrayList<>();
        for (PluginWrapper plugin : jenkins.getPluginManager().getPlugins()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", plugin.getShortName());
            entry.put("version", plugin.getVersion());
            entry.put("active", plugin.isActive());
            entry.put("updateAvailable", plugin.hasUpdate());
            plugins.add(entry);
        }
        return Map.of("plugins", plugins);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propose(Map<String, Object> args, boolean rebuild) throws ToolException {
        BuildProposals store = proposals;
        if (store == null || !config.isAllowActions()) {
            throw new ToolException("An administrator has not allowed the assistant to propose builds.");
        }
        String job = string(args, "job");
        if (job.isEmpty()) {
            throw new ToolException("Say which job, by its full name.");
        }
        BuildProposals.Proposal proposal;
        try {
            if (rebuild) {
                proposal = store.proposeRebuild(config, job, integer(args, "number", -1));
            } else {
                Object parameters = args.get("parameters");
                proposal = store.proposeBuild(
                        config, job, parameters instanceof Map ? (Map<String, Object>) parameters : Map.of());
            }
        } catch (BuildProposals.ProposalException e) {
            throw new ToolException(e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposed", true);
        out.put("job", proposal.job());
        out.put("parameters", proposal.parameters());
        out.put(
                "note",
                "Nothing has run. The person now sees a confirmation card and decides. Tell them to check"
                        + " the card; do not say the build started.");
        return out;
    }

    // ------------------------------------------------------------------ helpers

    @NonNull
    private static Job<?, ?> job(Map<String, Object> args) throws ToolException {
        String name = string(args, "job");
        if (name.isEmpty()) {
            throw new ToolException("Say which job, by its full name.");
        }
        Job<?, ?> job;
        try {
            job = Jenkins.get().getItemByFullName(name, Job.class);
        } catch (AccessDeniedException e) {
            job = null;
        }
        // The same answer whether the job is missing or hidden, so the model cannot probe for names.
        if (job == null || !job.hasPermission(Item.READ)) {
            throw new ToolException("There is no job called " + name + " that the user can see.");
        }
        return job;
    }

    @NonNull
    private static Run<?, ?> run(Map<String, Object> args) throws ToolException {
        Job<?, ?> job = job(args);
        int number = integer(args, "number", -1);
        Run<?, ?> run = number > 0 ? job.getBuildByNumber(number) : job.getLastBuild();
        if (run == null) {
            throw new ToolException(
                    number > 0
                            ? "Build " + number + " of " + job.getFullName() + " does not exist."
                            : "That job has no builds.");
        }
        return run;
    }

    private static Map<String, Object> buildSummary(Run<?, ?> run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("number", run.getNumber());
        out.put("result", run.isBuilding() ? "BUILDING" : String.valueOf(run.getResult()));
        out.put("startedMillis", run.getStartTimeInMillis());
        out.put("duration", run.isBuilding() ? null : run.getDurationString());
        return out;
    }

    /** Test counts from the JUnit plugin's result action, if that plugin is installed and the build has one. */
    @CheckForNull
    static Map<String, Object> testSummary(@NonNull Run<?, ?> run) {
        for (hudson.model.Action action : run.getAllActions()) {
            for (Class<?> c = action.getClass(); c != null; c = c.getSuperclass()) {
                if (c.getName().equals("hudson.tasks.test.AbstractTestResultAction")) {
                    try {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("total", c.getMethod("getTotalCount").invoke(action));
                        out.put("failed", c.getMethod("getFailCount").invoke(action));
                        out.put("skipped", c.getMethod("getSkipCount").invoke(action));
                        return out;
                    } catch (ReflectiveOperationException e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<ChangeLogSet<? extends ChangeLogSet.Entry>> changeSets(Run<?, ?> run) {
        // AbstractBuild and WorkflowRun both expose getChangeSets() but share no interface for it.
        try {
            Object sets = run.getClass().getMethod("getChangeSets").invoke(run);
            if (sets instanceof List<?> list) {
                return (List<ChangeLogSet<? extends ChangeLogSet.Entry>>) list;
            }
        } catch (ReflectiveOperationException e) {
            // No change sets for this kind of build.
        }
        return List.of();
    }

    private static List<String> names(List<JobRef> refs) {
        return refs.stream().map(JobRef::getFullName).toList();
    }

    @CheckForNull
    private static Integer number(@CheckForNull Run<?, ?> run) {
        return run == null ? null : run.getNumber();
    }

    /** Job descriptions may contain HTML; the model gets text. */
    @NonNull
    static String plain(@CheckForNull String html) {
        if (html == null) {
            return "";
        }
        String text = html.replaceAll("(?s)<[^>]{0,2000}>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    @NonNull
    private static String string(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof String s ? s.trim() : "";
    }

    private static int integer(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Llm.ToolSpec spec(
            String name, String description, Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        return new Llm.ToolSpec(name, description, schema);
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static Map<String, Object> enumProp(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private interface ToolBody {
        Object get() throws ToolException, IOException;
    }

    private static Object requireShared(boolean allowed, String what, ToolBody body) throws ToolException, IOException {
        if (!allowed) {
            throw new ToolException("An administrator has not allowed sharing " + what + " with the assistant.");
        }
        return body.get();
    }

    /** A tool could not do what was asked; the message goes back to the model. */
    static final class ToolException extends Exception {
        private static final long serialVersionUID = 1L;

        ToolException(String message) {
            super(message);
        }
    }
}
