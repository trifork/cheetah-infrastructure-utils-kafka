# Upgrade from strimzi-0.39.0-kafka-3.6.1-trifork-1.5.0 to strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0

Jira ticket: [KAMSTRUPKUB-1743](https://jira.netic.dk/browse/KAMSTRUPKUB-1743)

## Steps for upgrading Strimzi Kafka

- Strimzi Operator (0.39.0) -> (0.40.0) IMPORTANT: SET HELM RELEASE `spec.values.featureGates: "-UseKRaft"`. SEE NOTES FOR EXPLANATION
- Strimzi Kafka (strimzi-0.39.0-kafka-3.6.1-trifork-1.7.0) -> (strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0)

**Remember to update the CRDs for the Strimzi Operator. This is only necessary to do after the last upgrade since it simply extends the functionality of the operator**

## Notes for upgrading

- Strimzi 0.40.0 has moved the new KRaft consensus algorithm for leader election into beta mode which means it is the new default. Since the Data Platform is currently moving into production, we would like to avoid big changes so switching from Zookeeper to KRaft will be done later. KRaft mode can be disabled by settings `spec.values.featureGates: "-UseKRaft"` on the operator helm release. The "-" (minus) in from of the feature gate UseKRaft means that the feature gate is disabled as opposed to "+UseKRaft" which would enable the UseKRaft feature gate
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
