# Report Module — Data Rules

**Implementation status**: Fully implemented with report submission, moderation triage, lifecycle status transitions, and REST/OpenAPI endpoints with unit/integration coverage.

---

## Section 1: Canonical Data

| Table | Key Columns | Notes |
|-------|-------------|-------|
| `reports` | `id`, `reporter_id`, `report_type`, `report_reason`, `entity_id`, `description`, `status`, `reviewed_by`, `reviewed_at`, `resolution_note`, `escalated_by`, `escalated_at`, `escalation_reason` | One row per user-submitted content flag. Polymorphic target: `report_type` indicates whether `entity_id` references a post, comment, user, story, or message. The three escalation columns are columns rather than a join to `admin_actions`, so the queue read that shows the reason does not depend on the audit log. |

This table cannot be rebuilt from any other source if lost.

---

## Section 2: Derived Data / Cache / Projection

| Data | Location | Rebuilt From | Rebuild Trigger |
|------|----------|--------------|-----------------|
| Pending reports view | `pending_reports` (DB view, V17) | `reports` where `status = 'pending'`, ordered by `created_at ASC` | Query-time |
| Report counts per entity | Computed at query time | `COUNT(*)` from `reports` grouped by `entity_id` and `report_type` | Query-time |

---

## Section 3: Business Rules

### A. Rules Enforced by the Database

| Rule | Enforced By |
|------|-------------|
| `report_type` must be one of `'post'`, `'comment'`, `'user'`, `'story'`, `'message'` | `report_type` enum |
| `report_reason` must be one of the 8 values in `report_reason` enum | `report_reason` enum |
| `status` must be one of `'pending'`, `'reviewing'`, `'resolved'`, `'dismissed'`, `'escalated'`; defaults to `'pending'` | `report_status` enum, `DEFAULT 'pending'` |
| `escalated_by` becomes NULL if the escalating moderator deletes their account | `ON DELETE SET NULL` on `escalated_by` FK |
| `reviewed_by` becomes NULL if the reviewing moderator/admin deletes their account | `ON DELETE SET NULL` on `reviewed_by` FK |
| Deleting the reporter cascades to their submitted reports | `ON DELETE CASCADE` on `reporter_id` FK |
| `entity_id` is a raw `UUID` with no enforced FK — it is polymorphic | No referential constraint; application must validate |

### B. Rules Enforced by Application Code

| Rule | Service / Component |
|------|---------------------|
| A user may not report the same entity more than once | `ReportServiceImpl.validateDuplicateReport` — enforced via `ReportRepository.existsByReporterIdAndReportTypeAndEntityId`, with the unique index `uq_reports_reporter_type_entity` (V30) as the authoritative guard against a concurrent double-submit |

> **Why V89's index swap runs in a transaction.**
> V89 narrowed `uq_reports_reporter_type_entity` to active reports only, so a viewer can report the same target again once the previous report is closed.
> It did so as a `DROP INDEX` followed by a plain `CREATE UNIQUE INDEX` in one transaction, which reads as a violation of the project rule that index-creating migrations must build `CONCURRENTLY` and run non-transactionally.
> It is not one: holding both statements in a single transaction is what keeps the uniqueness guarantee unbroken across the swap, and `CREATE INDEX CONCURRENTLY` cannot participate in a transaction at all.
> The cost is a write lock on `reports` for the duration of the build, which was momentary at the 180 rows the table held when V89 was written.
> The reasoning was never recorded in the migration, and it can no longer be added there: editing an applied migration fails Flyway's checksum validation and the application refuses to start until an operator runs a repair.
> It is recorded here instead.
> A future swap should follow V107 rather than V89 - build the replacement `CONCURRENTLY` under a second name, then `DROP INDEX CONCURRENTLY` the original, so both guards are live in between.

