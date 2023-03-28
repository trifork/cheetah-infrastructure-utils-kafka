FROM quay.io/strimzi/kafka:0.33.2-kafka-3.2.3

USER root:root
COPY ./target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001