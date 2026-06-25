package com.ulp.features.home;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Landing controller rendered after a successful login.
 *
 * <p>Sprint 0: displays the authenticated account's username and granted
 * authorities to prove the end-to-end authentication flow is working.
 * Future sprints will replace this view with a role-specific dashboard.
 */
@Controller
public class HomeController {

    /**
     * Handles GET {@code /} and renders the home view for the authenticated user.
     *
     * <p>Uses the generic {@link Authentication} object so the controller works
     * for both form-login (principal is a {@code UlpUserDetails}) and OAuth2
     * login (principal is a {@code CustomOidcUserPrincipal}). Both types
     * expose authorities through the {@link Authentication} contract and a
     * username via {@link Authentication#getName()}.
     *
     * @param authentication the current Spring Security authentication
     * @param model          the Spring MVC model used to pass attributes to the view
     * @return the logical view name {@code "home"}
     */
    @GetMapping("/")
    public String home(Authentication authentication, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("authorities", authentication.getAuthorities());
        return "home";
    }
}
