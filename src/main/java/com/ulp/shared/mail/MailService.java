package com.ulp.shared.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Gui email don gian qua SMTP. Duoc boc trong try/catch de khong bao gio
 * throw ra ngoai — email that bai chi log, khong anh huong den UX.
 */
@Service
@ConditionalOnProperty("spring.mail.host")
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Gui mot email. Neu that bai (SMTP down, sai credential, ...) chi log
     * loi, khong throw. Tra ve true neu gui thanh cong.
     */
    public boolean send(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            log.info("Email sent to {}", to);
            return true;
        } catch (MailException e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
            return false;
        }
    }
}
