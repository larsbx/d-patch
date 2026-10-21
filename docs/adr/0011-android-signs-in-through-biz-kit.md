# ADR-0011: Android signs in through biz-kit's native sign-in

- **Status:** accepted — it replaces two §23.1 **MUST**s (token lifetime, refresh tokens); the product owner decided on 2026-10-21 that the token is not revoked until the person signs out
- **Date:** 2026-10-21
- **Deciders:** platform engineering
- **Specification sections:** §23.1, §25.1, §25.2

## Context

§23.1 asks for OIDC Authorization Code with PKCE, access tokens of at most 15
minutes, and rotating refresh tokens in Keystore-backed storage. ADR-0010 moved
the server to biz-kit, whose identity service (`BizKit.Identity`) already
brokers sign-in: a person signs in with the provider, is resolved to a
principal (`prn_…`), and gets a biz-kit session. biz-kit's native sign-in
(`docs/architecture/2026-10-21-native-sign-in.md` there) gives a native app
that session as a bearer token, and dispatch accepts it on `/v1`
(`TOKEN_VERIFIER_ADAPTER=Dispatch.Identity.Tokens.BizKitSession`).

A browser's session lasts one hour. A native app's lasts until the person
signs out: it has no time limit and is kept, by hash, in biz-kit's Postgres,
so a restart does not end it (biz-kit's native sign-in note). There is no
refresh token.

## Decision

`core:auth` signs in through biz-kit, RFC 8252 with PKCE (RFC 7636):

1. `AuthSession.begin` makes a verifier and a state, seals them, and returns
   `{auth}/auth/{provider}/native/login?redirect_uri&code_challenge&code_challenge_method=S256&state`
   for the **system browser** (RFC 8252 §6), never a web view.
2. biz-kit redirects to `{applicationId}:/oauth2redirect?code&state` (a
   private-use scheme, RFC 8252 §7.1), or with `error=access_denied`.
   `SignInConfig.read` accepts only that path with the state of the sign-in in
   progress; anything else any app could send is ignored and changes nothing.
   Reading the redirect and spending the sign-in are one step, so a redirect
   delivered twice is exchanged once.
3. The code is exchanged at `POST /auth/token` with the verifier, then
   `GET /auth/me` names the principal, which is the `sub` capability documents
   are bound to (ADR-0009). The sign-in is spent before the exchange whatever
   it answers, as biz-kit spends the code.
4. The session, its principal and expiry, and any sign-in in flight, are
   kept in one record sealed by AES-256-GCM under an Android Keystore key, in
   app-private preferences excluded from backup. A record that no longer
   opens reads as signed out. A write the disk refuses changes nothing: a
   sign-in whose verifier cannot be kept does not open the browser, and a
   session that cannot be kept is not used. Forgetting (a spent sign-in,
   sign-out) takes effect in memory even then.
5. The token is sent until the person signs out. The grant carries no
   `expires_in`; one that did would not be sent within 30 seconds of its
   expiry. When the server ends a session early (the principal's sessions
   revoked, the identity unbound), dispatch answers `401`, which keeps queued
   events queued (§25.2). Every `401` is reported to the
   session (`SessionProvider.rejected`), and one answering the current token
   turns `signInRequired` on: the status screen offers sign-in beside
   everything it already showed, and declaring still queues. The principal is
   kept until sign-out, so the stored capability document still verifies
   offline.
6. A sign-in reloads capabilities and sends queued events at once
   (`SyncRequester.requestSyncNow`): their backoff was earned against the
   refused session, so it is reset, and the sync replaces a WorkManager chain
   sitting out its own backoff rather than queueing behind it.
7. Sign-out forgets the session locally first, then ends it on biz-kit if
   reachable.

**Replaced MUSTs.** The access token lasts until the person signs out, not
fifteen minutes, and there is no refresh token: one credential, kept sealed
on the device. Revocation is immediate regardless (logout, revoking the
principal's sessions, or removing the identity binding ends it in dispatch at
once, since biz-kit re-resolves the session on every use).

## Consequences

### Accepted

- A stolen token works until the person signs out or an administrator revokes
  the principal's sessions. Its value is bounded by the sealed storage, the
  per-request re-resolution, and the device key that signs each declaration
  (ADR-0012).
- biz-kit's `/auth` (SpruceGoose's web endpoint) binds loopback only, by a
  governed decision there. A deployment must front `/auth/*/native/login`,
  `/auth/*/callback`, `/auth/token`, `/auth/me` and `/auth/logout` publicly for
  devices to reach them, and list `{applicationId}:/oauth2redirect` in
  `BIZKIT_NATIVE_REDIRECT_URIS`. Debug builds reach the host's loopback through
  the emulator's `10.0.2.2`.
- Queued events carry their role assignment, not the person. If someone else
  signs in on the same handset, the previous person's undelivered events are
  refused by dispatch (`403 ROLE_ASSIGNMENT_INVALID`: the assignment is not
  theirs) — never attributed to the new person. Hand-over between people is
  left to a later slice.
- Section 4.3's role switcher is not built; `selectedRoleAssignmentId` is null
  and dispatch selects the only active assignment.

### Rejected alternatives

| Alternative | Why not |
| --- | --- |
| AppAuth-Android | Built for OIDC discovery and client registration, neither of which biz-kit's broker has; the parts used here are a challenge, a URL and a form post |
| The app runs PKCE against the provider directly | The app would be bound to the provider; biz-kit's identity rule is that a provider change changes how sessions start, nothing else |
| `androidx.security:security-crypto` | Deprecated upstream; a Keystore AES-GCM key is the same protection in a few lines that are tested |
| A web view | RFC 8252 §8.12: the app could read the person's credentials |
| Custom Tabs | Better looking, one more dependency; the plain system browser meets RFC 8252 and can be swapped in later without changing the flow |

## Verification

- `SignInTest`: RFC 7636 Appendix B; the login URL carries the challenge,
  never the verifier; only this sign-in's redirect is read, by path and state.
- `AuthApiTest`: the token request on the wire; a non-bearer, zero-lifetime or
  unparseable grant is refused; no answer is not a refusal.
- `AuthSessionTest`: the round trip, across a process death in the browser;
  forged, replayed and denied redirects; one exchange for a redirect delivered
  twice at once (300 barrier-released rounds); failed exchanges; writes the
  disk refuses; expiry and refusal turning `signInRequired` on, a stale
  token's refusal not; sign-out online and offline.
- `VaultTest`: sealing round-trips with fresh nonces, refuses tampering and
  foreign keys, stores nothing in the clear, and a record that no longer opens
  reads as signed out.
- `DispatchClientTest`: a `401` is reported against the token refused.
- `StatusOutboxTest`, `SyncWorkerTest`, `SyncRequesterTest`: after sign-in,
  deferred events are due at once and the sync replaces a backed-off chain.
- `SignInFlowTest`, `StatusPresenterTest`, `StatusScreenTest`,
  `StatusViewModelTest`: a session reloads capabilities and sends what waited
  at once; signed out, the screen offers sign-in and nothing else; refused,
  it offers sign-in and still lets the participant declare.
- Mutations: removing the state check, the redirect match, the in-progress
  check, single use, the bearer check, expiry, the principal lookup, local
  sign-out first, S256, the unreadable-record fallback, the atomic redirect
  read, keeping only what was written, the `commit` check, ignoring a stale
  token's refusal, the client's `401` report, the backoff reset, the
  `REPLACE` policy, or the sign-in prompt beside the status choices each
  fails a test.
- Not on the JVM: the Keystore key itself (Robolectric has none); the same
  sealing code is tested with a software key.
