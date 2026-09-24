# Real-Time Surface Guide

The OpenAPI document describes the REST surface and nothing else.
It mentions STOMP zero times, SockJS zero times and `/ws/` zero times, so a consumer reading it cannot discover that a real-time surface exists at all and will poll for everything.

This document is the missing half.
It is Markdown rather than a machine-readable schema on purpose: a consumer needs to find the destinations and the handshake, not to generate types from them, and the payloads that arrive over the socket are the same DTOs the REST document already declares.

---

## 1. What is pushed and what must be polled

| Domain | Pushed over the socket | Still polled |
|---|---|---|
| Comments | create, edit, delete, like, unlike on one post | the initial page of comments |
| Post engagement | like and unlike on one post | the post itself, saves, view counts |
| Notifications | typed feed events for one account (a row written, grouped or retracted, read state, deletion, the seen watermark, a new follow request), each with the feed state after it | the notification list after load and after a reconnect |
| Direct messages | every new message in one conversation | the conversation list, read state, unread counts |
| Reports and moderation | **nothing** | the escalated-report count, the report queue, the audit log |
| Hashtags and trending | **nothing** | trending, search, the registry |
| Follows and follow requests | **nothing** | the follow-request list and counters |
| Stories | **nothing** | the story feed and view counts |

The last four rows are the ones a consumer most often assumes are wrong.
They are not: no producer exists for them.
An escalated-report badge has to be polled, and `GET /api/v1/admin/reports/escalated/count` is the cheap read written for exactly that.

Every push is a mirror of a state change the REST surface already made.
A client that misses a frame loses nothing permanently: the next REST read is authoritative, and reconnecting plus re-reading is the recovery path rather than a replay mechanism, because there is none.

---

## 2. Handshake

Four SockJS endpoints, one per domain:

| Endpoint | Domain |
|---|---|
| `/ws/comments` | comment events for one post |
| `/ws/posts` | like and unlike events for one post |
| `/ws/notifications` | notifications for the connected account |
| `/ws/messages` | messages in one conversation |

They are separate endpoints rather than one, so a client that needs only notifications does not open a connection carrying every other domain's interceptors.
They share one STOMP broker and one inbound channel, so the ordering guarantees and the send guard below apply identically to all four.

Each is registered `.withSockJS()`, so a client connects with a SockJS client and speaks STOMP over it.
A raw WebSocket connection to the same path also works, because SockJS registers the native transport alongside the fallbacks.

### 2.1 The ticket

A browser cannot set an `Authorization` header on a WebSocket upgrade, so the credential rides in the query string.
Putting the access token there would write it into every proxy, load balancer and CDN access log along the way, where it stays for as long as those logs are retained.
A single-use ticket is used instead.

```
POST /api/v1/auth/ws-ticket
Authorization: Bearer <access token>

200 {"success":true,"code":"OK","data":{"ticket":"9f2c4a1e..."},"timestamp":"..."}
```

Then connect:

```
GET /ws/notifications?ticket=9f2c4a1e...
```

The ticket:

- expires **30 seconds** after issue;
- redeems **exactly once**, so a reconnect needs a fresh one;
- is exchanged server-side for the access token it was issued against, and it is that token, never the ticket, that authorises the session.

Request one ticket per connection attempt, including every reconnect.
`POST /api/v1/auth/ws-ticket` carries its own rate-limit rule sized for a reconnect burst, so a flapping network does not lock the client out.

A handshake with no ticket, an expired ticket, or a ticket already redeemed is refused at the upgrade.
So is one whose underlying account is banned, suspended, deactivated, or has had its token epoch advanced by a force-logout.

### 2.2 Revocation mid-session

A session that authenticated legitimately does not stay connected forever if the account's authority is withdrawn.
A sweep runs every 30 seconds and closes sessions whose token has been blacklisted, whose account status no longer permits access, or whose token epoch has been advanced.
A client sees this as a normal close and should treat it as "re-authenticate", not "retry the socket".

---

## 3. Destinations

The broker is a simple in-memory broker on the `/topic` prefix.
Client-to-server frames go to the `/app` prefix.

