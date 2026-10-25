/*
 * Runs once, the first time the demo controller starts. The file name must be a valid Groovy class
 * name (no leading digit, no dash), because Groovy compiles the script into a class of that name.
 *
 * Seeds a small, secured instance that shows
 * what Concierge is for: three people who each see a different Jenkins.
 *
 *   admin / admin   everything
 *   alice / alice   developer on the payments team: reads and builds payments/*, uses the assistant
 *   bob   / bob     read-only on payments/*, no assistant
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
import hudson.model.FreeStyleProject
import hudson.model.Item
import hudson.model.ParametersDefinitionProperty
import hudson.model.StringParameterDefinition
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

def strategy = new ProjectMatrixAuthorizationStrategy()
strategy.add(Jenkins.ADMINISTER, PermissionEntry.user('admin'))
['alice', 'bob'].each { strategy.add(Jenkins.READ, PermissionEntry.user(it)) }
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

def payments = jenkins.createProject(Folder, 'payments')
payments.setDescription('The payments team: the API, the web front end and their tests.')
def paymentsMatrix = new FolderMatrix()
['alice', 'bob'].each { paymentsMatrix.add(Item.READ, PermissionEntry.user(it)) }
paymentsMatrix.add(Item.BUILD, PermissionEntry.user('alice'))
payments.addProperty(paymentsMatrix)

def api = freestyle(payments, 'api-build', 'Builds and tests the payments API.',
        'echo "Compiling payments-api"\necho "Running 412 tests"\necho "Tests run: 412, Failures: 0"\necho "BUILD SUCCESS"')
api.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition('BRANCH', 'main', 'Branch to build')))
api.save()

freestyle(payments, 'web-deploy', 'Deploys the payments web front end to staging.',
        'echo "Packaging web-frontend 2.14.0"\necho "Deploying to staging.payments.internal:8443"\n' +
        'echo "Using DEPLOY_TOKEN=tok_live_51HxQ2demo0secret"\necho "ERROR: connection refused (staging.payments.internal:8443)"\nexit 1')

freestyle(payments, 'nightly-tests', 'Runs the full end-to-end suite every night.',
        'echo "Tests run: 1280, Failures: 3, Skipped: 12"\necho "3 flaky tests in CheckoutFlowIT"\nexit 3', 3)

def platform = jenkins.createProject(Folder, 'platform')
platform.setDescription('Infrastructure. Visible to administrators only.')
def platformMatrix = new FolderMatrix()
platformMatrix.setInheritanceStrategy(new NonInheritingStrategy())
platformMatrix.add(Item.READ, PermissionEntry.user('admin'))
platform.addProperty(platformMatrix)
freestyle(platform, 'terraform-apply', 'Applies infrastructure changes.', 'echo "Error: state lock held"\nexit 1')
freestyle(platform, 'secrets-rotation', 'Rotates service credentials.', 'echo "rotated 14 secrets"')

[api, payments.getItem('web-deploy'), payments.getItem('nightly-tests'),
 platform.getItem('terraform-apply'), platform.getItem('secrets-rotation')].each { it.scheduleBuild2(0) }

// ------------------------------------------------------------------ concierge

def concierge = ConciergeConfiguration.get()
concierge.setWelcomeMessage('Welcome to the payments team Jenkins.\nStart with the jobs below, then take a tour. Questions: #payments-ci.')
concierge.setStartHere([
        new PinnedJob('payments/api-build', 'Our main build. If this is red, nothing ships.'),
        new PinnedJob('payments/web-deploy', 'Deploys to staging after every merge.'),
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
