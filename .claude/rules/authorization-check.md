# Authorization Check Rules (ULP)

**IMPORTANT:** Apply these rules whenever adding, changing, or reviewing code that
exposes data or an action to a user — controller endpoint, service method that
reads/writes owned rows, or template that shows a link/button. Phân quyền ở ULP
có **hai tầng**; kiểm tra một tầng là chưa đủ.

---

## When this applies

Trigger on any of:
- Thêm/sửa `@GetMapping` / `@PostMapping` bất kỳ
- Thêm/sửa `@PreAuthorize`, `requestMatchers(...)` trong `SecurityConfig`
- Thêm/sửa `sec:authorize` trong template, hoặc thêm link/nút mới
- Đụng vào `*AccessPolicy` / `*Resolver` / `*Guard`
- Query có điều kiện theo `departmentId` / `classId` / `ownerId` / `contributorId`
- Bug report kiểu "role X bấm vào bị lỗi" / "role X thấy cái không nên thấy"

---

## Mô hình hai tầng (bắt buộc hiểu trước khi sửa)

```
Tầng 1 — ROLE GATE:  ai được vào endpoint này?
  SecurityConfig requestMatchers  +  @PreAuthorize trên controller
  → sai thì trả 403

Tầng 2 — DATA SCOPE:  trong số đó, ai được đụng vào DÒNG DỮ LIỆU NÀY?
  *AccessPolicy / *Resolver / *Guard ở tầng service
  → sai thì trả 403 / 404 / empty state
```

**Tầng 1 KHÔNG thay thế được tầng 2.** `@PreAuthorize(PREAUTH_LECTURER_OR_ABOVE)`
cho cả LECTURER, HEAD, ADMIN vào — nhưng không nói gì về việc người đó được xem
lớp nào, bộ môn nào. Đó là việc của tầng 2.

Ví dụ thật trong repo: `LecturerQuestionBankController` để `LECTURER_OR_ABOVE`,
nhưng cái thực sự chặn ADMIN là `QuestionBankAccessPolicy.resolveDepartmentId()`
trả `null` vì admin không có `department_id`.

---

## Hard rules

1. **Mọi endpoint phải có role gate rõ ràng.**
   - `@PreAuthorize` trên class (ưu tiên) hoặc method
   - Ngoại lệ hợp lệ (không cần `@PreAuthorize`): controller public hoặc
     self-service — `AuthController`, `PasswordRecoveryController`,
     `HomeController`, `ProfileController`, `ChangePasswordController`,
     `PublicViewController`, `PublicUploadsController`,
     `StudentAssignmentController`. Thêm controller mới vào nhóm này phải
     giải thích được vì sao.

2. **Dữ liệu có chủ sở hữu (department / class / owner) BẮT BUỘC đi qua tầng 2.**
   - Không tự viết `if (user.getRole() == ADMIN)` rải rác trong service
   - Dùng / mở rộng policy sẵn có (bảng bên dưới)

3. **Ẩn UI KHÔNG phải là phân quyền.**
   - `sec:authorize` chỉ để UX cho gọn. Gõ thẳng URL vẫn phải bị chặn ở tầng 1/2
   - Thêm `sec:authorize` mà không kiểm tra endpoint phía sau → chưa xong việc

4. **`sec:authorize` trong template phải khớp với `@PreAuthorize` của đích đến.**
   - Link tới `/head/**` → `sec:authorize="hasRole('HEAD')"`
   - Rộng hơn quyền thật = user bấm vào ăn 403 / trang lỗi
   - Hẹp hơn quyền thật = ẩn nhầm tính năng của người có quyền

5. **Role qua được tầng 1 nhưng không có scope ở tầng 2 phải ra empty state, KHÔNG phải 500.**
   - Service ném `QuestionBankValidationException` / `AccessDeniedException` /
     `EntityNotFoundException` → controller **phải** bắt hoặc kiểm tra trước
   - Handler chỉ đọc (GET list) nên check trước rồi trả list rỗng
   - Đừng để exception rơi vào catch-all `GlobalExceptionHandler` → 500

6. **Không nới `requestMatchers` để "cho dễ".**
   - Muốn role mới vào được → sửa `@PreAuthorize` + bổ sung tầng 2, không permitAll

---

## Building blocks (do not reinvent)

| Type | Role |
|---|---|
| `config/SecurityConfig` | URL-level role gate (`/lecturer/**`, `/head/**`, `/admin/**`) |
| `security/Roles` | Hằng số SpEL: `PREAUTH_LECTURER_OR_ABOVE`, `LECTURER_OR_ABOVE` |
| `questionbank/service/QuestionBankAccessPolicy` | Scope theo bộ môn (`canAccessDepartment` / `canCurateDepartment`) |
| `lessons/support/ClassAccessPolicy` | Scope theo lớp (`requireModeratorOrEnrolled`) |
| `lessons/support/LessonAccessResolver` | Scope bài giảng |
| `tests/support/TestAccessResolver` | `requireAttemptable` / `requireManageable` / `requireOwnAttempt` |
| `flashcards/support/DeckAccessResolver` | Scope bộ thẻ |
| `head/service/HeadDepartmentResolver` | Bộ môn mà HEAD phụ trách |
| `admin/users/service/AdminUsersGuard` | Bảo vệ thao tác admin (self / last-admin) |
| `admin/permissions/service/AdminPermissionsGuard` | Bảo vệ RBAC động |