### 3.1 Subscribe

| Destination | Endpoint to connect to | Who may subscribe |
|---|---|---|
| `/topic/comments.{postId}.events` | `/ws/comments` | any account that may read the post |
| `/topic/posts.{postId}.events` | `/ws/posts` | any account that may read the post |
| `/topic/notifications.{userId}` | `/ws/notifications` | that account only |
| `/topic/conversations.{conversationId}.messages` | `/ws/messages` | a participant in that conversation |

`{postId}`, `{userId}` and `{conversationId}` are the canonical 36-character UUID renderings.
A subscription to a destination that does not match the expected shape is refused, as is one whose authorisation check fails, so a client cannot discover another account's notifications by guessing an identifier.

Subscribing to `/topic/notifications.{someoneElsesId}` is refused rather than silently returning nothing.

### 3.2 Send

**A client may not SEND to a `/topic/**` destination.**
Every inbound SEND frame is checked before any module logic runs: only the `/app` prefix is accepted, and a frame addressed straight at a `/topic` destination is rejected rather than relayed.
Without that guard, any connected client could publish a forged comment event to every other subscriber of a post.

There are no client-initiated writes over the socket in the current surface.
Every write is a REST call, and the socket carries the resulting event outward only.
Post a comment with `POST /api/v1/posts/{postId}/comments` and the created event arrives on `/topic/comments.{postId}.events` for every subscriber, including the caller.

### 3.3 Block filtering

Comment and post events are filtered on the way out, per subscriber: a subscriber who has blocked the actor, or whom the actor has blocked, does not receive the frame.
The filter is on the outbound channel rather than at publication, because one event fans out to many subscribers whose block relationships differ.

---

## 4. Frame payloads

Every frame is a JSON object carrying an `eventType` discriminator plus the fields for that type, except the notification topic, whose discriminator is `event`.

| Destination | `eventType` values |
|---|---|
| `/topic/comments.{postId}.events` | `comment.created.v1`, `comment.edited.v1`, `comment.deleted.v1`, `comment.liked.v1`, `comment.unliked.v1` |
| `/topic/posts.{postId}.events` | `post.live.liked.v1`, `post.live.unliked.v1` |
| `/topic/notifications.{userId}` | `event`: `upserted`, `read-state`, `deleted`, `seen`, `requests`; every frame carries `state` (badge, watermarks, pinned follow requests). See `notification/DATA_RULES.md` and the `NotificationLiveEnvelope` record |
| `/topic/conversations.{conversationId}.messages` | the message payload, sent without an event wrapper |

Switch on `eventType`.
The versioned suffix is there so a payload can change shape without breaking a client that has not been redeployed: a new shape gets a new version and both are published until the old one has no readers.

---

## 5. Feature flags

Every real-time domain is behind its own flag, and each defaults to **off**:

| Flag | Environment variable | Endpoint it enables |
|---|---|---|
| `app.comment.live.enabled` | `COMMENT_LIVE_ENABLED` | `/ws/comments` |
| `app.notification.live.enabled` | `NOTIFICATION_LIVE_ENABLED` | `/ws/notifications` |
| `app.post.live.enabled` | `POST_LIVE_ENABLED` | `/ws/posts` |
| `app.message.live.enabled` | `MESSAGE_LIVE_ENABLED` | `/ws/messages` |

With every flag off there is no STOMP broker at all and the paths do not exist.
A client must therefore treat a failed handshake as "real time is not available in this environment" and fall back to polling, rather than as an error worth retrying indefinitely.

Ask which flags are on in the environment being integrated against; do not infer it from a successful handshake in one environment.

---

## 6. What a client should do

1. Read the REST surface first. It is authoritative and complete on its own.
2. Request a ticket, connect, subscribe. Treat every frame as a hint to update local state, not as the source of truth.
3. On disconnect: request a fresh ticket, reconnect, and re-read the REST resource for the window that was missed. Nothing replays.
4. On repeated handshake refusal: stop and poll. Real time is optional in every environment.
5. Never assume a domain is pushed. Section 1 is the whole list.
