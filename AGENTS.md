# Agent instructions

## Skills

- `upgrade-kafka` ([.agents/skills/upgrade-kafka/SKILL.md](.agents/skills/upgrade-kafka/SKILL.md)): guides the user through upgrading Strimzi/Kafka in this image, tests it, logs decisions and generates the upgrade guide in [docs/upgrade-guides/](docs/upgrade-guides/README.md). Read it when asked to upgrade Kafka, Strimzi or the image's dependencies, or to write an upgrade guide.

## General rules

- Never commit, push, merge or trigger workflows; the user does that.
- Files in other repositories need the user's explicit permission before editing.
