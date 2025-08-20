# Build stage
FROM maven:3.9.8-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -e -DskipTests package

# Run stage
FROM eclipse-temurin:21-jre
ENV JAVA_OPTS=""
ENV SPRING_PROFILES_ACTIVE=docker
WORKDIR /opt/app
COPY --from=build /app/target/car-rental-*.jar /opt/app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /opt/app/app.jar"]
