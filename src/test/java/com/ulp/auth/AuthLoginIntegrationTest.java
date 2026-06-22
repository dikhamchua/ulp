package com.ulp.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

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
 * <p>Mat khau cua moi tai khoan test la "password".
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
        mockMvc.perform(formLogin("/login").user("admin@ulp.edu.vn").password("password"))
                .andExpect(authenticated().withRoles("ADMIN"))
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
        mockMvc.perform(formLogin("/login").user("khongton@ulp.edu.vn").password("password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void dangNhap_taiKhoanStudent_mapDungRole() throws Exception {
        mockMvc.perform(formLogin("/login").user("student@ulp.edu.vn").password("password"))
                .andExpect(authenticated().withRoles("STUDENT"));
    }

    @Test
    void dangXuat_chuyenHuongVeLoginLogout() throws Exception {
        mockMvc.perform(logout())
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?logout"));
    }
}
