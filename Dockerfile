FROM quay.io/strimzi/kafka:0.37.0-kafka-3.5.1

USER root:root
COPY ./target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001