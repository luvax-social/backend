---
trigger: always_on
description: Always active. Establishes .workspace/ as the agent working directory; the shared checklist is in the workspace root and this file adds what is specific to this repository.
---

# Workspace Rule - Backend

The shared rule is in the workspace root `.claude/rules/WORKING_DIRECTORY.md` (mirrored in `.agents/rules`).
Read that file as well; this one adds only what is specific to the backend.

## Backend additions

1. Read `.workspace/README.md` before the shared first-action checklist.
2. Besides the shared folders, `scripts/` holds one-off scripts for the current task only.
   A script a future session must be able to run belongs in the tracked `scripts/` directory at the repository root, alongside `regenerate_struct_figures.sh` and `regenerate_schema_sql.sh`.
   `STRUCT.md` once cited `.workspace/scripts/regenerate_struct_md.sh` as the source of its test table while no such file existed, which is what keeping a lasting script in `.workspace/` produces.
3. Do not write plans, reports, scripts, or intermediate files to `src/`, `docs/`, `database/` or the project root unless the file is source code, a Flyway migration, or official documentation.
4. Load `skill-workspace` for the full file-naming conventions, multi-session continuity rules, and guidance on organizing output within `.workspace/`.
