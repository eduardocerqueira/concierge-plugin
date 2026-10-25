package io.jenkins.plugins.concierge;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.PermissionScope;
import jenkins.model.Jenkins;

/**
 * Who may do what in Concierge. See docs/security-design.md, rule 1.
 *
 * <p>Reading the welcome page and following tours is implied by Overall/Read, because everything on
 * them is filtered to what the reader can already see. Editing what newcomers are shown, and spending
 * the instance's AI key, are implied by Overall/Administer, so on a matrix-secured controller nobody
 * else gets them until an admin grants them.
 */
public final class ConciergePermissions {

    public static final PermissionGroup GROUP =
            new PermissionGroup(ConciergePermissions.class, Messages._ConciergePermissions_Group());

    /** See the welcome page, the learning paths and the tours. */
    public static final Permission USE =
            new Permission(GROUP, "Use", Messages._ConciergePermissions_Use(), Jenkins.READ, PermissionScope.JENKINS);

    /** Change the welcome message and the "start here" jobs. */
    public static final Permission MANAGE_TOURS = new Permission(
            GROUP,
            "ManageTours",
            Messages._ConciergePermissions_ManageTours(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    /** Ask the AI assistant questions, using the key an admin configured. */
    public static final Permission ASSISTANT = new Permission(
            GROUP,
            "Assistant",
            Messages._ConciergePermissions_Assistant(),
            Jenkins.ADMINISTER,
            PermissionScope.JENKINS);

    private ConciergePermissions() {}

    /** Loads the class, and so registers the permissions, before security is configured. */
    @Initializer(before = InitMilestone.SYSTEM_CONFIG_LOADED)
    public static void register() {
        // Nothing to do: touching the class is enough.
    }
}
