package com.ulp.features.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.logout;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test cho luong xac thuc — chay tren context day du + DB that
 * (Flyway da seed cac tai khoan test trong V2/V5). Day cung la KHUON MAU
 * test cho cac feature sau cua nhom.
 *
 * <p>Mat khau cua moi tai khoan test la "123456".
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** Flattens an authentication's authorities to plain strings for assertions. */
    private static List<String> authorityNames(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    void trangLogin_truyCapCongKhai_tra200() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    void truyCapTrangChu_chuaDangNhap_chuyenHuongVeLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void dangNhap_dungThongTin_thanhCongVaChuyenVeTrangChu() throws Exception {
        // withRoles() asserts an exact authority set, so it cannot be used now that RBAC
        // appends PERM_* alongside ROLE_*. The assertion below keeps the original intent:
        // logging in maps the account to its role.
        mockMvc.perform(formLogin("/login").user("admin@ulp.edu.vn").password("123456"))
                .andExpect(authenticated().withAuthentication(
                        auth -> assertThat(authorityNames(auth)).contains("ROLE_ADMIN")))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void dangNhap_saiMatKhau_thatBaiVaChuyenVeLoginError() throws Exception {
        mockMvc.perform(formLogin("/login").user("admin@ulp.edu.vn").password("sai-mat-khau"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void dangNhap_emailKhongTonTai_thatBai() throws Exception {
        mockMvc.perform(formLogin("/login").user("khongton@ulp.edu.vn").password("123456"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void dangNhap_taiKhoanStudent_mapDungRole() throws Exception {
        mockMvc.perform(formLogin("/login").user("student@ulp.edu.vn").password("123456"))
                .andExpect(authenticated().withAuthentication(auth -> {
                    assertThat(authorityNames(auth)).contains("ROLE_STUDENT");
                    // A student must not pick up a higher role's authority.
                    assertThat(authorityNames(auth))
                            .doesNotContain("ROLE_ADMIN", "ROLE_HEAD", "ROLE_LECTURER");
                }));
    }

    @Test
    void dangXuat_chuyenHuongVeLoginLogout() throws Exception {
        mockMvc.perform(logout())
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?logout"));
    }
}
