FROM eclipse-temurin:21-jre

WORKDIR /opt/api-gateway

# Create config directory
RUN mkdir /config

# Copy the application jar
COPY app/common/target/api-gateway.jar app.jar

# Bake configuration files into the image for portability
# The build context is the project root (.), so paths are relative to root.
COPY docker/apigw/routes.yaml /config/routes.yaml
COPY docker/apigw/security.yaml /config/security.yaml
COPY docker/apigw/logback.xml /config/logback.xml

EXPOSE 8080

ENTRYPOINT ["java", "-Dlogging.config=/config/logback.xml", "-jar", "/opt/api-gateway/app.jar"]
