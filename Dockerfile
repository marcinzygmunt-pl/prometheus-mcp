# ---- build stage: fat JAR via the Gradle wrapper (Gradle 8.14, JDK 25 — Micronaut 5 baseline) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# Normalize CRLF (repo authored on Windows) and build the shadow JAR
RUN chmod +x gradlew && sed -i 's/\r$//' gradlew && ./gradlew --no-daemon clean shadowJar

# ---- runtime stage ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/*-all.jar /app/app.jar
RUN useradd -u 1001 -m appuser
USER 1001
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD bash -c ': > /dev/tcp/127.0.0.1/8080' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
