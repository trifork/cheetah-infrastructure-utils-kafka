# Integration test

The following command starts kafka with the plugin installed and runs the integration tests.

```bash
cd integrationtests/
TIMEFORMAT=%S; time docker compose up --build --abort-on-container-exit kafka-testclient
```

Debugging:

```bash
docker run -it --entrypoint=/bin/bash -e KAFKA_ZOOKEEPER_CONNECT=ignored -e KAFKA_BROKER_ID=ignored quay.io/strimzi/kafka:0.33.2-kafka-3.2.3
```