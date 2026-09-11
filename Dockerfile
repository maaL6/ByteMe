FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -Daether.connector.requestTimeout=60000 -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/application.jar application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
