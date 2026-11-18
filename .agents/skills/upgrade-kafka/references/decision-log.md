# Decision log

The log records every decision, change, test and source of one upgrade, so the guide can be generated from it and a later session can resume.

## Location

`docs/upgrade-guides/.upgrade-log/<target-tag>.md` on the upgrade branch (create the folder if needed). Do not commit it; the user decides what to commit. At the end the user decides whether it is kept or deleted.

## Rules

- Write an entry as soon as something is decided, changed, tested, found, or researched. Show it to the user when written.
- Append only. If something is corrected, add a new entry that references the old one (`supersedes: E007`); do not rewrite history.
- Entries are factual: what, why, evidence. No guesses presented as facts.
- Every claim that comes from a document has a source (see Sources below).
- The log keeps the full story, including failed attempts and fixes. The guide uses only the final state.

## File structure

```markdown
# Upgrade log: <from-tag> -> <to-tag>

## Status

- Hop type: <kafka-only | strimzi-minor | strimzi-major | image-only>
- Current phase: <0-9 and name>
- Branch: <name>
- Snapshot tags: <list>
- RC tag: <tag or none>
- Release tag: <tag or none>
- Repos in scope: <paths>

## Sources

| ID   | Source (URL or path) | Section          | Date read  | Used for                          | Readable? |
|------|----------------------|------------------|------------|-----------------------------------|-----------|
| S001 | https://...          | <section/anchor> | 2026-11-18 | <finding or decision it supports> | yes       |

## Entries

### E001 - <short title>
- Phase: <number and name>
- Type: prerequisite | change | test | decision | issue
- Date: <date>
- Description: <what was decided, changed, tested or found>
- Reason: <why>
- Evidence: <command output excerpt, file and line, log excerpt, source IDs (S001)>
- Files: <repo: path, ...> (for changes and prerequisites)
- Result: <for tests: pass | fail | unconfirmed>; <who ran it: agent | user>
- Supersedes: <entry ID, optional>
```

## Entry types

- `prerequisite`: something an operator must check or change before upgrading (a breaking change, removal or deprecation that affects, or was checked against, our config). Record "affected" or "not affected" and the evidence.
- `change`: something that was changed (in this repo, or in another repo with permission).
- `test`: something that was tested. Record whether it passed, and whether the agent ran it or the user confirmed it. Never record a test as done unless it was.
- `decision`: a choice and its reasoning (hop type, versions, split of hops, metadataVersion strategy, which pins to bump).
- `issue`: a problem found, with symptom, root cause and the fix chosen.

## Sources

Log every source you read, before using it in an entry: Strimzi docs and release notes, the Strimzi changelog, Kafka upgrade notes and KIPs, the Strimzi supported-versions matrix, files in the repos, command outputs. Use the exact URL (version-pinned where possible) and the section or anchor. If a page could not be fetched or was incomplete, log it with `Readable? no` and say what was missing, so the user knows what was not covered.
