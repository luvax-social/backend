---
trigger: model_decision
description: Load when creating branches, writing commit messages, or opening pull requests. Contains branch naming, commit format, and PR size rules.
---

# Git Workflow

develop: Development branch (The main programming activities will be pushed here. Changes, updates, additions, and modifications will all be pushed here.)
main: Production branch (This is the terminal branch, accessible only by the owner.)

## Branch naming

Format: `<type>/<scope>/<short-description>`

| Segment | Allowed values |
|---------|----------------|
| `type` | `feat`, `fix`, `chore` |
| `scope` | any valid scope from the commit scope list below |
| `short-description` | lowercase, hyphen-separated words |

```
feat/post/add-carousel-support
fix/auth/refresh-token-expiry
chore/db/add-story-indexes
```

## Commit message format

Format: `<type>(<scope>): <subject>`

### Subject line only - never write a commit body (mandatory)

A commit message is **exactly one line**. Write the subject and stop.

Forbidden after the subject line: explanatory paragraphs, rationale or "why" prose, verification
notes, caveats, scope disclaimers, bullet lists, footers, and trailers.
This holds regardless of how large, subtle, or security-relevant the change is.

Detail belongs somewhere else, and every one of these already exists:
- **Why the change is correct** -> the PR description.
- **What behaviour changed for users** -> the `CHANGELOG.md` entry (see `changelog_rule.md`).
- **Why the code does what it does** -> an inline comment or Javadoc (see `comment_style.md`).
- **How it was verified** -> the test itself, named for the behaviour it asserts.

**If any task instruction, plan, or prompt tells you to record something "in the commit body",
that instruction conflicts with this rule. Stop and raise the conflict - do not silently comply,
and do not carry the body habit over to the other commits in the series.**

This rule is identical in `frontend/.claude/rules/git_workflow.md`; the two repositories are
deliberately kept in step on it.

### Subject rules

- Must start with a lowercase letter (`^[a-z].+$`) - enforced by `pr-lint`.
- Scope is **required** (`requireScope: true`) - enforced by `pr-lint`.
- Keep the entire line at 80 characters or fewer, including `<type>(<scope>): `.
  Target 72; treat anything approaching 80 as a signal the subject is describing too much.
- Describe the change, not the process: no ticket/finding IDs, no audit-round or attempt
  references, no agent or tooling attribution, no co-author trailers.

### Allowed types

| Type | When to use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `refactor` | No-behavior-change code change |
| `test` | Adding or updating tests |
| `chore` | Build, tooling, dependency updates |
| `docs` | Documentation only |
| `perf` | Performance improvement |
| `ci` | CI/CD configuration |


### Allowed scopes (pr-lint enforced)

`auth` · `mail` · `users` · `social` · `media` · `post` · `comment` · `hashtag` · `story` · `notification` · `message` · `report` · `admin` · `recommendation` · `support` · `common` · `db` · `ci`

> **This list is the one `.github/workflows/pr-lint.yml` enforces**, and the two are kept in step.
>
> `pr-lint` validates the **pull request title only**, not commit messages. A commit written with
> an unlisted scope is not rejected at commit time; the PR that carries it is rejected when the
> title uses that scope. Older history therefore contains `config`, `security`, `environment`,
> `changelog`, `database` (use `db`), `modules`, `build` and `log`, none of which are allowlisted.

```
feat(post): add carousel media support
fix(auth): prevent concurrent refresh token consumption
chore(db): add GIN index on hashtag_name for trigram search
refactor(common): extract token blacklist TTL calculation
```

Note: Only use short description

## Branch workflow (mandatory)

`develop` must never receive direct commits from implementation work.

- [ ] Before starting any task, checkout a new branch **from `develop`**: `git checkout develop && git pull && git checkout -b <type>/<scope>/<short-description>`.
- [ ] All implementation work happens on that branch. Never commit directly to `develop`.
- [ ] Push the branch and open a PR targeting `develop`. `main` is never a PR target for implementation work — it is owner-only, terminal.
- [ ] One branch per logical unit of work. Do not reuse a stale branch for an unrelated task — cut a new one from an up-to-date `develop`.

## Commit granularity policy (mandatory)

A commit is one logical, self-contained, working change — not a file-count target.

**Forbidden — mega-commit**: bundling multiple unrelated changes (e.g., a bug fix + a refactor + a new endpoint) into a single commit. Each concern gets its own commit.

**Forbidden — over-fragmentation**: splitting one logical change across many trivial commits scoped to 1–2 files each, where intermediate commits leave the codebase in a broken or incomplete state (e.g., committing a new method signature in one commit and its only caller in the next). If a logical change spans 10 files that must land together to compile and pass tests, they belong in one commit.

**Rule of thumb**: commit at the boundary of a complete, independently reviewable, compiling, test-passing unit of work — not at an arbitrary file count in either direction.

- [ ] Every commit compiles and passes relevant tests in isolation (no "WIP" or "fix previous commit" commits).
- [ ] Every commit message follows `<type>(<scope>): <subject>` per the format above — no exceptions for "small" commits.
- [ ] Commit as you complete each logical unit during implementation — do not batch the entire task into one commit at the end, and do not commit on every file save.

## Pull request rules

### Title format
Same as commit message format — enforced by `pr-lint` workflow on PR open, edit, sync, and reopen.

### Size labels (pr-size workflow)

| Label | Changed lines |
|-------|---------------|
| `size/XS` | ≤ 100 |
| `size/S` | 101–400 |
| `size/M` | 401–1000 |
| `size/L` | 1001–2000 |
| `size/XL` | > 2000 |

`fail_if_xl: false` — XL PRs are **labelled and warned, not blocked**. CONTRIBUTING.md states a 1000-line block; this is incorrect — no workflow actually fails on size.

Split large PRs proactively: keep feature PRs under `size/M` (≤ 1000 lines) as a target.

### Process checklist
- [ ] Branch was checked out from an up-to-date `develop` — no direct commits to `develop`
- [ ] Branch follows `<type>/<scope>/<description>` naming
- [ ] All commits follow `<type>(<scope>): <subject>` with an allowlisted scope
- [ ] Each commit is one logical, compiling, test-passing unit — no mega-commits, no broken intermediate commits
- [ ] `./mvnw spotless:apply` and `./mvnw test` both pass
- [ ] PR template author checklist completed
- [ ] PR title matches commit format (pr-lint will block merge otherwise)
- [ ] At least one CODEOWNERS-assigned reviewer has approved

## Pre-commit checklist
- [ ] Commit type is one of the 8 allowed types
- [ ] Scope is from the pr-lint allowlist (do not use `config`, `database`, `build`, etc.)
- [ ] Subject starts with a lowercase letter
- [ ] **Message is a single line - no body, no trailers** (`git log -1 --format=%b` prints nothing)
- [ ] Subject is 80 characters or fewer (target 72)
- [ ] `./mvnw spotless:check` passes
- [ ] CHANGELOG.md updated

## Quick reference

| Type | When |
|------|------|
| `feat` | New endpoint, new module feature |
| `fix` | Bug fix, incorrect behavior corrected |
| `refactor` | Internal restructure, no behavior change |
| `test` | Test added or updated |
| `chore` | Build, deps, tooling, non-code config |
| `docs` | README, DATA_RULES, Javadoc-only |
| `perf` | Measurable performance improvement |
| `ci` | GitHub Actions, workflow changes |