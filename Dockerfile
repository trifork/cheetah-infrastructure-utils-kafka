FROM quay.io/strimzi/kafka:0.39.0-kafka-3.6.0

USER root:root
COPY ./target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001