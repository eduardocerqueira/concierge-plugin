package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/** A job someone curating the instance wants newcomers to look at first, with a note on why. */
public final class PinnedJob {

    private final String fullName;
    private final String note;

    public PinnedJob(@NonNull String fullName, String note) {
        this.fullName = fullName.trim();
        this.note = note == null ? "" : note.trim();
    }

    @NonNull
    public String getFullName() {
        return fullName;
    }

    @NonNull
    public String getNote() {
        return note == null ? "" : note;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PinnedJob other
                && fullName.equals(other.fullName)
                && getNote().equals(other.getNote());
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullName, getNote());
    }
}
