# 0011 Cloudflare R2 Object Storage

Date: 2026-07-26

## Status

Accepted

## Context

ULP stores every uploaded byte on the local disk under `app.upload.dir`
(`uploads/`) via five storage services (avatar, exam image, lesson
attachment, lesson video, library). Local disk does not scale across
hosts, video Range streaming is heavy on the app process, and
`WebConfig` currently maps the entire `/uploads/**` tree as a public
static resource — so a guessed `lessons/` or `library/` URL can leak
private files.

The team wants Cloudflare R2 (S3-compatible, private bucket) as an
optional provider while keeping the existing URL/API contracts and
supporting a dual-read path for files already on disk.

## Decision

1. **Abstraction** — introduce `com.ulp.features.storage.ObjectStorage`
   with `LocalObjectStorage`, `R2ObjectStorage`, and
   `DualReadObjectStorage`. Application code talks only to
   `ObjectStorage`.
2. **Write path** — active provider only:
   - `storage.provider=local` → write local
   - `storage.provider=r2` and R2 config complete → write R2
   - `storage.provider=r2` and config incomplete → reject write
     (`StorageNotConfiguredException`), **no silent local fallback**
3. **Read path** — always dual-read: try local first, then R2 when
   ready. No bulk migrate of existing files.
4. **Delete path** — best-effort on both backends.
5. **Upload model** — server receives `MultipartFile` and streams to
   storage (no browser presign).
6. **Bucket** — private. No public R2 URLs are ever returned.
7. **Secrets** — stored plain text in `system_settings` (same MVP
   trade-off as SMTP, decision 0008). HTTP layer masks
   `storage.r2.secret_access_key` as `********`.
8. **Public `/uploads`** — remove the broad `WebConfig` disk mapping.
   Serve only `avatars/**` and `exams/**` through
   `PublicUploadsController` (dual-read). Lesson/library keys return
   404 on `/uploads/**`.
9. **Keys** — relative keys without a leading slash:
   `avatars/`, `exams/`, `lessons/`, `library/`. Avatar/exam services
   still **return** `/uploads/...` URLs into the DB/HTML.
10. **Admin UI** — `/admin/settings/storage` clones the Email settings
    design system. Permission `PERM_system.storage` (ADMIN). Test
    connection runs HeadBucket.
11. **CI / dev default** — seed `storage.provider=local`. Tests use
    `LocalObjectStorage` / dual-read with in-memory or temp local;
    never call real Cloudflare.

## Alternatives Considered

1. **Browser presigned PUT to R2** — rejected: more CORS/security
   surface, diverges from existing multipart controllers, hard to keep
   validation (magic bytes, size) consistent.
2. **Bulk migrate on provider switch** — rejected for v1; dual-read is
   enough and safer for rollback.
3. **Encrypt secrets at rest** — deferred (same as 0008); revisit
   before production.
4. **Keep WebConfig `/uploads/**` + controller** — rejected: would
   leave lesson/library files publicly reachable on disk.

## Consequences

Positive:

- Single storage abstraction for all four upload families.
- Fail-closed R2 writes prevent silent data-location drift.
- Hardening: lesson/library blobs are no longer public static files.
- Offline CI remains green with `provider=local`.

Tradeoffs:

- Dual-read means a key can exist on both backends; delete is
  best-effort both sides; read prefers local.
- Plain-text R2 secret in DB (same class of risk as SMTP password).
- Large video uploads still stream through the app JVM (document reverse
  proxy body size limits).

## Follow-Up

- Optional background migrate job (out of scope for this change).
- Secret encryption-at-rest shared with SMTP.
- Cloudflare Stream / HLS if video traffic grows.
