FROM quay.io/strimzi/kafka:0.33.0-kafka-3.3.2

USER root:root
COPY ./target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001