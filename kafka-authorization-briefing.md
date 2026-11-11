# Kafka authorization: briefing

## 1. How Kafka authorizes

Per request: the client sends a JWT and Strimzi OAuth validates it (authentication). `OAuthKafkaPrincipalBuilder` builds an `OAuthKafkaPrincipal` (`User:<name>` plus the JWT). The broker turns each request into `Action`s (operation + resource), e.g. produce = WRITE on TOPIC. The authorizer answers ALLOWED or DENIED per action.

`StandardAuthorizer` is a lookup in stored **ACL rows** in the KRaft metadata log:

`(principal, host, operation, resource, LITERAL|PREFIX, ALLOW|DENY)`

- Matching: any DENY wins, then any ALLOW, otherwise DENIED. READ/WRITE/DELETE/ALTER imply DESCRIBE.
- It matches on `type:name` only. `baseKafkaPrincipal()` strips the `OAuthKafkaPrincipal` subclass, so **the JWT and groups are dropped there**.
- Kafka itself never reads JWT claims.

## 2. What this repo does today

`CheetahKRaftAuthorizer` makes its own decisions from the JWT `roles` claim (`<prefix><topic>_<operation>`), with hand-written matching and implication logic (~500 lines).

- The wrapped `StandardAuthorizer` is only used for ACL management. Its `authorize()` is never called.
- It exists because roles live in Keycloak/the token and Kafka can't match on claims.
- Issues: NPE if `super.users` is unset, super users ignored for OAuth principals, only the first cluster role is checked, README and code disagree on group DESCRIBE, topic claims grant access to all groups.

## 3. Options to let Kafka decide

All options use the same pure core: **claim → ACL rows** (e.g. `Kafka_orders_read` → TOPIC READ + GROUP READ). They differ in when and where it runs.

|                      | A: per-user sync                        | B: per-role rows + wrapper                                      | C: generate in memory (PR 223)                    |
|----------------------|-----------------------------------------|-----------------------------------------------------------------|---------------------------------------------------|
| How                  | Job syncs Keycloak to `User:<sub>` rows | Rows per role (`Role:x`), plugin asks Kafka once per role claim | Plugin builds ACLs from the token at request time |
| Provisioning         | sync service                            | `Role:*` rows                                                   | none                                              |
| Revocation           | sync lag                                | token expiry                                                    | token expiry                                      |
| DENY / `super.users` | yes                                     | DENY can't veto across roles                                    | yes                                               |
| Custom code          | none                                    | small                                                           | medium                                            |

## 4. PR 223 (the implemented approach)

`ClaimAclTranslator` turns roles into ACLs for the user's own principal. A **private** `StandardAuthorizer` (stored ACLs plus role ACLs) decides, cached and dropped when stored ACLs change. The shared authorizer is never mutated.

**Pros:** no provisioning, token stays the source of truth, correct deny-wins, stored ACLs and `super.users` apply to OAuth users, audit log names the user, about 900 lines of decision code removed.

**To check before merge:**
- Cache is effectively per user + roles, rebuilt in full on every ACL change and wiped at 1000 entries. Load test with many principals.
- Claims are re-parsed and ACLs rebuilt on every `authorize` call.
- Depends on non-public Kafka classes (`StandardAuthorizer`, `StandardAcl`), so each Kafka upgrade needs a check.
- Stored ACLs are mirrored in memory, plus one copy per cached authorizer.
- Role ACLs are invisible to `kafka-acls --list`.

**Behaviour changes to confirm:**
- Topic roles no longer grant cluster permissions. Idempotent producers rely on Kafka's WRITE-on-any-topic rule.
- Leading/middle `*` patterns are dropped.
- Group access is still on all groups.
- Stored DENY ACLs and `super.users` now apply to OAuth users.

## 5. Recommendation

Take PR 223 if the number of distinct principals is in the low hundreds. If thousands are expected, settle the cache design first (load test, per-principal claim caching).
