package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerProxy;

/**
 * {@code /concierge/usage}: what the assistant has cost this month, per person, against the budget.
 * Administrators only, because it says who asked how much.
 */
public class UsagePage implements StaplerProxy {

    @Override
    public Object getTarget() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return this;
    }

    public String getDisplayName() {
        return Messages.UsagePage_DisplayName();
    }

    @NonNull
    public String getMonth() {
        return UsageLedger.currentMonth();
    }

    /** One row per person this month, most expensive first. */
    @NonNull
    public List<Row> getRows() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, UsageLedger.Entry> entry :
                UsageLedger.get().month(getMonth()).entrySet()) {
            rows.add(new Row(entry.getKey(), entry.getValue()));
        }
        rows.sort((a, b) -> b.entry().getCost().compareTo(a.entry().getCost()));
        return rows;
    }

    @NonNull
    public UsageLedger.Entry getTotal() {
        return UsageLedger.get().total(getMonth());
    }

    /** Earlier months' totals, newest first. */
    @NonNull
    public List<Row> getHistory() {
        List<Row> rows = new ArrayList<>();
        for (String month : UsageLedger.get().months()) {
            if (!month.equals(getMonth())) {
                rows.add(new Row(month, UsageLedger.get().total(month)));
            }
        }
        return rows;
    }

    @CheckForNull
    public String getBudget() {
        BigDecimal budget = AssistantConfiguration.get().getBudget();
        return budget == null ? null : ModelPrice.dollars(budget);
    }

    /** Percentage of the budget used this month, 0 to 100, or -1 without a budget. */
    public int getBudgetPercent() {
        BigDecimal budget = AssistantConfiguration.get().getBudget();
        if (budget == null) {
            return -1;
        }
        BigDecimal used = getTotal().getCost().multiply(BigDecimal.valueOf(100));
        return Math.min(100, used.divide(budget, 0, java.math.RoundingMode.DOWN).intValue());
    }

    @CheckForNull
    public String getPrice() {
        ModelPrice price = AssistantConfiguration.get().getPrice();
        return price == null ? null : price.describe() + " (" + price.source() + ")";
    }

    @NonNull
    public String getModel() {
        return AssistantConfiguration.get().getProvider().getDisplayName() + " / "
                + AssistantConfiguration.get().getModel();
    }

    /** A user (or month) and its counts. */
    public record Row(String name, UsageLedger.Entry entry) {
        public String getName() {
            return name;
        }

        public UsageLedger.Entry getEntry() {
            return entry;
        }

        /** "$1.23". */
        public String getCost() {
            return ModelPrice.dollars(entry.getCost());
        }
    }

    /** "$1.23", for the page's total. */
    public String money(BigDecimal value) {
        return ModelPrice.dollars(value);
    }
}
