# Upgrade from strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0 to strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0

> **Superseded - do not use 2.1.0.** Use [strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1](strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1.md) instead, which fixes an authorizer `NullPointerException` for idempotent producers without a write role. This guide is kept for archive purposes only.

Only the Kafka version moves, 4.1.0 → 4.2.0. Strimzi 1.0.1 already supports Kafka 4.2.0 (see <https://strimzi.io/downloads/> "Supported versions"), so this is a straight rolling upgrade of the brokers.

Starting state should be:

- Strimzi Cluster Operator on 1.0.1.
- Kafka brokers running healthy on `ghcr.io/trifork/cheetah-infrastructure-utils-kafka:strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0`.

## Steps for upgrading Strimzi Kafka

**Prerequisite - no operator or CRD change.** Do not bump the Cluster Operator or the Strimzi CRDs in this upgrade. Kafka 4.2.0 is the highest Kafka version supported by operator 1.0.1.

**Prerequisite - check your list-valued Kafka configs.** Kafka 4.2 rejects empty entries, trailing commas and duplicates in comma-separated configs at broker startup (KIP-1161). If you override `ssl.cipher.suites`, `ssl.enabled.protocols` or `sasl.enabled.mechanisms` in `spec.kafka.config`, check them.

**Prerequisite - check your logging configuration.** Kafka 4.2.0 ships log4j-core 2.25.3, which rejects legacy Log4j1 `log4j.*` keys that Kafka 4.1.0 silently ignored. Any such key under `spec.kafka.logging.loggers` aborts broker startup and causes CrashLoopBackOff.

```powershell
kubectl get kafka -A -o yaml | Select-String "log4j\."
```

Any hit must be rewritten in Log4j2 syntax before the image bump. See more here: <https://github.com/strimzi/strimzi-kafka-operator/blob/main/documentation/api/io.strimzi.api.kafka.model.kafka.KafkaClusterSpec.adoc>

For appenders: <https://logging.apache.org/log4j/2.x/manual/configuration.html>

1. **Bump Kafka, pin the metadata version.** On the Kafka CR:
   - Add `metadataVersion: "4.1-IV1"` if not already set. If left unset it follows `spec.kafka.version` automatically, which would jump the metadata to 4.2 in the same roll and remove your rollback option.
   - `version: 4.1.0` → `4.2.0`
   - `image:` → `ghcr.io/trifork/cheetah-infrastructure-utils-kafka:strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0`

   ```yaml
   spec:
     kafka:
       version: 4.2.0
       metadataVersion: "4.1-IV1"
       image: ghcr.io/trifork/cheetah-infrastructure-utils-kafka:strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0
   ```

2. **Watch the roll and verify.** All pods Running, `Ready=True`, `observedGeneration == generation`, clients producing and consuming.

   This is the rollback point - metadata is still 4.1, so reverting the upgrade still works.

3. **Finalise.** Set `metadataVersion: "4.2-IV1"`. The operator does a final roll. After this, downgrade is no longer supported.

4. **Verify.** `status.kafkaMetadataVersion` reads 4.2, `Ready=True` with no Warning conditions.

## Notes for upgrading

**CLI flag deprecations (KIP-1147).** Only relevant if you have scripts or runbooks that call the `kafka-console-producer.sh` / `kafka-console-consumer.sh` command line tools. Will be removed in a future major release.

**Custom principal builders (KIP-1157).** Only relevant if you have overlaid your own `principal.builder.class` - it must now also implement `KafkaPrincipalSerde`.

**Client fallout during the roll.** Expect ~1-2 minutes of client-side disconnection per broker. librdkafka-based clients (Go, .NET, Python, C++) may need a `kubectl rollout restart` afterwards; Redpanda-console has historically been one of these.

## Tests performed by Trifork

### Documentation

- Review the Kafka 4.2.0 release notes and assess potential issues.
- Confirm Strimzi 1.0.1 supports Kafka 4.2.0, so no operator or CRD change is needed.

### Local test

- Update the Kafka dependency and base image to 4.2.0 for the cheetah-infrastructure-utils-kafka image; unit tests pass.
- Check and assess new and removed CVEs for the image.
- Upload the image to the container registry.
- Run the repository integration test with both broker and test client on 4.2.0, covering the full OAuth + authorizer path.
- Create a pull request for the development infrastructure and verify that CI tests are successful.

### Cluster test

- Run a job using a continuous input source.
- Ensure the job runs without issues.
- Perform the guide's steps in order, each phase landing through its own PR / GitOps commit, with a health check between them.
- Monitor broker rolls; expect ~1-2 minutes of client-side disconnection per broker as usual.
- Confirm the rollback window behaves as documented: cluster runs on 4.2.0 binaries while `status.kafkaMetadataVersion` still reports 4.1.
- Restart any long-lived librdkafka-based clients that do not recover on their own.
- Verify that no data was lost while jobs reconnected to Kafka.
