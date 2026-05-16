FROM jenkins/inbound-agent:latest-alpine-jdk21

USER root

# Install Maven
RUN apk add --no-cache maven

# Switch back to the jenkins user
USER jenkins
