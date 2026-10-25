package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * The guided tours, built for the current user.
 *
 * <p>A tour is a list of steps. Each step names a page by its path relative to the Jenkins root, a
 * CSS selector for the thing to point at, and what to say about it. {@code tour.js} walks through
 * them. When a selector matches nothing, because a plugin or a newer Jenkins changed the page, the
 * step is shown in the middle of the screen instead of being skipped, so a tour degrades rather
 * than breaks.
 *
 * <p>Every path comes from a job the current user can read, and all text is sent as plain text and
 * written with {@code textContent} (docs/security-design.md, rule 6).
 */
final class TourCatalog {

    static final String DASHBOARD = "dashboard";
    static final String JOB = "job";
    static final String START_HERE = "start-here";

    private TourCatalog() {}

    /** The tours available to the current user, in the order the welcome page lists them. */
    @NonNull
    static List<TourInfo> available(@NonNull Examples examples, @NonNull List<StartHereEntry> startHere) {
        List<TourInfo> tours = new ArrayList<>();
        if (!startHere.isEmpty()) {
            tours.add(new TourInfo(START_HERE, Messages.Tour_startHere_title(), Messages.Tour_startHere_text()));
        }
        tours.add(new TourInfo(DASHBOARD, Messages.Tour_dashboard_title(), Messages.Tour_dashboard_text()));
        if (examples.builtJob != null) {
            tours.add(new TourInfo(
                    JOB, Messages.Tour_job_title(), Messages.Tour_job_text(examples.builtJob.getFullDisplayName())));
        }
        return tours;
    }

    /** The steps of one tour as JSON, or null if there is no such tour for this user. */
    @CheckForNull
    static JSONObject steps(
            @CheckForNull String id, @NonNull Examples examples, @NonNull List<StartHereEntry> startHere) {
        JSONArray steps = new JSONArray();
        if (DASHBOARD.equals(id)) {
            steps.add(step(
                    "",
                    "#root-action-SearchAction, .jenkins-header__actions",
                    Messages.TourStep_search_title(),
                    Messages.TourStep_search_text()));
            steps.add(step(
                    "",
                    "#tasks, #side-panel",
                    Messages.TourStep_sidePanel_title(),
                    Messages.TourStep_sidePanel_text()));
            steps.add(step(
                    "",
                    "#projectstatus, #main-panel",
                    Messages.TourStep_jobList_title(),
                    Messages.TourStep_jobList_text()));
            steps.add(step(
                    "",
                    "#buildQueue, #executors, #side-panel",
                    Messages.TourStep_queue_title(),
                    Messages.TourStep_queue_text()));
            steps.add(step(
                    "concierge/",
                    ".concierge-paths, #main-panel",
                    Messages.TourStep_back_title(),
                    Messages.TourStep_back_text()));
        } else if (JOB.equals(id) && examples.builtJob != null) {
            String job = JobRef.url(examples.builtJob);
            String build = Examples.lastBuildUrl(examples.builtJob);
            steps.add(step(
                    job,
                    ".jenkins-app-bar, h1",
                    Messages.TourStep_jobTitle_title(),
                    Messages.TourStep_jobTitle_text()));
            steps.add(step(
                    job, "#tasks, #side-panel", Messages.TourStep_jobTasks_title(), Messages.TourStep_jobTasks_text()));
            steps.add(step(
                    job,
                    "#jenkins-build-history, #buildHistoryPage, #side-panel",
                    Messages.TourStep_history_title(),
                    Messages.TourStep_history_text()));
            if (build != null) {
                steps.add(step(
                        build,
                        ".jenkins-app-bar, h1",
                        Messages.TourStep_build_title(),
                        Messages.TourStep_build_text()));
                steps.add(step(
                        build + "console",
                        ".console-output, #main-panel",
                        Messages.TourStep_console_title(),
                        Messages.TourStep_console_text()));
            }
            steps.add(step(
                    "concierge/",
                    ".concierge-paths, #main-panel",
                    Messages.TourStep_back_title(),
                    Messages.TourStep_back_text()));
        } else if (START_HERE.equals(id) && !startHere.isEmpty()) {
            for (StartHereEntry entry : startHere) {
                String note = entry.getNote().isEmpty() ? Messages.TourStep_pinned_defaultNote() : entry.getNote();
                steps.add(step(
                        entry.getJob().getUrl(),
                        ".jenkins-app-bar, h1",
                        entry.getJob().getDisplayName(),
                        note));
            }
            steps.add(step(
                    "concierge/",
                    ".concierge-paths, #main-panel",
                    Messages.TourStep_back_title(),
                    Messages.TourStep_back_text()));
        } else {
            return null;
        }
        JSONObject tour = new JSONObject();
        tour.element("id", id);
        tour.element("steps", steps);
        return tour;
    }

    private static JSONObject step(String path, String selector, String title, String text) {
        JSONObject step = new JSONObject();
        step.element("path", path);
        step.element("selector", selector);
        step.element("title", title);
        step.element("text", text);
        return step;
    }

    /** A tour as the welcome page lists it. */
    public static final class TourInfo {
        private final String id;
        private final String title;
        private final String text;

        TourInfo(String id, String title, String text) {
            this.id = id;
            this.title = title;
            this.text = text;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getText() {
            return text;
        }
    }
}
