# ---- Build ----
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Integration tests need Docker (Testcontainers), so they run locally / in CI, not in the image build.
RUN mvn -B -q -DskipTests package

# ---- Run ----
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/attendance-management-system.jar app.jar
USER app

# Cloud instances serve the API only; the ZKTeco .mdb is synced from the office PC.
ENV SYNC_ENABLED=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"

EXPOSE 9010
ENTRYPOINT ["java", "-jar", "app.jar"]
