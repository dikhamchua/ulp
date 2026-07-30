package com.ulp.features.flashcards.controller;

import com.ulp.features.ai.client.AiClientException;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenDtos.GenerateRequest;
import com.ulp.features.ai.flashcardgen.AiFlashcardGenerationService;
import com.ulp.features.flashcards.dto.FlashcardDtos;
import com.ulp.features.flashcards.dto.FlashcardDtos.ImportResult;
import com.ulp.features.flashcards.dto.FlashcardDtos.ImportedCardRow;
import com.ulp.features.flashcards.dto.FlashcardDtos.ReviewRatingRequest;
import com.ulp.features.flashcards.dto.FlashcardDtos.ReviewResult;
import com.ulp.features.flashcards.dto.FlashcardDtos.SaveCardsRequest;
import com.ulp.features.flashcards.imports.FlashcardImportParser;
import com.ulp.features.flashcards.imports.FlashcardImportTemplate;
import com.ulp.features.flashcards.service.CardService;
import com.ulp.features.flashcards.service.SmartReviewService;
import com.ulp.features.flashcards.support.DeckAccessResolver;
import com.ulp.security.UlpUserDetails;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

import static com.ulp.common.IConstant.API_FLASHCARDS;
import static com.ulp.common.IConstant.MSG_AI_NO_MATERIAL;
import static com.ulp.common.IConstant.SUBPATH_AI_GENERATE;
import static com.ulp.features.lessons.controller.support.AjaxResponses.badRequest;
import static com.ulp.features.lessons.controller.support.AjaxResponses.forbidden;
import static com.ulp.features.lessons.controller.support.AjaxResponses.internalError;
import static com.ulp.features.lessons.controller.support.AjaxResponses.notFound;
import static com.ulp.features.lessons.dto.SectionDtos.AjaxResult;

/**
 * JSON API for flashcards under {@code /api/flashcards}: bulk card save,
 * Smart-Review rating submit, Excel import, and AI card generation.
 *
 * <p>All authorization lives in the services / {@code DeckAccessResolver}; this
 * controller only maps exceptions onto the shared {@link AjaxResult} envelope
 * ({@link IllegalArgumentException} → 400, {@link AccessDeniedException} → 403,
 * {@link EntityNotFoundException} → 404, anything else → 500).
 */
