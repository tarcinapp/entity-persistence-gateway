FROM maven:3.8.4-openjdk-17 AS builder
COPY . /workspace/
WORKDIR /workspace
RUN mvn package -Dmaven.test.skip=true


FROM azul/zulu-openjdk:17-jre-headless-latest

COPY --from=builder /workspace/target/*.jar  /opt/app.jar
WORKDIR /opt
EXPOSE 8080
CMD ["java", "-Dlog4j2.isThreadContextMapInheritable=true", "-jar", "app.jar"]
