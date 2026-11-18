# Playbooks and research sources

## Hop types

Decide the hop type in phase 0, explain it, and let the user confirm.

| Hop type | Example | What changes |
|---|---|---|
| `kafka-only` | Kafka 4.1 -> 4.2 on Strimzi 1.0.1 | Image and Kafka CR `version`; metadataVersion pin and finalise; no operator or CRD change |
| `strimzi-minor` | Strimzi 0.48 -> 0.51 | Operator + CRDs + image bump; no CRD API migration |
| `strimzi-major` | Strimzi 0.51 -> 1.0.1 | CRD API migration (storage versions, CR conversion), Flux suspend, crd-upgrade Job, managedFields, operator + image bump |
| `image-only` | oauth libs, junit, base image patch | New trifork version without Strimzi/Kafka change; usually Renovate-driven |

If the target is not supported by the operator version in use, or a hop skips a required intermediate version, say so and propose splitting it into several hops (one guide per hop). Strimzi has required intermediate stops before (0.51 before 1.0.x).

## Kafka-only pattern (from the 2.1.1 guide)

- Pin `metadataVersion` to the old metadata version (for example `"4.1-IV1"`) while changing `version` and `image`, so a rollback stays possible.
- Watch the roll and verify (pods, Ready, observedGeneration == generation, clients producing/consuming). This is the rollback point.
- Finalise by raising `metadataVersion` (for example `"4.2-IV1"`). After that, downgrade is not supported.
- Verify `status.kafkaMetadataVersion`, Ready=True, no Warning conditions.

## Strimzi-major pattern (from the 2.0.0 guide)

Read `docs/upgrade-guides/1.x/strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0.md` and its reference sheet before starting such a hop. Key points: convert CRs, suspend the Flux Kustomization, run the crd-upgrade Job back-to-back with the operator/CRD bump, clear stale managedFields.

## Research sources

Fetch these every time (version-pinned URLs where possible), read the sections for the from->to range, and log each in the decision log with section and date. Notes from earlier research; verify that the URLs still work.

| Source | URL | Use |
|---|---|---|
| Strimzi supported versions matrix | https://strimzi.io/downloads/ | Which Kafka versions and Kubernetes versions each operator supports. Columns: Operators, Strimzi HTTP Bridge, Strimzi OAuth, Kafka versions, Tested Kubernetes versions |
| Strimzi upgrade docs (version pinned) | `https://strimzi.io/docs/operators/<operator-version>/deploying.html#assembly-upgrade-str` | The upgrade procedure, sequence (`#con-upgrade-sequence-str`), Kafka upgrade (`#proc-upgrade-kafka-kraft-str`), upgrade paths (`#con-upgrade-paths-str`), downgrade. The page is very large; fetch the specific section, not the whole page |
| Strimzi changelog | https://github.com/strimzi/strimzi-kafka-operator/blob/main/CHANGELOG.md | Most complete list of breaking changes. Per release, read the "Major changes, deprecations, and removals" subheading. Read every release from the target down to (excluding) the source. Search for "removed", "deprecated", "must", "before upgrading" |
| Strimzi release notes | https://github.com/strimzi/strimzi-kafka-operator/releases | Per-release notes, Kubernetes support changes, CRD warnings. The tag list may be JavaScript-rendered; open `.../releases/tag/<version>` directly |
| Kafka upgrade notes | `https://kafka.apache.org/<major><minor>/getting-started/upgrade/` (e.g. `/44/`) | Per-version "Notable changes", rolling upgrade, metadata version finalise, downgrade limits. Per-version anchors like `#upgrading-to-432` |
| Kafka downloads | https://kafka.apache.org/community/downloads/ | Supported and archived Kafka releases |
| Kafka release notes and KIPs | linked from the downloads page, and `https://cwiki.apache.org/confluence/display/KAFKA/` for KIPs | Details of breaking changes mentioned in the upgrade notes (for example KIP-1161 list-config validation, KIP-1157 principal builders, KIP-1147 CLI deprecations) |
| Strimzi OAuth releases | https://github.com/strimzi/strimzi-kafka-oauth/releases | Our authorizer depends on the strimzi-kafka-oauth libraries |
| Previous guides | `docs/upgrade-guides/` | Prerequisites, patterns and wording; see what past hops needed |

Known fetching caveats:
- `kafka.apache.org/#upgrade`, `/<ver>/documentation/#upgrade` and `kafka.apache.org/downloads` return redirect shells; use the URLs in the table.
- Fragment (`#...`) URLs point into one big page; you need the whole page, then read the section.
- The Strimzi docs URL changes per release; always pin the version.
- If a page cannot be fetched or summarised reliably, log it as not readable and ask the user to read that section or provide the text.

## What to check against our config

Always check, for the from->to range:

- Removed or deprecated Kafka CR / node pool / KafkaUser / KafkaTopic fields, and CRD API changes.
- Listener authentication and authorization types we use (custom / OAuth); the authorizer registered by the image (`CheetahKRaftAuthorizer`) and reserved config keys such as `authorizer.class.name`.
- Logging config under `spec.kafka.logging` (Log4j1 `log4j.*` keys vs Log4j2).
- List-valued Kafka configs (`ssl.cipher.suites`, `ssl.enabled.protocols`, `sasl.enabled.mechanisms`).
- Custom principal builders and Kafka CLI scripts in runbooks.
- Kubernetes minimum version, and NetworkPolicies (egress to the Kubernetes API for the Kafka pods).
- Java dependencies provided by the Strimzi image that this repo also declares in `pom.xml`.
- Client behaviour during the roll (librdkafka clients, Redpanda console).
- Changes to the integration test client or compose files in this repo and in dev-infrastructure.
