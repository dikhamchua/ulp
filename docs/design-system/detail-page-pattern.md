# Detail Page Pattern

> Developer guide for view-or-edit detail screens inside the admin / lecturer
> shell. Source of truth for chrome classes lives in
> `src/main/resources/static/css/detail-page.css`. Tab navigation behaviour
> lives in `src/main/resources/static/js/detail-tabs.js`.
>
> Templates and CSS comments already point here
> (`docs/design-system/detail-page-pattern.md`). Keep this file in sync when
> the pattern changes.

---

## 1. When to use

Use this pattern for any **single-entity detail / edit** screen that has:

- A sticky action toolbar (back / save / delete)
- A header with title + status / meta
- Optional sub-tabs (info, history, permissions, …)
- One or more section cards with forms or tables

**Reference implementations**

| Screen | Template |
|---|---|
| Admin user edit | `templates/admin/users-form.html` |
| Admin department edit | `templates/admin/departments-form.html` |
| AI provider edit | `templates/admin/settings-ai-form.html` |
| Class settings | `templates/classes/detail-settings.html` |
| Lecturer test edit | `templates/tests/lecturer-form.html` (own orchestrator) |
| Lesson / section edit | `templates/classes/lesson-form.html`, `section-form.html` (client-only toggle) |

---

## 2. Anatomy

```
main.col.detail-page
├── #flash-data                          (optional, toast drain)
├── .detail-toolbar
│   ├── a.toolbar-back
│   ├── span.toolbar-spacer
│   ├── button.toolbar-save              (form="…Form", disabled off info tab)
│   └── button.toolbar-delete            (optional)
├── h1.detail-page-title                 (edit mode)
├── .detail-page-meta                    (status pill, timestamps, …)
├── nav.detail-tabs                      (edit mode, a.detail-tab links)
└── #tabPanel                            (REQUIRED when tabs exist)
    ├── tab body for active tab only
    └── (pager links .page-link stay inside)
```

Elements that must **survive** an AJAX tab swap (delete forms, confirm modals,
page-level scripts) stay **outside** `#tabPanel`.

---

## 3. CSS load order

Any template using this design system **SHALL** include stylesheets in this
order so the cascade works:

1. `app-shell.css` — global layout, header
2. `class-detail.css` — legacy shared detail rules
3. **`detail-page.css`** — generic detail-page chrome (THIS pattern)
4. `admin.css` — admin-shell rules / base form-row
5. page-specific CSS (e.g. `admin-users.css`, `invite-code.css`)

```html
<link class="page-css" rel="stylesheet" th:href="@{/css/app-shell.css}">
<link class="page-css" rel="stylesheet" th:href="@{/css/class-detail.css}">
<link class="page-css" rel="stylesheet" th:href="@{/css/detail-page.css}">
<link class="page-css" rel="stylesheet" th:href="@{/css/admin.css}">
<link class="page-css" rel="stylesheet" th:href="@{/css/admin-users.css}">
```

---

## 4. Tabs — REQUIRED behaviour (AJAX)

### 4.1 Rule (non-negotiable)

**Detail pages with tabs MUST switch tabs via AJAX.** Do **not** full-reload
the page on tab click.

Reasons:

- Keeps toolbar / header / sidebar stable
- Avoids losing unsaved visual state in the chrome
- Feels instantaneous once cached; shows a loading spinner on first load

Server-side `?tab=` routing **stays** as the progressive-enhancement fallback
(no-JS, deep links, first paint). The JS layer enhances it.

### 4.2 Markup contract

```html
<!-- Tab strip: real <a> links with ?tab=… so no-JS still works -->
<nav class="detail-tabs" aria-label="…">
  <a class="detail-tab"
     th:href="@{.../edit(tab='info')}"
     th:classappend="${activeDetailTab == 'info'} ? 'active'">Thông tin</a>
  <a class="detail-tab"
     th:href="@{.../edit(tab='history')}"
     th:classappend="${activeDetailTab == 'history'} ? 'active'">Lịch sử</a>
</nav>

<!-- ONLY the active tab body is rendered server-side -->
<div id="tabPanel">
  <div th:if="${activeDetailTab == 'info'}">…form…</div>
  <section th:if="${activeDetailTab == 'history'}">…table + pager…</section>
</div>
```

Include the shared orchestrator **after** page scripts:

