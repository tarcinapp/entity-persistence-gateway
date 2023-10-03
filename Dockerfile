FROM maven:3.6.3-adoptopenjdk-11 AS builder
COPY . /workspace/
WORKDIR /workspace
RUN mvn package

FROM openjdk:11-jre-slim

COPY --from=builder /workspace/target/*.jar  /opt/app.jar
WORKDIR /opt
EXPOSE 8080
CMD ["java", "-Dlog4j2.isThreadContextMapInheritable=true", "-jar", "app.jar"]