/*
 * Runs once, the first time the demo controller starts. The file name must be a valid Groovy class
 * name (no leading digit, no dash), because Groovy compiles the script into a class of that name.
 *
 * Seeds a small, secured instance that shows
 * what Concierge is for: three people who each see a different Jenkins.
 *
 *   admin / admin   everything
 *   alice / alice   developer on the payments team: builds payments/*, uses the assistant
 *   bob   / bob     reads everything but platform, builds nothing, no assistant
 *
 * The platform folder is visible to admin only, so its jobs must never show up for alice or bob,
 * on the welcome page, in tours or in the assistant's answers.
 *
 * The assistant is configured from the CONCIERGE_* environment variables scripts/demo.sh passes in.
 * Nothing here is a recommendation for a real controller.
 */
import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.hudson.plugins.folder.properties.AuthorizationMatrixProperty as FolderMatrix
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import hudson.model.BooleanParameterDefinition
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.model.ChoiceParameterDefinition
import hudson.model.FreeStyleProject
import hudson.model.Item
import hudson.model.ParametersAction
import hudson.model.ParametersDefinitionProperty
import hudson.model.PasswordParameterDefinition
import hudson.model.StringParameterDefinition
import hudson.model.TextParameterDefinition
import hudson.security.HudsonPrivateSecurityRealm
import hudson.security.ProjectMatrixAuthorizationStrategy
import hudson.tasks.Shell
import hudson.util.Secret
import io.jenkins.plugins.concierge.AssistantConfiguration
import io.jenkins.plugins.concierge.ConciergeConfiguration
import io.jenkins.plugins.concierge.ConciergePermissions
import io.jenkins.plugins.concierge.PinnedJob
import io.jenkins.plugins.concierge.Provider
import jenkins.model.Jenkins
import org.jenkinsci.plugins.matrixauth.PermissionEntry
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl

def jenkins = Jenkins.get()
jenkins.setNumExecutors(4)
jenkins.setSystemMessage('Concierge demo controller. Throwaway data, passwords equal user names. Do not expose this port.')

// ------------------------------------------------------------------ people

def realm = new HudsonPrivateSecurityRealm(false)
['admin', 'alice', 'bob'].each { realm.createAccount(it, it) }
jenkins.setSecurityRealm(realm)

// alice and bob can read everything except the platform folder, which stops inheriting below.
def strategy = new ProjectMatrixAuthorizationStrategy()
strategy.add(Jenkins.ADMINISTER, PermissionEntry.user('admin'))
['alice', 'bob'].each {
    strategy.add(Jenkins.READ, PermissionEntry.user(it))
    strategy.add(Item.READ, PermissionEntry.user(it))
}
strategy.add(ConciergePermissions.ASSISTANT, PermissionEntry.user('alice'))
jenkins.setAuthorizationStrategy(strategy)

// ------------------------------------------------------------------ jobs

def freestyle(parent, name, description, command, unstableReturn = null) {
    def job = parent.createProject(FreeStyleProject, name)
    job.setDescription(description)
    def shell = new Shell(command)
    if (unstableReturn != null) {
        shell.setUnstableReturn(unstableReturn)
    }
    job.getBuildersList().add(shell)
    job.save()
    return job
}

// Every parameter gets a value: the override where given, the job's default otherwise.
def build(job, Map overrides = [:]) {
    def property = job.getProperty(ParametersDefinitionProperty)
    if (property == null) {
        return job.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause('admin')))
    }
    def values = property.parameterDefinitions.collect { d ->
        overrides.containsKey(d.name) ? d.createValue(String.valueOf(overrides[d.name])) : d.defaultParameterValue
    }
    return job.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause('admin')), new ParametersAction(values))
}

// payments: alice reads and builds, bob reads.
def payments = jenkins.createProject(Folder, 'payments')
payments.setDescription('The payments team: the API, the web front end, releases and their tests.')
def paymentsMatrix = new FolderMatrix()
paymentsMatrix.add(Item.BUILD, PermissionEntry.user('alice'))
payments.addProperty(paymentsMatrix)

