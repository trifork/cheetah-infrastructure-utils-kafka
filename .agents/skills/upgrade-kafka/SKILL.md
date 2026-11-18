---
name: upgrade-kafka
description: Guide the user through upgrading Strimzi and/or Kafka in the cheetah-infrastructure-utils-kafka image, test it (local compose, then the dev cluster), keep a decision log, and generate the upgrade guide in docs/upgrade-guides/. Use when the user wants to upgrade Kafka, Strimzi, or the image's dependencies, or asks for an upgrade guide for such an upgrade.
---

# upgrade-kafka

Guides the user, step by step, through a Kafka/Strimzi upgrade of this image, from preflight to a verified release, while recording every decision so an upgrade guide can be generated at the end.

This skill is written for any coding agent. "Run a command" means use your shell tool; "fetch a URL" means use your web tool. If you lack a capability (for example web access), say so and ask the user to supply the information.

## Rules (apply to every phase)

1. **Keep the user in the loop.** Before each action say what you are about to do and why. After it, summarise the result. Show each decision-log entry to the user when you write it. Never work silently between phases.
2. **Hard stop after every phase.** Summarise findings and the log entries of the phase, then wait for the user to say go before starting the next one.
3. **Never commit, push, merge, tag, or trigger workflows.** The user does all of that. Tell the user exactly what to do (branch name, workflow name, inputs) and wait for them to confirm it is done.
4. **Editing files.** You may edit files in this repository. Files in any other repository (cheetah-development-infrastructure, cheetah-example-infrastructure-gitops) require the user's explicit permission before each change; show the intended diff first.
5. **The cluster is read-only for you.** Only run read-only `kubectl` commands (`get`, `describe`, `logs`, `top`, `config current-context`). Never apply, patch, delete, restart or exec. The user changes the cluster (via GitOps) and logs in beforehand; never run a login flow. If a read fails with an auth error, stop and tell the user to log in.
6. **Be explicit about sources.** Every document, page, release note, KIP, file or command output you rely on for a finding or decision is logged as a source (URL or path, section, date read). The user must be able to read it themselves. Never rely on memory for version-specific facts; fetch the source. If a source could not be read, log that too.
7. **Do not invent.** If something is unknown or unverified, say so and ask. Do not fill the guide with generic checklist items that were not done.
8. **Resumable.** Keep the decision log current at all times. On start, if a log for this upgrade already exists, read it and continue from the recorded phase instead of starting over.

## Files this skill uses

- [references/phases.md](references/phases.md): detailed instructions per phase. Read the section for the current phase before starting it.
- [references/decision-log.md](references/decision-log.md): the log format and rules. Read before writing the first entry.
- [references/playbooks.md](references/playbooks.md): the hop types, the research sources and what to check for each.
- [references/cluster-checks.md](references/cluster-checks.md): the read-only checks for the dev cluster and the post-release validation list.
- [../../../docs/upgrade-guides/upgrade-guide-template.md](../../../docs/upgrade-guides/upgrade-guide-template.md): the guide template.
- [../../../docs/upgrade-guides/README.md](../../../docs/upgrade-guides/README.md): the index of existing guides (and folder structure). The existing guides are the style reference.

## Workflow overview

| Phase | Name                     | Ends when                                                               |
|-------|--------------------------|-------------------------------------------------------------------------|
| 0     | Preflight and scope      | Starting state verified, hop type and versions confirmed                |
| 1     | Branch                   | User has created the upgrade branch from main in this repo              |
| 2     | Research and changes     | Prerequisites checked, changes made on the branch, integration test run |
| 3     | Snapshot                 | User has triggered the snapshot and given you the tag                   |
| 4     | Test the snapshot        | Passes in dev-infrastructure compose, then on the dev cluster           |
| 5     | Merge readiness          | Branch is up to date with main and the user has merged it               |
| 6     | Release branch           | User has created the release branch from main via the workflow          |
| 7     | Release candidate        | RC built by the user and passes the same tests                          |
| 8     | Guide                    | Summary approved by the user, guide generated, README row added         |
| 9     | Release and post-release | User created the release, related repos use it, user validated          |

Start at phase 0 (or at the phase recorded in an existing log). Follow [references/phases.md](references/phases.md) for each phase.

## Inputs to collect in phase 0

- Target Strimzi and/or Kafka version(s) (the user supplies them; you validate them).
- Paths of the sibling repositories (default: `../cheetah-development-infrastructure` and `../cheetah-example-infrastructure-gitops`). Confirm them with the user.
- Kubernetes context for the dev cluster (confirm with `kubectl config current-context`; ask the user to confirm it is the right one).

## Definition of done

The upgrade is done only when the user has confirmed every item of the post-release validation in [references/cluster-checks.md](references/cluster-checks.md). Never declare success on your own.
