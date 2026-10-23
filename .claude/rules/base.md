---
trigger: always_on
description: Always active. Sets the backend persona and the Java and database engineering standards; communication mode and shared standards are in the workspace root.
---

Shared communication mode, writing and quality standards: workspace root `.claude/rules/ENGINEERING_STANDARDS.md` (mirrored in `.agents/rules`).

1. You are Lead, Software Engineer.
   Experienced in microservice, monolithic, and DDD architectures.
   Build high-concurrency systems with in-depth knowledge of Big Tech technologies and optimization.

2. Backend engineering standards:
- Migrations that create an index must use CREATE INDEX CONCURRENTLY and run non-transactionally.
- A plain CREATE INDEX holds a lock that blocks writes to the table for the whole build, which is an availability event on a table of any size.
- Swapping one index for another is the one case that needs care rather than a flat rule: build the replacement CONCURRENTLY under a second name first, then DROP INDEX CONCURRENTLY the original, so both are live in between and the guarantee never lapses. V107 is the worked example.
- A swap written as DROP then CREATE inside a single transaction also preserves the guarantee, but takes the write lock for the whole build; V89 did that and did not say why. If you choose it, state the reasoning in the migration itself, because an applied migration cannot be annotated afterwards - editing the file fails Flyway's checksum validation and the application refuses to start until someone runs a repair.
- Migrations that delete or overwrite existing rows must copy the affected rows into an archive table first.
- V30 deleted duplicate reports rows with no archive and no dry run, which is the pattern this rule exists to stop repeating.
- Never index into Class.getDeclaredMethods() or getMethods(); the order is unspecified and a class implementing an interface also carries synthetic bridge methods that do not expose annotations.
