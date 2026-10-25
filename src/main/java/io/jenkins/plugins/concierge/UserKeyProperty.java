package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * A user's own API key for the assistant, used instead of the administrator's when the
 * administrator allows it. Stored encrypted, like every Jenkins {@link Secret}, and only for the
 * provider the administrator configured.
 */
public class UserKeyProperty extends UserProperty {

    @CheckForNull
    private final Secret apiKey;

    @DataBoundConstructor
    public UserKeyProperty(@CheckForNull Secret apiKey) {
        this.apiKey = apiKey == null || apiKey.getPlainText().isBlank() ? null : apiKey;
    }

    @CheckForNull
    public Secret getApiKey() {
        return apiKey;
    }

    /** The current user's key, or null if they have none or users may not bring keys. */
    @CheckForNull
    static String forCurrentUser() {
        if (!AssistantConfiguration.get().isAllowUserKeys()) {
            return null;
        }
        User user = User.current();
        if (user == null) {
            return null;
        }
        UserKeyProperty property = user.getProperty(UserKeyProperty.class);
        return property == null || property.apiKey == null ? null : property.apiKey.getPlainText();
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.UserKeyProperty_DisplayName();
        }

        @Override
        public UserProperty newInstance(User user) {
            return new UserKeyProperty(null);
        }

        /** Only shown on the user's settings page when the administrator allows personal keys. */
        @Override
        public boolean isEnabled() {
            AssistantConfiguration config = AssistantConfiguration.get();
            return config.isEnabled() && config.isAllowUserKeys();
        }

        public String getProviderName() {
            return AssistantConfiguration.get().getProvider().getDisplayName();
        }
    }
}
