# Integration test

The following command starts kafka with the plugin installed and runs the integration tests.

```bash
docker compose up kafka-client --build --abort-on-container-exit
```

```bash
docker run -it --entrypoint=/bin/bash -e KAFKA_ZOOKEEPER_CONNECT=ignored -e KAFKA_BROKER_ID=ignored quay.io/strimzi/kafka:0.33.2-kafka-3.2.3
```