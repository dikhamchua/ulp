# Rule Review — Màn Detail / Edit (Detail Page Pattern)

> Dùng khi review hoặc implement màn **chi tiết / chỉnh sửa 1 entity** trong shell admin hoặc lecturer.
> Nguồn chuẩn: `docs/design-system/detail-page-pattern.md`.
> Reference: `templates/admin/users-form.html`, `departments-form.html`, `subjects-form.html`.

---

## 1. Khi nào áp dụng

Áp dụng nếu màn hình có **một hoặc nhiều** đặc điểm sau:

- [ ] Toolbar sticky (Quay lại / Lưu / Xóa)
- [ ] Header title + status / meta
- [ ] Sub-tabs (Thông tin, Lịch sử, …)
- [ ] Section card chứa form hoặc bảng

**Không** dùng pattern này cho list-only, dashboard, modal nhỏ, hoặc form create cực tối giản không cần chrome detail.

---

## 2. CSS load order (bắt buộc)

Thứ tự stylesheet trong `<head>` phải đúng cascade:

1. `app-shell.css`
2. `class-detail.css`
3. **`detail-page.css`**
4. `admin.css` (hoặc shell tương đương)
5. CSS page-specific (`admin-users.css`, `admin-departments.css`, …)

Checklist:

- [ ] Có `detail-page.css`
- [ ] Thứ tự không đảo (detail-page trước page-specific)
- [ ] Dùng `class="page-css"` trên link page nếu project đang theo convention đó

---

## 3. Anatomy khung trang

```
main.col.detail-page
├── #flash-data
├── .detail-toolbar
│   ├── a.toolbar-back
│   ├── span.toolbar-spacer
│   ├── button.toolbar-save   (form="…Form")
│   └── button.toolbar-delete (optional)
├── h1.detail-page-title                 (edit: tên entity)
├── .detail-page-meta                    (status pill, mã, timestamps…)
├── nav.detail-tabs                      (edit + có tab)
└── #tabPanel
    └── body của tab active only
```

Checklist:

- [ ] `main` có class `detail-page`
- [ ] Có `#flash-data` nếu server flash success/error (toast qua `notifications.js`, không drain 2 lần)
- [ ] Create mode: title tĩnh kiểu “Tạo …” — **không** bắt buộc tab strip
- [ ] Edit mode: title = **tên entity** (email / tên môn / tên bộ môn), không phải chữ “Chỉnh sửa …” chung chung

---

## 4. Toolbar

| Control | Class | Rule |
|---|---|---|
| Quay lại | `a.toolbar-back` | Luôn trái nhất, trỏ về list |
| Spacer | `span.toolbar-spacer` | Đẩy action sang phải |
| Lưu | `button.toolbar-save` + `form="entityForm"` | HTML5 form association; **disabled** khi tab ≠ `info` |
| Xóa | `button.toolbar-delete` | Optional; confirm trước khi submit |

Checklist:

- [ ] Lưu dùng `form="…"` trỏ đúng id form info
- [ ] Edit + multi-tab: `th:disabled="${mode == 'edit' and activeDetailTab != 'info'}"`
- [ ] Form xóa **không** nằm trong `#tabPanel` (sống sót sau AJAX swap)
- [ ] Không nhét nút “Sửa” thừa trên list nếu đã vào detail bằng link entity

---

## 5. Header / meta

```html
<h1 class="detail-page-title">…</h1>
<div class="detail-page-meta">
  <div class="meta-badges">
    <span class="status-pill status-active|status-inactive">…</span>
  </div>
  <span>Mã: <em>…</em></span>
  <span>…timestamps nếu có…</span>
</div>
```

Checklist:

- [ ] Có `.detail-page-meta` ở edit mode
- [ ] Status pill dùng modifier chuẩn: `status-active`, `status-inactive`, …
- [ ] Meta phản ánh state thật (active, code, department…), không hardcode

---

## 6. Tabs + AJAX (quan trọng)

### 6.1 Khi có tab

- [ ] Dùng `<nav class="detail-tabs">` + `<a class="detail-tab" href="...?tab=…">`
- [ ] Tab active bind server-side lần đầu (`activeDetailTab`)
- [ ] Body tab active **duy nhất** bọc trong `#tabPanel`
- [ ] Include `static/js/detail-tabs.js` (sau page script nếu có)
- [ ] **Cấm** full-page navigation khi JS available (orchestrator phải intercept)
- [ ] Request AJAX mang `X-Requested-With: XMLHttpRequest`
- [ ] Chỉ swap `#tabPanel`; toolbar / h1 / sidebar giữ nguyên
- [ ] Có loading spinner chuẩn (class của `detail-page.css`), không invent spinner riêng
- [ ] `history.pushState` / Back-Forward vẫn đúng tab
- [ ] No-JS: click tab vẫn mở được nhờ URL `?tab=`

### 6.2 Controller

