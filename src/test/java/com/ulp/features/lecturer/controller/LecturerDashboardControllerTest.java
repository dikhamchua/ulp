package com.ulp.features.lecturer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for {@link LecturerDashboardController}
 * (auth guards + dashboard render).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LecturerDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymous_redirects_to_login() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_is_forbidden() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_root_redirects_to_dashboard() throws Exception {
        mockMvc.perform(get("/lecturer"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/lecturer/dashboard"));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_dashboard_renders_kpis() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("lecturer/dashboard"))
                .andExpect(model().attributeExists("teachingStats"))
                .andExpect(model().attributeExists("teachingClassRows"))
                .andExpect(model().attributeExists("teachingQuery"))
                .andExpect(model().attributeExists("teachingSize"))
                .andExpect(model().attributeExists("params"))
                .andExpect(content().string(containsString("Tổng quan giảng dạy")))
                .andExpect(content().string(containsString("Tổng lớp")))
                .andExpect(content().string(containsString("Tổng sinh viên")))
                .andExpect(content().string(containsString("Lớp đang hoạt động")))
                .andExpect(content().string(containsString("Tiến độ trung bình")))
                .andExpect(content().string(containsString("Danh sách lớp")))
                .andExpect(content().string(containsString("Tìm theo tên hoặc mã lớp")));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_dashboard_accepts_search_and_page_params() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard")
                        .param("q", "E2E")
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(view().name("lecturer/dashboard"))
                .andExpect(model().attribute("teachingQuery", "E2E"))
                .andExpect(model().attribute("teachingSize", 5));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_can_open_dashboard() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("lecturer/dashboard"))
                .andExpect(content().string(containsString("Tổng quan giảng dạy")));
    }

    @Test
    @WithUserDetails("head@ulp.edu.vn")
    void head_can_open_dashboard() throws Exception {
        mockMvc.perform(get("/lecturer/dashboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("lecturer/dashboard"));
    }
}
