# Upgrade guides

Archive of upgrade guides for the `cheetah-infrastructure-utils-kafka` image.

## Folder structure

```text
upgrade-guides/
├── README.md
├── upgrade-guide-template.md   template for new guides
├── 0.x/    all guides and supporting files targeting Strimzi 0.x
└── 1.x/    all guides and supporting files targeting Strimzi 1.x
```

Each version folder is flat: current guides, superseded guides and reference files all live directly in it.

## Guides

Newest first.

| Guide | From | To | Notes |
|---|---|---|---|
| [strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1](1.x/strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1.md) | `strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0` | `strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1` | Kafka 4.1 → 4.2, no operator/CRD change. Requires Log4j2 logging config. Current. |
| [strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0](1.x/strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0.md) | `strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0` | `strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0` | Superseded by 2.1.1, do not use. |
| [strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0](1.x/strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0.md) | `strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0` | `strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0` | Strimzi 0.51 → 1.0.1, v1beta2 → v1 CRD migration. Has a [reference sheet](1.x/reference-sheet-strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0.md). |
| [strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0](0.x/strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0.md) | `strimzi-0.48.0-kafka-4.1.0-trifork-1.9.0` | `strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0` | Strimzi 0.48 → 0.51. |
| [migration-zookeeper-to-kraft](0.x/migration-zookeeper-to-kraft-strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0.md) | `strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0` | `strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0` | Migration (no version change): Zookeeper → KRaft. |
| [strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0](0.x/strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0.md) | `strimzi-0.45.0-kafka-3.8.0-trifork-1.7.0` | `strimzi-0.45.0-kafka-3.9.0-trifork-1.8.0` | Kafka 3.8 → 3.9. Last Strimzi version supporting Zookeeper. |
| [strimzi-0.45.0-kafka-3.8.0-trifork-1.7.0](0.x/strimzi-0.45.0-kafka-3.8.0-trifork-1.7.0.md) | `strimzi-0.43.0-kafka-3.8.0-trifork-1.7.0` | `strimzi-0.45.0-kafka-3.8.0-trifork-1.7.0` | Strimzi 0.43 → 0.45. |
| [strimzi-0.43.0-kafka-3.8.0-trifork-1.7.0](0.x/strimzi-0.43.0-kafka-3.8.0-trifork-1.7.0.md) | `strimzi-0.43.0-kafka-3.7.1-trifork-1.7.0` | `strimzi-0.43.0-kafka-3.8.0-trifork-1.7.0` | Kafka 3.7 → 3.8. |
| [strimzi-0.43.0-kafka-3.7.1-trifork-1.7.0](0.x/strimzi-0.43.0-kafka-3.7.1-trifork-1.7.0.md) | `strimzi-0.42.0-kafka-3.7.1-trifork-1.7.0` | `strimzi-0.43.0-kafka-3.7.1-trifork-1.7.0` | Strimzi 0.42 → 0.43. |
| [strimzi-0.42.0-kafka-3.7.1-trifork-1.7.0](0.x/strimzi-0.42.0-kafka-3.7.1-trifork-1.7.0.md) | `strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0` | `strimzi-0.42.0-kafka-3.7.1-trifork-1.7.0` | Strimzi 0.40 → 0.42. UseKRaft feature gate is now GA; remove the `-UseKRaft` override. |
| [strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0](0.x/strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0.md) | `strimzi-0.39.0-kafka-3.6.1-trifork-1.5.0` | `strimzi-0.40.0-kafka-3.7.0-trifork-1.7.0` | Strimzi 0.39 → 0.40, Kafka 3.7. Set `-UseKRaft` feature gate. |
| [strimzi-0.39.0-kafka-3.6.1-trifork-1.7.0](0.x/strimzi-0.39.0-kafka-3.6.1-trifork-1.7.0.md) | `strimzi-0.38.0-kafka-3.6.0-trifork-1.4.0` | `strimzi-0.39.0-kafka-3.6.1-trifork-1.7.0` | Strimzi 0.38 → 0.39. Fixes a critical Zookeeper CVE. |
| [strimzi-0.38.0-kafka-3.6.0-trifork-1.4.0](0.x/strimzi-0.38.0-kafka-3.6.0-trifork-1.4.0.md) | `strimzi-0.33.0-kafka-3.3.2-trifork-1.4.0` | `strimzi-0.38.0-kafka-3.6.0-trifork-1.4.0` | Strimzi 0.33 → 0.38 in five steps (via 0.35.1 and 0.37.0). |
| [general-upgrade-guide](0.x/general-upgrade-guide.md) | any | any | General procedure for upgrading Strimzi, Kafka and the CRDs, and how to check success. |
