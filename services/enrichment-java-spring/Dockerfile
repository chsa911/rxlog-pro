FROM gradle:8.10-jdk21 AS build
WORKDIR /app

# copy only build files first (better caching)
COPY build.gradle settings.gradle ./
RUN gradle --no-daemon build -x test || true

# copy sources
COPY . .

# build the runnable jar
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# optional (worker usually doesn't need host port, but harmless)
EXPOSE 8088

ENTRYPOINT ["java","-jar","/app/app.jar"]