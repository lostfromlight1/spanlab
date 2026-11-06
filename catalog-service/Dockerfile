FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY catalog-service/settings.gradle catalog-service/build.gradle ./
COPY catalog-service/src ./src
COPY proto /proto
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
COPY ops/http-health.sh /usr/local/bin/http-health.sh
RUN sed -i 's/\r$//' /usr/local/bin/http-health.sh && chmod +x /usr/local/bin/http-health.sh
WORKDIR /app
COPY --from=build /src/build/libs/catalog-service.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
