package com.ulp.classes.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test cho man quan ly lop cua giang vien.
 *
 * <p>Route: GET /lecturer/classes — chi LECTURER, HEAD, ADMIN truy cap.
 * STUDENT bi tu choi (403). Chua dang nhap -> redirect /login.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClassesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Render success ────────────────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void manageClasses_admin_render200_hienThiMockData() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SE1865")))
                .andExpect(content().string(containsString("NILXM")))
                .andExpect(content().string(containsString("Toán 10A2")))
                .andExpect(content().string(containsString("Hiện xếp hạng")))
                .andExpect(content().string(containsString("Tạo lớp học")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void manageClasses_lecturer_render200() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SE1865")));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void manageClasses_head_render200() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk());
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void manageClasses_assetsPaths_coCSSvaJS() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/classes.css")))
                .andExpect(content().string(containsString("/js/classes.js")));
    }

    // ── Auth guards ───────────────────────────────────────────────────

    @Test
    void manageClasses_chuaDangNhap_chuyenHuongVeLogin() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void manageClasses_student_bi403() throws Exception {
        mockMvc.perform(get("/lecturer/classes"))
                .andExpect(status().isForbidden());
    }
}
