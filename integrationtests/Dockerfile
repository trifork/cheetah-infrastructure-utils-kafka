FROM maven:3.8.6-openjdk-11 as build
WORKDIR /app
COPY pom.xml ./

# Download dependencies
RUN mvn dependency:go-offline -B -U 

# Then add source.
COPY src ./src
# Build
RUN mvn -B package --file pom.xml

FROM quay.io/strimzi/kafka:0.35.1-kafka-3.4.0

USER root:root
COPY --from=build /app/target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001