def api = freestyle(payments, 'api-build', 'Builds and tests the payments API.',
        'echo "Compiling payments-api from $BRANCH"\necho "Running 412 tests"\necho "Tests run: 412, Failures: 0"\necho "BUILD SUCCESS"')
api.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition('BRANCH', 'main', 'Branch to build')))
api.save()

def webDeploy = freestyle(payments, 'web-deploy', 'Deploys the payments web front end to staging.',
        'echo "Packaging web-frontend 2.14.0"\necho "Deploying to staging.payments.internal:8443"\n' +
        'echo "Using DEPLOY_TOKEN=tok_live_51HxQ2demo0secret"\necho "ERROR: connection refused (staging.payments.internal:8443)"\nexit 1')

def nightly = freestyle(payments, 'nightly-tests', 'Runs the full end-to-end suite every night.',
        'echo "Tests run: 1280, Failures: 3, Skipped: 12"\necho "3 flaky tests in CheckoutFlowIT"\nexit 3', 3)

// The job with many kinds of parameter, for trying builds from the assistant: text, choices, a
// boolean, multi-line text, and a password the assistant must never set.
def release = freestyle(payments, 'release-train',
        'Releases the payments services. Production releases must run the smoke tests.',
        'echo "Releasing payments $VERSION to $ENVIRONMENT in $REGION"\n' +
        'echo "Canary: $CANARY_PERCENT% of traffic"\n' +
        'echo "Smoke tests: $RUN_SMOKE_TESTS"\n' +
        'echo "Release notes:"\necho "$RELEASE_NOTES"\n' +
        'if [ -z "$DEPLOY_TOKEN" ]; then echo "ERROR: no deploy token"; exit 1; fi\n' +
        'if [ "$ENVIRONMENT" = production ] && [ "$RUN_SMOKE_TESTS" != true ]; then\n' +
        '  echo "ERROR: production releases must run the smoke tests"; exit 1\nfi\n' +
        'echo "Release $VERSION is live in $ENVIRONMENT"')
release.addProperty(new ParametersDefinitionProperty(
        new StringParameterDefinition('VERSION', '2.15.0', 'Version to release, for example 2.15.0'),
        new ChoiceParameterDefinition('ENVIRONMENT', ['staging', 'production', 'sandbox'] as String[], 'Where to release'),
        new ChoiceParameterDefinition('REGION', ['eu-west-1', 'us-east-1', 'ap-south-1'] as String[], 'Cloud region'),
        new ChoiceParameterDefinition('CANARY_PERCENT', ['5', '10', '25', '50', '100'] as String[], 'Share of traffic sent to the new version first'),
        new BooleanParameterDefinition('RUN_SMOKE_TESTS', true, 'Run the smoke tests after deploying. Required for production.'),
        new TextParameterDefinition('RELEASE_NOTES', 'Routine release.', 'Shown in the release announcement'),
        new PasswordParameterDefinition('DEPLOY_TOKEN', Secret.fromString('tok_demo_release_0secret'), 'Token for the deploy API')))
release.save()

def checkout = freestyle(payments, 'checkout-service', 'Builds the checkout microservice.',
        'echo "Building checkout-service"\necho "Tests run: 186, Failures: 0"')

def ledger = freestyle(payments, 'ledger-reconcile', 'Reconciles the ledger against the bank feed. Takes a while.',
        'echo "Reconciling 2,481,903 ledger entries"\nsleep 1800\necho "Done"')

freestyle(payments, 'fraud-model-train', 'Retrains the fraud model. Run by hand when the data team asks.',
        'echo "Training fraud model"')

def legacy = freestyle(payments, 'legacy-batch', 'The old nightly batch. Replaced by ledger-reconcile.', 'echo "legacy"')
legacy.disable()

