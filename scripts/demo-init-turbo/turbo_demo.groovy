/*
 * Runs after seed_demo.groovy (init scripts run in name order), and only in ./scripts/demo.sh
 * --mode turbo, which installs the plugins it imports. It lives outside scripts/demo-init because
 * Groovy compiles every import, and the minimal mode has no Dashboard View or Pipeline to import.
 *
 * Adds what the extra plugins make possible:
 *   overview                     a Dashboard View with no job list, only statistics portlets: the
 *                                dashboard tour must skip its job list step there, not point at
 *                                the whole page. --dashboard-default makes it the default view.
 *   payments/checkout-pipeline   a Pipeline with stages and JUnit results, one test failing, so
 *                                the build is unstable and the assistant has test results to read.
 */
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.plugins.view.dashboard.Dashboard
import hudson.plugins.view.dashboard.builds.LatestBuilds
import hudson.plugins.view.dashboard.stats.StatBuilds
import hudson.plugins.view.dashboard.stats.StatSlaves
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob

def jenkins = Jenkins.get()

// ------------------------------------------------------------------ a dashboard without a job list

def overview = new Dashboard('overview')
overview.setIncludeRegex('.*')
overview.setIncludeStdJobList(false)
overview.getTopPortlets().add(new StatSlaves('Agent statistics'))
overview.getTopPortlets().add(new StatBuilds('Build statistics'))
overview.getBottomPortlets().add(new LatestBuilds('Latest builds', 10))
jenkins.addView(overview)
if (System.getenv('CONCIERGE_DEMO_DASHBOARD_DEFAULT') == 'true') {
    jenkins.setPrimaryView(overview)
}

// ------------------------------------------------------------------ a Pipeline with test results

def payments = jenkins.getItem('payments')
def pipeline = payments.createProject(WorkflowJob, 'checkout-pipeline')
pipeline.setDescription('Builds, tests and deploys the checkout service as a Pipeline.')
pipeline.setDefinition(new CpsFlowDefinition('''
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                echo 'Compiling checkout-service 3.4.0'
            }
        }
        stage('Test') {
            steps {
                writeFile file: 'target/surefire-reports/TEST-CheckoutIT.xml', text: """<testsuite name="CheckoutIT" tests="4" failures="1" skipped="1">
  <testcase classname="CheckoutIT" name="paysWithCard" time="1.2"/>
  <testcase classname="CheckoutIT" name="refundsOrder" time="0.8"/>
  <testcase classname="CheckoutIT" name="appliesVoucher" time="0.1"><skipped message="voucher service stubbed"/></testcase>
  <testcase classname="CheckoutIT" name="handlesGatewayTimeout" time="30.0"><failure message="expected HTTP 504 but was 200">gateway stub did not time out</failure></testcase>
</testsuite>"""
                junit 'target/surefire-reports/*.xml'
            }
        }
        stage('Deploy') {
            steps {
                echo 'Deploying checkout-service 3.4.0 to staging'
            }
        }
    }
}
''', true))
pipeline.save()
pipeline.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause('admin')))

jenkins.save()
