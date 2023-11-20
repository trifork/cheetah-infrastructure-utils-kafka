FROM quay.io/strimzi/kafka:0.35.1-kafka-3.4.0

USER root:root
COPY ./target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001