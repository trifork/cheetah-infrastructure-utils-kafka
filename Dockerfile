FROM quay.io/strimzi/kafka:0.35.1-kafka-3.3.2@sha256:7d7ed4b524b1351781db9b7ca450fbac886e780745a65b879c8396359bf7f114

USER root:root
COPY ./target/cheetah-kafka-authorizer*.jar /opt/kafka/libs/cheetah-kafka-authorizer.jar
USER 1001