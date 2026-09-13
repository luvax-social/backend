---
trigger: always_on
description: Always active. Requires a CHANGELOG.md entry after every completed task — skipping is a task completion failure.
---

# Changelog Rule

Write a changelog entry after every completed task (feature, fix, refactor, test, config, schema migration). Skipping is a task completion failure.

Target file: root `CHANGELOG.md`. If absent, create it with this header first:

```
# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
```

Follow [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/). Add to the `[Unreleased]` block — do not create a new version header.

Merge the entry into the existing section under `[Unreleased]`, creating that section only if it is absent. Do not prepend a fresh `### Added` ahead of the one already there: followed literally by each task in turn, that is what produced 119 heading blocks where there should have been six, and it is the rule rather than any single entry that caused it. `[Unreleased]` carries at most one of each permitted heading.

```
## [Unreleased]

### Added
- <description>

### Changed
- <description>

### Fixed
- <description>

### Removed
- <description>

### Security
- <description>

### Tests
- <description>
```

- Omit sections not applicable to the task.
- One bullet per logical change, one sentence each.
- Omit internal implementation details, file paths, and class names unless part of the public API or configuration surface.
- Write the entry as the final step before marking the task done.
