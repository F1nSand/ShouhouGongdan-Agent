FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend/target/aftersales-agent.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
