# Authorizer spike

Small, standalone Java programs that check the assumptions behind the design of `CheetahKRaftAuthorizer`: letting Kafka's own `StandardAuthorizer` decide access for OAuth users, based on ACLs translated from their JWT roles. They run against the real Kafka classes the project builds with. No broker, Docker or Kubernetes is needed.

## Run

Requires JDK 17+ and Maven on `PATH`. From this folder:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Run-Spikes.ps1
.\Run-Spikes.ps1 -Only Strategies -Threads 1
```

`-Only` accepts `Log4j`, `AclRace`, `NpeCheck` or `Strategies`. A full run takes about 3 minutes. Output is printed and saved to `results/` (ignored by Git). Two earlier runs are kept in `reference-results/`.

## What each program checks

- `AclRace.java`: PR #83's pattern (add the user's ACL to the shared `StandardAuthorizer`, authorize, remove it again) with 1 thread and with 2-8 parallel threads. Kafka updates its ACL index without locking because it expects a single writer (the metadata thread). One thread is clean; parallel threads give wrong denies, rejected duplicate ACLs and ACLs that stay behind and keep granting access.
- `AuthzSpike.java`: five strategies under the same workload (40 users with 5 ACLs each, every fifth request must be denied), with 0, 101 and 1001 stored ACLs, with and without a thread that changes stored ACLs every millisecond. It counts wrong decisions, exceptions, lost metadata updates and leftover ACLs, and measures requests per second.
  The strategies are plain Kafka (reference), PR #83 as is, (a) a lock around add/authorize/remove, (b) a private `StandardAuthorizer` per request, and (c) a private `StandardAuthorizer` per set of claim ACLs, cached until the stored ACLs change.
- `NpeCheck.java`: calls `authorizeByResourceType` on the `CheetahKRaftAuthorizer` compiled from the checked-out branch, configured the way Kafka configures it. Kafka only injects metrics into plugins that implement `Monitorable`; without them the wrapped `StandardAuthorizer` throws `NullPointerException`. This was the case before the authorizer implemented `Monitorable`; on the current code it prints a decision.
- `Log4jConfigCheck.java`: loads `log4j2-audit.properties` (with `/mnt/logs/` pointed at a local folder) and checks that lines on `kafka.authorizer.logger` go only to the audit file and other loggers only to the console.

## Limits

- No broker, network, OAuth or real JWTs. Claim ACLs are built directly, so role parsing and which permissions a role should grant are not tested here; that belongs in the unit tests.
- The strategies are minimal versions written for this spike, not production code. PR #83's own code does not compile against Kafka 4.x, so its logic is re-implemented.
- The speed runs have no logging backend, so the cost of writing audit lines (the same for every strategy) is not included.
- The workload is synthetic. Compare strategies within one run; absolute numbers varied by up to about 40% between runs on the same machine.
