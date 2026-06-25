package com.ulp.features.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import com.ulp.security.UlpUserDetails;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test cho cac luong Sprint 1 (profile, change-password, forgot/reset).
 *
 * <p>Trong tam: KHOA LAI Bug #3 — cac trang authenticated dung
 * {@code sec:authentication="principal.fullName"}. Voi form-login, principal la
 * {@link UlpUserDetails}; truoc khi sua, principal la Spring's User (khong co
 * fullName) -> SpEL loi -> 500. Cac test render duoi day se 200 neu principal
 * dung. Tai khoan test: admin@ulp.edu.vn / password (seed V2+V6).
 */
@SpringBootTest
@AutoConfigureMockMvc
class Sprint1AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Bug #3: authenticated pages render with real principal ──────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void trangChu_userFormLogin_render200_hienFullName() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("System Admin")));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void trangProfile_userFormLogin_render200() throws Exception {
        mockMvc.perform(get("/profile"))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void trangDoiMatKhau_userFormLogin_render200() throws Exception {
        mockMvc.perform(get("/change-password"))
                .andExpect(status().isOk());
    }

    @Test
    void trangProfile_chuaDangNhap_chuyenHuongVeLogin() throws Exception {
        mockMvc.perform(get("/profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    // ── Change password flow ────────────────────────────────────────────────

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void doiMatKhau_saiMatKhauHienTai_baoLoi() throws Exception {
        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", "sai-mat-khau")
                        .param("newPassword", "newpass123")
                        .param("confirmPassword", "newpass123"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("change-password")));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void doiMatKhau_xacNhanKhongKhop_baoLoi() throws Exception {
        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", "password")
                        .param("newPassword", "newpass123")
                        .param("confirmPassword", "khac-nhau"))
                .andExpect(status().isOk());
    }

    // ── Forgot password: enumeration-safe ───────────────────────────────────

    @Test
    void quenMatKhau_emailTonTai_chuyenHuongTrungLap() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "admin@ulp.edu.vn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));
    }

    @Test
    void quenMatKhau_emailKhongTonTai_chuyenHuongTrungLap() throws Exception {
        // Enumeration-safe: cung 1 ket qua nhu email ton tai
        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "khongtontai@ulp.edu.vn"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/forgot-password"));
    }

    @Test
    void datLaiMatKhau_tokenKhongHopLe_hienTrangLoi() throws Exception {
        mockMvc.perform(get("/reset-password").param("token", "token-khong-ton-tai"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("không hợp lệ")));
    }

    @Test
    void trangForgotPassword_truyCapCongKhai_200() throws Exception {
        mockMvc.perform(get("/forgot-password"))
                .andExpect(status().isOk());
    }
}
