# Support Module — Data Rules

Scope: `com.app.modules.support` and the `support_tickets` table.

One request, one response.
There is no thread, so there is no message table.

---

## Section 1: Canonical Data

| Table | Owner | Notes |
|-------|-------|-------|
| `support_tickets` | support | One request and the single staff response to it (V97). |
| `support_category_configs` | support | Display metadata for the category enum (V97). |

### Why this module exists at all

`TokenPrincipalResolverImpl` is the single account-state enforcement point in the application and it admits only `ACTIVE`.
A banned, suspended or deactivated account can therefore reach no authenticated endpoint.
Before this module the only user-facing moderation surface was a read-only warning list, so the population most affected by a moderation decision had no route to contest it.

That is why the module carries its own entry mechanisms rather than a carve-out in the account-state check.
Weakening that check would have handed a blocked account a general-purpose session.

### Column mutability

Everything the user wrote is `updatable = false` on the entity and is never rewritten: `user_id`, `contact_email`, `category`, `subject`, `body`, `source`, `admin_action_id`.
Only the staff workflow columns move: `status`, `assigned_to`, `assigned_at`, `staff_response`, `internal_note`, `responded_by`, `responded_at`, `escalated_by`, `escalated_at`, `escalation_reason`.

This mirrors how `Report` and `AdminAction` split theirs, and it means no workflow step can rewrite the request it is answering.

### `contact_email` is captured even for an authenticated ticket

The account may be banned by the time staff answer.
The address is then the only channel that reaches the person, so it is stored on the ticket rather than resolved from `users` at send time.

### `internal_note`

Staff-only, and it never leaves the system.

The guarantee is structural rather than procedural.
`SupportTicketResponse`, the owner-facing record, has no component for it, and `SupportTicketMapper` is hand-written rather than generated precisely so that a field added to the entity cannot appear in it by name matching.
`internal_note` is also never placed into the `admin_actions` metadata map, so it cannot reach a mail template either.

---

## Section 2: Status Lifecycle

| From | Permitted targets |
|------|-------------------|
| `pending_confirmation` | `open`, and only by redeeming the confirmation token |
| `open` | `in_progress` (by claiming), `answered`, `rejected`, `escalated` |
| `in_progress` | `answered`, `rejected`, `escalated` |
| `escalated` | `answered`, `rejected` |
| `answered` | none — terminal |
| `rejected` | none — terminal |

`SupportTicketServiceImpl.validateTransition` is the enforcement point, in the shape `ReportServiceImpl.validateTransition` uses: one explicit switch, one error code (`SUPPORT_TICKET_INVALID_TRANSITION`) for every disallowed pair.

An escalated ticket does not return to the moderator queue, for the same reason an escalated report does not: a case handed up to an administrator must not be pulled back down.

`pending_confirmation` is not reachable by any staff action.
It exists only for the public form and is left by redeeming the confirmation token, never by a status write.

---

## Section 3: The One-Open-Ticket Invariant

A user may hold exactly one ticket that is not terminal, across every category.

Enforced twice, deliberately:

1. `uq_support_tickets_one_open_per_user` (V100), a partial unique index over `user_id` where the status is `open`, `in_progress` or `escalated`. This is what actually holds under a concurrent double submit.
2. `SupportTicketRepository.hasOpenTicket`, checked in the service. This exists so the ordinary case answers `SUPPORT_TICKET_ALREADY_OPEN` rather than surfacing a constraint violation as a 500.

`pending_confirmation` is outside the guard in both places.
An unconfirmed public submission is not yet a real ticket and must not block the account's genuine one.

The index also carries `user_id IS NOT NULL`.
Without it a partial unique index over a nullable column would admit unlimited public tickets, because `NULL` never equals `NULL`.

This narrowing to active statuses follows V89, which corrected the report duplicate guard for exactly the same reason: a guard that counts closed rows stops a user from ever asking again.

For a public ticket with a null `user_id` the index cannot apply.
The IP rate limit and the email-confirmation step are the controls there.

---

## Section 4: Authorization Matrix

Enforced in `SupportAuthorizationService`, which follows `AdminAuthorizationService`: a pure evaluator returning an outcome, and assert methods mapping outcomes to error codes.
Controller annotations are the first gate and this is the second; neither is load-bearing alone.

| Capability | `user` | `moderator` | `admin` |
|---|---|---|---|
| Create a ticket about themselves | Yes | Yes | Yes |
| Read their own ticket | Yes | Yes | Yes |
| List all tickets | No | Yes | Yes |
| Claim a ticket | No | Yes | Yes |
| Respond to or close a non-appeal ticket | No | Yes | Yes |
| Respond to or close an `appeal_*` ticket | No | **No** | Yes |
| Read an `appeal_*` ticket | No | Yes, read only | Yes |
| Escalate any ticket | No | Yes | Yes |
| Read `internal_note` | No | Yes | Yes |

