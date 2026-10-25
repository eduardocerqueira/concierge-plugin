package io.jenkins.plugins.concierge;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * The assistant's settings, on the system configuration page and so only for administrators.
 *
 * <p>Off by default, and when on, nothing beyond job names, statuses and build metadata is sent
 * until an administrator opts in to each kind of data (docs/security-design.md, rule 4).
 */
@Extension
@Symbol("conciergeAssistant")
public class AssistantConfiguration extends GlobalConfiguration {

    static final int DEFAULT_LOG_LINES = 200;
    static final int MAX_LOG_LINES = 2000;
    static final int DEFAULT_REQUESTS_PER_HOUR = 30;
    static final int DEFAULT_MAX_TOOL_CALLS = 8;

    private boolean enabled;
    private Provider provider = Provider.ANTHROPIC;
    private String model;
    private String credentialsId;
    private String baseUrl;
    private boolean allowDeepSeek;
    private boolean shareLogs;
    private boolean shareConfiguration;
    private boolean shareScm;
    private int maxLogLines = DEFAULT_LOG_LINES;
    private int requestsPerHour = DEFAULT_REQUESTS_PER_HOUR;
    private int maxToolCalls = DEFAULT_MAX_TOOL_CALLS;
    private boolean allowUserKeys;
    private boolean allowActions;
    private String actionJobs;
    private String customModel;
    private String modelPrices;

    public AssistantConfiguration() {
        load();
    }

    @NonNull
    public static AssistantConfiguration get() {
        return GlobalConfiguration.all().getInstance(AssistantConfiguration.class);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        // Unchecked boxes are absent from the submission; reset them so they can be turned off.
        enabled = false;
        allowDeepSeek = false;
        shareLogs = false;
        shareConfiguration = false;
        shareScm = false;
        allowUserKeys = false;
        allowActions = false;
        req.bindJSON(this, json);
        FormValidation problem = validate();
        if (enabled && problem.kind == FormValidation.Kind.ERROR) {
            // Put back what was saved, so a rejected form changes nothing.
            load();
            throw new FormException(problem.getMessage(), "provider");
        }
        save();
        return true;
    }

    /** Why the current settings cannot be used, or OK. */
    @NonNull
    FormValidation validate() {
        return validate(getProvider(), getModel(), getCredentialsId(), getBaseUrl(), allowDeepSeek);
    }

    @NonNull
    static FormValidation validate(
            @NonNull Provider provider,
            @NonNull String model,
            @CheckForNull String credentialsId,
            @NonNull String baseUrl,
            boolean allowDeepSeek) {
        if (provider == Provider.DEEPSEEK && !allowDeepSeek) {
            return FormValidation.error(Messages.Assistant_deepSeekNotAllowed());
        }
        if (model.isBlank()) {
            return FormValidation.error(Messages.Assistant_modelRequired());
        }
        if (provider.isKeyRequired() && (credentialsId == null || credentialsId.isBlank())) {
            return FormValidation.error(Messages.Assistant_keyRequired());
        }
        String url = baseUrl.isBlank() ? provider.getDefaultBaseUrl() : baseUrl;
        if (url.isBlank()) {
            return FormValidation.error(Messages.Assistant_baseUrlRequired());
        }
        return checkUrl(url);
    }

