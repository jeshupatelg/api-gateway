FROM eclipse-temurin:21-jre

WORKDIR /opt/api-gateway

COPY app/common/target/api-gateway.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/api-gateway/app.jar"]
