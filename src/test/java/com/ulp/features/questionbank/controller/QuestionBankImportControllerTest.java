package com.ulp.features.questionbank.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.questionbank.entity.QuestionBankItem;
import com.ulp.features.questionbank.repository.QuestionBankItemRepository;
import com.ulp.features.questionbank.repository.QuestionBankOptionRepository;
import com.ulp.features.subjects.entity.Subject;
import com.ulp.features.subjects.entity.SubjectChapter;
import com.ulp.features.subjects.repository.SubjectChapterRepository;
import com.ulp.features.subjects.repository.SubjectRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MockMvc coverage for question bank import template, preview, and confirm. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class QuestionBankImportControllerTest {

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String[] HEADERS = {
            "Mã môn học", "Chương (tuỳ chọn)", "Loại câu hỏi", "Nội dung câu hỏi", "Giải thích",
            "Đáp án A", "Đáp án B", "Đáp án C", "Đáp án D", "Đáp án đúng"
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private SubjectChapterRepository chapterRepository;
    @Autowired private QuestionBankItemRepository itemRepository;
    @Autowired private QuestionBankOptionRepository optionRepository;

    private Long lecturerId;
    private Long departmentId;
    private String subjectCode;
    private String chapterTitle;

    @BeforeEach
    void setUp() {
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        lecturerId = lecturer.getId();
        departmentId = lecturer.getDepartmentId();
        Subject subject = subjectRepository.findAllByDepartmentIdOrderByCodeAsc(departmentId).stream()
                .filter(Subject::isActive)
                .findFirst().orElseThrow();
        subjectCode = subject.getCode();
        chapterTitle = "Chương import";
        chapterRepository.save(new SubjectChapter(subject.getId(), chapterTitle, (short) 1, lecturerId));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void template_download_ok() throws Exception {
        mockMvc.perform(get("/lecturer/question-bank/import/template"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void preview_and_confirm_persist_valid_rows_into_own_bank_active() throws Exception {
        long before = itemRepository.count();
        MockMultipartFile file = new MockMultipartFile("file", "bank.xlsx", XLSX_MIME, buildWorkbook(new String[][]{
                HEADERS,
                {subjectCode, chapterTitle, "MCQ", "Derivative of x^2", "Power rule",
                        "2x", "x", "x^2", "2", "A"}
        }));

        MvcResult previewResult = mockMvc.perform(multipart("/lecturer/question-bank/import/preview")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(1))
                .andExpect(jsonPath("$.errorRows").value(0))
                .andExpect(jsonPath("$.confirmable").value(true))
                .andReturn();

        JsonNode previewJson = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        String sessionId = previewJson.get("sessionId").asText();

        mockMvc.perform(post("/lecturer/question-bank/import/confirm")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(itemRepository.count()).isEqualTo(before + 1);
        QuestionBankItem imported = itemRepository.findAll().stream()
                .filter(i -> i.getSubjectId() != null)
                .filter(i -> "<p>Derivative of x^2</p>".equals(i.getContent()))
                .findFirst().orElseThrow();
        assertThat(imported.getOwnerId()).isEqualTo(lecturerId);
        assertThat(imported.getStatus()).isEqualTo(QuestionBankItem.STATUS_ACTIVE);
        assertThat(imported.getChapterId()).isNotNull();
        assertThat(optionRepository.findByItemIdOrderBySortOrderAscIdAsc(imported.getId())).hasSize(4);
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void confirm_is_blocked_when_preview_has_errors() throws Exception {
        long before = itemRepository.count();
        MockMultipartFile file = new MockMultipartFile("file", "bank.xlsx", XLSX_MIME, buildWorkbook(new String[][]{
                HEADERS,
                {"MÃ KHÔNG TỒN TẠI", "", "MCQ", "Câu lỗi", "", "A", "B", "", "", "A"}
        }));

        MvcResult previewResult = mockMvc.perform(multipart("/lecturer/question-bank/import/preview")
                        .file(file)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(0))
                .andExpect(jsonPath("$.errorRows").value(1))
                .andExpect(jsonPath("$.confirmable").value(false))
                .andReturn();

        JsonNode previewJson = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        String sessionId = previewJson.get("sessionId").asText();

        mockMvc.perform(post("/lecturer/question-bank/import/confirm")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertThat(itemRepository.count()).isEqualTo(before);
    }

    private static byte[] buildWorkbook(String[][] grid) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Cau hoi");
            for (int r = 0; r < grid.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < grid[r].length; c++) {
                    row.createCell(c).setCellValue(grid[r][c]);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
