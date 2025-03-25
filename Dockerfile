FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml ./

RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn -B package

FROM quay.io/strimzi/kafka:0.45.0-kafka-3.8.0

LABEL version="strimzi-0.45.0-kafka-3.8.0-trifork-1.7.0"

USER root:root
COPY --from=build /app/target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001