### Why a moderator cannot decide an appeal

Unban, unsuspend, revoke-warning and revoke-strike are all administrator-only actions.
A moderator who could close an appeal would be recording a verdict they have no capability to execute, which would leave the ticket answered and the account still banned.

A moderator may still read the appeal and escalate it.
Escalation is how an appeal reaches an administrator, so removing it would strand the queue.

Refused with `SUPPORT_APPEAL_REQUIRES_ADMIN`, a distinct code, so a client can explain the refusal rather than showing a generic 403.

The narrowing is not expressible as a per-method `@PreAuthorize`, because it depends on the ticket's category, which an annotation cannot see.
That is why the service gate is the real one here and the annotation is only the outer perimeter.

### Conflict of interest

A staff member may not claim, respond to, close or escalate a ticket whose `admin_action_id` names an audit row they wrote.

The acting staff member is resolved from `admin_actions.admin_id`.
That column is nullable — it is null for the automatic strike the discipline ladder writes and for the automatic unsuspend the expiry sweep writes — and **a null actor blocks nobody**.

Reading is exempt.
Reading is not a decision, and hiding the ticket from the person who made the original call would make the queue harder to reason about without protecting anyone.

Refused with `SUPPORT_CONFLICT_OF_INTEREST`, again distinct from a plain 403.

Conflict of interest is evaluated **before** the appeal rule, so an administrator who wrote the decision is refused for the conflict — the accurate reason — rather than being admitted because their role is sufficient.

### Claiming

`assigned_to` and `assigned_at` are set by `SupportTicketRepository.claimIfUnassigned`, whose predicate re-checks `assigned_to IS NULL AND status = 'open'`.
Two moderators claiming at once produce one update of 1 and one of 0, and the loser is told with `SUPPORT_TICKET_ALREADY_CLAIMED` rather than silently overwriting the winner.

Shaped on `AdminUserRepository.reinstateExpiredSuspension`, which resolves the same race the same way.

Deciding and escalating both require holding the claim.
Escalated reports have no ownership model at all today, and that gap is why this one is guarded from the start.

### Audit

Every staff decision writes an `admin_actions` row through `AdminActionRecorder`: `respond_support_ticket`, `reject_support_ticket`, `escalate_support_ticket` (V98, with their config rows in V99).

Claiming is deliberately **not** audited.
It is a queue mechanic rather than a decision about a person, and a row for every claim would bury the rows that record verdicts.

---

## Section 5: The Three Entry Paths

### Path A — authenticated

`POST /api/v1/support/tickets`.
Ordinary authenticated request; the standard filter chain resolves the principal.

### Path B — signed link

Every punitive moderation notice carries a single-use appeal link.

The token follows `TokenServiceImpl` exactly: `SecureRandom` 32 bytes, Base64-URL encoded, only the SHA-256 hex stored in Redis, a forward key and a reverse key, atomic issue by Lua so a new token invalidates the previous, atomic consume by Lua GET-then-DEL so it can be redeemed exactly once.

TTL is **30 days**, against 24 hours for email verification and 15 minutes for a password reset.
Those two bound a window the user opened seconds earlier and is waiting on.
An appeal window is the opposite: the notice arrives unannounced, is bad news, and is routinely read late.
A shorter window would expire the link for exactly the people least able to act quickly, and the link authorises nothing but writing one ticket.
It is not indefinite, because a token that never expires is a credential.

The reverse key is keyed on the **audit row**, not the account, so a second appealable decision does not invalidate the link for the first.

The token binds to the `admin_actions` row, so the resulting ticket lands with `admin_action_id` populated and the appeal category taken from the token rather than from the request body.
A client-supplied category would let the submitter appeal something the token never authorised.

**Redeeming the token mints no session, no token pair and no refresh token row.**
It authorises exactly one write.
`AuthServiceImpl.verifyEmail` is the counter-example in this codebase — it consumes a token and calls `issueSession` with no account-state check — and this path deliberately does not repeat that shape.

Only the six punitive actions carry a link.
The two reinstating actions do not, because there is nothing to contest, and the two support ticket replies do not, because a rejected appeal could otherwise be appealed in an unbounded loop.

The token is minted at send time, not when the action is recorded, so a notice that is throttled or skipped leaves no live token behind.

### Path C — public form

For someone who no longer has the mail, or never received one.

