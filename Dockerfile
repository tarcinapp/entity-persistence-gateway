FROM openjdk:11-jre-slim AS MAVEN_BUILD
COPY pom.xml /build/
COPY src /build/src/
COPY mvnw /build/
COPY .mvn /build/
WORKDIR /build/
RUN mvnw package

FROM openjdk:11-jre-slim
ENV PORT 8080
ENV CLASSPATH /opt/lib
EXPOSE 8080
COPY --from=MAVEN_BUILD /build/target/*.jar  /opt/app.jar
WORKDIR /opt
CMD ["java", "-jar", "app.jar"]