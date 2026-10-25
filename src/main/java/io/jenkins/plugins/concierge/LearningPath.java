package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jenkins.model.Jenkins;

/**
 * A short list of steps for one kind of newcomer, pointing at real pages on this instance.
 *
 * <p>Steps whose page the user could not open are left out rather than shown as dead links, so a
 * developer is never sent to the plugin manager.
 */
public final class LearningPath {

    private final String id;
    private final String title;
    private final String intro;
    private final List<Step> steps;

    private LearningPath(String id, String title, String intro, List<Step> steps) {
        this.id = id;
        this.title = title;
        this.intro = intro;
        this.steps = steps;
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getIntro() {
        return intro;
    }

    @NonNull
    public List<Step> getSteps() {
        return steps;
    }

    public int getDoneCount() {
        return (int) steps.stream().filter(Step::isDone).count();
    }

    /** Every step id any path can contain, so the progress endpoint can reject anything else. */
    static boolean isKnownStep(@CheckForNull String id) {
        if (id == null) {
            return false;
        }
        for (String known : ALL_STEP_IDS) {
            if (known.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> ALL_STEP_IDS = List.of(
            "newcomer.job",
            "newcomer.build",
            "newcomer.console",
            "newcomer.pipeline",
            "newcomer.folders",
            "newcomer.agents",
            "developer.find",
            "developer.failure",
            "developer.rerun",
            "developer.parameters",
            "developer.changes",
            "devops.agents",
            "devops.queue",
            "devops.plugins",
            "devops.log",
            "devops.security");

    /** The three paths, built for the current user. */
    @NonNull
    static List<LearningPath> forCurrentUser(@NonNull Set<String> done) {
        Examples examples = Examples.forCurrentUser();
        Jenkins jenkins = Jenkins.get();
        List<LearningPath> paths = new ArrayList<>();

        Builder newcomer = new Builder(done);
        newcomer.add(
                "newcomer.job",
                Messages.Step_newcomer_job_title(),
                Messages.Step_newcomer_job_text(),
                examples.builtJob == null ? null : JobRef.url(examples.builtJob));
        newcomer.add(
                "newcomer.build",
                Messages.Step_newcomer_build_title(),
                Messages.Step_newcomer_build_text(),
                Examples.lastBuildUrl(examples.builtJob));
        String lastBuild = Examples.lastBuildUrl(examples.builtJob);
        newcomer.add(
                "newcomer.console",
                Messages.Step_newcomer_console_title(),
                Messages.Step_newcomer_console_text(),
                lastBuild == null ? null : lastBuild + "console");
        newcomer.add(
                "newcomer.pipeline",
                Messages.Step_newcomer_pipeline_title(),
                Messages.Step_newcomer_pipeline_text(),
                examples.pipelineJob == null ? null : JobRef.url(examples.pipelineJob));
        newcomer.add(
                "newcomer.folders",
                Messages.Step_newcomer_folders_title(),
                Messages.Step_newcomer_folders_text(),
                examples.folder == null ? "" : examples.folder.getUrl());
        newcomer.add(
                "newcomer.agents",
                Messages.Step_newcomer_agents_title(),
                Messages.Step_newcomer_agents_text(),
                "computer/");
        paths.add(new LearningPath(
                "newcomer", Messages.Path_newcomer_title(), Messages.Path_newcomer_intro(), newcomer.steps));

        Builder developer = new Builder(done);
        developer.add("developer.find", Messages.Step_developer_find_title(), Messages.Step_developer_find_text(), "");
        String failed = Examples.lastFailedBuildUrl(examples.failingJob);
        developer.add(
                "developer.failure",
                Messages.Step_developer_failure_title(),
                Messages.Step_developer_failure_text(),
                failed == null ? null : failed + "console");
        developer.add(
                "developer.rerun",
                Messages.Step_developer_rerun_title(),
                Messages.Step_developer_rerun_text(),
                examples.builtJob == null ? null : JobRef.url(examples.builtJob));
        if (examples.parameterizedJob != null) {
            developer.add(
                    "developer.parameters",
                    Messages.Step_developer_parameters_title(),
                    Messages.Step_developer_parameters_text(),
                    JobRef.url(examples.parameterizedJob));
        }
        developer.add(
                "developer.changes",
                Messages.Step_developer_changes_title(),
                Messages.Step_developer_changes_text(),
                lastBuild == null ? null : lastBuild + "changes");
        paths.add(new LearningPath(
                "developer", Messages.Path_developer_title(), Messages.Path_developer_intro(), developer.steps));

        Builder devops = new Builder(done);
        devops.add(
                "devops.agents", Messages.Step_devops_agents_title(), Messages.Step_devops_agents_text(), "computer/");
        devops.add("devops.queue", Messages.Step_devops_queue_title(), Messages.Step_devops_queue_text(), "");
        if (jenkins.hasPermission(Jenkins.SYSTEM_READ)) {
            devops.add(
                    "devops.plugins",
                    Messages.Step_devops_plugins_title(),
                    Messages.Step_devops_plugins_text(),
                    "manage/pluginManager/");
        }
        if (jenkins.hasPermission(Jenkins.ADMINISTER)) {
            devops.add("devops.log", Messages.Step_devops_log_title(), Messages.Step_devops_log_text(), "manage/log/");
            devops.add(
                    "devops.security",
                    Messages.Step_devops_security_title(),
                    Messages.Step_devops_security_text(),
                    "manage/configureSecurity/");
        }
        paths.add(new LearningPath("devops", Messages.Path_devops_title(), Messages.Path_devops_intro(), devops.steps));

        return paths;
    }

    /** One thing to look at, and why. */
    public static final class Step {
        private final String id;
        private final String title;
        private final String text;
        private final String href;
        private final boolean done;

        Step(String id, String title, String text, @CheckForNull String href, boolean done) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.href = href;
            this.done = done;
        }

        @NonNull
        public String getId() {
            return id;
        }

        @NonNull
        public String getTitle() {
            return title;
        }

        @NonNull
        public String getText() {
            return text;
        }

        /**
         * Where to look, relative to the Jenkins root ("" is the dashboard), or null when this
         * instance has nothing to show for this step yet.
         */
        @CheckForNull
        public String getHref() {
            return href;
        }

        public boolean isDone() {
            return done;
        }
    }

    private static final class Builder {
        private final Set<String> done;
        private final List<Step> steps = new ArrayList<>();

        Builder(Set<String> done) {
            this.done = done;
        }

        void add(String id, String title, String text, @CheckForNull String href) {
            steps.add(new Step(id, title, text, href, done.contains(id)));
        }
    }
}
