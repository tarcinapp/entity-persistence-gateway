FROM openjdk:11-jre-slim
RUN mvnw package
COPY /target/*.jar /opt/app.jar
EXPOSE 8080
WORKDIR /opt
CMD ["java", "-jar", "app.jar"]