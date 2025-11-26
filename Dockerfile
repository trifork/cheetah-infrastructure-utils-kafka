FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml ./

RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn -B package

FROM quay.io/strimzi/kafka:0.49.0-kafka-4.1.0

LABEL version="strimzi-0.48.0-kafka-4.1.0-trifork-1.9.0"

USER root:root
COPY --from=build /app/target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001