| A user may not report their own content | `ReportServiceImpl.submitReport` — throws `REPORT_SELF_NOT_ALLOWED` when `reporterId` equals the entity owner |
| `entity_id` must correspond to an existing entity of the declared `report_type`; validate before insert | `ReportServiceImpl.validateEntityExists` — resolves owner via `ReportRepository.findOwnerId`; throws `REPORT_TARGET_NOT_FOUND` if absent |
| `PATCH /reports/{reportId}/status` performs exactly one transition, `pending` to `reviewing`, and records `reviewed_by` and `reviewed_at` | `ReportServiceImpl.validateTransition`, `ReportServiceImpl.updateStatus` |
| Resolving or dismissing a report is refused by `PATCH /reports/{reportId}/status` with `REPORT_INVALID_TRANSITION` | `ReportServiceImpl.validateTransition` — a terminal transition writes an `admin_actions` row, so it belongs to the admin module and giving that row a second, silent writer here is what this refusal prevents |
| Only users with `role = 'moderator'` or `role = 'admin'` may update report status | `SecurityConfig`, `ReportController` — role enforcement via Spring Security |
| A moderator or administrator may escalate an open report; the transition is `pending -> escalated` or `reviewing -> escalated`, and there is none back | `AdminServiceImpl.escalateReport` — a report that could fall into the queue it just left would defeat the point of escalating it |
| Only an administrator may resolve or dismiss an escalated report | `AdminServiceImpl.closeReport` — checked on the report's own state rather than by a path matcher, because resolve and dismiss are one shared path for every report |
| A moderator's report listing is narrowed to `pending` and `reviewing`; asking for any other status returns an empty page rather than an error | `ReportServiceImpl.listReports` — an error would confirm that rows exist behind the filter, which is the disclosure the narrowing prevents |
| `GET /reports/{reportId}` returns a report to a moderator only when its status is `pending` or `reviewing`, or when that moderator is the one that escalated it; anything else answers 404 | `ReportServiceImpl.getReport` - the listing was already narrowed to the open statuses, but the direct read was not, so every moderator could read every closed and escalated report by identifier. 404 rather than 403, because a 403 confirms the row exists, which is the disclosure the narrowing removes. An administrator is unaffected |
| The report listing's cursor is scoped per role | `CursorScope.REPORTS` and `REPORTS_MODERATOR` — filtering by role without scoping the cursor would let a moderator replay an administrator's cursor into rows its own listing never produces |
| Resolving a report with action `remove_post` or `ban_user` must be coordinated with the admin module's `admin_actions` log | Coordination delegated to `AdminServiceImpl.resolveReport` and `AdminServiceImpl.dismissReport` — the admin module is the entry point for resolution actions that carry moderation consequences |

### C. Scope Simplifications

- The application pre-check cannot close the race between two concurrent submissions; the unique index added in V30 is what actually rejects the second one, surfaced as `REPORT_DUPLICATE`.
- `entity_id` has no FK enforcement — if the reported entity is deleted before the report is reviewed, the `entity_id` will reference a non-existent row.
- No automatic escalation and no SLA on report review time. Escalation is a moderator's explicit act, not something a timer performs.
- An escalated report pushes no notification. `GET /api/v1/admin/reports/escalated/count` is the only signal one is waiting, so a dashboard that does not surface that count makes escalation a black hole: the moderator has handed the decision up and nobody is told it arrived. This is a frontend dependency, not something the backend can close on its own.
- `resolution_note` is written only by the admin close endpoints. The `resolutionNote` field on the triage request body is retained for wire compatibility and is ignored, because the one transition that endpoint still performs carries no resolution.
- There is no distinct error code for a missing resolution note. The requirement lives behind `AdminActionRequest.reason`, which is `@NotBlank`, so a close attempt without one is refused as a validation failure before any report logic runs. A dedicated code existed and was removed once it became unreachable: an error code nothing can raise is a promise the API cannot keep, and a client branching on it would be writing dead code against a response it can never receive.

---

## Section 4: Inter-Module Dependencies

| Dependency | Direction | Nature |
|------------|-----------|--------|
| `users` | inbound | `reporter_id` and `reviewed_by` reference `users.id` |
| `post` | inbound | Reports with `report_type = 'post'` target a `posts.id` via polymorphic `entity_id` |
| `comment` | inbound | Reports with `report_type = 'comment'` target a `comments.id` |
| `story` | inbound | Reports with `report_type = 'story'` target a `stories.id` |
| `message` | inbound | Reports with `report_type = 'message'` target a `messages.id` |
| `admin` | outbound | Resolved reports reference `admin_actions.report_id` for audit trail |
| `post`, `comment`, `social` | outbound | Each reads `ReportedTargetService` to embed `hasReported` in its viewer state. This is the only Java-level dependency any module has on `report`, and `report` itself imports no other module: its target-existence check reaches `posts`, `comments`, `users`, `stories`, and `messages` through native SQL in `ReportTargetRepositoryImpl`, not through their services. The dependency graph around `report` is therefore acyclic |
