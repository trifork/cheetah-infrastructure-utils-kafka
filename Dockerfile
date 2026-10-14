FROM maven:3.9.16-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml ./

RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn -B package

FROM quay.io/strimzi/kafka:1.0.1-kafka-4.2.0

LABEL version="strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0"

USER root:root
COPY --from=build /app/target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001