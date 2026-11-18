# Phases

Follow the general rules in `../SKILL.md`. After each phase: summarise, show the new log entries, and wait for the user.

Version tag format: `strimzi-<strimzi>-kafka-<kafka>-trifork-<trifork>`.

## Phase 0 - Preflight and scope

1. Ask for the target versions. Read the current versions (see "Version pins" below) and report them.
2. Scan all version pins across the three repositories and report any drift (pins that disagree with each other or with the latest released tag). Do not fix anything yet; log each drift as an `issue` entry and let the user decide.
3. Verify the starting state: the latest released tag (`git tag --sort=version:refname`), and what is deployed on the dev cluster if the user has access (read-only checks, see `cluster-checks.md`). The starting state becomes "From" in the guide.
4. Validate the target against the Strimzi supported-versions matrix (see `playbooks.md`). Decide the hop type (see `playbooks.md`) and whether the hop must be split in several. Explain the reasoning.
5. Propose the new trifork version from the history pattern and let the user confirm:
   - Kafka-only or Strimzi minor bump raises the trifork minor (1.9.0 -> 1.10.0).
   - A Strimzi major / CRD API migration raises the trifork major (1.x -> 2.0.0).
   - A fix to an already released version raises the patch (2.1.0 -> 2.1.1).
   - If ambiguous, ask.
6. Create the decision log (see `decision-log.md`) and record scope, hop type, from/to tags and the sources used.

### Version pins to scan

This repository:
- `Dockerfile`: the `FROM quay.io/strimzi/kafka:<strimzi>-kafka-<kafka>` line and `LABEL version=`.
- `pom.xml`: `<version>` and the Kafka/Strimzi dependency versions.
- `integrationtests/docker-compose.yml`: the `quay.io/strimzi/kafka` image of the test client.

`cheetah-development-infrastructure`:
- `docker-compose/kafka.yaml`: the `cheetah-infrastructure-utils-kafka` image tag and the `quay.io/strimzi/kafka` image of `kafka-setup`.

`cheetah-example-infrastructure-gitops` (`infrastructure/kafka/`):
- `release.yaml`: Kafka CR image, `version`, `metadataVersion`, apiVersion.
- `operator.yaml`: Strimzi operator chart version and env.
- CRDs and any other file that pins the Strimzi version (grep for the current Strimzi version).

Use `grep` for the current version strings to find pins not listed here, and log them.

## Phase 1 - Branch

Rule: create a branch from `main` on this repository with an appropriate name for the upgrade (for example `strimzi-<strimzi>-kafka-<kafka>`; follow existing branch naming such as `strimzi-1.0.1-kafka-4.2.0`).

