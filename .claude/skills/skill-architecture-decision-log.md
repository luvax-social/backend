---
trigger: model_decision
description: Load whenever a project-level architecture decision is made and confirmed by the task owner, or whenever you are about to ask the task owner to decide something architectural that may already have been settled. Owns the process for docs/modules/DECISIONS.md — the running decision log.
---

# Skill: Architecture Decision Log

## When to use

- (a) Whenever a project-level architecture decision is made and confirmed by the task owner during a session.
- (b) Whenever the agent is about to make, or ask the task owner about, a decision that touches architecture and might already have been settled in an earlier session.

This skill owns the *process* — where decisions get recorded and when. The decisions themselves live in `docs/modules/DECISIONS.md`, not in this file. `docs/modules/DECISIONS.md` is the natural sibling location to the other cross-cutting, non-per-module documents already in this tree (`docs/modules/GLOBAL_RULES.md`, `docs/modules/OPENAPI_GUIDE.md`) — those live at `docs/modules/`, not `docs/` root, and a decision log covering multiple modules follows the same placement.

## Entry format

Every entry in `docs/modules/DECISIONS.md` uses this exact structure:

```
## DEC-<NNN>: <short title>
**Date**: <date>
**Context**: <what prompted the decision>
**Decision**: <the exact decision, stated unambiguously>
**Alternatives considered**: <...>
**Scope of applicability**: <which modules/features this governs>
**Status**: Active | Superseded by DEC-<NNN>
```

Number entries sequentially. Never reuse a `DEC-NNN` number, even for a superseded entry — supersession is recorded via `Status: Superseded by DEC-<NNN>` on the old entry, not by deleting or renumbering it.

## Check before asking

Before asking the task owner to re-decide something architectural, check this log first for an existing, still-`Active` entry covering the same question. Re-litigating a settled decision wastes the task owner's time and risks landing on a different answer than the one already in use elsewhere in the codebase. If an `Active` entry covers the question, apply it and cite the `DEC-NNN` id rather than asking again. If the entry is `Superseded`, follow the superseding entry instead.

## Recording a new decision

Appending a new confirmed architecture decision to `docs/modules/DECISIONS.md` is the final step of any task that produced one — not optional, in the same spirit as the shared `CHANGELOG_RULE.md`'s requirement to update `CHANGELOG.md`. A decision confirmed during a task but never logged is a decision that will be re-asked and possibly re-answered differently in the next session.

## Initial seed content

`docs/modules/DECISIONS.md` does not exist yet in this repository as of this skill's authoring. The two decisions below are already known from prior sessions and must be the first two entries written into that file **the first time it is created** — do not create the file as part of authoring this skill; create it (seeded with exactly these two entries) when the next task that needs it runs, per this skill's own "recording a new decision" step.

```
## DEC-001: Comment-module live delivery transport
**Date**: (carry forward the date from the session that made this decision; not independently verifiable from this repository's current files — the comment module is scaffolding-only with no Service/Controller/Repository implemented yet)
**Context**: Live delivery of comment activity (new comments, replies, likes) needed a transport decision before implementation of the comment module begins.
**Decision**: Comment-module live delivery uses WebSocket, not SSE. The WebSocket contract includes `watch`, `unwatch`, and `heartbeat` handlers.
**Alternatives considered**: Server-Sent Events (SSE) — not chosen; no further detail available beyond the decision itself.
**Scope of applicability**: `comment` module only. Does not apply to `notification` or `message`, which are documented elsewhere (`docs/modules/GLOBAL_RULES.md`, `docs/modules/notification/DATA_RULES.md`, `docs/modules/message/DATA_RULES.md`) as polling-only with no real-time WebSocket delivery in v1 — that is a separate, unrelated decision for different modules and does not conflict with this one.
**Status**: Active

## DEC-002: Purpose split between comment_write_idempotency and processed_messages
**Date**: (carry forward the date from the session that made this decision; not independently verifiable from this repository's current files — no `comment_write_idempotency` table exists yet in any Flyway migration)
**Context**: The comment module's write path needs deduplication for HTTP client-side retries (e.g., a client retrying a POST after a timeout without knowing whether the first request succeeded). A deduplication mechanism, `processed_messages`, already exists but serves a different consumer.
**Decision**: The comment module gets its own idempotency table, `comment_write_idempotency`, introduced via a Flyway migration, keyed by an idempotency key header on HTTP write requests. This is distinct in purpose from `processed_messages` (`common/inbox/`), which exists specifically for RabbitMQ consumer inbox deduplication (guarding `ProcessedMessageService.processOnce(...)` against duplicate message delivery). Do not conflate the two: `comment_write_idempotency` guards HTTP retries; `processed_messages` guards message-broker redelivery.
**Alternatives considered**: Reusing `processed_messages` for HTTP idempotency — not chosen, because its schema and semantics are shaped around consumer-name + event-id from the message broker, not an HTTP idempotency-key header.
**Scope of applicability**: `comment` module write endpoints (create comment, create reply, and any other mutating endpoint that adopts idempotency-key semantics).
**Status**: Active
```

## Output contract

- `docs/modules/DECISIONS.md` seeded with DEC-001 and DEC-002 verbatim as above the first time it is created
- Every new architecture decision confirmed during a task is appended before the task is considered complete
- `DEC-NNN` ids never reused; supersession recorded via `Status`, not deletion
- Before asking the task owner to re-decide something architectural, this log is checked first for a covering `Active` entry

## Checklist

- [ ] `docs/modules/DECISIONS.md` checked for an `Active` entry before asking the task owner to re-decide an architectural question
- [ ] New confirmed decision appended before the task is marked complete
- [ ] Entry uses the exact seven-field format (`DEC-NNN`, Date, Context, Decision, Alternatives considered, Scope of applicability, Status)
- [ ] `DEC-NNN` numbers never reused
- [ ] Superseded entries marked `Status: Superseded by DEC-<NNN>`, not deleted
- [ ] `docs/modules/DECISIONS.md`, when first created, is seeded with DEC-001 and DEC-002 exactly as specified in this file