    @NonNull
    static FormValidation checkUrl(@NonNull String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) {
                return FormValidation.error(Messages.Assistant_baseUrlScheme());
            }
            if (uri.getHost() == null || uri.getUserInfo() != null) {
                return FormValidation.error(Messages.Assistant_baseUrlInvalid());
            }
            if (scheme.equals("http") && !isLocalHost(uri.getHost())) {
                return FormValidation.warning(Messages.Assistant_baseUrlPlainHttp());
            }
            return FormValidation.ok();
        } catch (URISyntaxException e) {
            return FormValidation.error(Messages.Assistant_baseUrlInvalid());
        }
    }

    private static boolean isLocalHost(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    // ------------------------------------------------------------------ getters and setters

    public boolean isEnabled() {
        return enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @NonNull
    public Provider getProvider() {
        return provider == null ? Provider.ANTHROPIC : provider;
    }

    @DataBoundSetter
    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    /**
     * The model in use: a custom id when one is set, otherwise the one picked from the provider's
     * list, otherwise the provider's default where it has one.
     */
    @NonNull
    public String getModel() {
        if (customModel != null && !customModel.isBlank()) {
            return customModel;
        }
        if (model == null || model.isBlank()) {
            return getProvider().getDefaultModel();
        }
        return model;
    }

    /** The model picked from the list, as stored. For the settings form. */
    @NonNull
    public String getSelectedModel() {
        return model == null ? "" : model;
    }

    @DataBoundSetter
    public void setSelectedModel(String selectedModel) {
        setModel(selectedModel);
    }

    /** A model id typed by hand, for gateways, fine-tunes or models the list does not show. */
    @NonNull
    public String getCustomModel() {
        return customModel == null ? "" : customModel;
    }

    @DataBoundSetter
    public void setCustomModel(String customModel) {
        this.customModel = customModel == null || customModel.isBlank() ? null : customModel.trim();
    }

    /** Administrator prices, one model per line: {@code model-id = 2.00 / 10.00}. */
    @NonNull
    public String getModelPrices() {
        return modelPrices == null ? "" : modelPrices;
    }

    @DataBoundSetter
    public void setModelPrices(String modelPrices) {
        this.modelPrices = modelPrices == null ? null : modelPrices.trim();
    }

    /** What the configured model costs, or null when no source knows. */
    @CheckForNull
    ModelPrice getPrice() {
        return ModelPrice.find(getProvider(), getModel(), null, ModelPrice.parseOverrides(modelPrices, null));
    }

    @DataBoundSetter
    public void setModel(String model) {
        this.model = model == null ? null : model.trim();
    }

    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId == null || credentialsId.isBlank() ? null : credentialsId;
    }

    /** The configured base URL, or empty to use the provider's own endpoint. */
    @NonNull
    public String getBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    @DataBoundSetter
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? null : baseUrl.trim();
    }

    /** Where requests actually go. */
    @NonNull
    public String getEffectiveBaseUrl() {
        return getBaseUrl().isBlank() ? getProvider().getDefaultBaseUrl() : getBaseUrl();
    }

    public boolean isAllowDeepSeek() {
        return allowDeepSeek;
    }

    @DataBoundSetter
    public void setAllowDeepSeek(boolean allowDeepSeek) {
        this.allowDeepSeek = allowDeepSeek;
    }

    public boolean isShareLogs() {
        return shareLogs;
    }

    @DataBoundSetter
    public void setShareLogs(boolean shareLogs) {
        this.shareLogs = shareLogs;
    }

    public boolean isShareConfiguration() {
        return shareConfiguration;
    }

    @DataBoundSetter
    public void setShareConfiguration(boolean shareConfiguration) {
        this.shareConfiguration = shareConfiguration;
    }

    public boolean isShareScm() {
        return shareScm;
    }

    @DataBoundSetter
    public void setShareScm(boolean shareScm) {
        this.shareScm = shareScm;
    }

    public int getMaxLogLines() {
        return maxLogLines <= 0 ? DEFAULT_LOG_LINES : Math.min(maxLogLines, MAX_LOG_LINES);
    }

    @DataBoundSetter
    public void setMaxLogLines(int maxLogLines) {
        this.maxLogLines = maxLogLines;
    }

    public int getRequestsPerHour() {
        return requestsPerHour <= 0 ? DEFAULT_REQUESTS_PER_HOUR : requestsPerHour;
    }

    @DataBoundSetter
    public void setRequestsPerHour(int requestsPerHour) {
        this.requestsPerHour = requestsPerHour;
    }

    public int getMaxToolCalls() {
        return maxToolCalls <= 0 ? DEFAULT_MAX_TOOL_CALLS : Math.min(maxToolCalls, 20);
    }

    @DataBoundSetter
    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }

    public boolean isAllowUserKeys() {
        return allowUserKeys;
    }

    @DataBoundSetter
    public void setAllowUserKeys(boolean allowUserKeys) {
        this.allowUserKeys = allowUserKeys;
    }

    public boolean isAllowActions() {
        return allowActions;
    }

    @DataBoundSetter
    public void setAllowActions(boolean allowActions) {
        this.allowActions = allowActions;
    }

    /** Job full-name patterns, one per line, that the assistant may propose running. */
    @NonNull
    public String getActionJobs() {
        return actionJobs == null ? "" : actionJobs;
    }

    @DataBoundSetter
    public void setActionJobs(String actionJobs) {
        this.actionJobs = actionJobs == null ? null : actionJobs.trim();
    }

    @NonNull
    JobPatterns getActionJobPatterns() {
        return new JobPatterns(actionJobs);
    }

    /** The configured API key, looked up as SYSTEM because only administrators configure it. */
    @CheckForNull
    String lookupApiKey() {
        return lookupApiKey(credentialsId);
    }

    @CheckForNull
    static String lookupApiKey(@CheckForNull String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StringCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of()),
                CredentialsMatchers.withId(id));
        return credentials == null ? null : credentials.getSecret().getPlainText();
    }

    // ------------------------------------------------------------------ form helpers

    /**
     * The provider's models, with prices where known. Fetched with the chosen credential, so
     * admin-only and POST-only like the connection test (docs/security-design.md, rule 3).
     */
    @POST
    public ListBoxModel doFillSelectedModelItems(
            @QueryParameter String provider,
            @QueryParameter String credentialsId,
            @QueryParameter String baseUrl,
            @QueryParameter String modelPrices) {
        ListBoxModel items = new ListBoxModel();
        // The saved choice, read on the server: a fill method cannot take its own field as input.
        String current = getSelectedModel();
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            if (!current.isEmpty()) {
                items.add(current, current);
            }
            return items;
        }
        Provider p;
        try {
            p = provider == null || provider.isBlank() ? getProvider() : Provider.valueOf(provider);
        } catch (IllegalArgumentException e) {
            return items;
        }
        String url = baseUrl == null || baseUrl.isBlank() ? p.getDefaultBaseUrl() : baseUrl.trim();
        Map<String, ModelPrice> overrides = ModelPrice.parseOverrides(modelPrices, null);
        items.add(Messages.Models_choose(), "");
        boolean currentListed = current.isEmpty();
        if (url.isBlank() || checkUrl(url).kind == FormValidation.Kind.ERROR) {
            items.add(Messages.Models_noBaseUrl(), "");
        } else {
            String key = lookupApiKey(credentialsId);
            if (p.isKeyRequired() && key == null) {
                items.add(Messages.Models_noKey(), "");
            } else {
                try {
                    for (ModelCatalog.Model m : ModelCatalog.list(p, url, key)) {
                        items.add(label(p, m.id(), m.displayName(), m.livePrice(), overrides), m.id());
                        currentListed |= m.id().equals(current);
                    }
                } catch (Llm.ProviderException e) {
                    items.add(Messages.Models_failed(e.getStatus(), e.getMessage()), "");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    items.add(Messages.Models_failed(0, e.getClass().getSimpleName()), "");
                }
            }
        }
        if (!currentListed) {
            items.add(label(p, current, current + " " + Messages.Models_notListed(), null, overrides), current);
        }
        for (ListBoxModel.Option option : items) {
            option.selected = !current.isEmpty() && option.value.equals(current);
        }
        return items;
    }

    @NonNull
    static String label(
            Provider provider, String id, String displayName, ModelPrice live, Map<String, ModelPrice> overrides) {
        ModelPrice price = ModelPrice.find(provider, id, live, overrides);
        String name = displayName == null || displayName.equals(id) ? id : displayName + " (" + id + ")";
        return price == null
                ? name + " \u2014 " + Messages.Models_priceUnknown()
                : name + " \u2014 " + price.describe() + " (" + price.source() + ")";
    }

    @POST
    public FormValidation doCheckModelPrices(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        List<String> problems = new java.util.ArrayList<>();
        ModelPrice.parseOverrides(value, problems);
        return problems.isEmpty()
                ? FormValidation.ok()
                : FormValidation.error(Messages.Models_badPrices(String.join("; ", problems)));
    }

    public ListBoxModel doFillProviderItems() {
        ListBoxModel items = new ListBoxModel();
        for (Provider p : Provider.values()) {
            items.add(p.getDisplayName(), p.name());
        }
        return items;
    }

    @POST
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM2, Jenkins.get(), StringCredentials.class, List.of(), CredentialsMatchers.always())
                .includeCurrentValue(credentialsId);
    }

    @POST
    public FormValidation doCheckBaseUrl(@QueryParameter String value) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return value == null || value.isBlank() ? FormValidation.ok() : checkUrl(value.trim());
    }

    /**
     * Sends one tiny request with the submitted settings. Admin-only and POST-only: otherwise a user
     * could point a stored key at a server they control and capture it (docs/security-design.md,
     * rule 3).
     */
    @POST
    public FormValidation doTestConnection(
            @QueryParameter String provider,
            @QueryParameter String selectedModel,
            @QueryParameter String customModel,
            @QueryParameter String credentialsId,
            @QueryParameter String baseUrl,
            @QueryParameter boolean allowDeepSeek) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        String model = customModel != null && !customModel.isBlank() ? customModel : selectedModel;
        Provider p;
        try {
            p = provider == null ? null : Provider.valueOf(provider);
        } catch (IllegalArgumentException e) {
            p = null;
        }
        if (p == null) {
            return FormValidation.error(Messages.Assistant_unknownProvider());
        }
        String effectiveModel = model == null || model.isBlank() ? p.getDefaultModel() : model.trim();
        String url = baseUrl == null ? "" : baseUrl.trim();
        FormValidation problem = validate(p, effectiveModel, credentialsId, url, allowDeepSeek);
        if (problem.kind == FormValidation.Kind.ERROR) {
            return problem;
        }
        String key = lookupApiKey(credentialsId);
        if (p.isKeyRequired() && key == null) {
            return FormValidation.error(Messages.Assistant_keyNotFound());
        }
        try {
            Llm.Reply reply = new HttpLlmClient(p, url.isBlank() ? p.getDefaultBaseUrl() : url, effectiveModel, key)
                    .complete(
                            "Reply with the single word OK.",
                            List.of(new Llm.UserTurn("Connection test from the Jenkins Concierge plugin.")),
                            List.of());
            return FormValidation.ok(
                    Messages.Assistant_connectionOk(reply.text().trim()));
        } catch (Llm.ProviderException e) {
            return FormValidation.error(Messages.Assistant_connectionFailed(e.getStatus(), e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FormValidation.error(Messages.Assistant_connectionFailed(0, "interrupted"));
        } catch (Exception e) {
            return FormValidation.error(
                    Messages.Assistant_connectionFailed(0, e.getClass().getSimpleName()));
        }
    }
}
