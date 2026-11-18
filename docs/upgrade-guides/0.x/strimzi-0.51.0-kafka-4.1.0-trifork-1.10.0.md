# Upgrade from strimzi-0.48.0-kafka-4.1.0-trifork-1.9.0 to strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0

## Steps for upgrading Strimzi Kafka

1. **Prerequisite - Kubernetes 1.30 or newer.** Strimzi 0.51 drops support for 1.27, 1.28 and 1.29 (see <https://github.com/strimzi/strimzi-kafka-operator/releases/tag/0.51.0>). Upgrade the cluster first if needed.
2. **Prerequisite - update KafkaUser resources.** If you use KafkaUser with `type: simple` authorization, rename `.spec.authorization.acls[].operation` (singular) to `.spec.authorization.acls[].operations` (plural). The singular form is removed in Strimzi 0.49+. Do this before merging the operator bump. See <https://strimzi.io/docs/operators/0.51.0/deploying.html#con-api-conversion-v1-str>.
3. **Prerequisite - allow Kubernetes API egress from Kafka pods.** If the kafka namespace uses default-deny egress NetworkPolicies, allow 443/TCP to the Kubernetes API from both broker and controller pods (labels `strimzi.io/kind=Kafka`). Without this the first pod the operator rolls will crash-loop on startup. See "Notes for upgrading" below.
4. Update the Strimzi CRDs to 0.51.0.
5. Bump the custom Trifork Kafka image to `ghcr.io/trifork/cheetah-infrastructure-utils-kafka:strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0`.
6. Watch for rolling upgrade.

> Remember to update the CRDs for the Strimzi Operator when bumping to 0.51.0!

## Notes for upgrading

Strimzi 0.49 introduces a new v1 CRD API alongside the existing v1alpha1, v1beta1 and v1beta2 versions. The old API versions continue to work through 0.51 and are only removed in Strimzi 1.0.0 / 0.52.0, so no conversion is required at this hop. Plan to run the v1 API Conversion Tool (`bin/convert-file` in the operator image) on your custom resources before the next upgrade - that migration also folds in field renames like `KafkaUser.operations` (step 2 above) and the move of `spec.kafka.resources` onto `KafkaNodePool.spec.resources`. See <https://strimzi.io/docs/operators/0.51.0/deploying.html#assembly-api-conversion-str>.

Strimzi 0.49 switched Kafka nodes from JKS/P12 keystores to PEM certificates, wired through `KubernetesSecretConfigProvider`. Each broker/controller pod now resolves `${strimzisecrets:kafka/<pod>:...}` placeholders in its Kafka config by calling the Kubernetes API at startup. If you use default-deny egress in the kafka namespace you must allow 443/TCP egress to the API server for pods labelled `strimzi.io/kind=Kafka` (both broker and controller node pools). See the Strimzi 0.49.0 release notes: <https://github.com/strimzi/strimzi-kafka-operator/releases/tag/0.49.0>.

If you set `authorizer.class.name` in `spec.kafka.config`, remove it. `authorizer.class.name` is on Strimzi's reserved-key list and the operator warns on it; the custom `CheetahKRaftAuthorizer` is registered by the Trifork image itself.

## Tests performed by Trifork

### Documentation

- Review release notes for relevant changes and assess potential issues.

### Local test

- Update image and Java dependency versions for the cheetah-infrastructure-utils-kafka image, as some Java dependencies are provided in the Strimzi image.
- Check and assess new and removed CVEs for the image.
- Upload the image to the container registry.
- Insert the image into the development infrastructure, run some component tests, and check Redpanda etc. to ensure functionality with the image from the container registry.
- Create a pull request for the development infrastructure and verify that CI tests are successful.

### Cluster test

- Run a job using a continuous input source.
- Ensure the job runs without issues.
- Upgrade Kafka and monitor job status. We typically observed ~1-2 minutes of downtime on the jobs when they lost connection to the Kafka broker. Redpanda pods had to be restarted.
- Other applications connected to a Kafka broker may crash if they do not handle the lost connection gracefully. Redpanda-console was observed to be amongst these.
- Verify that no data was lost while the job reconnected to Kafka.
