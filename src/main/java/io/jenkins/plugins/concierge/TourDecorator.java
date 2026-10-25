package io.jenkins.plugins.concierge;

import hudson.Extension;
import hudson.model.PageDecorator;
import jenkins.model.Jenkins;

/**
 * Adds the tour runner to every Jenkins page, because tours walk across real pages: a job, its
 * last build, the console. The script does nothing unless the user started a tour in this browser
 * tab, and it is only added for users who may use Concierge at all.
 */
@Extension
public class TourDecorator extends PageDecorator {

    public boolean isEnabled() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins != null && jenkins.hasPermission(ConciergePermissions.USE);
    }

    /** Whether to add the "Ask Concierge" panel to the page. */
    public boolean isAssistantAvailable() {
        return AssistantEndpoint.isAvailable();
    }
}
