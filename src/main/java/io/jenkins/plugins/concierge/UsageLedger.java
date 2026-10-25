package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.XmlFile;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Tokens and estimated cost of assistant questions, per user per month (UTC), kept in
 * {@code JENKINS_HOME/io.jenkins.plugins.concierge.UsageLedger.xml} for the last twelve months.
 *
 * <p>Only counts are stored: no questions, answers or tool results. Questions asked with a
 * person's own API key are counted separately and never count towards the instance's budget,
 * since the instance is not paying for them.
 */
public final class UsageLedger {

    private static final Logger LOGGER = Logger.getLogger(UsageLedger.class.getName());
    static final int MONTHS_KEPT = 12;

    /** One user's month. */
    public static final class Entry {
        private long questions;
        private long inputTokens;
        private long outputTokens;
        private String cost = "0";
        private long unpricedQuestions;
        private long ownKeyQuestions;

        public long getQuestions() {
            return questions;
        }

        public long getInputTokens() {
            return inputTokens;
        }

        public long getOutputTokens() {
            return outputTokens;
        }

        /** Estimated cost in US dollars of the questions whose price was known. */
        @NonNull
        public BigDecimal getCost() {
            if (cost == null) {
                return BigDecimal.ZERO;
            }
            try {
                return new BigDecimal(cost);
            } catch (NumberFormatException e) {
                // A hand-edited or damaged usage file must not stop every question.
                LOGGER.log(Level.WARNING, "Ignoring an unreadable cost in the Concierge usage file: {0}", cost);
                return BigDecimal.ZERO;
            }
        }

        public long getUnpricedQuestions() {
            return unpricedQuestions;
        }

        public long getOwnKeyQuestions() {
            return ownKeyQuestions;
        }

        void add(Entry other) {
            questions += other.questions;
            inputTokens += other.inputTokens;
            outputTokens += other.outputTokens;
            cost = getCost().add(other.getCost()).toPlainString();
            unpricedQuestions += other.unpricedQuestions;
            ownKeyQuestions += other.ownKeyQuestions;
        }
    }

    /** month ("2026-10") to user id to entry. */
    private TreeMap<String, TreeMap<String, Entry>> months = new TreeMap<>();

    private static UsageLedger instance;

    /** The ledger, loaded from disk the first time. */
    @NonNull
    static synchronized UsageLedger get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** Forgets the cached instance, so the next {@link #get()} reads the file again. For tests. */
    static synchronized void reload() {
        instance = null;
    }

    private static XmlFile file() {
        return new XmlFile(
                Jenkins.XSTREAM2, new File(Jenkins.get().getRootDir(), UsageLedger.class.getName() + ".xml"));
    }

    private static UsageLedger load() {
        XmlFile file = file();
        if (file.exists()) {
            try {
                Object read = file.read();
                if (read instanceof UsageLedger ledger) {
                    ledger.repair();
                    return ledger;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Could not read the Concierge usage ledger; starting a new one", e);
            }
        }
        return new UsageLedger();
    }

    /** A file written by hand, or by an older version, may lack the map. */
    private synchronized void repair() {
        if (months == null) {
            months = new TreeMap<>();
        }
    }

    @NonNull
    static String currentMonth() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    /**
     * Records one question.
     *
     * @param cost the estimated cost, or null when the model's price is unknown
     * @param ownKey whether the person used their own API key
     */
    synchronized void record(
            @NonNull String user, long inputTokens, long outputTokens, @CheckForNull BigDecimal cost, boolean ownKey) {
        TreeMap<String, Entry> month = months.computeIfAbsent(currentMonth(), k -> new TreeMap<>());
        Entry entry = month.computeIfAbsent(user, k -> new Entry());
        entry.questions++;
        entry.inputTokens += inputTokens;
        entry.outputTokens += outputTokens;
        if (ownKey) {
            entry.ownKeyQuestions++;
        } else if (cost == null) {
            entry.unpricedQuestions++;
        } else {
            entry.cost = entry.getCost().add(cost).toPlainString();
        }
        while (months.size() > MONTHS_KEPT) {
            months.pollFirstEntry();
        }
        try {
            file().write(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not save the Concierge usage ledger", e);
        }
    }

    /** Estimated cost so far this month, paid by the instance. */
    @NonNull
    synchronized BigDecimal instanceCostThisMonth() {
        return total(currentMonth()).getCost();
    }

    /** Each user's entry for a month, by user id. */
    @NonNull
    synchronized Map<String, Entry> month(@NonNull String month) {
        TreeMap<String, Entry> found = months.get(month);
        return found == null ? Map.of() : Map.copyOf(found);
    }

    @NonNull
    synchronized Entry total(@NonNull String month) {
        Entry total = new Entry();
        month(month).values().forEach(total::add);
        return total;
    }

    /** Months with any usage, newest first. */
    @NonNull
    synchronized List<String> months() {
        List<String> out = new ArrayList<>(months.descendingKeySet());
        return out;
    }
}
