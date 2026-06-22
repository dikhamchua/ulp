package com.ulp.admin.settings.controller;

import com.ulp.admin.settings.dto.EmailSettingsDtos.EmailSettingsForm;
import com.ulp.admin.settings.dto.EmailSettingsDtos.TestResult;
import com.ulp.admin.settings.service.EmailSettingsService;
import com.ulp.auth.Roles;
import com.ulp.auth.service.UlpUserDetails;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Man hinh quan tri Email Settings (SMTP) — chi ADMIN truy cap.
 *
 * <p>URLs:
 * <ul>
 *   <li>{@code GET  /admin/settings/email}      — render form</li>
 *   <li>{@code POST /admin/settings/email}      — save form (full page reload)</li>
 *   <li>{@code POST /admin/settings/email/test} — gui test email (AJAX JSON)</li>
 * </ul>
 */
@Controller
@RequestMapping("/admin/settings/email")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class EmailSettingsController {

    private final EmailSettingsService service;

    public EmailSettingsController(EmailSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public String view(@AuthenticationPrincipal UlpUserDetails principal, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", service.load());
        }
        model.addAttribute("activeTab", "settings");
        model.addAttribute("defaultTestRecipient",
                principal != null ? principal.getUsername() : "");
        return "admin/settings-email";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") EmailSettingsForm form,
                       BindingResult result,
                       @AuthenticationPrincipal UlpUserDetails principal,
                       Model model,
                       RedirectAttributes redirectAttributes) {
        // Principal có thể null khi admin login qua OAuth (CustomOidcUserPrincipal
        // khác type với UlpUserDetails — @AuthenticationPrincipal inject null khi
        // type mismatch). MVP yêu cầu user.id để stamp updated_by, nên từ chối
        // thay vì ném NPE. Khi RBAC OAuth admin được hỗ trợ, mở rộng principal
        // resolver ở Security layer.
        if (principal == null) {
            redirectAttributes.addFlashAttribute("flashError",
                    "Phiên đăng nhập không hỗ trợ thao tác này. Vui lòng đăng nhập lại bằng email/mật khẩu.");
            return "redirect:/admin/settings/email";
        }
        if (result.hasErrors()) {
            model.addAttribute("activeTab", "settings");
            model.addAttribute("defaultTestRecipient", principal.getUsername());
            return "admin/settings-email";
        }
        service.save(form, principal.getId());
        redirectAttributes.addFlashAttribute("flashSuccess", "Đã lưu cài đặt email.");
        return "redirect:/admin/settings/email";
    }

    @PostMapping(value = "/test", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TestResult sendTest(@RequestParam("testRecipient") String testRecipient) {
        return service.sendTest(testRecipient);
    }
}
