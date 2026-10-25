# A throwaway Jenkins with Concierge and a seeded, secured instance. Built by scripts/demo.sh; the
# build context it needs is assembled there, not checked in.
#
# Anything under /usr/share/jenkins/ref is copied into JENKINS_HOME the first time the container
# starts, which is the supported way to preinstall plugins and a startup hook.

ARG JENKINS_IMAGE=jenkins/jenkins:lts-jdk21
FROM ${JENKINS_IMAGE}

COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY --chown=jenkins:jenkins concierge.hpi /usr/share/jenkins/ref/plugins/concierge.jpi
COPY --chown=jenkins:jenkins init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/

# No setup wizard: the startup script creates the users. The passwords are the user names, so you
# must not publish the port beyond localhost. See docs/demo.md.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
