package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.BallColor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;

/**
 * Real jobs from this instance to teach with, chosen from what the current user can read.
 *
 * <p>Learning paths and tours explain Jenkins with these instead of a toy example. Any of them can
 * be missing on a young or locked-down instance, and everything that uses them has to cope.
 */
final class Examples {

    /** Pipeline jobs are recognised by class name, so this plugin does not depend on Pipeline. */
    private static final String PIPELINE_JOB_CLASS = "org.jenkinsci.plugins.workflow.job.WorkflowJob";

    @CheckForNull
    final Job<?, ?> builtJob;

    @CheckForNull
    final Job<?, ?> failingJob;

    @CheckForNull
    final Job<?, ?> parameterizedJob;

    @CheckForNull
    final Job<?, ?> pipelineJob;

    @CheckForNull
    final TopLevelItem folder;

    private Examples(@NonNull Jenkins jenkins) {
        List<Job<?, ?>> readable = jenkins.getAllItems(Job.class).stream()
                .filter(job -> job.hasPermission(Item.READ))
                .sorted(Comparator.comparing(Job::getFullName))
                .<Job<?, ?>>map(job -> job)
                .collect(Collectors.toList());

        // A job that has built and is healthy makes the calmest first example; a failing one will
        // do if that is all there is.
        Job<?, ?> built = null;
        for (Job<?, ?> job : readable) {
            if (job.getLastBuild() == null) {
                continue;
            }
            if (built == null || (isFailing(built) && !isFailing(job))) {
                built = job;
            }
        }
        this.builtJob = built;

        this.failingJob = readable.stream()
                .filter(job -> isFailing(job) && job.getLastFailedBuild() != null)
                .findFirst()
                .orElse(null);
        this.parameterizedJob = readable.stream()
                .filter(job -> job.getProperty(ParametersDefinitionProperty.class) != null)
                .findFirst()
                .orElse(null);
        this.pipelineJob = readable.stream()
                .filter(job -> job.getClass().getName().equals(PIPELINE_JOB_CLASS))
                .findFirst()
                .orElse(null);
        this.folder = jenkins.getItems().stream()
                .filter(item -> item instanceof ItemGroup<?> && item.hasPermission(Item.READ))
                .findFirst()
                .orElse(null);
    }

    @NonNull
    static Examples forCurrentUser() {
        return new Examples(Jenkins.get());
    }

    private static boolean isFailing(@NonNull Job<?, ?> job) {
        BallColor color = job.getIconColor();
        return color != null && color.noAnime() == BallColor.RED;
    }

    @CheckForNull
    static String lastBuildUrl(@CheckForNull Job<?, ?> job) {
        if (job == null) {
            return null;
        }
        Run<?, ?> last = job.getLastBuild();
        return last == null ? null : JobRef.url(job) + last.getNumber() + "/";
    }

    @CheckForNull
    static String lastFailedBuildUrl(@CheckForNull Job<?, ?> job) {
        if (job == null) {
            return null;
        }
        Run<?, ?> failed = job.getLastFailedBuild();
        return failed == null ? null : JobRef.url(job) + failed.getNumber() + "/";
    }
}
