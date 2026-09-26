---
trigger: always_on
description: Always active. Establishes .workspace/ as the agent working directory and defines the required first-action checklist for every session.
---

# Workspace Rule

## What is `.workspace/`

`.workspace/` exists at the project root. It is the agent's working directory for every task — plans, reports, scripts, progress files, and scratch work all go here. Content is not tracked by git; each developer has their own copy. Writing here does not affect the project or other developers.

## First action — every session, every task

1. Read `.workspace/README.md`.
2. Check `.workspace/progress/` for any `*_progress.md` files. If one exists for the current task, read it to determine what is complete and what is pending before doing anything else.

## All output goes to `.workspace/`

Do not write plans, reports, scripts, or intermediate files to the project root or to any project-owned directory (`src/`, `docs/`, `database/`, etc.) unless the file genuinely belongs to the project — source code, Flyway migrations, or official documentation.

## Default subdirectories

| Folder | Contents |
|--------|---------|
| `plans/` | Upcoming work not yet started |
| `progress/` | Active session state — progress tracking files |
| `reports/` | Completed outputs — scans, summaries, PR text |
| `scripts/` | One-off scripts for the current task only; see the note below |
| `tmp/` | Scratch work; do not rely on these persisting |

Agents may create additional subfolders within any of the above as needed.

`.workspace/` is untracked by rule: `.workspace/.gitignore` lets only `.gitignore`, `.gitkeep` and `README.md` through. A script a future session must be able to run therefore does not belong in `.workspace/scripts/` - it is gone for the next session and for every other machine. `STRUCT.md` once cited `.workspace/scripts/regenerate_struct_md.sh` as the source of its test table while no such file existed, which is what that arrangement produces. A script meant to last goes in the tracked `scripts/` directory at the repository root, alongside `regenerate_struct_figures.sh` and `regenerate_schema_sql.sh`.

## Skill reference

Load `skill-workspace` for the full file-naming conventions, multi-session continuity rules, and guidance on organizing output within `.workspace/`.
