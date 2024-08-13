FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./

# Download dependencies
RUN mvn dependency:go-offline -B -U 

# Then add source.
COPY src ./src
# Build
RUN mvn -B package --file pom.xml

FROM quay.io/strimzi/kafka:0.42.0-kafka-3.7.1

USER root:root
COPY --from=build /app/target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001