// platform: administrators only.
def platform = jenkins.createProject(Folder, 'platform')
platform.setDescription('Infrastructure. Visible to administrators only.')
def platformMatrix = new FolderMatrix()
platformMatrix.setInheritanceStrategy(new NonInheritingStrategy())
platformMatrix.add(Item.READ, PermissionEntry.user('admin'))
platform.addProperty(platformMatrix)
def terraform = freestyle(platform, 'terraform-apply', 'Applies infrastructure changes.', 'echo "Error: state lock held"\nexit 1')
def secrets = freestyle(platform, 'secrets-rotation', 'Rotates service credentials.', 'echo "rotated 14 secrets"')
def k8s = freestyle(platform, 'k8s-cluster-upgrade', 'Upgrades the clusters one node pool at a time.',
        'echo "3 of 4 node pools upgraded; pool gpu-a waiting for capacity"\nexit 3', 3)

// Top level: everyone.
def docs = freestyle(jenkins, 'docs-site', 'Publishes the engineering handbook.', 'echo "Published 214 pages"')
def audit = freestyle(jenkins, 'dependency-audit', 'Scans every service for vulnerable dependencies.',
        'echo "3 advisories: 1 high, 2 moderate"\nexit 3', 3)

// History, so the build pages and the assistant have something to look at. Builds of the same job
// only queue separately when their parameters differ.
build(api, [BRANCH: 'main'])
build(api, [BRANCH: 'feature/refunds'])
build(release, [VERSION: '2.14.0', ENVIRONMENT: 'production', REGION: 'eu-west-1', CANARY_PERCENT: '10',
                RELEASE_NOTES: 'Faster refunds.\nNew retry logic for card declines.'])
build(release, [VERSION: '2.15.0-rc1', ENVIRONMENT: 'staging', REGION: 'us-east-1', CANARY_PERCENT: '100'])
build(release, [VERSION: '2.15.0', ENVIRONMENT: 'production', REGION: 'us-east-1', CANARY_PERCENT: '25',
                RUN_SMOKE_TESTS: 'false', RELEASE_NOTES: 'Hotfix for the checkout timeout.'])
[webDeploy, nightly, checkout, ledger, terraform, secrets, k8s, docs, audit].each { build(it) }

// ------------------------------------------------------------------ concierge

def concierge = ConciergeConfiguration.get()
concierge.setWelcomeMessage('Welcome to the payments team Jenkins.\nStart with the jobs below, then take a tour. Questions: #payments-ci.')
concierge.setStartHere([
        new PinnedJob('payments/api-build', 'Our main build. If this is red, nothing ships.'),
        new PinnedJob('payments/web-deploy', 'Deploys to staging after every merge.'),
        new PinnedJob('payments/release-train', 'How we ship. Production needs the smoke tests.'),
        new PinnedJob('platform/terraform-apply', 'Pinned for admins; alice and bob never see this one.'),
])

def providerName = System.getenv('CONCIERGE_PROVIDER') ?: ''
if (providerName) {
    def assistant = AssistantConfiguration.get()
    def provider = Provider.valueOf(providerName)
    def key = System.getenv('CONCIERGE_API_KEY') ?: ''
    if (key) {
        SystemCredentialsProvider.getInstance().getCredentials().add(new StringCredentialsImpl(
                CredentialsScope.GLOBAL, 'concierge-ai-key', 'Concierge demo API key', Secret.fromString(key)))
        SystemCredentialsProvider.getInstance().save()
        assistant.setCredentialsId('concierge-ai-key')
    }
    assistant.setProvider(provider)
    assistant.setModel(System.getenv('CONCIERGE_MODEL') ?: '')
    assistant.setBaseUrl(System.getenv('CONCIERGE_BASE_URL') ?: '')
    assistant.setShareLogs(true)
    assistant.setShareConfiguration(true)
    assistant.setAllowActions(true)
    assistant.setActionJobs('payments/**')
    assistant.setEnabled(true)
    assistant.save()
}

jenkins.save()
