# Rule Review — Màn Danh Sách (List Screen Pattern)

> Dùng khi review hoặc implement màn **danh sách / quản lý 1 entity** (list có filter, bảng, thao tác hàng loạt).
> Nguồn chuẩn: tổng hợp từ các yêu cầu UI thực tế trên màn `/head/question-bank`, `/lecturer/question-bank`, `/admin/subjects`.
> Reference: `templates/questionbank/list.html`, `templates/questionbank/manage.html`, `templates/admin/subjects.html`.

---

## 1. Khi nào áp dụng

Áp dụng nếu màn hình có **một hoặc nhiều** đặc điểm sau:

- [ ] Toolbar hành động (Thêm mới / Tải file mẫu / Import / …)
- [ ] Filter bar (dropdown, ô tìm kiếm, nút Lọc / Xoá lọc)
- [ ] Bảng dữ liệu có cột "Thao tác" và checkbox chọn hàng loạt
- [ ] Cột identifier (tên / code / nội dung) là entry point vào trang edit

**Không** dùng pattern này cho detail/edit form đơn, dashboard, hoặc modal nhỏ.

---

## 2. Layout tổng thể (bắt buộc)

```
Toolbar  →  [Thêm mới]  [Tải file mẫu]  [Import …]
Filter    →  [dropdown 1] [dropdown 2] [ô tìm kiếm] [Lọc] [Xoá lọc]
Bảng      →  checkbox-bulk | Cột-identifier | các cột dữ liệu | Trạng thái | Thao tác
```

Checklist:

- [ ] Dùng **bảng + filter bar**, KHÔNG dùng kiểu drill-down chips ("chọn X rồi mới thấy bảng")
- [ ] Filter dropdown phụ thuộc nhau (VD: chọn Môn → tự populate Chương) qua `data-qb-subject-select` / `data-qb-chapter-select` + inline JSON `QbChaptersBySubject`
- [ ] Bảng có checkbox "Chọn tất cả" + per-row checkbox để thao tác hàng loạt (bulk)
- [ ] Nút bulk dùng `formaction` (cùng 1 form bọc bảng)

---

## 3. Cột identifier = thẻ `<a>` màu xanh → vào edit (quan trọng)

**Quy tắc vàng:** KHÔNG bao giờ để button/link "Sửa" hoặc "Xem" trong cột "Thao tác". Cột identifier là entry point DUY NHẤT để xem/edit — bấm vào nội dung là vào trang chi tiết/edit luôn.

Checklist:

- [ ] Cột identifier (title / code / nội dung) là `<a>` trỏ thẳng `/…/{id}/edit`
- [ ] Cột "Thao tác" CHỈ chứa hành động ghi/mutate (Lưu trữ / Bỏ lưu trữ / Xoá / …) — KHÔNG có "Sửa", KHÔNG có "Xem"
- [ ] Hành động ở cột "Thao tác" phải là **button thật** (`btn-ghost btn-sm`) — KHÔNG dùng `.link-button` (text không viền, trông như link)
- [ ] Màu link dùng token primary: `var(--primary, #1E88E5)`, `font-weight: 600`, gạch chân khi hover

```html
<!-- Cột identifier: entry point xem/edit -->
<td>
  <a class="qb-title-link" th:href="@{'/lecturer/question-bank/' + ${item.id()} + '/edit'}">Nội dung</a>
</td>
<!-- Cột Thao tác: CHỈ hành động mutate (Lưu trữ / Bỏ lưu trữ / Xoá) — dùng button, không dùng link -->
<td class="qb-row-actions">
  <form th:if="${item.archivable()}" th:action=".../archive" method="post" class="qb-inline-form">
    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
    <button type="submit" class="btn-ghost btn-sm">Lưu trữ</button>
  </form>
</td>
```

CSS:

```css
.qb-title-link {
  color: var(--primary, #1E88E5);
  font-weight: 600;
  text-decoration: none;
}
.qb-title-link:hover {
  color: var(--primary-dark, #1565C0);
  text-decoration: underline;
}
```

---

## 4. Trạng thái active/ẩn = switch (không phải text + nút phụ)

- [ ] Dùng `<label class="dept-switch">` + checkbox `onchange="this.form.submit()"` (pattern có sẵn trong `admin-departments.css`)
- [ ] KHÔNG hiển thị text "Hiện/Ẩn" + nút riêng trong list

