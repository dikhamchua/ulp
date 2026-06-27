package com.ulp.common;

/**
 * Project-wide constants shared by controllers (and any other layer that
 * benefits from the same key set). Consumers reference these unqualified via
 * a static import — interface fields are implicitly
 * {@code public static final}, so {@code import static com.ulp.common.IConstant.*}
 * lets a class write {@code ATTR_FORM} directly without {@code IConstant.} prefix.
 *
 * <p><b>Why an interface, not a final class with statics?</b>
 * The interface stays as a namespace for the constants. Consumers pull them
 * in with {@code import static com.ulp.common.IConstant.*}, which keeps the
 * keys as an <em>implementation detail</em> of the consumer rather than
 * leaking through its public type (the classic "constant interface
 * anti-pattern" — Effective Java Item 22). Sub-interfaces can still extend
 * this one to compose specialised key bags if the surface grows.
 *
 * <p><b>What goes here:</b>
 * <ul>
 *   <li>Route prefixes / canonical URLs that span more than one controller.</li>
 *   <li>View names used by SSR templates.</li>
 *   <li>Model attribute keys consumed by Thymeleaf.</li>
 *   <li>Flash message text (Vietnamese — UI surface).</li>
 *   <li>Enum-like discriminators: mode values, tab keys, sub-tab keys.</li>
 *   <li>Pagination / size defaults.</li>
 * </ul>
 *
 * <p><b>What does NOT go here:</b>
 * <ul>
 *   <li>Entity column names — they belong on the entity.</li>
 *   <li>Role / permission strings — see {@code com.ulp.security.Roles}.</li>
 *   <li>One-off literals used by a single method — keep them inline.</li>
 *   <li>Localised UI strings once {@code MessageSource} ships — migrate to
 *       {@code messages.properties}.</li>
 * </ul>
 *
 * <p><b>Backward compatibility:</b> values here are CONTRACT with the
 * Thymeleaf templates. Changing a value (e.g. {@code ATTR_FORM = "form"}
 * → {@code "formData"}) breaks every template that reads {@code ${form}}.
 * Add a new constant for a new key instead of editing an existing one.
 */
public interface IConstant {

    // ───────── Route prefixes / canonical URLs ───────────────────────
    String BASE_LECTURER     = "/lecturer";
    String PATH_CLASSES      = "/classes";
    String URL_CLASSES_LIST  = BASE_LECTURER + PATH_CLASSES;

    // ───────── View names ────────────────────────────────────────────
    String VIEW_CLASS_MANAGE            = "classes/manage";
    String VIEW_CLASS_FORM              = "classes/form";
    String VIEW_CLASS_DETAIL_BOARD      = "classes/detail-board";
    String VIEW_CLASS_DETAIL_MEMBERS    = "classes/detail-members";
    String VIEW_CLASS_DETAIL_SETTINGS   = "classes/detail-settings";
    String VIEW_CLASS_DETAIL_PLACEHOLDER = "classes/detail-placeholder";
    String VIEW_LESSON_FORM             = "classes/lesson-form";
    String VIEW_STUDENT_CLASS_LESSONS   = "student/class-lessons";

    // ───────── Model attribute keys ──────────────────────────────────
    String ATTR_FORM              = "form";
    String ATTR_MODE              = "mode";
    String ATTR_FORM_ACTION       = "formAction";
    String ATTR_CLASS_ID          = "classId";
    String ATTR_CLAZZ             = "clazz";
    String ATTR_ACTIVE_TAB        = "activeTab";
    String ATTR_ACTIVE_DETAIL_TAB = "activeDetailTab";
    String ATTR_FLASH_SUCCESS     = "flashSuccess";
    String ATTR_FLASH_ERROR       = "flashError";
    String ATTR_CLASSES           = "classes";
    String ATTR_CLASSES_PAGE      = "classesPage";
    String ATTR_MEMBERS           = "members";
    String ATTR_MEMBER_TOTAL      = "memberTotal";
    String ATTR_ACTIVE_CODE       = "activeCode";
    String ATTR_ACTIVE_LINK       = "activeLink";
    String ATTR_CAN_REGENERATE    = "canRegenerate";
    String ATTR_PLACEHOLDER_TAB   = "placeholderTab";
    String ATTR_PLACEHOLDER_LABEL = "placeholderLabel";

    // Additional cross-cutting model attribute keys (used by ≥2 controllers).
    String ATTR_USER          = "user";
    String ATTR_REQUEST       = "request";
    String ATTR_TOKEN         = "token";
    String ATTR_INVALID       = "invalid";
    String ATTR_FLASH_INFO    = "flashInfo";
    String ATTR_FLASH_WARNING = "flashWarning";

    // Lesson-related model attribute keys (ULP-4.0b).
    String ATTR_LESSON          = "lesson";
    String ATTR_LESSONS         = "lessons";
    String ATTR_LESSON_ID       = "lessonId";
    String ATTR_SELECTED_LESSON = "selectedLesson";

    // Lesson attachments (ULP-4.0c).
    String ATTR_ATTACHMENTS     = "attachments";

    // Student class-lessons view (ULP-4.1).
    String ATTR_VIEW              = "view";
    String ATTR_ACTIVE_SECTION_ID = "activeSectionId";