```html
<script th:src="@{/js/app.js}"></script>
<script th:src="@{/js/admin-users.js}"></script>   <!-- page-specific -->
<script th:src="@{/js/detail-tabs.js}"></script>    <!-- shared AJAX tabs -->
```

### 4.3 What `detail-tabs.js` does

1. Intercepts clicks on `a.detail-tab` inside `nav.detail-tabs`
2. Intercepts in-panel pagination links (`.page-link` / `.detail-pagination a`)
3. Shows a loading spinner inside `#tabPanel` (`Đang tải…`)
4. `fetch`es the same URL with `X-Requested-With: XMLHttpRequest`
5. Parses the HTML response, lifts `#tabPanel`, swaps `innerHTML`
6. Updates active tab chrome + disables `.toolbar-save` when tab ≠ `info`
7. `history.pushState` so Back / Forward re-fetch the right tab
8. Dispatches `ulp:detail-tab-loaded` (`detail: { panel, tab, url }`)
9. On any failure (no `#tabPanel`, auth redirect, non-2xx) → full navigation

### 4.4 Loading UI

Provided by `detail-page.css`:

- `#tabPanel.is-loading` + `.detail-tab-loading` / `.detail-tab-spinner`
- Soft fade-in after a successful swap
- `aria-busy="true"` + `role="status"` while loading

Do **not** invent a second spinner for this pattern. Reuse these classes.

### 4.5 Controller contract

```java
@GetMapping("/{id}/edit")
public String edit(@PathVariable Long id,
                   @RequestParam(defaultValue = "info") String tab,
                   @RequestParam(defaultValue = "0") int page,
                   Model model) {
    // 1. Load entity; 404/redirect if missing
    // 2. Normalize tab against a whitelist (invalid → "info")
    model.addAttribute(ATTR_ACTIVE_DETAIL_TAB, activeTab);
    // 3. Only query heavy data for the visible tab
    if (TAB_HISTORY.equals(activeTab)) {
        model.addAttribute(ATTR_ACTIVITIES_PAGE,
                service.listHistory(id, PageRequest.of(page, 20)));
    }
    return VIEW_FORM; // full HTML page — AJAX reuses the same endpoint
}
```

There is **no** separate fragment endpoint. The AJAX layer scrapes `#tabPanel`
out of the full page response. Keep the markup contract stable.

### 4.6 Save button

```html
<button type="submit" form="entityForm" class="btn-primary toolbar-save"
        th:disabled="${mode == 'edit' and activeDetailTab != 'info'}">Lưu</button>
```

`detail-tabs.js` keeps this in sync client-side after every swap
(`.toolbar-save` disabled when active tab ≠ `info`).

### 4.7 Page-script rules after AJAX

Because `#tabPanel` is replaced, **one-shot** `querySelectorAll(…).forEach(addEventListener)`
bindings inside the panel die on the next swap.

**Required approach: document-level event delegation.**

```js
// GOOD — survives tab swaps
document.addEventListener('click', function (e) {
  var btn = e.target.closest('.invite-panel .copy-btn');
  if (!btn) return;
  // …
});

// BAD — dies after the first AJAX swap
document.querySelectorAll('.invite-panel .copy-btn').forEach(function (btn) {
  btn.addEventListener('click', …);
});
```

Reference: `static/js/invite-code.js`, permission toggle in `admin-users.js`.

Optional hooks if a tab needs mount/teardown (timers, rich editors):

```js
window.UlpDetailTabs = window.UlpDetailTabs || {};
window.UlpDetailTabs.onBeforeSwap = function () { /* clear intervals */ };
window.UlpDetailTabs.onAfterSwap  = function (panel, tab) { /* remount */ };
// or listen:
document.addEventListener('ulp:detail-tab-loaded', function (e) {
  // e.detail.panel, e.detail.tab, e.detail.url
});
```

### 4.8 Special cases

| Case | Approach |
|---|---|
| **Lecturer test edit** | Owns `test-detail-tabs.js` (monitor timers + Quill remount). Marks `data-ajax-tabs="owned"` so the shared script no-ops. Still shows the same loading spinner. |
| **Lesson / section edit** | Both panels are eager-rendered; tabs are `<button data-tab-target>` toggled client-side with `history.replaceState`. No network. Acceptable when history payload is tiny. |
| **Create mode** | No tab strip, no `#tabPanel` required. Form submits normally. |

