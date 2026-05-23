FROM eclipse-temurin:21-jre

WORKDIR /opt/api-gateway

# Create config directory
RUN mkdir /config

# Copy the application jar
COPY app/common/target/api-gateway.jar app.jar

# The config files will be mounted at runtime from the host node.

EXPOSE 8080

ENTRYPOINT ["java", "-Dlogging.config=/config/logback.xml", "-jar", "/opt/api-gateway/app.jar"]
