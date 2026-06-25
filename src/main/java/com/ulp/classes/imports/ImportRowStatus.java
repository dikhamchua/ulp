package com.ulp.classes.imports;

/**
 * Status code attached to every parsed Excel row during student import (ULP-3.4).
 *
 * <p>Rows can be in one of three categories:
 * <ul>
 *   <li>{@code OK} / {@code RE_ENROLL}: will be imported on confirm.</li>
 *   <li>{@code DUPLICATE_IN_CLASS}: skipped silently — the student is already
 *       enrolled and ACTIVE.</li>
 *   <li>Everything else: hard error — the row is shown in red and is NEVER
 *       imported, regardless of the "skip errors" toggle.</li>
 * </ul>
 *
 * <p>The {@link #message()} field returns a short Vietnamese label used in the
 * preview table; this is the only place where Vietnamese strings live so that
 * controllers, services, and tests remain in English.
 */
public enum ImportRowStatus {

    /** Row passed all validations and references an existing STUDENT user. */
    OK("OK"),

    /** Student is currently REMOVED from the class; confirming the import will reactivate the enrollment. */
    RE_ENROLL("Đã từng rời lớp — sẽ được kích hoạt lại"),

    /** Student is already ACTIVE in the class; row is skipped silently. */
    DUPLICATE_IN_CLASS("Đã có trong lớp — bỏ qua"),

    /** Email value present but does not match the basic email pattern. */
    INVALID_EMAIL("Email không hợp lệ"),

    /** MSSV value present but does not match the configured pattern. */
    INVALID_STUDENT_ID("MSSV không hợp lệ"),

    /** No active user matches the supplied email/MSSV — the import does NOT create new users. */
    USER_NOT_FOUND("Không tìm thấy tài khoản"),

    /** User exists but their role is not STUDENT. */
    NOT_A_STUDENT("Tài khoản không phải sinh viên"),

    /** User exists but is locked or deactivated. */
    USER_INACTIVE("Tài khoản bị khoá hoặc vô hiệu hoá"),

    /** Same email or MSSV appears in another row of the uploaded file. */
    DUPLICATE_IN_FILE("Trùng với dòng khác trong file"),

    /** Both email and MSSV are blank. */
    MISSING_REQUIRED("Thiếu email và MSSV");

    private final String message;

    ImportRowStatus(String message) {
        this.message = message;
    }

    /** Short Vietnamese label rendered in the preview table badge. */
    public String message() {
        return message;
    }

    /** {@code true} when the row will be imported on confirm (OK or RE_ENROLL). */
    public boolean isImportable() {
        return this == OK || this == RE_ENROLL;
    }

    /** {@code true} for hard errors that block the row from being imported. */
    public boolean isError() {
        return switch (this) {
            case INVALID_EMAIL, INVALID_STUDENT_ID, USER_NOT_FOUND,
                 NOT_A_STUDENT, USER_INACTIVE, DUPLICATE_IN_FILE,
                 MISSING_REQUIRED -> true;
            default -> false;
        };
    }

    /** {@code true} for warnings (informational, but the row is still imported or skipped silently). */
    public boolean isWarning() {
        return this == RE_ENROLL || this == DUPLICATE_IN_CLASS;
    }
}
