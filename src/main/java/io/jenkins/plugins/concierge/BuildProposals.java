package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.SimpleParameterDefinition;
import hudson.model.User;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.springframework.security.access.AccessDeniedException;

/**
 * Builds the assistant has proposed, waiting for the person to confirm (docs/security-design.md,
 * rule 5).
 *
 * <p>The model can only create a proposal. A proposal is checked when it is made, so the model gets
 * a useful answer, and checked again, in full, when the person confirms it: the job may have
 * changed, the person may have lost permission, or an administrator may have turned actions off in
 * between. Proposals live in the person's HTTP session, belong to that user, expire after ten
 * minutes and can be used once.
 */
final class BuildProposals implements Serializable {

    private static final long serialVersionUID = 1L;

    static final long TTL_MILLIS = 10L * 60 * 1000;
    static final int MAX_PENDING = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * One proposed build. {@code requested} holds only the values asked for, which is what is
     * re-validated and used on confirm; {@code parameters} is what the card shows, defaults
     * included and sensitive values hidden.
     */
    record Proposal(
            String id,
            String userId,
            String job,
            String jobUrl,
            String kind,
            LinkedHashMap<String, String> requested,
            LinkedHashMap<String, String> parameters,
            Integer fromBuild,
            long createdAt)
            implements Serializable {

        /** What the browser gets: only what the card shows. */
        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("job", job);
            out.put("url", jobUrl);
            out.put("kind", kind);
            out.put("fromBuild", fromBuild);
            List<Object> params = new ArrayList<>();
            parameters.forEach((name, value) -> params.add(Map.of("name", name, "value", value)));
            out.put("parameters", params);
            return out;
        }
    }

    /** A proposal could not be made or confirmed; the message is for the person or the model. */
    static final class ProposalException extends Exception {
        private static final long serialVersionUID = 1L;

        ProposalException(String message) {
            super(message);
        }
    }

    private final LinkedHashMap<String, Proposal> pending = new LinkedHashMap<>();

    /** Proposals made while answering the current question, for the response. Not kept. */
    private transient List<Proposal> madeNow;

    synchronized List<Proposal> takeMadeNow() {
        List<Proposal> out = madeNow == null ? List.of() : List.copyOf(madeNow);
        madeNow = null;
        return out;
    }

    // ------------------------------------------------------------------ proposing

    @NonNull
    synchronized Proposal proposeBuild(
            @NonNull AssistantConfiguration config, @NonNull String jobName, @NonNull Map<String, Object> requested)
            throws ProposalException {
        Job<?, ?> job = checkJob(config, jobName);
        LinkedHashMap<String, String> parameters = parameters(job, requested);
        return add(new Proposal(
                newId(),
                currentUserId(),
                job.getFullName(),
                JobRef.url(job),
                "build",
                strings(requested),
                parameters,
                null,
                now()));
    }

    @NonNull
    synchronized Proposal proposeRebuild(@NonNull AssistantConfiguration config, @NonNull String jobName, int number)
            throws ProposalException {
        Job<?, ?> job = checkJob(config, jobName);
        Run<?, ?> run = number > 0 ? job.getBuildByNumber(number) : job.getLastBuild();
        if (run == null) {
            throw new ProposalException("That build does not exist.");
        }
        Map<String, Object> previous = new LinkedHashMap<>();
        ParametersAction action = run.getAction(ParametersAction.class);
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        if (action != null && property != null) {
            for (ParameterValue value : action.getParameters()) {
                ParameterDefinition definition = property.getParameterDefinition(value.getName());
                // Sensitive values are never copied, and only simple parameters the job still has
                // are; everything else falls back to the job's default.
                if (!value.isSensitive()
                        && value.getValue() != null
                        && definition instanceof SimpleParameterDefinition
                        && !isSecret(definition)) {
                    previous.put(value.getName(), String.valueOf(value.getValue()));
                }
            }
        }
        LinkedHashMap<String, String> parameters = parameters(job, previous);
        return add(new Proposal(
                newId(),
                currentUserId(),
                job.getFullName(),
                JobRef.url(job),
                "rebuild",
                strings(previous),
                parameters,
                run.getNumber(),
                now()));
    }

    private Proposal add(Proposal proposal) {
        pending.values().removeIf(p -> now() - p.createdAt() > TTL_MILLIS);
        while (pending.size() >= MAX_PENDING) {
            pending.remove(pending.keySet().iterator().next());
        }
        pending.put(proposal.id(), proposal);
        if (madeNow == null) {
            madeNow = new ArrayList<>();
        }
        madeNow.add(proposal);
        return proposal;
    }

    // ------------------------------------------------------------------ confirming

    /** Starts the proposed build, after checking everything again. Returns the queue item. */
    @NonNull
    synchronized Queue.Item confirm(@NonNull AssistantConfiguration config, @CheckForNull String id)
            throws ProposalException {
        Proposal proposal = id == null ? null : pending.remove(id);
        if (proposal == null) {
            throw new ProposalException(Messages.Action_unknownProposal());
        }
        if (now() - proposal.createdAt() > TTL_MILLIS) {
            throw new ProposalException(Messages.Action_expired());
        }
        if (!proposal.userId().equals(currentUserId())) {
            throw new ProposalException(Messages.Action_unknownProposal());
        }
        Job<?, ?> job = checkJob(config, proposal.job());
        // Parameters are re-validated against the job as it is now.
        Map<String, Object> requested = new LinkedHashMap<>(proposal.requested());
        List<ParameterValue> values = parameterValues(job, requested);

        List<hudson.model.Action> actions = new ArrayList<>();
        actions.add(new CauseAction(new Cause.UserIdCause()));
        if (!values.isEmpty()) {
            actions.add(new ParametersAction(values));
        }
        Queue.Item item = ParameterizedJobMixIn.scheduleBuild2(job, 0, actions.toArray(new hudson.model.Action[0]));
        if (item == null) {
            throw new ProposalException(Messages.Action_notScheduled());
        }
        return item;
    }

    synchronized boolean cancel(@CheckForNull String id) {
        if (id == null) {
            return false;
        }
        Proposal proposal = pending.get(id);
        if (proposal == null || !proposal.userId().equals(currentUserId())) {
            return false;
        }
        pending.remove(id);
        return true;
    }

    // ------------------------------------------------------------------ checks

    @NonNull
    static Job<?, ?> checkJob(@NonNull AssistantConfiguration config, @NonNull String name) throws ProposalException {
        if (!config.isEnabled() || !config.isAllowActions()) {
            throw new ProposalException(Messages.Action_disabled());
        }
        Job<?, ?> job;
        try {
            job = Jenkins.get().getItemByFullName(name, Job.class);
        } catch (AccessDeniedException e) {
            job = null;
        }
        if (job == null || !job.hasPermission(Item.READ)) {
            throw new ProposalException("There is no job called " + name + " that the user can see.");
        }
        if (!config.getActionJobPatterns().matches(job.getFullName())) {
            throw new ProposalException(Messages.Action_notAllowedJob(job.getFullName()));
        }
        if (!job.hasPermission(Item.BUILD)) {
            throw new ProposalException(Messages.Action_noBuildPermission(job.getFullName()));
        }
        if (!job.isBuildable() || !(job instanceof ParameterizedJobMixIn.ParameterizedJob)) {
            throw new ProposalException(Messages.Action_notBuildable(job.getFullName()));
        }
        return job;
    }

    /**
     * Whether a parameter holds a secret or points at one: passwords, and the Credentials plugin's
     * parameter, whose value is a credentials id. Matched by name to avoid depending on that plugin's
     * parameter classes.
     */
    static boolean isSecret(@NonNull ParameterDefinition definition) {
        return definition instanceof PasswordParameterDefinition
                || definition.getClass().getName().contains("Credentials");
    }

    /** The values a build would start with: requested where allowed, defaults otherwise. For display. */
    @NonNull
    static LinkedHashMap<String, String> parameters(@NonNull Job<?, ?> job, @NonNull Map<String, Object> requested)
            throws ProposalException {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (ParameterValue value : parameterValues(job, requested)) {
            out.put(value.getName(), value.isSensitive() ? "(default, hidden)" : String.valueOf(value.getValue()));
        }
        return out;
    }

    @NonNull
    static List<ParameterValue> parameterValues(@NonNull Job<?, ?> job, @NonNull Map<String, Object> requested)
            throws ProposalException {
        ParametersDefinitionProperty property = job.getProperty(ParametersDefinitionProperty.class);
        List<ParameterDefinition> definitions = property == null ? List.of() : property.getParameterDefinitions();
        for (String name : requested.keySet()) {
            if (definitions.stream().noneMatch(d -> d.getName().equals(name))) {
                throw new ProposalException(job.getFullName() + " has no parameter called " + name + ".");
            }
        }
        List<ParameterValue> values = new ArrayList<>();
        for (ParameterDefinition definition : definitions) {
            Object wanted = requested.get(definition.getName());
            ParameterValue value;
            if (wanted != null) {
                if (isSecret(definition)) {
                    throw new ProposalException(Messages.Action_noSecrets(definition.getName()));
                }
                if (!(definition instanceof SimpleParameterDefinition simple)) {
                    throw new ProposalException(Messages.Action_complexParameter(definition.getName()));
                }
                try {
                    value = simple.createValue(String.valueOf(wanted));
                } catch (RuntimeException e) {
                    throw new ProposalException(
                            "The value for " + definition.getName() + " is not allowed: " + e.getMessage());
                }
            } else {
                value = definition.getDefaultParameterValue();
                if (value == null) {
                    throw new ProposalException(Messages.Action_needsValue(definition.getName()));
                }
            }
            values.add(value);
        }
        return values;
    }

    private static LinkedHashMap<String, String> strings(Map<String, Object> values) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        values.forEach((name, value) -> out.put(name, String.valueOf(value)));
        return out;
    }

    private static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @NonNull
    static String currentUserId() {
        User user = User.current();
        return user == null ? "anonymous" : user.getId();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
