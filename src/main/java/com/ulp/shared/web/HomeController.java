package com.ulp.shared.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Trang chu sau khi dang nhap. Sprint 0 chi hien thi thong tin tai khoan
 * dang dang nhap de chung minh luong xac thuc chay end-to-end. Cac sprint sau
 * se thay bang dashboard theo tung vai tro.
 */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home(@AuthenticationPrincipal UserDetails userDetails, Model model) {
        model.addAttribute("username", userDetails.getUsername());
        model.addAttribute("authorities", userDetails.getAuthorities());
        return "home";
    }
}
