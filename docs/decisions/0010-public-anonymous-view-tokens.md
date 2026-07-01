# 0010 Public Anonymous View Tokens for MS Office Documents

Date: 2026-07-01

## Status

Accepted (MVP — revisit before production launch)

## Context

The lesson content feature lets lecturers attach documents (PDF, DOCX, PPTX,
XLSX) to a lesson. Students view them inline without downloading:

- **PDF** → rendered client-side by PDF.js.
- **DOCX** → rendered client-side by JSZip + docx-preview.
- **PPTX / XLSX** → no reliable client-side renderer exists, so we embed the
  **MS Office Online Viewer** (`view.officeapps.live.com`).

MS Office Online Viewer works by fetching the file **from its own servers**.
It therefore requires a **publicly reachable URL** — it cannot send the
student's session cookie, and it cannot reach a URL behind Spring Security.

To bridge this, `PublicViewTokenService` mints a short-lived, unguessable
token (`UUID` without dashes, 32 hex chars, 1-hour TTL) stored in
`public_view_tokens`. The token maps to a single `lesson_attachments` row.
`PublicViewController` serves the file at `GET /public/view/{token}` with
`permitAll` so the Microsoft servers can fetch it.

Two Spring Security changes were needed:

1. `/public/view/**` added to the `permitAll` matcher list.
2. `frameOptions(sameOrigin)` applied globally so the internal PDF.js /
   docx viewer pages can be shown inside an `<iframe>` on the lesson page.

## Decision

1. **Token minting is lazy.** The token is created only when the student
   actually clicks "Xem" on an Office file — the click routes through
   `GET /file-viewer/office`, which enforces the same authorization gate as
   the download endpoint (`LessonAttachmentsService.download`) **before**
   minting the token. Merely rendering the attachments list has no write
   side-effect. `StudentLessonDetailService.getLessonDetail` stays
   `@Transactional(readOnly = true)`.

2. **`/public/view/{token}` is anonymous.** Access control is the
   unguessable token plus the 1-hour expiry, not a session. Expired tokens
   are deleted on access and swept every 30 minutes by
   `TokenCleanupScheduler`.

3. **Embedding headers use CSP only.** `PublicViewController` sets
   `Content-Security-Policy: frame-ancestors https://view.officeapps.live.com`.
   The deprecated `X-Frame-Options: ALLOW-FROM` header is intentionally
   **not** sent — no modern browser honours it, so it added noise without
   effect.

4. **Global `frameOptions(sameOrigin)`.** The whole app now allows
   same-origin framing (previously the Spring Security default `DENY`). This
   is required for the in-app PDF.js / docx viewer iframes.

## Alternatives Considered

1. **Server-side rendering of PPTX/XLSX to PDF/HTML** (LibreOffice headless,
   Apache POI). Rejected for MVP: heavy new dependency, conversion fidelity
   issues, and per-file CPU cost. Revisit if avoiding the third-party viewer
   becomes a requirement.
2. **Proxy the file through our server to MS Office Viewer.** Not possible —
   the Viewer fetches the `src` URL from Microsoft's own infrastructure; it
   cannot be routed through an authenticated proxy.
3. **Signed URL instead of a DB token** (HMAC of attachmentId + expiry). Same
   security properties without a table, but the DB token is simpler to revoke
   and audit, and the cleanup sweep already bounds table growth.
4. **Scope `frameOptions` to only the viewer paths** instead of globally.
   Deferred: Spring Security applies `frameOptions` at the filter-chain
   level; per-path override is possible but adds config surface for a
   same-origin allowance that is low-risk on its own.

## Consequences

Positive:

- Students view PPTX/XLSX inline with zero server-side conversion cost.
- Lazy minting means listing a lesson is a pure read — no token spam, no
  write amplification on a hot read path.
- Token table growth is bounded (1-hour TTL + 30-minute cleanup sweep).

Tradeoffs:

- **Internal documents transit a third party.** While a token is valid,
  the PPTX/XLSX file is fetched by `view.officeapps.live.com`. Microsoft may
  cache or index the content. For a 1-hour window, any holder of the token
  URL (e.g. leaked from browser history or a shared link) can fetch the file
  anonymously. Acceptable for course material in a capstone context; **not**
  acceptable for confidential documents.
- **`permitAll` on `/public/view/**`.** A new unauthenticated surface. Risk
  is mitigated by the 128-bit token entropy and short TTL, but it is still an
  anonymous file-serving endpoint that bypasses enrollment checks by design.
- **Global same-origin framing.** Slightly widens clickjacking surface
  versus `DENY`. Same-origin only, so external sites still cannot frame the
  app; the practical exposure is low.

## Follow-Up

- **Revisit trigger:** Before production launch, OR when any lesson may hold
  a confidential/non-public document — whichever comes first.
- **Hardening options when revisiting:**
  1. Replace the third-party Office Viewer with server-side conversion
     (LibreOffice headless → PDF) so no file leaves our infrastructure.
  2. Shorten the token TTL (e.g. 5 minutes) if the viewer fetches promptly.
  3. Scope `frameOptions` to the viewer paths only, keeping `DENY` global.
  4. Add an access log for `/public/view/{token}` hits for auditability.
- **In the meantime:** keep the TTL short, ensure the cleanup scheduler runs,
  and do not use the Office viewer path for any attachment flagged sensitive.
