# Scripts

Checks that CI runs and a contributor can run locally. Both are called by the
`lint` gate and by `make lint`.

| Script | Asserts |
| --- | --- |
| `check-layout.sh` | Every path in Section 20's tree exists, including a README in each deployable directory |
| `check-invariants.sh` | Textual MUST NOTs: no `authorize?: false` outside migrations and seeds, no vendor type outside its adapter, no `<Dial>`/`<Conference>`, no priority or severity field, no CDN-loaded Datastar, no hand-encoded Datastar events |
| `code-lines.py` | Support for the above: emits `path:line:text` for code lines with comments and doc blocks stripped |

## Why textual checks

Some of the specification's rules are about what must be *absent* from the
source. A type system cannot express "no module outside this directory may
mention `CallSid`", and a test only catches such a leak if it happens to
exercise the path. A search fails the moment the construct appears.

They are deliberately conservative: `code-lines.py` strips comments and doc
blocks first, so documenting *why* a vendor type is forbidden never counts as
using it. Without that, a well-documented codebase would fail its own checks —
precisely the wrong incentive.

## Running

```sh
./scripts/check-layout.sh
./scripts/check-invariants.sh
```

Both exit non-zero on a violation and name the file and line.

## Adding an invariant

Add a `check` call to `check-invariants.sh` with the specification section in
the description. Then verify it actually fires: write the violation into a
throwaway file, confirm the check fails, and delete it. An invariant check that
has never been seen to fail is not known to work.
