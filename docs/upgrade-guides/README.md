# Upgrade guides

Archive of upgrade guides for the `cheetah-infrastructure-utils-kafka` image.

## Folder structure

```text
upgrade-guides/
├── README.md
├── 0.x/    all guides and supporting files targeting Strimzi 0.x
└── 1.x/    all guides and supporting files targeting Strimzi 1.x
```

Each version folder is flat: current guides, superseded guides and reference files all live directly in it.

## Guides

Newest first.

| Guide                                                                                         | From                                        | To                                          | Notes                                                                                                                                      |
|-----------------------------------------------------------------------------------------------|---------------------------------------------|---------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| [strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1](1.x/strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1.md)     | `strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0`   | `strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1`   | Kafka 4.1 → 4.2, no operator/CRD change. Requires Log4j2 logging config. Current.                                                          |
| [strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0](1.x/strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0.md)     | `strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0`   | `strimzi-1.0.1-kafka-4.2.0-trifork-2.1.0`   | Superseded by 2.1.1, do not use.                                                                                                           |
| [strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0](1.x/strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0.md)     | `strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0` | `strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0`   | Strimzi 0.51 → 1.0.1, v1beta2 → v1 CRD migration. Has a [reference sheet](1.x/reference-sheet-strimzi-1.0.1-kafka-4.1.0-trifork-2.0.0.md). |
| [strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0](0.x/strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0.md) | `strimzi-0.48.0-kafka-4.1.0-trifork-1.9.0`  | `strimzi-0.51.0-kafka-4.1.0-trifork-1.10.0` | Strimzi 0.48 → 0.51.                                                                                                                       |
