# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY mini-orm-pool/pom.xml mini-orm-pool/
COPY mini-orm-core/pom.xml mini-orm-core/
COPY mini-orm-examples/pom.xml mini-orm-examples/
COPY mini-orm-pool/src mini-orm-pool/src
COPY mini-orm-core/src mini-orm-core/src
COPY mini-orm-examples/src mini-orm-examples/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -pl mini-orm-examples -am package

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/mini-orm-examples/target/mini-orm-examples-0.1.0.jar app.jar
COPY --from=build /src/mini-orm-examples/target/lib lib
USER app
EXPOSE 8204
ENTRYPOINT ["java", "-Xmx256m", "-jar", "app.jar"]
