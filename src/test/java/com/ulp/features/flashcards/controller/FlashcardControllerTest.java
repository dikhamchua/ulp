package com.ulp.features.flashcards.controller;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.CardItem;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.service.CardService;
import com.ulp.features.flashcards.service.DeckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static com.ulp.common.IConstant.DEFAULT_DECK_PAGE_SIZE;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the flashcard controllers: page routes, authz (404/403),
 * and the AJAX endpoints (card save, review, Excel import).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlashcardControllerTest {

    private static final String OWNER = "student@ulp.edu.vn";
    private static final String MEMBER = "sv02@ulp.edu.vn";
    private static final String OUTSIDER = "sv01@ulp.edu.vn";

    @Autowired private MockMvc mockMvc;
    @Autowired private DeckService deckService;
    @Autowired private CardService cardService;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;

    private Long deckId;
    private Long cardId;

    @BeforeEach
    void setUp() {
        User owner = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow();
        User member = userRepository.findByEmailIgnoreCase(MEMBER).orElseThrow();
        User lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        ClassEntity clazz = saveClass(lecturer, "FC ctrl class", "FCCTL");
        enroll(owner, clazz);
        enroll(member, clazz);

        deckId = deckService.createDeck(owner.getId(), new DeckForm("Bộ thẻ ctrl", null));
        cardService.replaceCards(deckId, owner.getId(), List.of(
                new CardItem(null, "front", "back")));
        deckService.share(deckId, owner.getId(), clazz.getId());
        cardId = cardService.getEditorView(deckId, owner.getId()).cards().get(0).id();
    }

    @Test
    @WithUserDetails(OWNER)
    void list_page_ok() throws Exception {
        mockMvc.perform(get("/my/flashcards")).andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(OWNER)
    void list_second_page_selects_page_and_renders_pager() throws Exception {
        // Seed enough own decks to guarantee a second SSR page (page size = 12).
        Long ownerId = userRepository.findByEmailIgnoreCase(OWNER).orElseThrow().getId();
        for (int i = 0; i < DEFAULT_DECK_PAGE_SIZE + 1; i++) {
            deckService.createDeck(ownerId, new DeckForm("Bộ trang " + i, null));
        }
        mockMvc.perform(get("/my/flashcards").param("page", "1"))
                .andExpect(status().isOk())
                // The controller must surface the requested SSR page to the view.
                .andExpect(model().attribute("ownDecksPage", hasProperty("number", is(1))))
                // With >1 page the shared numbered pager fragment is rendered.
                .andExpect(content().string(containsString("pager-item")));
    }

    @Test
    @WithUserDetails(OWNER)
    void detail_page_ok() throws Exception {
        mockMvc.perform(get("/my/flashcards/" + deckId)).andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(OWNER)
    void new_edit_flip_review_pages_render() throws Exception {
        mockMvc.perform(get("/my/flashcards/new")).andExpect(status().isOk());
        mockMvc.perform(get("/my/flashcards/" + deckId + "/edit")).andExpect(status().isOk());
        mockMvc.perform(get("/my/flashcards/" + deckId + "/flip")).andExpect(status().isOk());
        mockMvc.perform(get("/my/flashcards/" + deckId + "/review")).andExpect(status().isOk());
    }

    @Test
    @WithUserDetails(OUTSIDER)
    void outsider_flip_returns_404() throws Exception {
        mockMvc.perform(get("/my/flashcards/" + deckId + "/flip"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(OWNER)
    void owner_save_cards_ok() throws Exception {
        mockMvc.perform(post("/api/flashcards/" + deckId + "/cards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"front\":\"a\",\"back\":\"b\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    @WithUserDetails(MEMBER)
    void member_save_cards_forbidden() throws Exception {
        mockMvc.perform(post("/api/flashcards/" + deckId + "/cards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"front\":\"a\",\"back\":\"b\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void blank_card_side_returns_400() throws Exception {
        mockMvc.perform(post("/api/flashcards/" + deckId + "/cards").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cards\":[{\"front\":\"   \",\"back\":\"b\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void owner_review_ok() throws Exception {
        mockMvc.perform(post("/api/flashcards/cards/" + cardId + "/review").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quality\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.dueRemaining").exists());
    }

    @Test
    @WithUserDetails(OUTSIDER)
    void outsider_review_returns_404() throws Exception {
        mockMvc.perform(post("/api/flashcards/cards/" + cardId + "/review").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quality\":4}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithUserDetails(OWNER)
    void owner_import_excel_returns_parsed_count() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cards.xlsx",
                XLSX_MIME, xlsxBytes());
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/import")
                        .file(file).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.count").value(2));
    }

    @Test
    @WithUserDetails(MEMBER)
    void member_import_excel_forbidden() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "cards.xlsx",
                XLSX_MIME, xlsxBytes());
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/import")
                        .file(file).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void non_excel_import_returns_400() throws Exception {
        MockMultipartFile bad = new MockMultipartFile("file", "cards.xlsx",
                XLSX_MIME, "not excel".getBytes());
        mockMvc.perform(multipart("/api/flashcards/" + deckId + "/import")
                        .file(bad).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    @WithUserDetails(OWNER)
    void import_template_download_ok() throws Exception {
        mockMvc.perform(get("/api/flashcards/import-template"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(XLSX_MIME));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Builds a 2-column .xlsx (header + 2 data rows) as bytes. */
    private static byte[] xlsxBytes() throws IOException {
        String[][] grid = {
                {"Mặt trước", "Mặt sau"},
                {"apple", "quả táo"},
                {"book", "quyển sách"},
        };
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Thẻ");
            for (int r = 0; r < grid.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < grid[r].length; c++) {
                    row.createCell(c).setCellValue(grid[r][c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void enroll(User u, ClassEntity clazz) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(User lecturer, String name, String code) {
        ClassEntity entity = new ClassEntity(name, lecturer.getId(), lecturer.getId(),
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }
}