@RestController
@RequestMapping(value = API_FLASHCARDS, produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
public class FlashcardApiController {

    private static final Logger log = LoggerFactory.getLogger(FlashcardApiController.class);

    // XLSX MIME type + template filename for the download endpoint.
    private static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String TEMPLATE_FILENAME = "flashcard-import-mau.xlsx";

    private final CardService cardService;
    private final SmartReviewService smartReviewService;
    private final DeckAccessResolver accessResolver;
    private final FlashcardImportParser importParser;
    private final FlashcardImportTemplate importTemplate;
    private final AiFlashcardGenerationService aiGenerationService;

    public FlashcardApiController(CardService cardService,
                                  SmartReviewService smartReviewService,
                                  DeckAccessResolver accessResolver,
                                  FlashcardImportParser importParser,
                                  FlashcardImportTemplate importTemplate,
                                  AiFlashcardGenerationService aiGenerationService) {
        this.cardService = cardService;
        this.smartReviewService = smartReviewService;
        this.accessResolver = accessResolver;
        this.importParser = importParser;
        this.importTemplate = importTemplate;
        this.aiGenerationService = aiGenerationService;
    }

    /** Replaces the deck's cards with the submitted set; owner-only. */
    @PostMapping(value = "/{deckId}/cards", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> saveCards(@PathVariable Long deckId,
                                       @RequestBody SaveCardsRequest req,
                                       @AuthenticationPrincipal UlpUserDetails user) {
        try {
            List<FlashcardDtos.CardItem> items = req.cards() == null ? List.of() : req.cards();
            cardService.replaceCards(deckId, user.getId(), items);
            return ResponseEntity.ok(AjaxResult.success());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to save cards for deck {}", deckId, ex);
            return internalError();
        }
    }

    /** Records a recall rating for a card and upserts the user's SM-2 state. */
    @PostMapping(value = "/cards/{cardId}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> review(@PathVariable Long cardId,
                                    @RequestBody ReviewRatingRequest req,
                                    @AuthenticationPrincipal UlpUserDetails user) {
        try {
            ReviewResult result = smartReviewService.recordRating(cardId, user.getId(), req.quality());
            return ResponseEntity.ok(AjaxResult.success(result));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to record review for card {}", cardId, ex);
            return internalError();
        }
    }

    /**
     * Parses an uploaded Excel file into card rows for editor review; owner-only.
     * Does NOT persist — the JS appends the rows and the existing save flow
     * commits them once the user confirms.
     */
    @PostMapping(value = "/{deckId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importExcel(@PathVariable Long deckId,
                                         @RequestParam("file") MultipartFile file,
                                         @AuthenticationPrincipal UlpUserDetails user) {
        try {
            // Owner-only gate before parsing the upload.
            accessResolver.requireOwner(deckId, user.getId());
            List<ImportedCardRow> rows = importParser.parse(file);
            return ResponseEntity.ok(AjaxResult.success(new ImportResult(rows, rows.size())));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to import Excel for deck {}", deckId, ex);
            return internalError();
        }
    }

    /**
     * Generates card rows from an uploaded document or pasted text; owner-only.
     *
     * <p>Same contract as {@link #importExcel}: nothing is persisted, the rows go back to
     * the editor as unsaved fields and only the existing save flow commits them. That is why
     * the browser can feed AI rows and Excel rows through one code path.
     *
     * <p>Multipart rather than JSON because the file and the parameters arrive together;
     * {@code file} and {@code text} are each optional, but one of them is required.
     *
     * @param deckId   the deck being edited
     * @param file     an uploaded PDF or DOCX, optional when text is pasted
     * @param text     pasted material, optional when a file is uploaded
     * @param count    how many cards to generate
     * @param language language hint; blank means "follow the document"
     * @param user     the authenticated owner
     * @return the generated rows, or a Vietnamese error message
     */
    @PostMapping(value = "/{deckId}" + SUBPATH_AI_GENERATE,
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> aiGenerate(@PathVariable Long deckId,
                                        @RequestParam(name = "file", required = false) MultipartFile file,
                                        @RequestParam(name = "text", required = false) String text,
                                        @RequestParam(name = "count", defaultValue = "20") int count,
                                        @RequestParam(name = "language", required = false) String language,
                                        @AuthenticationPrincipal UlpUserDetails user) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (!hasFile && !hasText) {
            return badRequest(MSG_AI_NO_MATERIAL);
        }
        try {
            return ResponseEntity.ok(AjaxResult.success(aiGenerationService.generate(
                    user.getId(), deckId, hasFile ? file : null, text,
                    new GenerateRequest(count, language))));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (AiClientException ex) {
            // Provider outage or misconfiguration: surfaced as a retryable failure rather
            // than a 500 — the deck itself is untouched.
            return badRequest(ex.getMessage());
        } catch (AccessDeniedException ex) {
            return forbidden();
        } catch (EntityNotFoundException ex) {
            return notFound(ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Failed to generate AI cards for deck {}", deckId, ex);
            return internalError();
        }
    }

    /** Streams the two-column .xlsx import template; any authenticated user. */
    @GetMapping(value = "/import-template", produces = XLSX_MIME)
    public ResponseEntity<byte[]> importTemplate() {
        try {
            byte[] bytes = importTemplate.build();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(XLSX_MIME));
            headers.setContentDispositionFormData("attachment", TEMPLATE_FILENAME);
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
        } catch (IOException ex) {
            log.error("Failed to generate flashcard import template", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
