# Upgrade from strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0 to strimzi-0.42.0-kafka-3.7.1-trifork-1.7.0

Jira ticket: [KAMSTRUPKUB-2380](https://jira.netic.dk/browse/KAMSTRUPKUB-2380)

## Steps for upgrading Strimzi Kafka

- Strimzi Operator (0.40.0) -> (0.42.0).
  - [ ] **REMOVE FROM HELM RELEASE:** `spec.values.featureGates: "-UseKRaft"`
- Strimzi Kafka (strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0) -> (strimzi-0.42.0-kafka-3.7.1-trifork-1.7.0)
  - [ ] Set `spec.kafka.version: 3.7.1`

**Remember to update the CRDs for the Strimzi Operator**.

## Notes for upgrading

- <https://github.com/strimzi/strimzi-kafka-operator/releases/tag/0.42.0>
  The UseKRaft feature gate moves to GA stage and is permanently enabled without the possibility to disable it.
  To use KRaft (ZooKeeper-less Apache Kafka), you still need to use the `strimzi.io/kraft`: enabled annotation on the Kafka custom resources or migrate from an existing ZooKeeper-based cluster.
- We typically observed ~1-2 minutes downtime on the Flink jobs when they lost connection to the Kafka broker due to the Kafka upgrade
- Other applications that are connected to a Kafka broker may crash if they do not handle the missing connection gracefully
  - Using the Sarama package in Go, we observed an error saying Kafka is in the middle of a leadership election

## Testing done (by Trifork)

Documentation:

- Check release notes for any relevant changes and assess possible problems

Local testing:

- Update image and java dependency versions for the cheetah-infrastructure-utils-kafka image since some Java dependencies are provided in the Strimzi image
- Check and assess new and removed CVEs for image
- Upload image to container registry
- Insert image in development infrastructure and run some component tests and check Redpanda etc. to ensure functionality with image from container registry
- Create pull request for development infrastructure and check that CI tests are successful

Cluster testing:

- Run a job using a continuous input source
- Ensure that the job is running without any issues
- Upgrade Kafka and monitor job status. We typically observed ~1-2 minutes downtime on the jobs when they lost connection to the Kafka broker.
- Other applications that are connected to a Kafka broker may crash if they do not handle the missing connection gracefully. Using the Sarama package in Go, we observed an error saying Kafka is in the middle of a leadership election
- Check that no data was lost while the job was reestablishing connection to Kafka
