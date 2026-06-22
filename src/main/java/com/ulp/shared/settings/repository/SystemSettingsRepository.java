package com.ulp.shared.settings.repository;

import com.ulp.shared.settings.entity.SystemSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repository cho bang {@code system_settings}. Cung cap query theo group/key
 * va helper {@link #loadGroupAsMap} de flatten ra map cho service consumer.
 */
@Repository
public interface SystemSettingsRepository extends JpaRepository<SystemSetting, Long> {

    List<SystemSetting> findBySettingGroup(String settingGroup);

    Optional<SystemSetting> findBySettingKey(String settingKey);

    /**
     * Tien ich: load tat ca rows trong 1 group va tra ve {@code Map<key, value>}.
     * Default method tren interface — khong can co class impl rieng.
     */
    default Map<String, String> loadGroupAsMap(String settingGroup) {
        return findBySettingGroup(settingGroup).stream()
                .collect(Collectors.toMap(
                        SystemSetting::getSettingKey,
                        s -> s.getSettingValue() == null ? "" : s.getSettingValue()
                ));
    }
}
