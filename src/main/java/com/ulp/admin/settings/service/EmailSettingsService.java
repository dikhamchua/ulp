package com.ulp.admin.settings.service;

import com.ulp.admin.settings.dto.EmailSettingsDtos;
import com.ulp.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.admin.settings.dto.EmailSettingsDtos.TestResult;
import com.ulp.shared.mail.MailSendResult;
import com.ulp.shared.mail.MailService;
import com.ulp.shared.settings.SystemSettingGroups;
import com.ulp.shared.settings.entity.SystemSetting;
import com.ulp.shared.settings.repository.SystemSettingsRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service xu ly Email Settings cho admin: load cau hinh hien tai, save cau
 * hinh moi, va gui test email de verify SMTP transport.
 *
 * <p>Masking: cac key trong {@link #SECRET_KEYS} (hien tai chi co
 * {@code smtp.password}) duoc thay the bang {@link #MASKED} truoc khi
 * tra ve view. Khi save, neu form gui ve gia tri rong HOAC chinh xac la
 * {@code MASKED} thi service skip viec ghi de row {@code smtp.password}.
 *
 * <p>{@link #save} la {@code @Transactional} — toan bo upsert chay trong
 * 1 transaction, neu fail thi rollback all-or-nothing.
 */
@Service
public class EmailSettingsService {

    private static final Logger log = LoggerFactory.getLogger(EmailSettingsService.class);

    private static final String GROUP = SystemSettingGroups.SMTP;
    private static final String MASKED = EmailSettingsDtos.MASKED;
    private static final Set<String> SECRET_KEYS = Set.of("smtp.password");

    private static final String TEST_SUBJECT = "ULP — SMTP test email";
    private static final String TEST_BODY =
            "This is a test email from ULP. If you received this, your SMTP "
                    + "configuration works.";

    private final SystemSettingsRepository repository;
    private final MailService mailService;

    public EmailSettingsService(SystemSettingsRepository repository, MailService mailService) {
        this.repository = repository;
        this.mailService = mailService;
    }

    /**
     * Load Email Settings tu DB. Password luon duoc mask thanh {@code "********"}
     * truoc khi tra ve form, ke ca khi value rong (de UI thong nhat).
     */
    @Transactional(readOnly = true)
    public EmailSettingsForm load() {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);
        return new EmailSettingsForm(
                cfg.getOrDefault("smtp.host", ""),
                parsePortOrNull(cfg.get("smtp.port")),
                cfg.getOrDefault("smtp.encryption", "tls"),
                cfg.getOrDefault("smtp.username", ""),
                MASKED, // password luon mask
                cfg.getOrDefault("smtp.from_name", ""),
                cfg.getOrDefault("smtp.from_email", ""),
                cfg.getOrDefault("smtp.reply_to", "")
        );
    }

    /**
     * Save Email Settings. Atomicity dam bao boi {@code @Transactional}:
     * neu upsert mot row fail thi rollback tat ca cac row da write.
     *
     * <p>Password handling: neu {@code form.password()} la null/blank hoac
     * bang {@link #MASKED} thi skip row {@code smtp.password} hoan toan
     * (khong update gia tri, khong update {@code updated_by}).
     */
    @Transactional
    public void save(EmailSettingsForm form, Long currentUserId) {
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("smtp.host", form.host().trim());
        incoming.put("smtp.port", form.port() == null ? "" : String.valueOf(form.port()));
        incoming.put("smtp.encryption", form.encryption().trim());
        incoming.put("smtp.username", form.username().trim());
        incoming.put("smtp.from_name", form.fromName().trim());
        incoming.put("smtp.from_email", form.fromEmail().trim());
        incoming.put("smtp.reply_to", form.replyTo() == null ? "" : form.replyTo().trim());

        // Password: chi update khi nguoi dung nhap gia tri moi (khong rong va khong la MASKED)
        if (form.password() != null && !form.password().isBlank()
                && !MASKED.equals(form.password())) {
            incoming.put("smtp.password", form.password());
        }

        upsertAll(incoming, currentUserId);
    }

    /**
     * Gui email test toi {@code to}. Tra ve {@link TestResult} co {@code ok}
     * va {@code error} message (khi fail) de UI render toast.
     *
     * <p>Khong dung {@code @Transactional} vi method chi delegate sang
     * mail transport (network call). Repository duoc invoke ben trong
     * {@code DbConfiguredMailSender} co transaction tu Spring Data JPA.
     */
    public TestResult sendTest(String to) {
        if (to == null || to.isBlank()) {
            return new TestResult(false, "Vui lòng nhập email người nhận");
        }
        try {
            InternetAddress addr = new InternetAddress(to.trim());
            addr.validate();
        } catch (AddressException e) {
            return new TestResult(false, "Email người nhận không hợp lệ");
        }

        MailSendResult result =
                mailService.sendWithDetail(to.trim(), TEST_SUBJECT, TEST_BODY);
        if (result.ok()) {
            return new TestResult(true, null);
        }
        return new TestResult(false, result.errorMessage());
    }

    // ─────────────────────────────────────────────────────────────────

    private void upsertAll(Map<String, String> incoming, Long currentUserId) {
        // Load tat ca row hien co cua group SMTP — chi cap nhat row da ton tai
        // (V1/V9 seed da insert het row can thiet). Neu row missing, insert moi.
        Map<String, SystemSetting> existing = new HashMap<>();
        for (SystemSetting s : repository.findBySettingGroup(GROUP)) {
            existing.put(s.getSettingKey(), s);
        }

        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            SystemSetting row = existing.get(key);
            if (row == null) {
                row = new SystemSetting(key, value, GROUP);
            } else {
                row.setSettingValue(value);
            }
            row.setUpdatedBy(currentUserId);
            repository.save(row);
        }

        log.info("Email settings saved by user {} (updated {} keys{})",
                currentUserId, incoming.size(),
                incoming.containsKey("smtp.password") ? ", incl. password" : ", password unchanged");
    }

    private Integer parsePortOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
