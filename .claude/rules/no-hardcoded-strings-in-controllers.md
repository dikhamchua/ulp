# No Hardcoded Strings in Controllers

> Quy tắc này áp dụng cho mọi `@Controller` / `@RestController` trong
> `src/main/java/com/ulp/**`. Mục tiêu: giữ controller method body không
> chứa magic string lặp lại, dễ refactor và đổi route/view/message.

---

## Pattern bắt buộc — `IConstant` interface + **static import**

Tất cả constants chia sẻ giữa các controller nằm trong:

```
com.ulp.common.IConstant
```

Controller dùng bằng **static import**, KHÔNG `implements IConstant`:

```java
package com.ulp.features.classes.controller;

import com.ulp.security.Roles;
// ... other imports

import static com.ulp.common.IConstant.*;

@Controller
@RequestMapping(BASE_LECTURER)
@PreAuthorize(Roles.PREAUTH_LECTURER_OR_ABOVE)
public class ClassesController {

    // method body có thể viết thẳng MODE_CREATE, VIEW_CLASS_FORM, ...
    // không cần IConstant.MODE_CREATE
}
```

### Tại sao static import, không `implements IConstant`?

`implements IConstant` là **constant interface anti-pattern** (Effective
Java Item 22):
- Constants bị leak vào public API của class. `ClassesController instanceof IConstant`
  trả `true` — vô nghĩa về domain.
- Subclass thừa kế constants không cần thiết — tight coupling implementation detail.
- Mọi class trong codebase nếu cần constants đều bị buộc implements interface.

Static import giữ constants là **implementation detail**, không leak ra API:
```java
import static com.ulp.common.IConstant.*;
```

Đây là pattern chuẩn JDK (`Math.PI`, `Assertions.assertEquals`, `Collectors.toList`).

---

## Áp dụng khi nào

Khi viết hoặc sửa controller, **TRƯỚC khi commit**, kiểm tra 5 nhóm sau:

1. **URL paths** — đường dẫn route, redirect target.
2. **View names** — chuỗi Thymeleaf view ("classes/form", "admin/users", ...).
3. **Model attribute keys** — tham số đầu tiên của `model.addAttribute(...)`.
4. **Flash messages** — text tiếng Việt trong `ra.addFlashAttribute("flashSuccess", "...")`.
5. **Magic enum-like strings** — tab names, mode values ("create", "edit", "info", "invite"), status keys.

Nếu một literal xuất hiện trong **>= 2 controller** hoặc lặp trong cùng class **>= 2 lần**,
BẮT BUỘC chuyển vào `IConstant`.

Nếu literal chỉ xuất hiện 1 lần trong 1 method và không có ý nghĩa domain
chung, GIỮ inline — không spam interface.

---

## Cấu trúc `IConstant` — nhóm có comment header

```java
public interface IConstant {

    // ───────── Route prefixes / canonical URLs ───────────────────────
    String BASE_LECTURER     = "/lecturer";
    String PATH_CLASSES      = "/classes";
    String URL_CLASSES_LIST  = BASE_LECTURER + PATH_CLASSES;

    // ───────── View names ────────────────────────────────────────────
    String VIEW_CLASS_MANAGE = "classes/manage";
    String VIEW_CLASS_FORM   = "classes/form";

    // ───────── Model attribute keys ──────────────────────────────────
    String ATTR_FORM          = "form";
    String ATTR_FLASH_SUCCESS = "flashSuccess";

    // ───────── Form mode discriminators ──────────────────────────────
    String MODE_CREATE = "create";
    String MODE_EDIT   = "edit";

    // ───────── Flash messages (Vietnamese UI text) ───────────────────
    String MSG_CLASS_CREATED = "Đã tạo lớp ";

    // ───────── Pagination ────────────────────────────────────────────
    int DEFAULT_PAGE_SIZE = 20;
}
```

### Naming convention

| Prefix | Nội dung | Ví dụ |
|---|---|---|
| `BASE_` | Path prefix gắn vào `@RequestMapping` class level | `BASE_LECTURER`, `BASE_ADMIN` |
| `PATH_` | Segment URL nhỏ, ghép vào `BASE_` | `PATH_CLASSES` |
| `URL_` | URL đầy đủ đã ghép từ BASE + PATH | `URL_CLASSES_LIST` |
| `VIEW_` | Tên Thymeleaf view, đặt theo `<feature>_<purpose>` | `VIEW_CLASS_FORM`, `VIEW_ADMIN_USERS` |
| `ATTR_` | Model attribute key | `ATTR_FORM`, `ATTR_ACTIVE_TAB` |
| `MODE_` | Form mode discriminator | `MODE_CREATE`, `MODE_EDIT` |
| `TAB_` | Tab key | `TAB_BOARD`, `TAB_SETTINGS` |
| `SUBTAB_` | Sub-tab key bên trong 1 tab | `SUBTAB_INFO`, `SUBTAB_INVITE` |
| `MSG_` | Flash message tiếng Việt | `MSG_CLASS_CREATED` |
| `DEFAULT_` | Giá trị mặc định cho config | `DEFAULT_PAGE_SIZE` |

---

## Helper khi URL có path variable

URL có `{id}` xuất hiện ≥ 2 nơi → tách `private static String fooUrl(Long id)`
trong controller:

```java
/** Builds the canonical URL for a single class. */
private static String classUrl(Long id) {
    return URL_CLASSES_LIST + "/" + id;
}
```

Dùng: `return "redirect:" + classUrl(id) + "/" + TAB_BOARD;`

Helper KHÔNG cho vào `IConstant` — nó dùng path variable, không phải hằng.

---

## Cái gì KHÔNG cho vào `IConstant`

- **Entity column names** — đặt trên entity dưới `@Column` annotation.
- **Role / permission strings** — đã có `com.ulp.security.Roles`.
- **Literal one-off** chỉ dùng 1 lần trong 1 method, không có ý nghĩa domain.
- **Path trong `@GetMapping`/`@PostMapping` value khi chỉ 1 method dùng** — giữ literal cho rõ ràng RESTful.
- **i18n strings sau khi setup `MessageSource`** — migrate sang `messages.properties` một lượt.

---

## Backward compatibility

**Giá trị string trong `IConstant` là CONTRACT với template Thymeleaf.**

Khi refactor:
- **Extract** literal sang constant: giá trị GIỮ NGUYÊN.
- **Đổi tên constant** (Java identifier): OK, không ảnh hưởng template.
- **Đổi giá trị constant**: PHẢI grep template + JS để update tất cả nơi consume key đó.

---

## Checklist khi review controller

- [ ] Có `import static com.ulp.common.IConstant.*;`?
- [ ] KHÔNG có `implements IConstant`?
- [ ] Không có `private static final String` inline (trừ constant specific cho 1 controller)?
- [ ] Có literal nào lặp lại trong nhiều controller mà chưa nằm trong `IConstant`?
- [ ] Path variable URL có dùng helper `xxxUrl(id)` trong controller không?
- [ ] Constants mới có theo naming convention (`ATTR_*`, `MSG_*`, `VIEW_*`, ...)?
- [ ] Template Thymeleaf vẫn nhận key/value GIỐNG trước khi refactor không?

---

## Tài liệu tham khảo

- **Interface gốc**: `src/main/java/com/ulp/common/IConstant.java`
- **Canonical example**: `src/main/java/com/ulp/features/classes/controller/ClassesController.java`
- CLAUDE.md project root, mục 9 (Java coding conventions).
- Effective Java, Item 22 — *Use interfaces only to define types*.
