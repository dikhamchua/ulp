package com.ulp.shared.settings;

/**
 * Tên các nhóm setting trong bảng {@code system_settings.setting_group}.
 *
 * <p>Hằng số dùng chung để tránh duplicate magic string giữa
 * {@code EmailSettingsService}, {@code DbConfiguredMailSender} và các
 * service settings khác trong tương lai (General, OAuth, AI).
 */
public final class SystemSettingGroups {

    public static final String SMTP = "SMTP";
    public static final String GENERAL = "GENERAL";
    public static final String OAUTH = "OAUTH";
    public static final String AI = "AI";

    private SystemSettingGroups() {
        // utility class
    }
}