    // Lessons feature — shared cross-controller attrs
    // (used by SectionsController + LessonsController).
    String ATTR_CANCEL_URL    = "cancelUrl";
    String ATTR_EDIT_BASE_URL = "editBaseUrl";
    String ATTR_ACTIVITY_PAGE = "activityPage";
    String ATTR_SECTION       = "section";

    // ───────── Form mode discriminators ──────────────────────────────
    String MODE_CREATE = "create";
    String MODE_EDIT   = "edit";

    // ───────── Class-detail tab keys ─────────────────────────────────
    String TAB_BOARD       = "board";
    String TAB_SCHEDULE    = "schedule";
    String TAB_MEMBERS     = "members";
    String TAB_ROLES       = "roles";
    String TAB_GROUPS      = "groups";
    String TAB_ASSIGNMENTS = "assignments";
    String TAB_SCORES      = "scores";
    String TAB_LESSONS     = "lessons";
    String TAB_MATERIALS   = "materials";
    String TAB_SETTINGS    = "settings";

    // Additional tab keys (used across admin / detail screens).
    String TAB_DASHBOARD = "dashboard";
    String TAB_USERS     = "users";
    String TAB_INFO      = "info";
    String TAB_HISTORY   = "history";
    String TAB_ACTIVITY  = "activity";

    // Settings sub-tabs
    String SUBTAB_INFO   = "info";
    String SUBTAB_INVITE = "invite";

    // ───────── Flash messages (Vietnamese UI text) ───────────────────
    // Migrate to messages.properties once MessageSource is wired up.
    String MSG_CLASS_CREATED       = "Đã tạo lớp ";
    String MSG_CLASS_UPDATED       = "Đã cập nhật lớp";
    String MSG_CLASS_DELETED       = "Đã xoá lớp";
    String MSG_INVITE_REGENERATED  = "Đã tạo mã mời mới";
    String MSG_INVALID_INVITE_TYPE = "Loại mã không hợp lệ";

    // Cross-controller invite / join messages
    // (used by InviteLinkController + StudentClassesController).
    String MSG_JOINED_CLASS        = "Đã tham gia lớp ";
    String MSG_ALREADY_IN_CLASS    = "Bạn đã ở trong lớp ";
    String MSG_INVALID_INVITE_LINK = "Liên kết không hợp lệ";

    // Cross-controller admin-settings session guard
    // (used by EmailSettingsController + OauthSettingsController).
    String MSG_OAUTH_SESSION_UNSUPPORTED =
            "Phiên đăng nhập của bạn không hỗ trợ thao tác này. Vui lòng đăng nhập lại bằng email và mật khẩu.";

    // ───────── Lesson status discriminators (ULP-4.0b) ───────────────
    String LESSON_STATUS_DRAFT     = "DRAFT";
    String LESSON_STATUS_PUBLISHED = "PUBLISHED";

    // ───────── Lesson flash messages (Vietnamese UI text) ────────────
    String MSG_LESSON_CREATED      = "Đã tạo bài giảng";
    String MSG_LESSON_UPDATED      = "Đã cập nhật bài giảng";
    String MSG_LESSON_DELETED      = "Đã xoá bài giảng";
    String MSG_LESSON_PUBLISHED    = "Đã xuất bản bài giảng";
    String MSG_LESSON_UNPUBLISHED  = "Đã chuyển bài giảng về nháp";
    String MSG_LESSON_NOT_FOUND    = "Bài giảng không tồn tại";

    // Lessons feature — shared cross-controller messages
    // (used by SectionsController + LessonsController).
    String MSG_SECTION_NOT_FOUND   = "Chương không tồn tại";
    String MSG_FORBIDDEN_FOR_CLASS = "Bạn không có quyền thao tác với lớp này.";
    String MSG_GENERIC_RETRY       = "Có lỗi xảy ra, vui lòng thử lại.";

    // ───────── Lesson attachments (ULP-4.0c) Vietnamese UI text ──────
    String MSG_ATTACHMENT_UPLOADED       = "Đã tải lên tệp đính kèm";
    String MSG_ATTACHMENT_DELETED        = "Đã xoá tệp đính kèm";
    String MSG_ATTACHMENT_INVALID        = "Tệp đính kèm không hợp lệ";
    String MSG_ATTACHMENT_TOO_LARGE      = "Tệp đính kèm vượt quá giới hạn 20MB";
    String MSG_ATTACHMENT_EXT_NOT_ALLOWED =
            "Chỉ chấp nhận tệp PDF, DOCX, PPTX, XLSX hoặc ZIP";
    String MSG_ATTACHMENT_MAGIC_FAIL     = "Định dạng tệp không hợp lệ";
    String MSG_ATTACHMENT_NOT_FOUND      = "Không tìm thấy tệp đính kèm";
    String MSG_ATTACHMENT_EMPTY          = "Tệp đính kèm rỗng";

    // ───────── Student class-lessons (ULP-4.1) Vietnamese UI text ────
    String MSG_STUDENT_LESSONS_EMPTY_SECTION = "Chương này chưa có bài giảng";
    String MSG_STUDENT_LESSONS_EMPTY_CLASS   = "Lớp này chưa có chương";

    // ───────── Pagination ────────────────────────────────────────────
    int DEFAULT_PAGE_SIZE = 20;

    // Lessons feature — shared paging (history tab page size).
    int DEFAULT_HISTORY_PAGE_SIZE = 20;
}
