---
trigger: model_decision
description: Load when creating branches, writing commit messages, or opening pull requests. Contains the allowed scopes and the repository specific git policy; the shared rules are in the workspace root.
---

# Git Workflow - Backend

The branch naming, commit message, commit granularity and pull request rules shared with the frontend are in the workspace root `.claude/rules/GIT_WORKFLOW.md` (mirrored in `.agents/rules`).
Read that file as well; this one adds only what is specific to the backend.

develop: Development branch (The main programming activities will be pushed here. Changes, updates, additions, and modifications will all be pushed here.)
main: Production branch (This is the terminal branch. The owner may push to it directly, without a pull request.)

## Git worktrees

Never create, use or keep a git worktree for implementation work in this repository.
All work happens in the main checkout.
`.worktrees/` is git-ignored for this reason; a directory found there is leftover state from a
prior session, not a place to work from, and must be verified clean and pushed before removal.

## Allowed scopes (pr-lint enforced)

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

## Direct pushes to main

The repository owner may push to `main` without opening a pull request.
`main` has no branch protection, so nothing blocks the push; this rule records that the owner does it deliberately and does not need to tag a reviewer.
Everything else in the shared rules still applies to the commits themselves: the one-line `<type>(<scope>): <subject>` format, `./mvnw spotless:check`, `./mvnw test`, and a `CHANGELOG.md` entry.
Contributors other than the owner keep working through a branch from `develop` and a pull request targeting `develop`.
`CODEOWNERS` lists the owner as the only code owner, so a pull request the owner did not author requests the owner's review and nobody else's.

## Checklist additions

- [ ] `./mvnw spotless:apply` and `./mvnw test` both pass
- [ ] The owner, the only code owner, has approved (not needed for a pull request the owner authored)
- [ ] `./mvnw spotless:check` passes before every commit