Two independent controls, both required:

1. **Cloudflare Turnstile**, verified server-side before anything is written. Configured under `app.support.turnstile.*`.
2. **Email confirmation.** The ticket is written in `pending_confirmation` and is invisible to every staff query until the confirmation link is followed. Turnstile proves the submitter is probably not a bot; only this proves they can read the mailbox they named, which is what stops the form opening tickets in someone else's name.

Appeal categories are refused on this path with `SUPPORT_CATEGORY_NOT_PUBLIC`.
An appeal needs an audit row to appeal against, and this form supplies no way to name one.
Path B supplies it inside a signed token and path D reads it from the caller's own session; a category posted to this form names nothing at all.
What this form now offers instead is path E, which does not open an appeal but re-sends the link that opens one.

**Turnstile fails closed.**
When Cloudflare is unreachable, times out, or answers anything other than a clear success, the submission is refused.
Failing open would keep appeals flowing during an outage, but it would also mean anyone able to cause a timeout can switch the bot control off at will, and a control an attacker can disable by making one request slow is not a control.
Failing closed makes an outage visible and temporary: the public form stops, while the two authenticated paths and every appeal link already in existing moderation mail keep working, so nobody who was actually mailed a decision loses their route to contest it.

An unconfigured secret is treated the same way, and refuses, so a deployment that forgets `TURNSTILE_SECRET_KEY` fails the form closed rather than silently removing the control.

### Path D - in-product appeal

`POST /api/v1/support/appeals`, authenticated.

A signed link exists because its holder has no session.
An appellant who still has one does not need one, and routing them through a mail round trip made the emailed notice a single point of failure for them too.
This path is the same ticket by a second door, not a second kind of ticket: it shares one implementation with path B, so the one-open-ticket guard, the resulting ticket shape and the one-appeal-per-decision guard cannot drift between the two.

Ownership is read from `admin_actions.target_user_id` server-side.
The identifier in the request body is not a credential and proves nothing.
A row belonging to another account answers `SUPPORT_APPEAL_ACTION_NOT_FOUND`, which is the identical answer an unknown identifier gets, so the route cannot be used to discover whether an identifier names a real decision.

The category is derived from the action type through `AppealCategories`, never taken from the request, for the same reason path B takes it from the token.
An action type outside the appealable set is refused with `SUPPORT_APPEAL_NOT_APPEALABLE`.

The resulting ticket carries `source = authenticated`, because that is how it arrived.
`admin_action_id` being populated is what makes it an appeal, exactly as it is for a path B ticket.

**One decision, one appeal**, in any status, enforced by `SUPPORT_APPEAL_ALREADY_FILED`.
The one-open-ticket guard does not cover this: a rejected first appeal is terminal, so without it the same decision could be contested again indefinitely.

Opening an appeal here **revokes any unspent signed link for the same decision**.
Leaving it live would hand its holder a credential that can only ever be refused, and one that would outlive a rejection of this very appeal.

### Path E - appeal link recovery

`POST /api/v1/support/appeal/resend`, anonymous.

The gap it closes: before it, the link contesting a specific decision existed only inside one email.
A bounced, filtered or deleted notice therefore removed that account's only route to contest the decision, permanently, and a banned or suspended account cannot reach any authenticated surface to recover it.

It re-mints the link for the most recent appealable decision on the account that has not already been appealed, and mails it to **that account's own stored address**, never to the address the caller typed.

**The answer is identical whether or not the address matches anything** - same body, same status, same elapsed time.
Response time is equalized by `ForgotPasswordTimingEqualizer`, the same component the forgot-password path uses, and the Turnstile verification runs **inside** that window rather than before it.
A remote verification takes real and variable time; measuring from before it would leave that variance outside the floor and hand back the enumeration channel the equalizer exists to close.

**Turnstile fails closed here**, matching path C rather than the auth surfaces.
Those can afford to admit a request Cloudflare did not answer for because a per-caller rule stands behind them.
This route has no per-caller identity at all and it triggers mail, so the challenge is its only real control.

An **unverified** address is refused silently, the same refusal `ModerationMailEventHandler` applies and for the same reason.
The platform has never proved an unverified address belongs to the account, so mailing it would tell whoever owns that mailbox both that the address is registered here and that a moderation decision concerns its supposed owner.
That is worse here than in the notice path, because here an anonymous caller chooses the address.

Re-minting goes through the ordinary issue path, so the reverse-key contract destroys the previous link for that decision.
One decision never holds two live links, however often a replacement is requested.

