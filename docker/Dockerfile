FROM jenkins/jenkins:lts-jdk21

USER root

# Install Maven, Python3, curl
RUN apt-get update && apt-get install -y \
    maven \
    python3 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Ensure workspace directory is writable
RUN mkdir -p /var/jenkins_home/workspace && chown -R jenkins:jenkins /var/jenkins_home

# Skip initial setup wizard
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# Install plugins
RUN jenkins-plugin-cli --plugins \
    workflow-aggregator \
    pipeline-stage-view \
    git \
    credentials \
    credentials-binding \
    plain-credentials

USER jenkins
