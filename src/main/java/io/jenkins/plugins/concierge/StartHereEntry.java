package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.springframework.security.access.AccessDeniedException;

/** A pinned job resolved for the current user: only jobs they can read survive. */
public final class StartHereEntry {

    private final JobRef job;
    private final String note;

    private StartHereEntry(JobRef job, String note) {
        this.job = job;
        this.note = note;
    }

    @NonNull
    public JobRef getJob() {
        return job;
    }

    @NonNull
    public String getNote() {
        return note;
    }

    /**
     * The curated "start here" list as the current user may see it. A pinned job the user cannot
     * read is dropped silently, so the list never reveals that it exists.
     */
    @NonNull
    static List<StartHereEntry> forCurrentUser() {
        Jenkins jenkins = Jenkins.get();
        List<StartHereEntry> entries = new ArrayList<>();
        for (PinnedJob pinned : ConciergeConfiguration.get().getStartHere()) {
            Job<?, ?> job;
            try {
                job = jenkins.getItemByFullName(pinned.getFullName(), Job.class);
            } catch (AccessDeniedException e) {
                // Discover without Read: the user may know it exists, but we do not point at it.
                continue;
            }
            if (job != null && job.hasPermission(Job.READ)) {
                entries.add(new StartHereEntry(new JobRef(job), pinned.getNote()));
            }
        }
        return entries;
    }
}
