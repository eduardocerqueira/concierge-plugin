package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guided tours, built for the current user.
 *
 * <p>A tour is a list of steps. Each step names a page by its path relative to the Jenkins root, a
 * CSS selector for the thing to point at, and what to say about it. {@code tour.js} walks through
 * them. When a selector matches nothing, because a plugin or a newer Jenkins changed the page, the
 * step is shown in the middle of the screen instead of being skipped, so a tour degrades rather
 * than breaks. An optional step is skipped instead: one about a part of the page that is not
 * always there, such as the job list, which a dashboard made with another kind of view (the
 * Dashboard View plugin's, for one) may not have at all.
 *
 * <p>Jenkins has two layouts for the dashboard, job and build pages: the classic one, and the newer
 * one users can turn on under Experiments. A selector lists the newer layout's element first where
 * the two differ, since some classic classes (such as {@code .jenkins-app-bar}) are still present in
 * the newer layout but mean something else there. Never end a selector with a whole panel such as
 * {@code #side-panel}: pointing at the wrong thing is worse than pointing at nothing. Point at a
 * block element rather than a table: the highlight is an outline, which tables do not draw well.
 *
 * <p>Every path comes from a job the current user can read, and all text is sent as plain text and
 * written with {@code textContent} (docs/security-design.md, rule 6).
 */
final class TourCatalog {

    static final String DASHBOARD = "dashboard";
    static final String JOB = "job";
    static final String START_HERE = "start-here";

    /** The page title: the newer layout's build bar, else the classic app bar. */
    private static final String TITLE = ".app-build-bar__content, .jenkins-app-bar, h1";

    /** What you can do on this page: the classic side-panel tasks, else the newer layout's top-bar controls. */
    private static final String TASKS = "#tasks, .app-build-bar__controls";

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

    /**
     * The steps of one tour, for {@link Json#write}, or null if there is no such tour for this user.
     * Not json-lib, which would turn a curated note such as "[x] done" or "null" into something else.
     */
    @CheckForNull
    static Map<String, Object> steps(
            @CheckForNull String id, @NonNull Examples examples, @NonNull List<StartHereEntry> startHere) {
        List<Object> steps = new ArrayList<>();
        if (DASHBOARD.equals(id)) {
            steps.add(step(
                    "",
                    "#root-action-SearchAction, .jenkins-header__actions",
                    Messages.TourStep_search_title(),
                    Messages.TourStep_search_text()));
            steps.add(step("", TASKS, Messages.TourStep_sidePanel_title(), Messages.TourStep_sidePanel_text()));
            steps.add(optional(step(
                    "",
                    ".app-project-status-table, #projectstatus",
                    Messages.TourStep_jobList_title(),
                    Messages.TourStep_jobList_text())));
            steps.add(
                    optional(step("", "#buildQueue", Messages.TourStep_queue_title(), Messages.TourStep_queue_text())));
            steps.add(optional(
                    step("", "#executors", Messages.TourStep_executors_title(), Messages.TourStep_executors_text())));
            steps.add(step(
                    "concierge/",
                    ".concierge-paths, #main-panel",
                    Messages.TourStep_back_title(),
                    Messages.TourStep_back_text()));
        } else if (JOB.equals(id) && examples.builtJob != null) {
            String job = JobRef.url(examples.builtJob);
            String build = Examples.lastBuildUrl(examples.builtJob);
            steps.add(step(job, TITLE, Messages.TourStep_jobTitle_title(), Messages.TourStep_jobTitle_text()));
            steps.add(step(job, TASKS, Messages.TourStep_jobTasks_title(), Messages.TourStep_jobTasks_text()));
            steps.add(step(
                    job,
                    "#jenkins-build-history, #buildHistoryPage",
                    Messages.TourStep_history_title(),
                    Messages.TourStep_history_text()));
            if (build != null) {
                steps.add(step(build, TITLE, Messages.TourStep_build_title(), Messages.TourStep_build_text()));
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
                steps.add(step(entry.getJob().getUrl(), TITLE, entry.getJob().getDisplayName(), note));
            }
            steps.add(step(
                    "concierge/",
                    ".concierge-paths, #main-panel",
                    Messages.TourStep_back_title(),
                    Messages.TourStep_back_text()));
        } else {
            return null;
        }
        Map<String, Object> tour = new LinkedHashMap<>();
        tour.put("id", id);
        tour.put("steps", steps);
        return tour;
    }

    /** Marks a step to be skipped, rather than shown in the middle, when its page has no such part. */
    private static Map<String, Object> optional(Map<String, Object> step) {
        step.put("optional", true);
        return step;
    }

    private static Map<String, Object> step(String path, String selector, String title, String text) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("path", path);
        step.put("selector", selector);
        step.put("title", title);
        step.put("text", text);
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
