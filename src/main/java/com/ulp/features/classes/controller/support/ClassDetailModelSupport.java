package com.ulp.features.classes.controller.support;

import com.ulp.entities.ClassEntity;
import com.ulp.features.classes.dto.ClassesDtos.InviteCodeView;
import com.ulp.features.classes.dto.ClassesDtos.InviteLinkView;
import com.ulp.features.classes.service.ClassesService;
import com.ulp.features.classes.service.invites.InviteCodeService;
import com.ulp.security.Role;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

import static com.ulp.common.IConstant.*;

/**
 * Shared model-population + URL/label helpers for the class detail screens.
 * Extracted from {@code ClassesController} during the file-size refactor so
 * the controllers stay focused on request mapping.
 *
 * <p>{@link #populateDetail} centralises the attributes consumed by the
 * detail layout (sidebar fragments, share box, settings invite panel) so
 * every tab — board, members, settings, and the placeholder tabs — receives
 * the same {@code activeCode}, {@code activeLink}, and {@code canRegenerate}
 * data sourced from {@code class_invite_codes}.
 */
@Component
public class ClassDetailModelSupport {

    private final ClassesService classesService;
    private final InviteCodeService inviteCodeService;

    public ClassDetailModelSupport(ClassesService classesService,
                                   InviteCodeService inviteCodeService) {
        this.classesService = classesService;
        this.inviteCodeService = inviteCodeService;
    }

    /**
     * Populates common model attributes required by the class-detail layout.
     *
     * <p>Beyond the basic {@code clazz} and {@code activeTab} pair consumed by
     * the sidebar fragment, this helper also injects the active invite tokens
     * ({@code activeCode}, {@code activeLink}) and the {@code canRegenerate}
     * flag so that EVERY detail tab — including the sidebar share-box and the
     * Settings tab invite panel — can render real invite data sourced from
     * {@code class_invite_codes} rather than the immutable {@code classes.code}.
     */
    public void populateDetail(Model model, ClassEntity clazz, String activeTab,
                               Long userId, Role role) {
        model.addAttribute(ATTR_CLAZZ, clazz);
        model.addAttribute(ATTR_ACTIVE_TAB, activeTab);

        // Active invite tokens may be absent for legacy classes — render null.
        InviteCodeView activeCode = inviteCodeService.findActiveCode(clazz.getId())
                .map(ic -> new InviteCodeView(ic.getCode(), ic.getId(), ic.getUseCount()))
                .orElse(null);
        InviteLinkView activeLink = inviteCodeService.findActiveLink(clazz.getId())
                .map(ic -> new InviteLinkView(ic.getCode(),
                        inviteCodeService.buildLinkUrl(ic),
                        ic.getId(),
                        ic.getUseCount()))
                .orElse(null);
        boolean canRegenerate = classesService.isEditableBy(clazz, userId, role);

        model.addAttribute(ATTR_ACTIVE_CODE, activeCode);
        model.addAttribute(ATTR_ACTIVE_LINK, activeLink);
        model.addAttribute(ATTR_CAN_REGENERATE, canRegenerate);
    }

    /** Builds the canonical URL for a single class — used by redirects and form actions. */
    public static String classUrl(Long id) {
        return URL_CLASSES_LIST + "/" + id;
    }

    /** Maps a tab key to its Vietnamese sidebar label; unknown keys pass through. */
    public static String labelFor(String tab) {
        return switch (tab) {
            case TAB_BOARD       -> "Bảng tin";
            case TAB_SCHEDULE    -> "Lịch học";
            case TAB_MEMBERS     -> "Thành viên";
            case TAB_ROLES       -> "Vai trò lớp";
            case TAB_GROUPS      -> "Nhóm học tập";
            case TAB_ASSIGNMENTS -> "Bài tập";
            case TAB_SCORES      -> "Bảng điểm";
            case TAB_LESSONS     -> "Bài giảng";
            case TAB_MATERIALS   -> "Tài liệu";
            case TAB_SETTINGS    -> "Cài đặt lớp học";
            // Fallback so future tabs render their raw key until labelled.
            default -> tab;
        };
    }
}