The selecting query is bounded and indexed rather than a scan: `idx_admin_actions_target_created` (V82) serves both the filter and the ordering, `idx_support_tickets_admin_action` (V100) serves the anti-join, and the query takes one row.

### Appeal status token

The appeal token is spent by the atomic get-then-delete that redeems it, and the appellant holds no session.
After filing they therefore held nothing at all and could never learn whether the appeal was read.

Redemption now also mints a **status token** and returns it alongside the ticket.
Key family `support:token:appeal-status:`, following the other two exactly, TTL **90 days**.
Ninety against the appeal link's thirty: the appeal window bounds how long the appellant has to act, this one bounds how long they can watch the result, and it must outlive it.

`GET /api/v1/support/appeal/status` is anonymous, read-only and idempotent.
It **peeks and never consumes**, because a status link is meant to be followed repeatedly over the life of an appeal, and there is deliberately no consuming counterpart on the interface at all.

It answers `SupportTicketResponse`, the same record the owner-facing read returns.
Reusing it is the point: a parallel record for this one caller would be two types obliged to stay in sync, and the field they must agree to withhold is `internal_note`.
That record structurally has no component for `internal_note`, `assigned_to`, `responded_by` or `escalation_reason`, so no later mapper edit can leak one to an anonymous caller.

An unknown token, an expired token and a token naming a ticket that no longer exists all answer `SUPPORT_TOKEN_INVALID`, so the route cannot report which guesses landed.

### Rate limits on path C

Three per hour keyed on the caller's IP, in `app.rate-limit.endpoint-rules` in all three profiles.

Ten per day keyed on the submitted **address**, enforced in `SupportTicketServiceImpl` on the same Redis sliding-window primitive the filter uses.
`AuthRateLimitFilter` carries one window per endpoint rule, so a second window cannot be expressed there.
The two bounds are deliberately keyed differently: the filter stops one machine bursting, and the service stops one address being used all day from many machines.

---

## Section 6: Deployment Prerequisite — Trusted Proxy

`IpExtractor` trusts `X-Forwarded-For` only when the direct peer matches a CIDR in `app.security.trusted-proxy-cidrs`.

**That list is empty in the production profile.**

Behind an unconfigured reverse proxy every caller presents the proxy's address, so the IP-keyed hourly limit on the public form buckets every caller together and becomes a global cap of three submissions per hour.

Configure `app.security.trusted-proxy-cidrs` with the ingress CIDRs before relying on the public form in production.
The daily per-address bound is unaffected, because it is keyed on the submitted address rather than the IP.

---

## Section 7: Notifications and Mail

A terminal status change notifies the user twice.

In-product: a `support_ticket_update` notification (V98, config row in V99).
It is not user-toggleable, for the reason V61 gives for the warning row: an account that could switch it off would ask a question and never be told it had been answered.
It is a platform notice with no actor: the staff member who decided the ticket is not named, and opening the notice opens the ticket.
A verification decision sends the same notice, anchored to the `verification_request`.
Granting or revoking a badge also enqueues `user.verification-changed.v1`, on which the notification module rewrites `actor_verified` on the feed rows that account leads, in batches.

By mail: through `moderation.mail.queue`, the path built for moderation notices.
That path applies no `ACTIVE`-only gate, which is exactly what a reply to a banned account requires.

The mail is raised by the audit row rather than by the service: `AdminActionRecorder` maps `respond_support_ticket` and `reject_support_ticket` to notice templates and enqueues the event inside the same transaction as the ticket write.

The mail carries `staff_response`.
It never carries `internal_note`, which is never placed in the metadata map at all.

A public ticket that never resolved to an account gets no in-product notification — there is nobody to notify — but still gets the mail, which is the whole point of that path.

---

## Section 8: Inter-Module Dependencies

| Depends on | For |
|------------|-----|
| `users` | The account, its role and its address |
| `admin` | `admin_actions` for the appealed decision and for writing audit rows |
| `mail` | The notice mail and the confirmation mail |
| `notification` | The in-product notification |
| `common/security` | The Redis sliding window and `IpExtractor` |

The notification module depends on this one in return, through `SupportTicketPreviewService`, for the target of a `support_ticket_update` row.

---

## Section 9: Verification Requests

Verification is a ticket type on this framework, not a parallel system.
A verification request is a `support_tickets` row with `category = 'verification_request'`, and a `verification_requests` child row keyed one-to-one on the ticket carries the structured claim.

Everything about the queue comes from the framework unchanged: claiming, the conflict-of-interest rule, escalation, the `admin_actions` audit trail, and the outbox mail path.
Only three things are specific to verification.

### The one-open-ticket guard is split by lane

