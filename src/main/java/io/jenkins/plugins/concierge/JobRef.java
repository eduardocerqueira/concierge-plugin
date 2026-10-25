package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.BallColor;
import hudson.model.Job;
import hudson.model.Run;

/**
 * What Concierge shows about one job: enough to name it, link to it and say how it is doing.
 * Only ever built from a job the current user can read.
 */
public final class JobRef {

    private final String fullName;
    private final String displayName;
    private final String url;
    private final String status;
    private final boolean building;
    private final long lastBuildTime;

    JobRef(@NonNull Job<?, ?> job) {
        this.fullName = job.getFullName();
        this.displayName = job.getFullDisplayName();
        this.url = url(job);
        this.status = status(job.getIconColor());
        this.building = job.isBuilding();
        Run<?, ?> last = job.getLastBuild();
        this.lastBuildTime = last == null ? 0L : last.getTimeInMillis();
    }

    /**
     * The job's URL relative to the Jenkins root. Not {@link Job#getUrl()}, which inside a request
     * rewrites itself against the nearest ancestor object.
     */
    @NonNull
    static String url(@NonNull Job<?, ?> job) {
        return job.getParent().getUrl() + job.getShortUrl();
    }

    /** One of success, unstable, failure, aborted, disabled or notbuilt. */
    @NonNull
    static String status(@CheckForNull BallColor color) {
        if (color == null) {
            return "notbuilt";
        }
        switch (color.noAnime()) {
            case BLUE:
                return "success";
            case YELLOW:
                return "unstable";
            case RED:
                return "failure";
            case ABORTED:
                return "aborted";
            case DISABLED:
                return "disabled";
            default:
                return "notbuilt";
        }
    }

    @NonNull
    public String getFullName() {
        return fullName;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    @NonNull
    public String getStatus() {
        return status;
    }

    public boolean isBuilding() {
        return building;
    }

    public long getLastBuildTime() {
        return lastBuildTime;
    }
}
