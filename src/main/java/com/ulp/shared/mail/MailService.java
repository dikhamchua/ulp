package com.ulp.shared.mail;

import org.springframework.stereotype.Service;

/**
 * Facade gui email cho cac caller noi bo (vd {@code PasswordRecoveryService}).
 * Tu Sprint 2: bean LUON ton tai, khong con conditional theo {@code spring.mail.host}.
 * Cau hinh SMTP doc tu bang {@code system_settings} qua {@link DbConfiguredMailSender}.
 *
 * <p>Contract: {@link #send} tra ve {@code true} khi gui thanh cong,
 * {@code false} khi SMTP chua cau hinh hoac gui that bai. Caller dung
 * boolean return de quyet dinh fallback (vd log reset link ra console).
 */
@Service
public class MailService {

    private final DbConfiguredMailSender sender;

    public MailService(DbConfiguredMailSender sender) {
        this.sender = sender;
    }

    /** Gui email best-effort. Tra ve {@code true} neu thanh cong. */
    public boolean send(String to, String subject, String body) {
        return sender.send(to, subject, body);
    }

    /**
     * Gui email va surface chi tiet loi neu co — phuc vu test-send tren
     * admin settings. Caller dung error message de show toast.
     */
    public MailSendResult sendWithDetail(String to, String subject, String body) {
        return sender.sendWithDetail(to, subject, body);
    }
}
