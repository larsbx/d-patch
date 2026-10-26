# Ash attribute-tenant upsert advisory

The unchanged Ash 3.37.1 pin blocked d-patch#34's dependency-audit job
121506642963 with CVE-2026-96581 / GHSA-vw7c-67pf-gh96 (HIGH).
This is a dependency prerequisite, not evidence about courier synchronization.

The [upstream 3.37.2 release](https://github.com/ash-project/ash/releases/tag/v3.37.2)
fixes tenant scoping of attribute-multitenant upserts. Its
[security patch](https://github.com/ash-project/ash/commit/c9f0cdb31e7c084def05f3f5be69dd7bb7721754)
also adds multitenancy regression cases. The
[advisory](https://github.com/ash-project/ash/security/advisories/GHSA-vw7c-67pf-gh96)
explains the cross-tenant read/write/takeover risk. Do not infer exploitability of
this application's configured resources solely from an affected library version.

Raise the Ash requirement floor to 3.37.2 within major version 3 and update only
its lock entry. Upstream's version diff leaves dependency declarations unchanged.
The release archive was verified against Hex's published outer SHA-256
`0f0dde3cd48808e15bf163d4f7da1873947104b9d9e61a0686c3e1cf22d5fa68`;
its inner CHECKSUM is
`20ce602e2c6c9345d057f2395d27ec158af59c97290cbf46142bafe4fb89ce01`.
Existing AshPostgres/AshSQL pins and the required helper migration remain intact.

This container has no Elixir/OTP installation. The lock entry uses the verified
release checksums and unchanged dependency metadata; normal hosted deps.get,
strict compilation, dependency and Hex audits, unit/PostgreSQL integration,
code-generation/migration and acceptance checks must pass before merge.
No audit exclusion or authorization bypass is introduced.
