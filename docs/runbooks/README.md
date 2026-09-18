# Runbooks

Operational procedures for the central service.

Two kinds of document live here, and the difference is load-bearing:

- **Operator runbooks** are prose for a human with production access.
- **Break-glass runbooks** (§23.4) are *code*: a pre-registered, named,
  idempotent Ash action with declared effects, a rollback, and an execution
  receipt. A break-glass runbook cannot execute arbitrary SQL, shell commands,
  BEAM evaluation, or dynamically supplied code, and it is granted only by a
  separate explicit approval naming the runbook and its resource scope.

A document here never becomes executable by being written. Registering a
break-glass runbook means adding a compiled module to
`BREAKGLASS_RUNBOOK_ALLOWLIST`; Slice 8 owns that framework.

## Available

| Runbook | Purpose |
| --- | --- |
| [`local-development.md`](local-development.md) | Start, reset, and debug the stack |
| [`migrations.md`](migrations.md) | Apply schema changes; production never auto-migrates |

## Owed by later slices

| Runbook | Slice |
| --- | --- |
| Provider endpoint cutover and rollback (§27.7) | 4 |
| Agent-runtime cutover and drain (§29.0) | 5 |
| Lost-device revocation and session termination (§13) | 7 |
| Break-glass request, approval, activation, revocation (§23.4) | 8 |
| Backup and restore drill (§16) | 8 |
| Audit-chain verification (§22.3) | 8 |