### 4.9 Do NOT

- ❌ Full-page navigation on tab click when JS is available
- ❌ Invent a JSON/fragment API just for tabs — reuse the HTML page
- ❌ Put delete forms / modals / CSRF-only chrome inside `#tabPanel`
- ❌ Bind panel listeners once at `DOMContentLoaded` without delegation
- ❌ Skip the loading spinner on slow tabs (history, permissions, …)
- ❌ Forget `?tab=` on pager links inside the history tab

---

## 5. Toolbar conventions

| Control | Class | Notes |
|---|---|---|
| Back | `a.toolbar-back` | Always leftmost |
| Spacer | `span.toolbar-spacer` | Pushes actions right (or clusters left — see CSS) |
| Save | `button.toolbar-save` + `form="…Form"` | HTML5 form association; disabled off info |
| Delete | `button.toolbar-delete` | Opens confirm modal; form lives outside `#tabPanel` |

---

## 6. Header / meta

```html
<h1 class="detail-page-title" th:text="${entity.name}">Name</h1>
<div class="detail-page-meta">
  <div class="meta-badges">
    <span class="status-pill status-active">ACTIVE</span>
    <!-- optional role-badge, code-badge, … -->
  </div>
  <span>Tạo lúc: <em>dd/MM/yyyy HH:mm</em></span>
  <span>Cập nhật: <em>…</em></span>
</div>
```

Status pill modifiers shipped by `detail-page.css`:
`status-active`, `status-inactive`, `status-locked`, `status-deleted`.

---

## 7. Section cards & forms

```html
<section class="detail-card">
  <h2 class="detail-card-title">Thông tin …</h2>
  <div class="detail-form-grid">
    <div class="form-row">…</div>
    <div class="form-row span-2">…</div>
    <div class="form-row span-3">…</div>
  </div>
</section>
```

- 3-column grid desktop → 2 → 1 (breakpoints in `detail-page.css`)
- Field errors stay **inline** next to the field (never toast)
- Top-level success/error uses `UlpToast` via `#flash-data`

---

## 8. History / audit tab

Standard shape (see user / department / AI provider):

```html
<section class="detail-card">
  <h2 class="detail-card-title">Lịch sử …</h2>
  <div th:if="${activitiesPage.empty}" class="detail-empty">…</div>
  <div th:if="${not activitiesPage.empty}">
    <table class="detail-table">…</table>
    <nav class="detail-pagination" th:if="${activitiesPage.totalPages > 1}">
      <a class="page-link" th:href="@{...(tab='history', page=${…})}">‹ Trước</a>
      <span class="page-current">Trang x / y</span>
      <a class="page-link" …>Sau ›</a>
    </nav>
  </div>
</section>
```

Page size convention: **20**. Only query when `activeDetailTab == 'history'`.

---

## 9. Checklist for a new detail screen

- [ ] CSS order follows §3
- [ ] `main` has class `detail-page`
- [ ] Sticky `.detail-toolbar` with back + save (`form=`) + optional delete
- [ ] Edit mode: title + `.detail-page-meta` + status pill
- [ ] Tabs use real `<a class="detail-tab" href="?tab=…">` links
- [ ] Active tab body wrapped in `#tabPanel`
- [ ] Delete form / confirm modal **outside** `#tabPanel`
- [ ] `detail-tabs.js` included (unless page owns a specialised orchestrator)
- [ ] Controller whitelists `tab`, lazy-loads heavy tab data
- [ ] Panel JS uses **event delegation** (survives swaps)
- [ ] Pager links preserve `tab=…`
- [ ] Flash → toast via `#flash-data` + shared drain
- [ ] No-JS fallback still works (full navigation)

---

## 10. Related files

| Path | Role |
|---|---|
| `static/css/detail-page.css` | Tokens + chrome (toolbar, tabs, cards, spinner) |
| `static/js/detail-tabs.js` | Shared AJAX tab orchestrator |
| `static/js/test-detail-tabs.js` | Test-edit specialised orchestrator |
| `static/js/invite-code.js` | Delegation example (invite tab) |
| `templates/admin/users-form.html` | Canonical multi-tab reference |
| `templates/admin/settings-ai-form.html` | AI provider detail + history |
| `CLAUDE.md` §9 | Toast / notification conventions |
