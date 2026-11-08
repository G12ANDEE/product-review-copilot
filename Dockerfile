# Build stage: compile and package the app with Gradle
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew installDist --no-daemon

# Runtime stage: only the JRE and the packaged app
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/product-review-copilot /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/product-review-copilot"]
CMD ["serve"]