- [ ] `@RequestParam tab` default `info`
- [ ] Whitelist tab; giá trị lạ → fallback `info`
- [ ] Data nặng (history) **chỉ query** khi tab đó active
- [ ] **Không** tạo fragment/JSON API riêng chỉ để đổi tab
- [ ] Cùng endpoint trả full HTML; JS scrape `#tabPanel`

### 6.3 Save button sync

- [ ] Server disable Lưu khi tab ≠ info
- [ ] `detail-tabs.js` tiếp tục disable/enable sau mỗi swap

### 6.4 JS trong panel

- [ ] Listener trong panel dùng **event delegation** (document-level)
- [ ] Không `querySelectorAll(…).forEach(addEventListener)` one-shot trong panel (chết sau swap)
- [ ] Delete form / modal / CSRF chrome **ngoài** `#tabPanel`

### 6.5 Khi chưa có tab

- [ ] Không bắt buộc `detail-tabs.js`
- [ ] Vẫn nên giữ anatomy toolbar + title + card cho đồng bộ UX
- [ ] Khi sau này thêm tab: phải làm đủ §6.1–6.4

---

## 7. Section card & form

- [ ] Mỗi khối nội dung: `section.detail-card` + `h2.detail-card-title`
- [ ] Field layout: `div.detail-form-grid` + `div.form-row` (`span-2` / `span-3` khi cần)
- [ ] Lỗi field: **inline** cạnh field — không đẩy ra toast
- [ ] Success/error top-level: flash → `#flash-data` → toast (single owner `notifications.js`)
- [ ] Boolean hiện/ẩn: ưu tiên **switch** (pattern `dept-switch`), không chỉ text “Hiện/Ẩn” + nút phụ nếu list/detail đã chuẩn hóa switch
- [ ] Create mode: có thể có `.form-actions` bottom; edit mode ưu tiên toolbar Lưu

---

## 8. Tab Lịch sử / audit (nếu có)

- [ ] Bảng audit riêng (append-only), FK entity + actor nullable
- [ ] Ghi log ở create / update / toggle / delete (và mutation quan trọng khác)
- [ ] UI: `section.detail-card` + empty state `.detail-empty` hoặc `table.detail-table`
- [ ] Pager: `.detail-pagination` + `.page-link`, **giữ `tab=history`**
- [ ] Page size mặc định **20**
- [ ] Chỉ load page history khi `activeDetailTab == history`

---

## 9. List → Detail entry

Khi list dẫn vào detail:

- [ ] Identifier chính (mã, tên, email) là **`<a href="…/edit">`** nhìn rõ là link (màu primary + underline hoặc pattern project)
- [ ] Không bắt buộc nút “Sửa” riêng nếu đã có link entity
- [ ] Toggle active trên list (nếu có) không thay thế màn detail

---

## 10. Authorization & data

- [ ] Tầng 1: `@PreAuthorize` / role gate đúng URL
- [ ] Tầng 2: scope dữ liệu (department/owner) trong service — không chỉ ẩn UI
- [ ] Role thiếu scope: empty state / 403 / 404 — **không 500**
- [ ] POST mutate: assert bằng DB/state, không chỉ status code

---

## 11. Checklist review nhanh (copy vào PR)

```
Detail page review
- [ ] CSS order đúng (§2)
- [ ] main.detail-page + toolbar back/save (+ delete nếu cần)
- [ ] Edit title = entity name + meta/status
- [ ] Form card + grid; field error inline; flash toast đúng owner
- [ ] Có tab? → AJAX detail-tabs.js + #tabPanel + whitelist tab + lazy data
- [ ] Lưu disabled off info tab
- [ ] Delete form ngoài #tabPanel
- [ ] History (nếu có): audit write + table/empty + pager giữ tab=
- [ ] List entry link rõ ràng vào /edit
- [ ] Auth 2 tầng OK; empty state không 500
```

---

## 12. Anti-patterns (reject khi review)

- ❌ Full reload mỗi lần đổi tab trong khi đã có JS
- ❌ Fragment endpoint / JSON chỉ để đổi tab
- ❌ Title edit mode là “Chỉnh sửa …” chung, không hiện entity
- ❌ Toast cho lỗi field validation
- ❌ Drain `#flash-data` trong page-script (double toast)
- ❌ Bind event panel một lần rồi để AJAX làm chết listener
- ❌ Nhét form xóa trong `#tabPanel`
- ❌ Query full history luôn dù user đang ở tab info

---

## 13. File tham chiếu

| Path | Vai trò |
|---|---|
| `docs/design-system/detail-page-pattern.md` | Spec UX/tech |
| `static/css/detail-page.css` | Chrome, tabs, spinner |
| `static/js/detail-tabs.js` | AJAX tab orchestrator |
| `templates/admin/users-form.html` | Multi-tab đầy đủ |
| `templates/admin/departments-form.html` | Info + history |
| `templates/admin/subjects-form.html` | Catalog entity + history AJAX |

---

*Cập nhật khi `detail-page-pattern.md` đổi. Reviewer tick §11 trước khi approve PR đụng màn detail.*
