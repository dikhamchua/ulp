# Deferred Upload on Save — Hoãn upload đến khi bấm Lưu

> Áp dụng cho MỌI màn hình / form có chức năng upload tài liệu (PDF, MP4,
> tệp đính kèm, ảnh, slide, v.v.) trong `src/main/resources/templates/**` +
> JS đi kèm trong `src/main/resources/static/js/**`. Mục tiêu: nút "Lưu" là
> điểm commit DUY NHẤT — không có gì lên server trước khi người dùng xác nhận.

---

## Nguyên tắc

1. **Chọn / kéo-thả file → KHÔNG upload ngay.** Chỉ ghi file vào biến chờ
   (pending) phía client và **hiển thị tên file đã chọn** ngay dưới ô upload,
   kèm gợi ý kiểu *"bấm Lưu thay đổi để tải lên"*.
2. **Chỉ khi bấm nút "Lưu thay đổi" / "Lưu"** mới thực sự: upload file lên
   server → persist → ghi lịch sử (activity log).
3. **Field text (URL video, link...) đi qua form field bình thường** — KHÔNG
   tự thêm nút "Lưu" riêng cho từng field. Nút Lưu chung của form lo tất.

---

## Tại sao

- Người dùng coi nút "Lưu" là điểm commit duy nhất. Upload/ghi nhận trước khi
  họ xác nhận gây cảm giác mất kiểm soát và để lại **rác trên server** khi họ
  chọn nhầm rồi rời trang.
- Đồng nhất hành vi giữa mọi loại nội dung (text field, file) — tất cả cùng
  lưu tại một thời điểm.

---

## Cách triển khai (pattern bắt buộc)

### 1. Một orchestrator submit DUY NHẤT trên mỗi `<form>`

Form thường đã có sẵn ≥ 1 submit listener (vd Quill copy HTML vào hidden
field, modal xác nhận đổi loại). **TUYỆT ĐỐI KHÔNG** thêm submit listener thứ
hai độc lập để upload file — sẽ gây **double-submit / race**. Gộp mọi "gate"
(xác nhận → upload file chờ → submit thật) vào MỘT handler tuần tự:

```js
form.addEventListener('submit', function (e) {
    if (proceeding) return;          // lần submit thật: để native submit chạy
    e.preventDefault();
    confirmIfNeeded(function (ok) {   // gate 1: xác nhận (nếu có)
        if (!ok) return;
        uploadPendingFiles(function (ok2) {  // gate 2: upload file chờ (await)
            if (!ok2) return;        // upload lỗi → KHÔNG submit
            proceeding = true;
            form.requestSubmit();    // requestSubmit (KHÔNG submit()) để
        });                          // các listener khác (Quill) vẫn chạy
    });
});
```

Canonical example: `bindSubmitFlow` trong
`src/main/resources/static/js/lesson-form-type.js`.

### 2. Upload PHẢI xong TRƯỚC khi POST form

Backend có thể validate dữ liệu đọc từ DB chứ không từ form. Ví dụ
`LessonContentTypeSwitcher.validateRequiredDataPresent` check `video_url` /
`pdf_attachment_id` trên entity — nếu file chưa upload xong, switch type sẽ
ném lỗi. Luôn `await` upload rồi mới `requestSubmit()`.

### 3. Dùng `requestSubmit()`, KHÔNG `submit()`

`form.submit()` bỏ qua mọi submit listener (Quill copy HTML sẽ không chạy →
mất nội dung). `requestSubmit()` dispatch submit event thật.

### 4. Mẫu drop-zone đã có sẵn — tái sử dụng

- `.lvid-drop` (`#lessonVideoDrop`) — video MP4
- `.lpdf-drop` (`#lessonPdfDrop`) — PDF chính
- `#lessonAttachDrop` (`.latt-drop`) — tệp đính kèm

Mỗi drop-zone: click/Enter mở picker + kéo-thả + `.is-dragover` khi hover file.

---

## Ngoại lệ

- **Tệp đính kèm phụ (supplementary attachments)** có thể giữ upload-on-select
  nếu chúng KHÔNG phải là dữ liệu bắt buộc của form chính và không bị validate
  khi save (chúng là thao tác AJAX độc lập, xoá được riêng). Cân nhắc theo
  từng màn hình — nếu người dùng phàn nàn thì chuyển sang deferred.
- Field text không bao giờ cần upload → luôn đi qua form field, không nút riêng.

---

## Checklist khi review form có upload

- [ ] Chọn file KHÔNG upload ngay — chỉ hiện tên file đã chọn?
- [ ] Upload + persist + ghi lịch sử chỉ xảy ra khi bấm "Lưu"?
- [ ] Chỉ có ĐÚNG MỘT submit listener orchestrator trên `<form>`?
- [ ] Upload file `await` xong TRƯỚC khi `requestSubmit()`?
- [ ] Dùng `requestSubmit()` (không `submit()`) để listener khác vẫn chạy?
- [ ] Field text không có nút "Lưu" riêng — đi qua form field chung?
- [ ] Upload lỗi thì KHÔNG submit form (dừng tại gate)?

---

## Tài liệu tham khảo

- Canonical example: `src/main/resources/static/js/lesson-form-type.js`
  (`bindSubmitFlow`, `uploadPendingVideo`, `bindVideoUpload`).
- Template: `src/main/resources/templates/classes/lesson-form.html`
  (section VIDEO + `#lessonVideoSelected`).
- CSS drop-zone: `src/main/resources/static/css/lesson-content-type.css`
  (`.lvid-drop`, `.lvid-selected`, `.lpdf-drop`).
