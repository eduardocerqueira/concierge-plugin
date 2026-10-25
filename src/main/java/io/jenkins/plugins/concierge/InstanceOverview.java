package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.PluginWrapper;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.TopLevelItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;

/**
 * The welcome page's picture of the instance, computed for one user from what that user can read.
 *
 * <p>Never cache one of these and show it to someone else (docs/security-design.md, rule 1). A
 * newcomer with access to one folder must not learn the names of the others from it.
 */
public final class InstanceOverview {

    /** How many jobs each list on the welcome page shows. */
    static final int LIST_LIMIT = 8;

    private final Map<String, Integer> statusCounts = new LinkedHashMap<>();
    private final List<JobRef> failing = new ArrayList<>();
    private final List<JobRef> building = new ArrayList<>();
    private final List<JobRef> recent = new ArrayList<>();
    private final List<FolderSummary> folders = new ArrayList<>();
    private final int jobCount;
    private final int queueLength;
    private final int agentCount;
    private final int agentsOnline;
    private final int executors;
    private final int busyExecutors;
    private final boolean pluginsVisible;
    private final int pluginCount;
    private final int pluginUpdates;

    private InstanceOverview(@NonNull Jenkins jenkins) {
        for (String status : List.of("failure", "unstable", "success", "aborted", "notbuilt", "disabled")) {
            statusCounts.put(status, 0);
        }

        List<JobRef> all = new ArrayList<>();
        for (Job<?, ?> job : jenkins.getAllItems(Job.class)) {
            // getAllItems already skips what the current user cannot read; checked again because
            // this is the line every other guarantee on the page rests on.
            if (!job.hasPermission(Item.READ)) {
                continue;
            }
            JobRef ref = new JobRef(job);
            all.add(ref);
            statusCounts.merge(ref.getStatus(), 1, Integer::sum);
        }
        this.jobCount = all.size();

        Comparator<JobRef> newestFirst =
                Comparator.comparingLong(JobRef::getLastBuildTime).reversed().thenComparing(JobRef::getFullName);
        all.sort(newestFirst);
        for (JobRef ref : all) {
            if (ref.getStatus().equals("failure") && failing.size() < LIST_LIMIT) {
                failing.add(ref);
            }
            if (ref.isBuilding() && building.size() < LIST_LIMIT) {
                building.add(ref);
            }
            if (ref.getLastBuildTime() > 0 && recent.size() < LIST_LIMIT) {
                recent.add(ref);
            }
        }

        for (TopLevelItem item : jenkins.getItems()) {
            if (item instanceof ItemGroup<?> && item.hasPermission(Item.READ)) {
                String prefix = item.getFullName() + "/";
                int count = (int) all.stream()
                        .filter(ref -> ref.getFullName().startsWith(prefix))
                        .count();
                folders.add(new FolderSummary(item.getFullDisplayName(), item.getUrl(), count));
            }
        }
        folders.sort(Comparator.comparing(FolderSummary::getName, String.CASE_INSENSITIVE_ORDER));

        int queued = 0;
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof Item task && task.hasPermission(Item.READ)) {
                queued++;
            }
        }
        this.queueLength = queued;

        int agents = 0;
        int online = 0;
        int totalExecutors = 0;
        int busy = 0;
        for (Computer computer : jenkins.getComputers()) {
            agents++;
            if (computer.isOnline()) {
                online++;
                totalExecutors += computer.countExecutors();
                busy += computer.countBusy();
            }
        }
        this.agentCount = agents;
        this.agentsOnline = online;
        this.executors = totalExecutors;
        this.busyExecutors = busy;

        // Which plugins are installed is system information; Jenkins itself shows it only to
        // Overall/SystemRead.
        this.pluginsVisible = jenkins.hasPermission(Jenkins.SYSTEM_READ);
        if (pluginsVisible) {
            int active = 0;
            for (PluginWrapper plugin : jenkins.getPluginManager().getPlugins()) {
                if (plugin.isActive()) {
                    active++;
                }
            }
            this.pluginCount = active;
            this.pluginUpdates = jenkins.getUpdateCenter().getUpdates().size();
        } else {
            this.pluginCount = 0;
            this.pluginUpdates = 0;
        }
    }

    /** Computes the overview as the current user sees the instance. */
    @NonNull
    public static InstanceOverview forCurrentUser() {
        return new InstanceOverview(Jenkins.get());
    }

    public int getJobCount() {
        return jobCount;
    }

    /** Job counts by status, in a fixed order: failure, unstable, success, aborted, notbuilt, disabled. */
    @NonNull
    public Map<String, Integer> getStatusCounts() {
        return statusCounts;
    }

    public int getCount(String status) {
        return statusCounts.getOrDefault(status, 0);
    }

    @NonNull
    public List<JobRef> getFailing() {
        return failing;
    }

    @NonNull
    public List<JobRef> getBuilding() {
        return building;
    }

    @NonNull
    public List<JobRef> getRecent() {
        return recent;
    }

    @NonNull
    public List<FolderSummary> getFolders() {
        return folders;
    }

    public int getQueueLength() {
        return queueLength;
    }

    public int getAgentCount() {
        return agentCount;
    }

    public int getAgentsOnline() {
        return agentsOnline;
    }

    public int getExecutors() {
        return executors;
    }

    public int getBusyExecutors() {
        return busyExecutors;
    }

    public boolean isPluginsVisible() {
        return pluginsVisible;
    }

    public int getPluginCount() {
        return pluginCount;
    }

    public int getPluginUpdates() {
        return pluginUpdates;
    }

    /** A top-level folder and how many jobs inside it the current user can read. */
    public static final class FolderSummary {
        private final String name;
        private final String url;
        private final int jobCount;

        FolderSummary(String name, String url, int jobCount) {
            this.name = name;
            this.url = url;
            this.jobCount = jobCount;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public int getJobCount() {
            return jobCount;
        }
    }
}
