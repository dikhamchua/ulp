## ADDED Requirements

### Requirement: Admin views the two-level category tree

The system SHALL render a `/admin/categories` page, restricted to the ADMIN role, that lists all course categories as a two-level tree: parent categories (parent_id = NULL) each followed by their child categories. Each row SHALL display the category name, slug, and an active/inactive status badge.

#### Scenario: Admin opens the categories page

- **WHEN** an ADMIN user navigates to `/admin/categories`
- **THEN** the page renders every parent category with its children nested beneath it, each row showing name, slug, and active/inactive badge, and the sidebar highlights the "Danh mục" tab

#### Scenario: Non-admin is denied access

- **WHEN** a non-ADMIN authenticated user requests `/admin/categories`
- **THEN** access is denied by Spring Security (HTTP 403)

### Requirement: Admin creates a category

The system SHALL allow an ADMIN to create a category by submitting a name, an optional description, an optional active flag, and an optional parent. The slug SHALL be auto-generated from the name (Vietnamese diacritics stripped, kebab-case) and MUST be unique; on collision the system SHALL append a numeric suffix (`-2`, `-3`, …). When a parent is supplied it MUST be a top-level category (parent_id = NULL), enforcing the hard two-level limit.

#### Scenario: Create a parent category

- **WHEN** an ADMIN submits the create form with a name and no parent
- **THEN** the system persists a new category with parent_id NULL, an auto-generated unique slug, and redirects to the list with a success toast

#### Scenario: Create a child category under a parent

- **WHEN** an ADMIN submits the create form with a name and a parent that is itself top-level
- **THEN** the system persists a new category whose parent_id points at the selected parent and shows a success toast

#### Scenario: Slug collision is resolved

- **WHEN** an ADMIN creates a category whose generated slug already exists
- **THEN** the system appends the smallest available numeric suffix so the stored slug is unique

#### Scenario: Name is blank

- **WHEN** an ADMIN submits the create form with a blank name
- **THEN** the form re-renders with an inline validation error next to the name field and no category is created

#### Scenario: Selected parent is itself a child

- **WHEN** an ADMIN submits the create form choosing a parent that already has a parent_id
- **THEN** the system rejects the request with an error and does not create the category, preserving the two-level limit

### Requirement: Admin edits a category

The system SHALL allow an ADMIN to edit a category's name, description, active flag, and parent. Re-generating the slug on rename SHALL preserve uniqueness. A category that currently has children MUST remain top-level (its parent cannot be set). A category MUST NOT be set as its own parent, and the chosen parent MUST be a top-level category.

#### Scenario: Rename a category

- **WHEN** an ADMIN edits a category and changes its name
- **THEN** the system updates the name, regenerates a unique slug, and shows a success toast

#### Scenario: Attempt to give a parent to a category that has children

- **WHEN** an ADMIN edits a category that has child categories and tries to assign it a parent
- **THEN** the system rejects the change with an error and the category stays top-level

#### Scenario: Attempt to set a category as its own parent

- **WHEN** an ADMIN edits a category and selects itself as the parent
- **THEN** the system rejects the change with an error and no update is persisted

### Requirement: Admin deletes a category

The system SHALL allow an ADMIN to hard-delete a category only when it is safe. Deletion MUST be blocked when the category still has child categories, and MUST be blocked when the category is still linked to any course via `course_categories`. When blocked, the system SHALL show an error toast and leave the category intact.

#### Scenario: Delete a leaf category with no course links

- **WHEN** an ADMIN deletes a category that has no children and no course_categories links
- **THEN** the system hard-deletes the row and shows a success toast

#### Scenario: Delete blocked by existing children

- **WHEN** an ADMIN deletes a parent category that still has child categories
- **THEN** the system does not delete it and shows an error toast explaining children must be removed first

#### Scenario: Delete blocked by course links

- **WHEN** an ADMIN deletes a category that is still linked to one or more courses
- **THEN** the system does not delete it and shows an error toast explaining the category is in use by courses

### Requirement: Admin toggles category active state

The system SHALL allow an ADMIN to toggle a category's `is_active` flag from the list page. The change SHALL persist and the page SHALL reflect the new state with a confirmation toast.

#### Scenario: Deactivate an active category

- **WHEN** an ADMIN toggles an active category
- **THEN** the system sets is_active to 0, persists it, and shows a confirmation toast with the new state

#### Scenario: Reactivate an inactive category

- **WHEN** an ADMIN toggles an inactive category
- **THEN** the system sets is_active to 1, persists it, and shows a confirmation toast with the new state