```html
<label class="dept-switch">
  <input type="checkbox" th:checked="${row.active()}" onchange="this.form.submit()">
  <span class="dept-switch-slider" aria-hidden="true"></span>
</label>
```

---

## 5. Tiêu đề trang

- [ ] `h1.detail-title` có margin-top nhẹ, không dính sát toolbar:

```css
.qb-page .detail-title {
  margin-top: 0.35rem;
  margin-bottom: 0.35rem;
}
```

---

## 6. Dropdown phân cấp: ẩn placeholder / inactive

- [ ] Subject placeholder (VD `UNASSIGNED`) là inactive → KHÔNG hiện trong dropdown tạo mới
- [ ] Chỉ hiện placeholder ở màn quản trị/HEAD (để xử lý dữ liệu legacy)
- [ ] DTO `SubjectOption` mang field `active` để template lọc `th:if="${subject.active()}"`

---

## 7. Form tạo/sửa: auto-derive thuộc tính thay vì bắt user chọn

- [ ] Bỏ dropdown "Loại câu hỏi" — user tick bao nhiêu đáp án đúng thì tự suy: **1 đáp án → MCQ, nhiều đáp án → MR**
- [ ] Server derive (VD `deriveQuestionType(List<Option>)`), client không cần gửi type
- [ ] Hint text hướng dẫn: "Tích 1 đáp án để tạo câu hỏi một đáp án (MCQ), tích nhiều để tạo câu hỏi nhiều đáp án (MR)"

---

## 8. Menu / link theo role

- [ ] Link menu phân biệt role để không đưa người dùng vào trang trống sai nơi
  - LECTURER → bank cá nhân
  - HEAD → bank bộ môn
- [ ] Dùng `sec:authorize="hasRole('…')"` riêng từng link thay vì `hasAnyRole` khi đích đến khác nhau

```html
<a sec:authorize="hasRole('LECTURER')" th:href="@{/lecturer/question-bank}">…</a>
<a sec:authorize="hasRole('HEAD')"     th:href="@{/head/question-bank}">…</a>
```

---

## 9. Checklist review nhanh (copy vào PR)

```
List screen review
- [ ] Bảng + filter bar; không drill-down chips (§2)
- [ ] Cột identifier là <a> xanh vào /edit (§3)
- [ ] Cột Thao tác KHÔNG có "Sửa" VÀ KHÔNG có "Xem" (§3)
- [ ] Trạng thái dùng switch dept-switch (§4)
- [ ] Title có margin-top (§5)
- [ ] Dropdown ẩn placeholder inactive (§6)
- [ ] Form auto-derive type từ số đáp án (§7)
- [ ] Menu theo role (§8)
- [ ] Auth 2 tầng OK; empty state không 500
- [ ] Flash → toast đúng owner (notifications.js); không drain trùng
```

---

## 10. Anti-patterns (reject khi review)

- ❌ Button/link "Sửa" hoặc "Xem" nằm trong cột "Thao tác" — entry point phải là thẻ `<a>` ở cột identifier
- ❌ Dùng `.link-button` cho hành động cột "Thao tác" (text không viền trông như link) — phải là `btn-ghost btn-sm` button thật
- ❌ Drill-down chips: phải chọn subject/chương rồi mới thấy bảng
- ❌ Text "Hiện/Ẩn" + nút phụ thay vì switch
- ❌ Hiện placeholder/inactive (UNASSIGNED) trong dropdown tạo mới
- ❌ Bắt user chọn "Loại câu hỏi" thay vì auto-derive
- ❌ Link menu chung `hasAnyRole` dẫn người dùng sang nhầm trang trống
- ❌ Drain `#flash-data` trong page-script (double toast)

---

## 11. File tham chiếu

| Path | Vai trò |
|---|---|
| `templates/questionbank/list.html` | List lecturer (filter subject/chương/q + bảng) |
| `templates/questionbank/manage.html` | List HEAD (bảng + filter + bulk archive) |
| `templates/admin/subjects.html` | List admin (code-link + dept-switch) |
| `static/css/question-bank.css` | `.qb-title-link`, `.qb-toolbar`, margin title |
| `static/css/admin-departments.css` | `.dept-switch` |
| `static/js/question-bank.js` | Dependent subject → chapter dropdown |
| `static/js/question-bank-form.js` | Auto-derive type từ số đáp án tick |
| `fragments/app-header.html` | Link menu theo role |

---

*Cập nhật khi pattern list screen đổi. Reviewer tick §9 trước khi approve PR đụng màn danh sách.*
