package com.ulp.shared.mail;

/**
 * Ket qua cua mot lan gui email. Top-level record de cac layer phia tren
 * (admin/service) co the import ma khong phai phu thuoc vao implementation
 * cu the cua transport ({@link DbConfiguredMailSender}).
 *
 * @param ok           {@code true} khi gui thanh cong
 * @param errorMessage mota loi khi {@code ok=false}; {@code null} khi success
 */
public record MailSendResult(boolean ok, String errorMessage) {

    public static MailSendResult success() {
        return new MailSendResult(true, null);
    }

    public static MailSendResult failure(String errorMessage) {
        return new MailSendResult(false, errorMessage);
    }
}
