FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY order-service/settings.gradle order-service/build.gradle ./
COPY order-service/src ./src
COPY proto /proto
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre
COPY ops/http-health.sh /usr/local/bin/http-health.sh
RUN sed -i 's/\r$//' /usr/local/bin/http-health.sh && chmod +x /usr/local/bin/http-health.sh
WORKDIR /app
COPY --from=build /src/build/libs/order-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
