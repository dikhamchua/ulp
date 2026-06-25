package com.ulp.features.admin.settings.service;

import com.ulp.features.admin.settings.dto.EmailSettingsDtos;
import com.ulp.features.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.features.admin.settings.dto.EmailSettingsDtos.TestResult;
import com.ulp.config.CacheConfig;
import com.ulp.features.mail.MailSendResult;
import com.ulp.features.mail.MailService;
import com.ulp.features.admin.settings.SystemSettingGroups;
import com.ulp.entities.SystemSetting;
import com.ulp.features.admin.settings.repository.SystemSettingsRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Service for managing SMTP email settings in the admin panel: loading the
 * current configuration, persisting changes, and sending a test email to
 * verify the SMTP transport.
 *
 * <p>Masking: keys listed in {@link #SECRET_KEYS} (currently only
 * {@code smtp.password}) are replaced with {@link #MASKED} before being
 * returned to the view. On save, if the form submits a blank value or the
 * exact sentinel {@code MASKED}, the service skips writing the
 * {@code smtp.password} row entirely.
 *
 * <p>{@link #save} is {@code @Transactional} — all upserts run within a
 * single transaction and are rolled back atomically on failure.
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
     * Loads the current email settings from the database.
     *
     * <p>The SMTP password is always replaced with {@link #MASKED} before the
     * form object is returned, even if the stored value is empty, so the UI
     * renders consistently without exposing the real credential.
     *
     * @return an {@link EmailSettingsForm} populated with the current settings
     */
    @Transactional(readOnly = true)
    public EmailSettingsForm load() {
        Map<String, String> cfg = repository.loadGroupAsMap(GROUP);
        return new EmailSettingsForm(
                cfg.getOrDefault("smtp.host", ""),
                parsePortOrNull(cfg.get("smtp.port")),
                cfg.getOrDefault("smtp.encryption", "tls"),
                cfg.getOrDefault("smtp.username", ""),
                MASKED, // password is always masked
                cfg.getOrDefault("smtp.from_name", ""),
                cfg.getOrDefault("smtp.from_email", ""),
                cfg.getOrDefault("smtp.reply_to", "")
        );
    }

    /**
     * Persists updated email settings to the database.
     *
     * <p>Atomicity is guaranteed by {@code @Transactional}: if any upsert
     * fails, all writes in this call are rolled back.
     *
     * <p>Password handling: if {@code form.password()} is {@code null},
     * blank, or equal to {@link #MASKED}, the {@code smtp.password} row is
     * skipped entirely — the stored value and {@code updated_by} are left
     * unchanged.
     *
     * <p>Cache invalidation: evicts the {@code SMTP} entry from the
     * {@code settingsGroup} cache so the next read picks up the new values.
     *
     * @param form          the submitted settings form
     * @param currentUserId ID of the admin user performing the save
     */
    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_SETTINGS_GROUP, key = "'SMTP'")
    public void save(EmailSettingsForm form, Long currentUserId) {
        Map<String, String> incoming = new LinkedHashMap<>();
        incoming.put("smtp.host", form.host().trim());
        incoming.put("smtp.port", form.port() == null ? "" : String.valueOf(form.port()));
        incoming.put("smtp.encryption", form.encryption().trim());
        incoming.put("smtp.username", form.username().trim());
        incoming.put("smtp.from_name", form.fromName().trim());
        incoming.put("smtp.from_email", form.fromEmail().trim());
        incoming.put("smtp.reply_to", form.replyTo() == null ? "" : form.replyTo().trim());

        // Password: only update when the user submits a new value (non-blank and not the masked sentinel)
        if (form.password() != null && !form.password().isBlank()
                && !MASKED.equals(form.password())) {
            incoming.put("smtp.password", form.password());
        }

        upsertAll(incoming, currentUserId);
    }

    /**
     * Sends a test email to the given address to verify the SMTP configuration.
     *
     * <p>Returns a {@link TestResult} containing an {@code ok} flag and, on
     * failure, an error message suitable for rendering as a UI toast.
     *
     * <p>This method is intentionally not {@code @Transactional} because it
     * only delegates to the mail transport layer (a network call). Any
     * repository access inside {@code DbConfiguredMailSender} runs under its
     * own Spring Data JPA transaction.
     *
     * @param to the recipient email address
     * @return a {@link TestResult} indicating success or failure
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
        // Load all existing rows for the SMTP group — only update rows that already exist
        // (seeded by V1/V9 migrations). Insert a new row if one is unexpectedly missing.
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