**Quy ước đặt tên (giữ đúng):**
- `requireXxx(...)` → ném exception khi không đạt (dùng trong luồng ghi)
- `canXxx(...)` / `isXxx(...)` → trả `boolean` (dùng để quyết định render / lọc)

---

## Checklist — chạy trước khi báo xong việc

Với **mỗi** endpoint vừa thêm/sửa:

- [ ] Tầng 1: có `@PreAuthorize` (hoặc thuộc nhóm ngoại lệ public/self-service ở rule 1)
- [ ] Tầng 1: `requestMatchers` trong `SecurityConfig` không mâu thuẫn với `@PreAuthorize`
- [ ] Tầng 2: dữ liệu có chủ sở hữu đã đi qua policy/resolver tương ứng
- [ ] Role qua tầng 1 nhưng **không có scope** → empty state / 403 / 404, **không 500**
- [ ] Luồng GHI (POST/PUT/DELETE) của role không có scope → **không ghi được dòng nào**
- [ ] `sec:authorize` của link/nút khớp đúng `@PreAuthorize` của trang đích
- [ ] Có test assert đúng status cho **cả** role được phép và role bị từ chối

---

## Cách kiểm chứng (đừng chỉ đọc code)

Suy luận từ code dễ sai vì tầng 2 nằm sâu trong service. Kiểm chứng bằng test thật:

**Đọc — role không có scope phải ra empty state, không 500:**
```java
@Test
@WithUserDetails("admin@ulp.edu.vn")
void listRendersEmptyStateForDepartmentLessAdmin() throws Exception {
    mockMvc.perform(get("/lecturer/question-bank"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("emptyDepartment", true));
}
```

**Ghi — assert bằng SỐ DÒNG DB, không phải status code.**
POST vẫn có thể trả 200 (form render lại kèm lỗi) trong khi không ghi gì. Chỉ
đếm dòng mới phân biệt được "bị chặn" với "ghi thành công":
```java
long before = itemRepository.count();
mockMvc.perform(post("/lecturer/question-bank").with(csrf()) /* params */)
        .andExpect(model().attributeExists("flashError"));
assertThat(itemRepository.count()).isEqualTo(before);
```

**Từ chối — role sai phải 403:**
```java
mockMvc.perform(get("/head/question-bank")).andExpect(status().isForbidden());
```

Repo đã có ~73 assert `isForbidden()` — theo mẫu sẵn có trong
`Sprint7RbacIntegrationTest`, `HeadDepartmentIntegrationTest`,
`Sprint3UserManagementIntegrationTest`.

---

## Lịch sử — bug đã xảy ra

ADMIN bấm "Ngân hàng câu hỏi" trên header → **trang 500**.

Chuỗi lỗi: header hiện link cho `LECTURER/HEAD/ADMIN` → `@PreAuthorize` cũng cho
ADMIN qua (tầng 1 pass) → service gọi `requireDepartment()` → admin không có
`department_id` → ném `QuestionBankValidationException` → handler `list` không
bắt (khác `detail`/`editForm` đã bắt) → rơi vào catch-all → 500.

Trớ trêu: `list.html` **đã có sẵn** empty state `emptyDepartment`, nhưng code nổ
trước khi tới được template.

Ba bài học thành rule ở trên:
- Tầng 1 pass không có nghĩa là an toàn (rule 2)
- Ẩn menu chỉ là UX, không phải fix (rule 3) — URL vẫn gõ tay được
- Handler đọc phải check scope **trước** khi gọi service (rule 5)

Cùng lỗi còn nằm ở nút "Mở ngân hàng câu hỏi" trong `tests/lecturer-form.html`
(trang cho `LECTURER+` vào) — sót vì lần đầu chỉ sửa header. **Grep toàn bộ
template cho URL vừa đụng**, đừng dừng ở chỗ user báo lỗi.

---

## Do not

- ❌ Coi `@PreAuthorize` là đủ cho dữ liệu có chủ sở hữu — luôn cần tầng 2
- ❌ Ẩn link bằng `sec:authorize` rồi coi như đã phân quyền xong
- ❌ Rải `if (role == ADMIN)` trong service thay vì dùng policy/resolver
- ❌ Nới `requestMatchers` / thêm `permitAll` để qua lỗi 403
- ❌ Để role thiếu scope rơi vào catch-all 500 thay vì empty state
- ❌ Kết luận "luồng ghi đã bị chặn" chỉ dựa vào status code — phải đếm dòng DB
- ❌ Chỉ sửa đúng chỗ user báo mà không grep các template khác dùng cùng URL
