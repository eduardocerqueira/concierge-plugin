package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.GlobalConfiguration;

/**
 * Everything Concierge stores for the whole instance.
 *
 * <p>The onboarding settings here are edited on Concierge's own page by anyone with
 * {@link ConciergePermissions#MANAGE_TOURS}, not on the system configuration page, so that a team
 * lead can curate them without being an administrator.
 */
@Extension
public class ConciergeConfiguration extends GlobalConfiguration {

    /** Longest welcome message we keep. It is shown as plain text, so this is about layout. */
    static final int MAX_WELCOME_LENGTH = 2000;

    /** Longest note on a pinned job. */
    static final int MAX_NOTE_LENGTH = 500;

    /** How many jobs can be pinned. A "start here" list longer than this is not a start. */
    static final int MAX_PINNED = 12;

    private String welcomeMessage;
    private List<PinnedJob> startHere;

    public ConciergeConfiguration() {
        load();
    }

    @NonNull
    public static ConciergeConfiguration get() {
        return GlobalConfiguration.all().getInstance(ConciergeConfiguration.class);
    }

    @NonNull
    public String getWelcomeMessage() {
        return welcomeMessage == null ? "" : welcomeMessage;
    }

    public void setWelcomeMessage(@CheckForNull String welcomeMessage) {
        String trimmed = welcomeMessage == null ? "" : welcomeMessage.trim();
        this.welcomeMessage =
                trimmed.length() > MAX_WELCOME_LENGTH ? trimmed.substring(0, MAX_WELCOME_LENGTH) : trimmed;
        save();
    }

    @NonNull
    public List<PinnedJob> getStartHere() {
        return startHere == null ? List.of() : Collections.unmodifiableList(startHere);
    }

    public void setStartHere(@CheckForNull List<PinnedJob> pinned) {
        List<PinnedJob> kept = new ArrayList<>();
        if (pinned != null) {
            for (PinnedJob job : pinned) {
                if (job.getFullName().isEmpty() || kept.size() >= MAX_PINNED) {
                    continue;
                }
                String note = job.getNote();
                kept.add(new PinnedJob(
                        job.getFullName(),
                        note.length() > MAX_NOTE_LENGTH ? note.substring(0, MAX_NOTE_LENGTH) : note));
            }
        }
        this.startHere = kept;
        save();
    }
}
