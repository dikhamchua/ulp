# Code Comments — Keep It Short and Useful

> Áp dụng cho mọi file Java/JS trong project. Mục tiêu: comment giúp người
> đọc hiểu **why**, không lặp lại **what** đã rõ trong code.

---

## Nguyên tắc

1. **Comment "why", không "what"** — code đã nói nó làm gì, comment giải thích lý do.
2. **1 dòng `//` ≤ 80 ký tự** cho logic inline. Không Javadoc-style nhiều paragraph cho 1 cái `if`.
3. **Tiếng Anh** cho comment code (kể cả khi nội dung UI tiếng Việt). Theo CLAUDE.md mục 9.
4. **Không spam** — code self-evident không cần comment.

---

## Javadoc (`/** */`)

BẮT BUỘC trên:
- Mọi `public class` / `public interface`.
- Mọi `public` method có logic (không phải getter/setter trivial).
- `private` helper khi ý đồ không hiển nhiên qua tên.

Format gọn:
```java
/** Maps a tab key to its Vietnamese sidebar label; unknown keys pass through. */
private static String labelFor(String tab) { ... }
```

Javadoc dài (nhiều paragraph `<p>`) chỉ dành cho:
- Class-level với contract phức tạp (endpoints, auth, validation rules).
- Method có behavior tinh tế (race conditions, soft-delete, two-phase write).

---

## Inline comment (`//`)

THÊM cho:
- `if` điều kiện có business intent (vd: whitelist, fallback, validation re-render).
- Switch `default` branch khi hành vi không trivial.
- Stream/lambda chain trả `null` / `Optional.empty()`.
- Magic operation (substring trick, bit manipulation, regex).
- Workaround / known limitation (kèm link issue hoặc lý do).

KHÔNG thêm cho:
- `model.addAttribute("foo", bar)` — tự rõ.
- `return ResponseEntity.ok()` — tự rõ.
- Standard DI constructor.
- Trivial getter/setter.

---

## Ví dụ

### Tốt — comment "why" ngắn gọn

```java
// Preserve flashed form values from a prior failed POST.
if (!model.containsAttribute(ATTR_FORM)) {
    model.addAttribute(ATTR_FORM, ClassForm.empty());
}

// Whitelist: only CODE or LINK invite types are valid.
if (!ClassInviteCode.TYPE_CODE.equals(type) && !ClassInviteCode.TYPE_LINK.equals(type)) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MSG_INVALID_INVITE_TYPE);
}

// Active invite tokens may be absent for legacy classes — render null.
InviteCodeView activeCode = inviteCodeService.findActiveCode(clazz.getId())
        .map(ic -> new InviteCodeView(ic.getCode(), ic.getId(), ic.getUseCount()))
        .orElse(null);
```

### Xấu — comment lặp lại "what"

```java
// Add form attribute to model              ← lặp lại code
model.addAttribute(ATTR_FORM, form);

// If has errors, re-render                 ← lặp lại if
if (result.hasErrors()) { ... }

// Loop through items                       ← lặp lại for
for (Item item : items) { ... }
```

### Xấu — Javadoc dài cho logic đơn giản

```java
/**
 * Checks whether the model already contains a form attribute. This is
 * important because when a previous POST request fails validation, Spring
 * flashes the bound form back into the model via flash attributes...
 * (10 dòng nữa)
 */
if (!model.containsAttribute(ATTR_FORM)) { ... }
```

Sửa thành 1 dòng `// Preserve flashed form values from a prior failed POST.`

---

## Checklist khi review

- [ ] Mọi `public` class/method có Javadoc?
- [ ] Mọi `if` có business intent (whitelist, fallback) có comment "why"?
- [ ] Switch `default` có comment giải thích fallback?
- [ ] Comment ≤ 80 ký tự, không Javadoc-style cho logic inline?
- [ ] Không có comment lặp lại "what" của code?
- [ ] Comment tiếng Anh (không phải tiếng Việt)?
- [ ] Workaround / TODO có link issue hoặc lý do?

---

## Tài liệu tham khảo

- Canonical example: `ClassesController.java`, `SectionsService.java`.
- CLAUDE.md mục 9 — Coding conventions.
