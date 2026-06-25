package com.ulp.features.admin.settings;

/**
 * Constants for the {@code setting_group} column in the {@code system_settings} table.
 *
 * <p>Centralises group-name literals to avoid duplicating magic strings across
 * {@code EmailSettingsService}, {@code DbConfiguredMailSender}, and any future
 * settings services (General, OAuth, AI).
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
