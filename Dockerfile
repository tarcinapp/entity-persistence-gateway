FROM maven:3.6.3-adoptopenjdk-11 AS MAVEN_BUILD
COPY pom.xml /build/
COPY src /build/src/
WORKDIR /build/
RUN mvn package

FROM openjdk:11-jre-slim
ENV PORT 8080
ENV CLASSPATH /opt/lib
EXPOSE 8080
COPY --from=MAVEN_BUILD /build/target/*.jar  /opt/app.jar
WORKDIR /opt
CMD ["java", "-jar", "app.jar"]