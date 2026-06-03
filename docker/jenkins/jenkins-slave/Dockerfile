FROM jenkins/inbound-agent:latest-alpine-jdk21

USER root

# Install Maven, Git, Docker CLI, and Docker Compose plugin
RUN apk add --no-cache maven git docker docker-cli-compose bash

# Switch back to the jenkins user
USER jenkins
