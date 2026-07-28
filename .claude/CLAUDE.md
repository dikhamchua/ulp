# Project-Local Rules for Claude

Tài liệu này được Claude Code đọc tự động khi mở project. Liệt kê các rule
cụ thể cho codebase ULP — đọc TRƯỚC khi viết hoặc sửa code.

## Rules

| File | Áp dụng cho | Mục đích |
|---|---|---|
| [`rules/no-hardcoded-strings-in-controllers.md`](./rules/no-hardcoded-strings-in-controllers.md) | `@Controller` / `@RestController` trong `src/main/java/com/ulp/**` | Extract magic string vào `com.ulp.common.IConstant` interface, dùng `import static com.ulp.common.IConstant.*` (KHÔNG `implements`). |
| [`rules/code-comments.md`](./rules/code-comments.md) | Mọi file Java / JS | Comment "why" ngắn gọn 1 dòng cho logic điều kiện; Javadoc dài chỉ cho contract phức tạp. |
| [`rules/deferred-upload-on-save.md`](./rules/deferred-upload-on-save.md) | Mọi form/màn hình upload tài liệu (template + JS) | Chọn file chỉ hiển thị tên, KHÔNG upload ngay; bấm "Lưu" mới upload + persist + ghi lịch sử. Một orchestrator submit duy nhất, `requestSubmit()`. |
| [`rules/flash-toast-drain.md`](./rules/flash-toast-drain.md) | Mọi code hiển thị thông báo cho user (flash server, toast client, page-script mới) | `notifications.js` là chủ sở hữu DUY NHẤT việc drain `#flash-data`; page script không tự đọc để bắn toast (gây toast lặp 2 lần). |
| [`rules/mail-job-queue.md`](./rules/mail-job-queue.md) | Mọi code gửi email hoặc fan-out notification tới nhiều user | Không gửi SMTP trong vòng lặp trên request thread; fan-out phải enqueue `MailJob` qua `MailJobEnqueueHelper.enqueueAfterCommit`. |

## Khi nào đọc

- Mở project lần đầu trong session.
- Trước khi tạo controller mới hoặc refactor controller hiện có.
- Trong code review — dùng rule làm checklist.

## Bổ sung rule

Thêm file `.md` mới vào `rules/` rồi cập nhật bảng phía trên.
Mỗi rule nên có: phạm vi áp dụng, pattern bắt buộc, ngoại lệ, checklist.
