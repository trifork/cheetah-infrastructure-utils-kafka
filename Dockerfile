FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app
COPY pom.xml ./

RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn -B package

FROM quay.io/strimzi/kafka:0.40.0-kafka-3.7.0

LABEL version="strimzi-0.40.0-kafka-3.7.0-trifork-1.5.0"

USER root:root
COPY --from=build /app/target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001