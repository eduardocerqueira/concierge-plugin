package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.User;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerProxy;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Concierge's home at {@code /concierge}: the welcome page, the learning paths, the tours, and the
 * page where the "start here" list is curated.
 *
 * <p>{@link #getTarget()} checks {@link ConciergePermissions#USE} before Stapler reaches anything
 * here, views and endpoints alike. Endpoints that change something are POST-only and check their
 * own permission on top (docs/security-design.md, rule 2).
 */
@Extension
public class ConciergeAction implements RootAction, StaplerProxy {

    static final String URL_NAME = "concierge";

    @Override
    public String getIconFileName() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins != null && jenkins.hasPermission(ConciergePermissions.USE) ? "symbol-help-circle" : null;
    }

    @Override
    public String getDisplayName() {
        return Messages.ConciergeAction_DisplayName();
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(ConciergePermissions.USE);
        return this;
    }

    /** The assistant, at {@code /concierge/assistant}. It checks its own permission. */
    @NonNull
    public AssistantEndpoint getAssistant() {
        return new AssistantEndpoint();
    }

    /** Assistant usage and cost, at {@code /concierge/usage}. Administrators only. */
    @NonNull
    public UsagePage getUsage() {
        return new UsagePage();
    }

    public boolean isAdministrator() {
        return Jenkins.get().hasPermission(Jenkins.ADMINISTER);
    }

    public boolean isAssistantAvailable() {
        return AssistantEndpoint.isAvailable();
    }

    @NonNull
    public InstanceOverview getOverview() {
        return InstanceOverview.forCurrentUser();
    }

    @NonNull
    public List<LearningPath> getPaths() {
        return LearningPath.forCurrentUser(ProgressProperty.of(User.current()));
    }

    @NonNull
    public List<StartHereEntry> getStartHere() {
        return StartHereEntry.forCurrentUser();
    }

    @NonNull
    public List<TourCatalog.TourInfo> getTours() {
        return TourCatalog.available(Examples.forCurrentUser(), getStartHere());
    }

    @NonNull
    public String getWelcomeMessage() {
        return ConciergeConfiguration.get().getWelcomeMessage();
    }

    /** The configured list, unfiltered, for the curation form. Only shown to tour managers. */
    @NonNull
    public List<PinnedJob> getPinnedJobs() {
        Jenkins.get().checkPermission(ConciergePermissions.MANAGE_TOURS);
        return ConciergeConfiguration.get().getStartHere();
    }

    public boolean isLoggedIn() {
        return User.current() != null;
    }

    public boolean isCanManageTours() {
        return Jenkins.get().hasPermission(ConciergePermissions.MANAGE_TOURS);
    }

    /** For {@code manage.jelly}'s layout check. */
    @NonNull
    public hudson.security.Permission getManageToursPermission() {
        return ConciergePermissions.MANAGE_TOURS;
    }

    public int getMaxPinned() {
        return ConciergeConfiguration.MAX_PINNED;
    }

    /** The steps of one tour, for {@code tour.js}. Read-only, so GET is fine. */
    public HttpResponse doTour(@QueryParameter String id) {
        JSONObject tour = TourCatalog.steps(id, Examples.forCurrentUser(), getStartHere());
        if (tour == null) {
            return HttpResponses.error(404, "No such tour.");
        }
        return hudson.util.HttpResponses.okJSON(tour);
    }

    /** Marks a learning-path step as done or not done for the current user. */
    @POST
    public HttpResponse doProgress(@QueryParameter String step, @QueryParameter boolean done) throws IOException {
        Jenkins.get().checkPermission(ConciergePermissions.USE);
        User user = User.current();
        if (user == null) {
            return HttpResponses.error(403, "Sign in to keep track of your progress.");
        }
        if (!LearningPath.isKnownStep(step)) {
            return HttpResponses.error(400, "Unknown step.");
        }
        ProgressProperty.mark(user, step, done);
        JSONObject result = new JSONObject();
        result.element("step", step);
        result.element("done", done);
        return hudson.util.HttpResponses.okJSON(result);
    }

    /** Saves the welcome message and the "start here" list from the curation form. */
    @POST
    public HttpResponse doSaveTours(StaplerRequest2 req) throws IOException, ServletException {
        Jenkins.get().checkPermission(ConciergePermissions.MANAGE_TOURS);
        JSONObject form = req.getSubmittedForm();
        ConciergeConfiguration config = ConciergeConfiguration.get();
        config.setWelcomeMessage(form.optString("welcomeMessage", ""));
        config.setStartHere(pinnedFrom(form.opt("pinned")));
        return HttpResponses.redirectTo(".");
    }

    /** The form sends one object for a single row and an array for several. */
    @NonNull
    static List<PinnedJob> pinnedFrom(@CheckForNull Object submitted) {
        List<PinnedJob> pinned = new ArrayList<>();
        if (submitted instanceof JSONObject row) {
            add(pinned, row);
        } else if (submitted instanceof JSONArray rows) {
            for (Object row : rows) {
                if (row instanceof JSONObject object) {
                    add(pinned, object);
                }
            }
        }
        return pinned;
    }

    private static void add(List<PinnedJob> pinned, JSONObject row) {
        String name = row.optString("fullName", "").trim();
        if (!name.isEmpty()) {
            pinned.add(new PinnedJob(name, row.optString("note", "")));
        }
    }
}
