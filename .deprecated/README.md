# Deprecated Build System Components

This directory contains the old templating and codegen system that has been replaced by the new unified build system.

## What was deprecated (2026-01-05)

- `process-config-templates.main.kts` - Old template processor with {{VAR}} syntax
- `generate-compose.main.kts` - Old compose file generator
- `compose.templates/` - Template files that mixed build-time and runtime substitution

## Why deprecated

The old system had multiple issues:
1. Two competing templating systems ({{VAR}} vs ${VAR})
2. Confusing output locations (repo vs ~/.datamancy/)
3. Secrets being hardcoded into generated files
4. Unclear separation between build and deployment

## New system

Use `build-datamancy.main.kts` which:
- Single unified build command
- Generates deployment-ready dist/ directory
- Hardcodes versions at build time
- Preserves ${SECRETS} for runtime
- Clear separation: source → build → deploy

See ../README-BUILD.md for complete documentation.
