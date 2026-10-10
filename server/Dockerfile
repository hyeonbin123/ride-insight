# Build the Spring Boot jar, then run it on a JRE image.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon -q dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/build/libs/*.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
