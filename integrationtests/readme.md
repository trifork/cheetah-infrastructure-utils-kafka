# Integration test

## Prerequisites

The test needs Keycloak from [cheetah-development-infrastructure](https://github.com/trifork/cheetah-development-infrastructure), which also creates the `cheetah-infrastructure` Docker network. Start only Keycloak and its realm setup, and wait until `keycloak-setup` has exited:

```bash
# in cheetah-development-infrastructure
docker compose up -d keycloak keycloak-setup
```

Do not run the development infrastructure's Kafka at the same time: it uses the same hostname (`kafka`) and ports (9092, 9093) as the Kafka started by this test.

## Run

The following command starts kafka with the plugin installed and runs the integration tests.

```bash
cd integrationtests/
TIMEFORMAT=%S; time docker compose up --build --abort-on-container-exit kafka-testclient
```

Debugging:

```bash
docker run -it --entrypoint=/bin/bash -e KAFKA_ZOOKEEPER_CONNECT=ignored -e KAFKA_BROKER_ID=ignored quay.io/strimzi/kafka:<version>
```