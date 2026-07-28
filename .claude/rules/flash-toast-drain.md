# Flash → Toast Drain Rules (ULP)

**IMPORTANT:** Apply these rules whenever adding, changing, or reviewing code that shows a message to the user — flash message từ server, toast từ client, hoặc bất kỳ page-script mới nào chạy trên trang có `fragments/app-header.html`.

Owner: `src/main/resources/static/js/notifications.js`

---

## When this applies

Trigger on any of:
- Thêm/sửa `redirectAttributes.addFlashAttribute("flashSuccess"|"flashError"|"flashInfo", ...)`
- Thêm/sửa `<span id="flash-data" th:attr="data-flash-...">` trong template
- Viết page-script JS mới dưới `static/js/` cho trang có nạp app-header
- Copy-paste một khối "Flash → toast on page load" từ file JS khác (dừng lại — đó chính là bug)
- Bất kỳ báo cáo nào về "toast hiện 2 lần" / "thông báo bị lặp"

---

## Hard rules

1. **`notifications.js` là chủ sở hữu DUY NHẤT của việc drain `#flash-data`.**
   - Page script TUYỆT ĐỐI không được đọc `#flash-data` để bắn toast
   - ❌ `var f = document.getElementById('flash-data'); if (f.dataset.flashSuccess) UlpToast.success(...)`
   - ✅ Không viết gì cả — `notifications.js` đã lo

2. **Báo user từ server → chỉ cần flash attribute + template span.**
   ```java
   redirectAttributes.addFlashAttribute("flashSuccess", "Đã cập nhật bài giảng");
   ```
   ```html
   <span id="flash-data"
         th:attr="data-flash-success=${flashSuccess},data-flash-error=${flashError}"
         style="display:none;"></span>
   ```
   Xong. **Không viết thêm dòng JS nào.** Trang phải include `fragments/app-header.html`.

3. **Báo user từ client (kết quả AJAX) → gọi thẳng `window.UlpToast`.**
   - ✅ `window.UlpToast.success('Đã sao chép mã lớp')`
   - ❌ Nhét message vào `#flash-data` rồi drain lại — `#flash-data` chỉ dành cho payload SSR

4. **Trang không có `fragments/app-header.html` phải tự drain bằng inline script.**
   - Hiện chỉ có `auth/login.html` và `auth/forgot-password.html`
   - Đây là ngoại lệ đã biết; inline trong template nên không dính allowlist của `FlashDrainSingleOwnerTest`
   - Thêm trang không-app-header mới → cân nhắc dùng app-header trước khi tự drain

5. **Lỗi validation field (BindingResult) render inline cạnh field, KHÔNG bắn toast.**
   - Toast dành cho thông báo top-level; lỗi field thuộc về form (xem `CLAUDE.md` mục 9)

---

## Building blocks (do not reinvent)

| Type | Role |
|---|---|
| `static/js/notifications.js` → `drainFlash()` | Drainer duy nhất. Chạy trong `boot()` khi DOM ready |
| `static/js/app.js` → `window.UlpToast` | Wrapper iziToast: `success` / `error` / `info` / `warning` |
| `fragments/app-header.html` | Nạp `notifications.js` — có fragment này là có drain |
| `<span id="flash-data" data-flash-*>` | Payload SSR do controller flash ra |
| `FlashDrainSingleOwnerTest` (`src/test/java/com/ulp/common/`) | Guard cơ học: fail build nếu page-script drain lại |

Flow:

```
controller
  → redirectAttributes.addFlashAttribute("flashSuccess", msg)
  → template render <span id="flash-data" data-flash-success="...">
  → app-header nạp notifications.js
  → drainFlash(): set data-flash-drained → remove data-flash-* → UlpToast.success
```

---

## Cơ chế guard `data-flash-drained`

`drainFlash()` làm đúng thứ tự này, và thứ tự là quan trọng:

1. Bỏ qua nếu `data-flash-drained === '1'` (đã có ai đó drain rồi)
2. Đọc `data-flash-success` / `data-flash-error` / `data-flash-info` ra biến local
3. **Set `data-flash-drained = '1'` TRƯỚC khi bắn toast**
4. **Remove cả 3 attribute `data-flash-*`**
5. Mới gọi `UlpToast.*`

Lý do phải remove attribute chứ không chỉ set guard: script cũ trong repo đọc thẳng `data-flash-success` mà **không** check guard. Set guard thôi thì chúng vẫn thấy attribute và vẫn bắn toast. Remove attribute làm payload biến mất → defence in depth, kể cả khi ai đó lỡ thêm drain mới chạy sau.

Guard chỉ là lưới an toàn. **Rule số 1 (page script không drain) mới là biện pháp chính** — vì script nạp đồng bộ (không `defer`) chạy TRƯỚC `notifications.js`, lúc đó guard còn trống nên guard không cứu được.

---

## Lịch sử — bug đã xảy ra

Trang `/lecturer/classes/{id}/sections/{sid}/lessons/{lid}/edit` hiện **2 toast trùng** "Đã cập nhật bài giảng" khi bấm "Lưu thay đổi".

Đo bằng Playwright trên app thật:
- Đúng 1 POST `.../edit` (302) + 1 POST `.../content/pdf` (200) → **không phải** double submit
- `visibleToastCount = 2`, cả hai cùng nội dung
- Toast #1 stack: `class-detail.js:14` — nạp đồng bộ, chạy trước, bắn toast rồi **để nguyên attribute**
- Toast #2 stack: `drainFlash (notifications.js:34)` — boot sau, thấy guard trống + attribute còn → bắn lại

9 file từng vi phạm (đã gỡ drain): `class-detail.js`, `classes.js`, `lecturer-dashboard.js`, `student-classes.js`, `assignments.js`, `student-lesson-nav.js`, `flashcard-common.js`, `head-department.js`, `question-bank.js`.

Bug ảnh hưởng MỌI trang nạp 1 trong 9 script đó, không riêng lesson-edit. `head-department.js` và `question-bank.js` từng được vá cục bộ bằng guard `data-flash-drained` — vá đúng hướng nhưng chỉ chữa xung đột giữa 2 file đó, không chữa xung đột với `notifications.js`. Nay cả 9 đều đã gỡ hẳn.

---

## Checklist before merging flash/toast work

- [ ] Không có page-script nào đọc `data-flash-*` / `dataset.flashSuccess` ngoài `notifications.js`
- [ ] `FlashDrainSingleOwnerTest` PASS
- [ ] Trang mới có `#flash-data` thì phải include `fragments/app-header.html` (hoặc là ngoại lệ auth đã biết)
- [ ] Toast từ AJAX gọi thẳng `window.UlpToast`, không đi vòng qua `#flash-data`
- [ ] Lỗi validation field vẫn render inline, không đẩy ra toast
- [ ] Không dùng `alert()` native hay `<div class="alert">` inline

---

## Do not

- ❌ Copy khối "Flash → toast on page load" sang file JS mới
- ❌ Thêm file vào allowlist của `FlashDrainSingleOwnerTest` để test xanh — gỡ drain mới đúng
- ❌ Dựa vào guard `data-flash-drained` để biện minh cho việc thêm drain thứ hai
- ❌ Bắn toast rồi để nguyên attribute `data-flash-*` trên DOM
- ❌ Nhét message client-side vào `#flash-data` để "tái sử dụng" drainer
