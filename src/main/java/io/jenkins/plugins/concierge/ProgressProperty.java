package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.User;
import hudson.model.UserProperty;
import hudson.model.UserPropertyDescriptor;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Which learning-path steps a user has marked as done. Only step ids are stored. */
public class ProgressProperty extends UserProperty {

    private Set<String> completed;

    @NonNull
    public synchronized Set<String> getCompleted() {
        return completed == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(completed));
    }

    private synchronized void set(@NonNull String stepId, boolean done) {
        if (completed == null) {
            completed = new TreeSet<>();
        }
        if (done) {
            completed.add(stepId);
        } else {
            completed.remove(stepId);
        }
    }

    @NonNull
    static Set<String> of(@CheckForNull User user) {
        if (user == null) {
            return Set.of();
        }
        ProgressProperty property = user.getProperty(ProgressProperty.class);
        return property == null ? Set.of() : property.getCompleted();
    }

    static void mark(@NonNull User user, @NonNull String stepId, boolean done) throws IOException {
        ProgressProperty property = user.getProperty(ProgressProperty.class);
        if (property == null) {
            property = new ProgressProperty();
            user.addProperty(property);
        }
        property.set(stepId, done);
        user.save();
    }

    @Extension
    public static class DescriptorImpl extends UserPropertyDescriptor {

        @Override
        public UserProperty newInstance(User user) {
            return new ProgressProperty();
        }

        /** Progress is changed from the welcome page, not the user's configuration form. */
        @Override
        public boolean isEnabled() {
            return false;
        }
    }
}
