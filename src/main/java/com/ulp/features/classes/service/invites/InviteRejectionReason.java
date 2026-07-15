package com.ulp.features.classes.service.invites;

/**
 * Enumerates the reasons a {@code /j/{token}} or {@code /my/classes/join}
 * attempt may be rejected. Each value carries the Vietnamese user-facing
 * message that should be shown back to the student.
 */
public enum InviteRejectionReason {
    INVALID("Mã không hợp lệ"),
    DISABLED("Mã đã hết hiệu lực, xin liên hệ giảng viên"),
    EXPIRED("Mã đã hết hạn"),
    EXHAUSTED("Mã đã đạt giới hạn lượt dùng"),
    CLASS_NOT_JOINABLE("Lớp không nhận thành viên mới"),
    CLASS_FULL("Lớp đã đầy"),
    ALREADY_COMPLETED("Bạn đã hoàn thành lớp này"),
    OWN_CLASS("Bạn là giảng viên chủ lớp này, không thể tham gia bằng mã mời");

    private final String defaultMessage;

    InviteRejectionReason(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
