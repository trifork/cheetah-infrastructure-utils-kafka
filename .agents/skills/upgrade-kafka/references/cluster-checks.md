# Cluster checks

Read-only. Never run `apply`, `patch`, `delete`, `edit`, `rollout restart`, `exec`, `scale` or anything else that changes the cluster. The user logs in beforehand and changes the cluster through GitOps.

## Before checking

1. `kubectl config current-context`: show it to the user and ask them to confirm it is the dev cluster.
2. `kubectl get kafka -A`: discover the Kafka CR name and namespace (normally namespace `kafka`). If this fails with an authentication error, stop and ask the user to log in.

Replace `<ns>` and `<name>` below with the discovered values.

## Automated checks (agent runs, then reports)

**Kafka CR status and operator version**

```bash
kubectl -n <ns> get kafka <name> -o jsonpath='Ready={.status.conditions[?(@.type=="Ready")].status} operator={.status.operatorLastSuccessfulVersion} metadata={.status.kafkaMetadataVersion} kafkaVersion={.status.kafkaVersion} gen={.metadata.generation}/{.status.observedGeneration}{"\n"}'
kubectl -n <ns> get kafka <name> -o jsonpath='{range .status.conditions[*]}{.type}={.status} {.reason} {.message}{"\n"}{end}'
```

Expect: Ready=True, `gen` equal on both sides, operator version and Kafka version as expected, `kafkaMetadataVersion` as intended for the phase (the old version while pinned, the new one after finalising), no Warning conditions.

**Pods and rollout state**

```bash
kubectl -n <ns> get pods -l strimzi.io/cluster=<name> -o wide
kubectl -n <ns> get pods -l strimzi.io/cluster=<name> -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.phase}{"\t"}{range .status.containerStatuses[*]}{.ready}/{.restartCount}/{.image}{" "}{end}{"\n"}{end}'
kubectl -n <ns> get kafkanodepool -o wide
kubectl -n <ns> get strimzipodset
```

Expect: all broker and controller pods Running and fully Ready, restart counts low and not increasing, the image tag on every pod equals the snapshot / RC / release tag under test, replica counts as expected per node pool. Compare restart counts across two checks a minute apart if any pod restarted.

**Logs scan**

```bash
kubectl -n <ns> logs <pod> --tail=500
kubectl -n <ns> logs <pod> --previous --tail=200   # only if the pod restarted
kubectl -n <ns> get pods -l strimzi.io/kind=cluster-operator -A
kubectl -n <ns> logs deploy/strimzi-cluster-operator --tail=500
```

Look for, in broker, controller and operator logs: `ERROR`, `FATAL`, `Exception`, `CrashLoopBackOff` reasons, authorizer exceptions (for example NullPointerException in the authorizer), Log4j configuration errors, certificate or OAuth/JWKS failures, `UnsupportedVersion`, config validation errors at startup, and operator warnings on Kafka CR fields. Summarise what you found with the pod names and quote the relevant lines. Say clearly when logs are clean, and which pods and how many lines you looked at.

Also list the Flux state if it helps explain what was applied: `kubectl get kustomization,helmrelease -A`.

## Checks only the user can do (ask, and log as user-confirmed)

- Run a job with a continuous input source; confirm it runs without issues through the roll.
- Observed client disconnection per broker (about 1-2 minutes in past upgrades) and which clients needed a restart (Redpanda console, librdkafka-based clients).
- No data was lost while jobs reconnected.
- Dependent applications are healthy.

## Post-release validation (phase 9)

Ask the user to confirm each item. Do not mark any as done yourself; log the confirmation as a `test` entry with "confirmed by user".

1. The released image works: the user has used it and it behaves as expected.
2. No errors in the cluster: the user has looked, and the agent's read-only checks above (re-run after the release is applied) are clean.
3. The release is used in all the right places: this repo (`Dockerfile`, `pom.xml`), cheetah-development-infrastructure (`docker-compose/kafka.yaml`), cheetah-example-infrastructure-gitops (`infrastructure/kafka/release.yaml`, plus operator/CRDs if changed). No snapshot or release-candidate tag remains in any of them. The latest release is the one referenced everywhere.
4. The upgrade is good enough to call finished: the guide matches what was done, and no open issues remain in the log.
