FROM gradle:8.10.0-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle ./
RUN gradle --no-daemon build -x test || true
COPY . .
RUN gradle --no-daemon bootJar
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java","-jar","/app/app.jar"]
