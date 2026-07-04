## 1. Domain & Persistence

- [x] 1.1 Create `entities/Category.java` — JPA entity mapping the `categories` table (id, name, slug, parentId as `Long`, description, active flag, createdAt, updatedAt). Self-reference stored as scalar `Long parentId` (no object relation, mirroring `Section`). Plain getters, no `@Data`. JPA-only protected constructor + application constructor. `@PrePersist`/`@PreUpdate` timestamps.
- [x] 1.2 Create `features/admin/categories/repository/CategoryRepository.java` — Spring Data JPA repo with: find all ordered for tree build, find parents only (`parentId IS NULL`), `existsByParentId(Long)` for child count, `existsBySlug` / `existsBySlugAndIdNot` for uniqueness, and a `course_categories` link-count query (native or `@Query`) to detect course usage. ← (verify: entity fields map exactly to V1 `categories` columns; Hibernate validate mode does not fail at startup; course-link query targets `course_categories`)

## 2. Utilities

- [x] 2.1 Add a Vietnamese-aware slugify helper (Unicode NFD normalize + strip diacritics + đ/Đ → d + lowercase + non-alphanumeric → single hyphen + trim hyphens). Place as a static method on `utils/StringUtils.java` (existing shared holder) with English Javadoc.
- [x] 2.2 Add unit coverage for slugify edge cases (Vietnamese diacritics, đ/Đ, multiple spaces/symbols, leading/trailing hyphens, empty → fallback). ← (verify: "Lập trình" → "lap-trinh", "C#/.NET" collapses cleanly, empty input yields a safe non-blank slug)

## 3. DTOs

- [x] 3.1 Create `features/admin/categories/dto/CategoryDtos.java` with `CategoryRow` (id, name, slug, description, active, childCount, courseCount — for the 2-level tree render) and `CategoryForm` (record: name, description, parentId, active) carrying bean-validation annotations (`@NotBlank` name, `@Size` limits matching schema: name 150, slug 150, description text). Provide `CategoryForm.empty()`.
- [x] 3.2 Ensure no entity leaks past the service boundary — controller only sees DTOs.

## 4. Service Layer

- [x] 4.1 Create `features/admin/categories/service/CategoryService.java` — read side: build the parent→children 2-level tree as `List<CategoryRow>` (parents ordered, each with its children + childCount + courseCount).
- [x] 4.2 Create: slugify from name, resolve collision by appending `-2`, `-3`… until unique; validate parent rule (if `parentId` set, target MUST be an existing top-level parent — enforce hard 2-level cap so a child cannot become a parent's parent); persist.
- [x] 4.3 Update: re-validate slug uniqueness excluding self; enforce that a category which currently has children MUST stay a parent (cannot be re-parented into a child); prevent self-parenting (`parentId != id`) and any cycle. ← (verify: 2-level cap holds — cannot nest 3 deep; cannot set a category as its own parent; a parent with children cannot be demoted to a child)
- [x] 4.4 Delete: block when the category still has children (raise domain error → toast); block when the category is still linked to any course via `course_categories` (raise domain error → toast); otherwise hard-delete. ← (verify: deleting a parent-with-children is refused; deleting a course-linked category is refused; deleting an unused leaf hard-deletes the row)
- [x] 4.5 Toggle active: flip `is_active`, persist, return outcome for flash.
- [x] 4.6 Define a small domain exception (e.g. `CategoryInUseException` / reuse a validation-style exception) for the two delete guards so the controller maps it to a specific flash message.

## 5. Controller & Constants

- [x] 5.1 Add constants to `common/IConstant.java`: `VIEW_ADMIN_CATEGORIES`, `TAB_CATEGORIES`, category `ATTR_*` keys (tree, form, parents dropdown), and `MSG_*` flash text (created / updated / deleted / toggled / delete-blocked-has-children / delete-blocked-in-use / slug-or-name errors). Follow existing naming + grouping conventions.
- [x] 5.2 Create `features/admin/categories/controller/AdminCategoriesController.java` — `@Controller @RequestMapping("/admin/categories") @PreAuthorize("hasRole('" + Roles.ADMIN + "')")`. Endpoints: `GET` list (render tree), `GET /new` + `POST` create, `GET /{id}/edit` + `POST /{id}/edit` update, `POST /{id}/delete` delete, `POST /{id}/toggle` toggle active. Use static import of `IConstant.*`. Validation errors re-render inline; success/error via `redirectAttributes` flash. Set `ATTR_ACTIVE_TAB = TAB_CATEGORIES`.
- [x] 5.3 Update `AdminController.java` — remove `categories` from the shared placeholder handler mapping so the new controller owns `/admin/classes`? (NOTE: placeholder currently maps `/departments` + `/classes`, NOT `/categories`; confirm no route collision — categories is a brand-new path). Ensure active-tab wiring stays consistent. ← (verify: `/admin/categories` resolves to the new controller, no ambiguous mapping, ADMIN-only access enforced)

## 6. Views & Assets

- [x] 6.1 Create `templates/admin/categories.html` — reuse `fragments/head`, `fragments/app-header`, `fragments/admin-sidebar` (activeTab=categories). Render the 2-level tree (parent rows with nested child rows), each row showing name, slug, active badge, child/course counts, and edit/delete/toggle actions. Include add-parent and add-child entry points. Add a `#flash-data` element carrying flashSuccess/flashError.
- [x] 6.2 Add the create/edit form (inline section or modal) — name input, description, parent dropdown (parents only; hidden/disabled when editing a category that has children), active checkbox. Field-level validation errors render inline beside inputs.
- [x] 6.3 Add `fragments/admin-sidebar.html` nav entry "Danh mục" with active-state `th:classappend` on `activeTab == 'categories'`, placed consistently with existing items.
- [x] 6.4 Create `static/css/admin-categories.css` (tree indentation, badges, action buttons) and `static/js/admin-categories.js` (drain `#flash-data` → `UlpToast.success/error`, confirm-before-delete, submit wiring). ← (verify: all user-facing notifications go through UlpToast, not inline alert()/div.alert; delete asks for confirmation; tree renders parent→child correctly)

## 7. Tests & Verification

- [x] 7.1 Create `src/test/java/com/ulp/features/admin/categories/AdminCategoriesIntegrationTest.java` (`@SpringBootTest` + `@AutoConfigureMockMvc`, ADMIN via `@WithMockUser`). Cover: list renders tree; create parent; create child under parent; slug auto-generation + collision suffix; delete blocked when has children; delete blocked when course-linked; successful delete of unused leaf; toggle active; 2-level cap + self-parent rejection; non-ADMIN forbidden. ← (verify: every spec scenario has a matching test; delete guards and 2-level cap are asserted, not just happy paths)
- [x] 7.2 Run `.\mvnw.cmd compile` then `.\mvnw.cmd test` — fix any failures until green. ← (verify: build compiles under Hibernate validate mode, full test suite passes)