The guard V100 created was global across every category.
Adding verification to it would mean a pending request for a badge blocks the same account from appealing a ban, which puts a discretionary request in the way of contesting an enforcement action.

V107 therefore replaced `uq_support_tickets_one_open_per_user` with two partial unique indexes:

| Index | Covers |
|---|---|
| `uq_support_tickets_one_open_support_per_user` | every category except `verification_request` |
| `uq_support_tickets_one_open_verification_per_user` | `verification_request` only |

Each lane still admits one non-terminal ticket per account.
`SupportTicketRepository.hasOpenTicket` excludes `verification_request` to match the first index exactly, and `hasOpenVerificationRequest` is the service-layer half of the second.
The two must agree: a service check wider than its index refuses what the database would admit, which is worse than either rule alone.

### A moderator may decide a verification request

`SupportCategory.VERIFICATION_REQUEST.isAppeal()` returns `false`, and that is load-bearing rather than incidental.
The appeal-requires-admin rule in `SupportAuthorizationServiceImpl` reads that method, so returning `true` would be the one thing standing between a moderator and the queue they are meant to work.

Verification is a discretionary grant rather than a verdict only an administrator can execute, so the narrowing that keeps unban and unsuspend administrator-only does not apply to it.

The conflict-of-interest rule **does** extend to it, and is made to fire by populating `admin_action_id` on a resubmission with the audit row of the moderator revocation being contested.
Without that link the rule would exist for this category and never trigger, because nothing else writes that column on a verification ticket.
A system revocation is deliberately excluded from the link: it has no author, so it can produce no conflict.

Two gates run on every decision, each owning what it is for:

| Gate | Owns |
|---|---|
| `SupportAuthorizationService` | holding the claim, and conflict of interest |
| `AdminAuthorizationService.assertMayDecideVerification` | not yourself, and not an administrator |

The second lives in `AdminAuthorizationServiceImpl` beside every other actor-and-target rule rather than being a role check written inline.

### No identity documents, deliberately

**There is no file upload on the verification form, and no column for one.**
No national ID, no passport, no scan of anything.

This is a design constraint, not an unfinished feature.
The form collects a category, the display name being claimed, and seven optional free-text or URL evidence fields, of which **at least three must be filled**.
That rule is enforced in `VerificationServiceImpl` with its own error code `VERIFICATION_INSUFFICIENT_EVIDENCE`, and again by the `verification_requests_min_evidence` CHECK constraint, so no path can write a request below the floor.

Do not "complete" this by adding document upload.
Collecting identity documents changes what this system holds about people, and that is a decision to be taken deliberately rather than inherited from an assumption that a verification flow must have one.

### The badge lifecycle

`user_verifications` holds the grant, and revocation is soft: the row stays with `revoked_at` set, so a moderator reviewing a resubmission can see what was granted before and why it was withdrawn.

`revocation_actor` distinguishes the two kinds of withdrawal, and the distinction is the point:

| Actor | Meaning | `admin_actions.admin_id` |
|---|---|---|
| `moderator` | A person judged the account | the moderator |
| `system` | An account status change swept the badge away | **null** |

A null actor on the audit row is the same convention the discipline ladder's automatic strike and the suspension expiry sweep already use, so a reader does not have to learn a second rule.

Status coupling, applied by `VerificationService.applyStatusChange` inside the same transaction as the status change:

| Status | Badge |
|---|---|
| `suspended` | revoked, automatically |
| `banned` | revoked, automatically |
| `deactivated` | **retained** |
| `active` | retained |

Deactivation retains it because it is a voluntary act by the account holder, the account is invisible to everyone while it lasts, and withdrawing a badge there would punish something that is not an offence.

**An automatic revocation never creates a support ticket.**
It is a status-driven side effect rather than a request, and a ticket would put a row in the moderator queue that nobody asked for and nobody can act on.

Reinstatement does not restore the badge.
A badge is a claim the platform makes, and re-making it is a decision somebody has to take again rather than one that unwinds automatically.

### `users.is_verified` and `users.verified_category`

Both are denormalised from `user_verifications` by the `trg_user_verification_sync` trigger and are **never written by application code**, for the reason the counter policy gives.
`verified_category` is mapped `insertable = false, updatable = false` on the entity, so a service cannot write it even by accident.

They live on `users` rather than being joined at read time because `UserSummaryResponse` is the shared identity projection embedded in every response that names an account, and it is built by one JPQL constructor expression over `users` alone.
Two columns there reach the post header, comments, replies, profile headers, profile list rows, search results, suggestions, conversation participants, story owners and notification actors without adding a join to the hottest read in the application.
