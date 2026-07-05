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
    String VIEW_CLASS_DETAIL_PROGRESS   = "classes/detail-progress";
    String VIEW_CLASS_DETAIL_TESTS      = "classes/detail-tests";
    String VIEW_LESSON_FORM             = "classes/lesson-form";
    String VIEW_STUDENT_CLASS_LESSONS   = "student/class-lessons";
    String VIEW_STUDENT_LESSON_DETAIL   = "student/lesson-detail";

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

    // Lecturer progress dashboard (lecturer-student-progress).
    String ATTR_PROGRESS_SUMMARY = "progressSummary";
    String ATTR_PROGRESS_PAGE    = "progressPage";
    String ATTR_PROGRESS_STATUS  = "progressStatus";
    String ATTR_PROGRESS_QUERY   = "progressQuery";
    String ATTR_PROGRESS_SIZE    = "progressSize";

    // Additional cross-cutting model attribute keys (used by ≥2 controllers).
    String ATTR_USER          = "user";
    String ATTR_REQUEST       = "request";
    String ATTR_TOKEN         = "token";
    String ATTR_INVALID       = "invalid";
    String ATTR_FLASH_INFO    = "flashInfo";
    String ATTR_FLASH_WARNING = "flashWarning";

    String ATTR_LESSON          = "lesson";
    String ATTR_LESSONS         = "lessons";
    String ATTR_LESSON_ID       = "lessonId";
    String ATTR_SELECTED_LESSON = "selectedLesson";

    String ATTR_ATTACHMENTS     = "attachments";

    String ATTR_VIEW              = "view";
    String ATTR_ACTIVE_SECTION_ID = "activeSectionId";

    String ATTR_LESSON_DETAIL     = "lessonDetail";

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
    String TAB_PROGRESS    = "progress";
    String TAB_TESTS       = "tests";
    String TAB_SETTINGS    = "settings";

    // Additional tab keys (used across admin / detail screens).
    String TAB_DASHBOARD = "dashboard";
    String TAB_USERS     = "users";
    String TAB_INFO      = "info";
    String TAB_HISTORY   = "history";
    String TAB_ACTIVITY  = "activity";
    String TAB_MONITOR     = "monitor";
    String TAB_SUBMISSIONS = "submissions";

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

    // ───────── Lesson status discriminators ─────────────────────────
    String LESSON_STATUS_DRAFT     = "DRAFT";
    String LESSON_STATUS_PUBLISHED = "PUBLISHED";

    // ───────── Lesson content-type discriminators ──────────────────
    String CONTENT_TYPE_RICHTEXT = "RICHTEXT";
    String CONTENT_TYPE_PDF      = "PDF";
    String CONTENT_TYPE_VIDEO    = "VIDEO";

    // YOUTUBE / VIMEO point at an external embed; UPLOAD points at a
    // server-relative MP4 path.
    String VIDEO_PROVIDER_YOUTUBE = "YOUTUBE";
    String VIDEO_PROVIDER_VIMEO   = "VIMEO";
    String VIDEO_PROVIDER_UPLOAD  = "UPLOAD";

    // Hard cap for uploaded MP4 size: 200 MB.
    long MAX_VIDEO_SIZE_BYTES = 200L * 1024L * 1024L;

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

    // ───────── Attachment messages (Vietnamese UI text) ──────────────
    String MSG_ATTACHMENT_UPLOADED       = "Đã tải lên tệp đính kèm";
    String MSG_ATTACHMENT_DELETED        = "Đã xoá tệp đính kèm";
    String MSG_ATTACHMENT_INVALID        = "Tệp đính kèm không hợp lệ";
    String MSG_ATTACHMENT_TOO_LARGE      = "Tệp đính kèm vượt quá giới hạn 20MB";
    String MSG_ATTACHMENT_EXT_NOT_ALLOWED =
            "Chỉ chấp nhận tệp PDF, DOCX, PPTX, XLSX hoặc ZIP";
    String MSG_ATTACHMENT_MAGIC_FAIL     = "Định dạng tệp không hợp lệ";
    String MSG_ATTACHMENT_NOT_FOUND      = "Không tìm thấy tệp đính kèm";
    String MSG_ATTACHMENT_EMPTY          = "Tệp đính kèm rỗng";

    // ───────── Content-type messages (Vietnamese UI text) ────────────
    String MSG_LESSON_CONTENT_TYPE_REQUIRED = "Loại nội dung không hợp lệ";
    String MSG_LESSON_PDF_NOT_UPLOADED      = "PDF chưa được tải lên";
    String MSG_LESSON_VIDEO_NOT_CONFIGURED  = "Chưa cấu hình video";
    String MSG_VIDEO_URL_INVALID            = "URL video không hợp lệ";
    String MSG_VIDEO_FILE_TOO_LARGE         = "Video vượt quá giới hạn 200MB";
    String MSG_VIDEO_FILE_NOT_MP4           = "Chỉ chấp nhận tệp MP4";

    // ───────── Student-facing messages (Vietnamese UI text) ──────────
    String MSG_STUDENT_LESSONS_EMPTY_SECTION = "Chương này chưa có bài giảng";
    String MSG_STUDENT_LESSONS_EMPTY_CLASS   = "Lớp này chưa có chương";

    // ───────── Learning-progress flash messages (ULP-4.5) ────────────
    String MSG_PROGRESS_MARKED_COMPLETE   = "Đã đánh dấu hoàn thành bài giảng";
    String MSG_PROGRESS_MARKED_INCOMPLETE = "Đã bỏ đánh dấu hoàn thành";

    // ───────── Lecturer progress dashboard (Vietnamese UI text) ───────
    String MSG_STUDENT_NOT_IN_CLASS = "Sinh viên không thuộc lớp này";

    // ───────── Lesson-comment messages (ULP-4.6, Vietnamese UI text) ──
    String MSG_COMMENT_BLANK         = "Nội dung không được để trống";
    String MSG_COMMENT_TOO_LONG      = "Nội dung tối đa 2000 ký tự";
    String MSG_COMMENT_PARENT_INVALID = "Không tìm thấy bình luận gốc";
    String MSG_COMMENT_NOT_FOUND     = "Không tìm thấy bình luận";
    // Moderator hide/unhide (ULP-11.7).
    String MSG_COMMENT_MODERATE_FORBIDDEN = "Bạn không có quyền ẩn bình luận này";

    // ───────── Flashcards (ULP-5.x) ──────────────────────────────────
    // Route prefixes / canonical URLs.
    String BASE_FLASHCARDS      = "/my/flashcards";
    String API_FLASHCARDS       = "/api/flashcards";

    // View names.
    String VIEW_FLASHCARD_LIST   = "flashcards/list";
    String VIEW_FLASHCARD_FORM   = "flashcards/deck-form";
    String VIEW_FLASHCARD_DETAIL = "flashcards/deck-detail";
    String VIEW_FLASHCARD_FLIP   = "flashcards/flashcard-flip";
    String VIEW_FLASHCARD_REVIEW = "flashcards/flashcard-review";

    // Model attribute keys.
    String ATTR_DECK          = "deck";
    String ATTR_DECKS_OWN_PAGE = "ownDecksPage";
    String ATTR_DECKS_SHARED  = "sharedDecks";
    String ATTR_CARDS         = "cards";
    String ATTR_CARDS_JSON    = "cardsJson";
    String ATTR_DUE_COUNT     = "dueCount";
    String ATTR_SHARE_CLASSES = "shareClasses";

    // Flash / error messages (Vietnamese UI text).
    String MSG_DECK_CREATED    = "Đã tạo bộ thẻ";
    String MSG_DECK_UPDATED    = "Đã lưu bộ thẻ";
    String MSG_DECK_DELETED    = "Đã xoá bộ thẻ";
    String MSG_DECK_SHARED     = "Đã chia sẻ bộ thẻ cho lớp";
    String MSG_DECK_UNSHARED   = "Đã chuyển bộ thẻ về riêng tư";
    String MSG_DECK_TITLE_BLANK = "Tiêu đề không được để trống";
    String MSG_CARD_SIDE_BLANK = "Mỗi thẻ phải có cả mặt trước và mặt sau";
    String MSG_CARD_NOT_FOUND  = "Không tìm thấy thẻ";
    String MSG_DECK_NOT_FOUND  = "Không tìm thấy bộ thẻ";
    String MSG_SHARE_CLASS_INVALID = "Bạn không thuộc lớp này";

    // SM-2 rating → quality map (Không nhớ / Khó / Tốt / Dễ).
    int QUALITY_FORGOT = 1;
    int QUALITY_HARD   = 3;
    int QUALITY_GOOD   = 4;
    int QUALITY_EASY   = 5;

    // ───────── MCQ online exams (Epic #11) ───────────────────────────
    // Route prefixes / canonical URLs.
    String BASE_MY_TESTS       = "/my/tests";
    String API_TESTS           = "/api/tests";
    String BASE_LECTURER_TESTS = BASE_LECTURER + "/tests";

    // View names.
    String VIEW_TEST_LIST          = "tests/list";
    String VIEW_TEST_TAKE          = "tests/take";
    String VIEW_TEST_RESULT        = "tests/result";
    String VIEW_TEST_REVIEW        = "tests/review";
    String VIEW_TEST_PRACTICE_NEW  = "tests/practice-new";
    String VIEW_TEST_READINESS     = "tests/readiness";
    String VIEW_TEST_LECTURER_LIST = "tests/lecturer-list";
    String VIEW_TEST_LECTURER_FORM = "tests/lecturer-form";
    String VIEW_STUDENT_CLASS_TESTS = "student/class-tests";

    // Model attribute keys.
    String ATTR_EXAMS_PAGE   = "examsPage";
    String ATTR_TAKE         = "take";
    String ATTR_RESULT       = "result";
    String ATTR_REVIEW       = "review";
    String ATTR_READINESS    = "readiness";
    String ATTR_PRACTICE     = "practice";
    String ATTR_EXAM_FORM    = "examForm";
    String ATTR_TEST         = "test";
    String ATTR_LED_CLASSES  = "ledClasses";
    String ATTR_MONITOR      = "monitor";
    String ATTR_SUBMISSIONS  = "submissions";
    String ATTR_TEST_ACTIVITIES_PAGE = "activitiesPage";

    // Readiness band labels (Vietnamese UI text).
    String BAND_NOT_READY = "Chưa sẵn sàng";
    String BAND_OK        = "Khá";
    String BAND_READY     = "Sẵn sàng";

    // Flash / validation messages (Vietnamese UI text).
    String MSG_EXAM_SAVED            = "Đã lưu bài test";
    String MSG_EXAM_DELETED          = "Đã xoá bài test";
    String MSG_EXAM_SUBMITTED        = "Đã nộp bài";
    String MSG_PRACTICE_CREATED      = "Đã tạo bài luyện tập";
    String MSG_EXAM_TITLE_BLANK      = "Tiêu đề bài test không được để trống";
    String MSG_EXAM_NEEDS_CLASS      = "Vui lòng chọn lớp cho bài test";
    String MSG_EXAM_NEEDS_QUESTIONS  = "Bài test phải có ít nhất một câu hỏi";
    String MSG_QUESTION_NEEDS_OPTIONS = "Mỗi câu hỏi phải có ít nhất hai lựa chọn";
    String MSG_QUESTION_NEEDS_CORRECT = "Mỗi câu hỏi phải có ít nhất một đáp án đúng";
    String MSG_MCQ_ONE_CORRECT       = "Câu hỏi một đáp án (MCQ) phải có đúng một đáp án đúng";
    String MSG_PRACTICE_EMPTY_POOL   = "Không có câu hỏi phù hợp để tạo bài luyện tập";
    String MSG_PRACTICE_INVALID_SOURCE = "Nguồn câu hỏi không hợp lệ";

    // Exam list / submissions page sizes (default + upper bound for ?size).
    int DEFAULT_EXAM_PAGE_SIZE        = 12;
    int DEFAULT_SUBMISSIONS_PAGE_SIZE = 20;

    // ───────── Notifications (Sprint 5, #63/#64) ─────────────────────
    // Route prefix / canonical URL.
    String BASE_MY_NOTIFICATIONS = "/my/notifications";

    // View names.
    String VIEW_NOTIFICATIONS_INDEX = "notifications/index";

    // Model attribute keys.
    String ATTR_NOTIFICATIONS   = "notifications";
    String ATTR_NOTIF_UNREAD    = "notifUnreadCount";

    // Flash messages (Vietnamese UI text).
    String MSG_NOTIF_READ = "Đã đánh dấu đã đọc";

    // ───────── Direct messaging (Epic #13, ULP-8.3 + ULP-8.4) ────────
    // Route prefix / canonical URL.
    String BASE_MY_MESSAGES = "/my/messages";

    // View names.
    String VIEW_MESSAGING_INDEX        = "messaging/index";
    String VIEW_MESSAGING_CONVERSATION = "messaging/conversation";
    String VIEW_STUDENT_CLASS_MESSAGES = "student/class-messages";

    // Model attribute keys.
    String ATTR_CONVERSATIONS = "conversations";
    String ATTR_CONVERSATION  = "conversation";
    String ATTR_MSG_UNREAD    = "msgUnreadCount";
    String ATTR_RECIPIENTS     = "recipients";
    String ATTR_COMPOSE        = "compose";
    String ATTR_COMPOSE_QUERY  = "composeQuery";

    // Pagination.
    int DEFAULT_PAGE_SIZE = 20;

    // Lessons feature — shared paging (history tab page size).
    int DEFAULT_HISTORY_PAGE_SIZE = 20;

    // Lecturer progress dashboard — table page size (default + upper bound).
    int DEFAULT_PROGRESS_PAGE_SIZE = 10;
    int MAX_PROGRESS_PAGE_SIZE = 100;

    // Lesson comments — root comments per "load more" page (default + upper bound).
    // MAX caps a client-supplied ?size so a huge value can't force an oversized query.
    int DEFAULT_COMMENT_PAGE_SIZE = 10;
    int MAX_COMMENT_PAGE_SIZE = 50;

    // Flashcards — own decks per SSR numbered-pager page.
    int DEFAULT_DECK_PAGE_SIZE = 12;

    // Shared pager fragment — Map of query params to preserve across pages
    // (status/q/size/…). Consumed by templates/fragments/pager.html.
    // Numbered-button window size lives in com.ulp.common.PageWindow.
    String ATTR_PAGER_PARAMS = "params";
}