1. Tell the user the branch name and the commands, ask them to create it (you must not create commits; creating a local branch is the user's action too, to keep git entirely theirs).
2. Verify with `git branch --show-current` and that it is based on current main.
3. Log a `decision` entry (branch name).

## Phase 2 - Research and changes

1. **Research.** Follow `playbooks.md` for the hop type. Fetch each listed source, read the relevant sections for the from->to range, and log each source. For every release in the range, collect breaking changes, deprecations and removals.
2. **Check against our config.** For each finding, check whether it affects us: this repo (Dockerfile, pom, authorizer code, tests, integration test config) and the cluster config in the gitops repo (Kafka CR, node pools, listeners, logging, authorization, users, topics) and the dev-infrastructure compose config. Use read-only searches in the other repos. Log each as a `prerequisite` entry: affected or not affected, with evidence (file and line).
3. Present the prerequisite list to the user. Agree on what to change.
4. **Make the changes in this repository on the branch:** Dockerfile, `pom.xml`, Java dependency versions, authorizer code, tests, integration test config. Do not commit. Log each as a `change` entry with files and reason.
5. For changes needed in other repos (dev-infrastructure, gitops), show the intended diff and ask for explicit permission before editing. Log them as `change` entries with the repo.
6. **Run tests on the branch** where possible: `mvn -B test` (or `mvn -B package`) and the integration test (`cd integrationtests && docker compose up --build --abort-on-container-exit kafka-testclient`). Check that Docker is running first; if not, ask the user to start it. Record results as `test` entries (what ran, result, who ran it).
7. Check new and removed CVEs for the image if tooling is available (trivy), otherwise ask the user to report it. Log as a `test` entry.
8. If something fails: diagnose, log an `issue` (symptom, root cause, evidence), propose a fix, ask, and repeat.

## Phase 3 - Snapshot

1. Ask the user to commit and push the branch and run the "Create Snapshot" workflow on it (workflow `create-snapshot.yml`).
2. Ask the user for the resulting image tag. Snapshot tags look like `ghcr.io/trifork/cheetah-infrastructure-utils-kafka:<version>-<branch-name>-SNAPSHOT-<run-number>`.
3. Log a `decision` entry with the snapshot tag.

## Phase 4 - Test the snapshot

Order matters: development-infrastructure first, then gitops.

**4a. cheetah-development-infrastructure (docker compose)**
1. With permission, point `docker-compose/kafka.yaml` (kafka and kafka-setup images) at the snapshot, or tell the user what to change.
2. Ask the user to run the stack (or run it yourself if Docker is available) and run `tests/kafka.sh`. Check that Kafka is healthy, the authorizer/OAuth path works, and Redpanda console sees brokers. Log results as `test` entries.

**4b. cheetah-example-infrastructure-gitops (dev cluster)**
1. Show the user the exact change for `infrastructure/kafka/release.yaml` (image tag, `version`, `metadataVersion` pin or the pattern from `playbooks.md`) and any other file. You may edit the file only with explicit permission; the user commits, merges and lets Flux apply it. Never apply anything to the cluster.
2. Ask the user to tell you when Flux has applied it. Then run the read-only checks in `cluster-checks.md` (Kafka CR status, pods and rollout, logs). Report the findings to the user.
3. Ask the user for the results of the functional checks only they can do (a job with continuous input source, no data loss, dependent apps recovered). Log them as `test` entries marked as confirmed by the user, never as run by you.
4. If a test fails: diagnose from logs, log an `issue` (symptom, root cause, evidence), propose a fix, and go back to phase 2 (new changes, then phase 3, a new snapshot). Repeat until everything passes.

## Phase 5 - Merge readiness

1. Ask the user to merge `main` into the branch. Look at what changed on main since the branch was created (`git log`, `git diff`).
2. If the changes on main are significant (touching the same files, dependencies, or the image), recommend a new snapshot and repeating phases 3 and 4. Log the decision either way.
3. When the user is satisfied, they merge the branch into main. Wait for confirmation.

## Phase 6 - Release branch

1. Ask the user to run the "Create Release Branch" workflow (`create-release-branch.yml`) with the version input `<target-tag>` (it bumps the Dockerfile label and the pom version on a `release/<target-tag>` branch and merges it into the triggering branch).
2. After the user confirms, verify (read-only) that `Dockerfile` `LABEL version` and `pom.xml` `<version>` now equal the target tag, and the `release/<target-tag>` branch exists. Log a `decision` entry.

## Phase 7 - Release candidate

1. Ask the user to run the "Create Release Candidate" workflow (`create-release-candidate.yml`) from the release branch, and to give you the resulting RC tag.
2. Repeat the tests of phase 4 with the RC tag: dev-infrastructure compose first, then the dev cluster with the read-only checks. Log all results as `test` entries.
3. If the RC fails, diagnose, log, fix on the branch and repeat from the right phase.

## Phase 8 - Guide

1. **Summarise first.** Before writing any file, present to the user a summary of exactly what will go in the guide: title (from -> to), starting state, every prerequisite, every numbered step, every note, and the list of tests under Documentation, Local test and Cluster test. Say which log entries each item comes from, and list the sources used. Apply the inclusion rule: only operator actions and visible image changes, the final state only, no workflow-trigger steps, only tests actually performed. Flag standard tests from past guides that have no log entry (CVE assessment, registry upload, dev-infrastructure PR with CI, continuous-input job, no data loss, release notes review) and ask whether they were done.
2. Wait for the user's approval or corrections. Update the log with any correction.
3. **Generate** the guide from `docs/upgrade-guides/upgrade-guide-template.md` and the approved summary:
   - File: `docs/upgrade-guides/<strimzi-major>.x/<target-tag>.md` (folders are flat: guides, superseded guides and reference files all directly in the version folder; create a new folder for a new Strimzi major line).
   - If a previous guide is replaced (patch release replacing a bad version), mark the old guide with the superseded banner from the template and update its README row.
   - If shared manifests or scripts are needed, put them in the same version folder and link them.
   - Add the row to the guides table in `docs/upgrade-guides/README.md` (columns: Guide, From, To, Notes; newest first).
   - Match the tone and structure of existing guides. No redundant information; link to upstream docs instead of copying them.
4. Show the user the generated files. Do not commit.

## Phase 9 - Release and post-release

1. Ask the user to run the "Create Release" workflow (`create-release.yml`) from the release branch. You do not run it. Ask for the released tag and verify it matches the guide's target tag. If the tag differs, update the guide.
2. Make sure the latest release is used in all the right places. Check (read-only, and with permission for any edits) and tell the user which pin still needs changing:
   - this repo: `Dockerfile` and `pom.xml` on main
   - cheetah-development-infrastructure: `docker-compose/kafka.yaml`
   - cheetah-example-infrastructure-gitops: `infrastructure/kafka/release.yaml` (and the operator/CRDs if the hop changed them): the RC or snapshot tag must be replaced with the release tag
3. After the user has merged those and Flux has applied the gitops change, run the read-only cluster checks again.
4. **Post-release validation.** Go through the checklist in `cluster-checks.md` with the user, item by item. The user validates each item; do not mark any as done yourself. If anything fails, log an `issue`, propose a fix (which may mean a patch release, phases 1-8 again with a bumped patch version, and a superseded banner on the old guide).
5. When the user has confirmed every item, record the final state in the log and tell the user the upgrade is finished. Ask whether to delete the decision log or keep it; follow the answer.
