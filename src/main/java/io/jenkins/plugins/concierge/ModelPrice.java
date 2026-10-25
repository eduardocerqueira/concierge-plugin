package io.jenkins.plugins.concierge;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a model costs per million tokens, and where that figure came from. The source is always
 * shown, because a price nobody can trace is worse than no price.
 */
public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion, String source) {

    private static final Logger LOGGER = Logger.getLogger(ModelPrice.class.getName());

    /** Prices shipped with the plugin, loaded once from {@code prices.json}. */
    private static volatile Bundled bundled;

    private record Bundled(String asOf, Map<String, Map<String, ModelPrice>> byProvider) {}

    /** "$2.00 in / $10.00 out per 1M tokens". */
    @NonNull
    public String describe() {
        return "$" + money(inputPerMillion) + " in / $" + money(outputPerMillion) + " out per 1M tokens";
    }

    /** The cost of a number of input and output tokens, in US dollars. */
    @NonNull
    public BigDecimal cost(long inputTokens, long outputTokens) {
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return inputPerMillion
                .multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPerMillion.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(million, 6, RoundingMode.HALF_UP);
    }

    /** A cost for display: "$12.35", or up to four decimals under a cent ("$0.0042"). */
    static String dollars(BigDecimal value) {
        if (value.signum() != 0 && value.abs().compareTo(new BigDecimal("0.01")) < 0) {
            return "$"
                    + value.setScale(4, RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString();
        }
        return "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String money(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        // Keep sub-cent prices readable: $0.10, but $0.075 rather than $0.08.
        return scaled.compareTo(value) == 0
                ? scaled.toPlainString()
                : value.stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------ lookup

    /**
     * The best price known for a model: an administrator's override first, then a price the
     * provider reported live, then the bundled list price. Null when nothing is known.
     */
    @CheckForNull
    static ModelPrice find(
            @NonNull Provider provider,
            @NonNull String model,
            @CheckForNull ModelPrice live,
            @NonNull Map<String, ModelPrice> overrides) {
        ModelPrice override = overrides.get(model);
        if (override != null) {
            return override;
        }
        if (live != null) {
            return live;
        }
        return bundled(provider, model);
    }

    @CheckForNull
    static ModelPrice bundled(@NonNull Provider provider, @NonNull String model) {
        Map<String, ModelPrice> prices = load().byProvider().get(provider.name());
        return prices == null ? null : prices.get(model);
    }

    @NonNull
    static String bundledAsOf() {
        return load().asOf();
    }

    private static Bundled load() {
        Bundled current = bundled;
        if (current == null) {
            current = read();
            bundled = current;
        }
        return current;
    }

    private static Bundled read() {
        Map<String, Map<String, ModelPrice>> byProvider = new HashMap<>();
        String asOf = "";
        try (InputStream in = ModelPrice.class.getResourceAsStream("prices.json")) {
            if (in == null) {
                return new Bundled(asOf, byProvider);
            }
            Map<String, Object> json = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            asOf = Json.string(json, "asOf", "");
            for (Map.Entry<String, Object> provider :
                    Json.object(json, "providers").entrySet()) {
                String source = Json.string(provider.getValue(), "source", "list price") + ", as of " + asOf;
                Map<String, ModelPrice> prices = new HashMap<>();
                for (Map.Entry<String, Object> model :
                        Json.object(provider.getValue(), "models").entrySet()) {
                    if (model.getValue() instanceof List<?> pair && pair.size() == 2) {
                        prices.put(model.getKey(), new ModelPrice(decimal(pair.get(0)), decimal(pair.get(1)), source));
                    }
                }
                byProvider.put(provider.getKey(), prices);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not read the bundled model prices", e);
        }
        return new Bundled(asOf, byProvider);
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    // ------------------------------------------------------------------ admin overrides

    /**
     * Parses administrator prices, one model per line: {@code model-id = 2.00 / 10.00} (input and
     * output, US dollars per 1M tokens). Lines that do not parse are reported, not guessed at.
     */
    @NonNull
    static Map<String, ModelPrice> parseOverrides(@CheckForNull String text, @CheckForNull List<String> problems) {
        Map<String, ModelPrice> out = new HashMap<>();
        if (text == null) {
            return out;
        }
        int lineNumber = 0;
        for (String raw : text.split("\\r?\\n")) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] sides = line.split("=", 2);
            String[] prices = sides.length == 2 ? sides[1].split("/", 2) : new String[0];
            try {
                if (prices.length != 2 || sides[0].isBlank()) {
                    throw new NumberFormatException();
                }
                BigDecimal input = new BigDecimal(prices[0].trim().replace("$", ""));
                BigDecimal output = new BigDecimal(prices[1].trim().replace("$", ""));
                if (input.signum() < 0 || output.signum() < 0) {
                    throw new NumberFormatException();
                }
                out.put(sides[0].trim(), new ModelPrice(input, output, "set by an administrator"));
            } catch (NumberFormatException e) {
                if (problems != null) {
                    problems.add(String.format(Locale.ROOT, "line %d: %s", lineNumber, line));
                }
            }
        }
        return out;
    }
}
