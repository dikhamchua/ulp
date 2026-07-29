package com.ulp.features.profile;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the change-password flow.
 *
 * <p>The pre-existing coverage in {@code Sprint1AuthIntegrationTest} only asserts
 * HTTP 200 on the rejection paths, which passes even if the error is silently
 * swallowed. These tests assert the actual outcome: the stored hash, the model
 * flags the template renders errors from, and the success flash message.</p>
 *
 * <p>{@code @Transactional} rolls the password change back so the seeded test
 * account keeps working for every other test in the suite.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ChangePasswordIntegrationTest {

    private static final String STUDENT_EMAIL = "student@ulp.edu.vn";
    private static final String CURRENT_PASSWORD = "123456";
    private static final String NEW_PASSWORD = "newpass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String storedHashOf(String email) {
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        return user.getPasswordHash();
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void doiMatKhauThanhCong_luuHashMoi_vaBaoFlashSuccess() throws Exception {
        String hashBefore = storedHashOf(STUDENT_EMAIL);

        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/change-password"))
                .andExpect(flash().attributeExists("flashSuccess"));

        String hashAfter = storedHashOf(STUDENT_EMAIL);
        assertThat(hashAfter).isNotEqualTo(hashBefore);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, hashAfter)).isTrue();
        assertThat(passwordEncoder.matches(CURRENT_PASSWORD, hashAfter)).isFalse();
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void doiMatKhau_saiMatKhauHienTai_giuNguyenHash_vaDatCoLoi() throws Exception {
        String hashBefore = storedHashOf(STUDENT_EMAIL);

        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", "sai-mat-khau")
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().isOk())
                // The template renders the inline field error from this flag.
                .andExpect(model().attribute("wrongCurrent", true))
                .andExpect(content().string(containsString("Mật khẩu hiện tại không đúng")));

        assertThat(storedHashOf(STUDENT_EMAIL)).isEqualTo(hashBefore);
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void doiMatKhau_xacNhanKhongKhop_giuNguyenHash_vaDatCoLoi() throws Exception {
        String hashBefore = storedHashOf(STUDENT_EMAIL);

        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", "khac-nhau"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("mismatch", true))
                .andExpect(content().string(containsString("Mật khẩu xác nhận không khớp")));

        assertThat(storedHashOf(STUDENT_EMAIL)).isEqualTo(hashBefore);
    }

    @Test
    @WithUserDetails(STUDENT_EMAIL)
    void doiMatKhau_matKhauMoiQuaNgan_bienValidation_giuNguyenHash() throws Exception {
        String hashBefore = storedHashOf(STUDENT_EMAIL);

        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", "123")
                        .param("confirmPassword", "123"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "newPassword"));

        assertThat(storedHashOf(STUDENT_EMAIL)).isEqualTo(hashBefore);
    }

    @Test
    void doiMatKhau_chuaDangNhap_chuyenHuongVeLogin() throws Exception {
        mockMvc.perform(post("/change-password").with(csrf())
                        .param("currentPassword", CURRENT_PASSWORD)
                        .param("newPassword", NEW_PASSWORD)
                        .param("confirmPassword", NEW_PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }
}
