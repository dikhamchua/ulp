package com.ulp.auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller cho luong xac thuc. Sprint 0 chi can trang dang nhap;
 * viec xu ly POST /login va /logout do Spring Security dam nhan.
 */
@Controller
public class AuthController {

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }
}
