<!--
TEMPLATE for upgrade guides. Derived from the guides in this folder.

How to use:
- Copy to <version-folder>/<target-tag>.md (e.g. 1.x/strimzi-1.0.1-kafka-4.2.0-trifork-2.1.1.md).
- Replace every <placeholder>. Delete every section marked (optional) that does not apply.
- Delete all HTML comments before finishing.

Content rules:
- Audience: people operating a Cheetah cluster. Write steps they must perform and facts they must know.
- Only the final state: describe the released target version, not failed attempts.
- No redundant information: do not repeat what the linked upstream docs already say; link to them.
- Do not include steps for triggering snapshot, release candidate or release workflows.
- "Tests performed by Trifork" lists only tests that were actually performed.
- Use the same tone as the existing guides: short imperative steps, bold lead-ins, code blocks for config.
-->

# Upgrade from <from-tag> to <to-tag>

<!-- (optional) Superseded banner. Only on a guide that has been replaced by a patch release:
> **Superseded - do not use <trifork-version>.** Use [<replacement-tag>](<replacement-tag>.md) instead, which <one-line reason>. This guide is kept for archive purposes only.
-->

<!-- One short paragraph: what moves in this hop (Strimzi / Kafka / Trifork image) and whether this is a straight rolling upgrade or involves CRD / operator changes. -->
<what-changes-in-this-hop>

<!-- (optional) Why this exact version, e.g. when a patch release replaced an earlier one. -->
**Why <to-version> and not <excluded-version>:** <reason visible to users>.

<!-- (optional) Multi-hop context: link the previous guide if this one must not be started before it. -->
Do not start this guide until you have finished [<previous-guide>](<previous-guide>.md).

Starting state should be:

- <Strimzi Cluster Operator on x.y.z.>
- <Kafka brokers running healthy on `ghcr.io/trifork/cheetah-infrastructure-utils-kafka:<from-tag>`.>
- <Any further condition, e.g. every Kafka CR reports Ready=True.>

Reference: <link to the upstream upgrade documentation for this hop>

## Steps for upgrading Strimzi Kafka

<!-- Prerequisites first. One bold lead-in per prerequisite: "**Prerequisite - <short name>.** <what to check or change, why, and what breaks otherwise>." Include a link to the source. Include a detection command if one exists. -->

**Prerequisite - <short name>.** <What to check or change. Why. What fails if skipped.> See <link>.

<!-- (optional) Before/after example for a non-obvious config change. -->

```yaml
<before / after example>
```

<!-- Numbered steps: only the actions an operator performs, in order. Each step: bold title, what to change (version, image, metadataVersion, CRDs, chart), then how to verify. Mark the rollback point and any irreversible step. -->

1. **<Step title>.** <What to change.>

   ```yaml
   <config to apply>
   ```

2. **Watch the roll and verify.** <Expected healthy state.> <Rollback point, if any.>

3. **<Finalise step, if the upgrade has a point of no return>.** <What and the consequence.>

4. **Verify.** <Final expected status.>

## Notes for upgrading

<!-- (optional) Only items that affect users and are not already covered in the steps. Examples used before: deprecations that apply only to users of a feature, client fallout during the roll, known footguns hit during testing, reserved config keys. Keep each note short and link to the source. -->

**<Note title>.** <What it is, who it affects, what to do.>

**Client fallout during the roll.** <Expected disconnection per broker and which clients need a restart.>

<!-- (optional) Further reading links. -->

## Tests performed by Trifork

<!-- List only tests that were actually performed. Do not copy bullets that were not done. -->

### Documentation

- <Release notes / upgrade docs reviewed (name which) and what was assessed.>
- <Compatibility confirmations, e.g. operator supports the Kafka version.>

### Local test

- <Image and dependency changes made, and test result (unit tests).>
- <New and removed CVEs assessed.>
- <Image uploaded to the container registry.>
- <Integration test and development-infrastructure run: what was covered (e.g. OAuth + authorizer path, Redpanda console reconnect).>
- <Pull request against the development infrastructure and CI result.>

### Cluster test

- <Job with continuous input source: ran without issues.>
- <Steps performed in order, each landing as its own PR / GitOps commit with a health check between them.>
- <Observed downtime per broker during the roll and observed client fallout.>
- <Final state verified: Kafka CR Ready, observedGeneration == generation, versions reported, pods Running.>
- <No data loss verified.>
