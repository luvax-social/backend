---
trigger: model_decision
description: Load before implementing any feature or modifying any module. Defines mandatory documentation reading order.
---

# Documentation-First Rule

Before implementing any feature or modifying any module, locate and read all documentation relevant to that feature or module.

---

## 1. Mandatory Reading Order

| Condition | Required files |
|-----------|---------------|
| Every task | `docs/modules/GLOBAL_RULES.md` — read first, without exception |
| Task touches a named module | `docs/modules/{module}/DATA_RULES.md` for that module |
| Any other `.md` under `docs/` whose name or content pertains to the feature | Read that file before writing code |

## 2. New Module Implementation (Scaffolded → Built Out)

Read all three before writing the first line of implementation:

1. `docs/modules/GLOBAL_RULES.md`
2. `docs/modules/{module}/DATA_RULES.md`
3. `.agents/rules/struct.md`

## 3. Missing Documentation

If the required documentation file does not exist, state this explicitly before proceeding. Do not invent rules, constraints, or data access patterns that are absent from the codebase or existing documentation.

## 4. Scope

This rule applies to:

- Feature implementation
- Bug fixes that touch business logic
- Any scaffolded module being built out

This rule does not apply to:

- Pure infrastructure changes (e.g., adding a config property, fixing a Spotless format violation) where no domain logic is involved
