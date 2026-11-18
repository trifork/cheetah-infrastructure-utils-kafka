# General Upgrade Guide

General steps for upgrading Strimzi Kafka and Strimzi Kafka Operator on cluster

- Refer to the "Supported versions" schema here <https://strimzi.io/downloads/>
- Upgrade to the latest version of Kafka that the operator supports (we disregarded the Strimzi and Trifork versions of the cheetah-infrastrucure-utils-kafka image and looked only at the Kafka version)
- Upgrade the operator to the latest version that still supports the new version of Kafka and update the crds. You can update the crds by following these steps:
  - Download <https://github.com/strimzi/strimzi-kafka-operator/releases/download/${operator_version}/strimzi-kafka-operator-helm-3-chart-${operator_version}.tgz> (change `${operator_version}` to the version you are upgrading to)
  - Extract the content of the crds folder to the Kafka operator's crds folder
  - Update kustomization.yaml in the crds folder by running `kustomize create --autodetect --recursive` or update it manually to include all crds
- Repeat until the newest version of both Kafka and the operator is running

## Testing that the upgrade was a success

- Check that all Kafka brokers on the cluster are running the new version. You can see the version under `metadata.annotations.strimzi.io/kafka-version` or by checking the image under `spec.containers.image`
- Check that Redpanda is running and showing the correct topics
- Check that Flink jobs (or other services) that use Kafka are back and running
