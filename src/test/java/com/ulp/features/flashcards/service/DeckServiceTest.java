package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.security.Role;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckDetailView;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ulp.features.flashcards.dto.FlashcardDtos.StudentDeckList;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import static com.ulp.common.IConstant.DEFAULT_DECK_PAGE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration tests for {@link DeckService}: CRUD, authz, and sharing. */
@SpringBootTest
@Transactional
class DeckServiceTest {

    @Autowired private DeckService deckService;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;

    private User owner;   // student@
    private User member;  // sv02@ — enrolled classmate
    private User outsider; // sv01@ — not enrolled
    private User lecturer;
    private ClassEntity clazz;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        member = userRepository.findByEmailIgnoreCase("sv02@ulp.edu.vn").orElseThrow();
        outsider = userRepository.findByEmailIgnoreCase("sv01@ulp.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        clazz = saveClass("Deck class", "DKCLS");
        enroll(owner);
        enroll(member);
    }

    @Test
    void create_then_detail_marks_owner() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Bộ thẻ 1", "mô tả"));
        DeckDetailView detail = deckService.getDetail(id, owner.getId());
        assertThat(detail.owner()).isTrue();
        assertThat(detail.title()).isEqualTo("Bộ thẻ 1");
        assertThat(detail.shared()).isFalse();
    }

    @Test
    void update_metadata_changes_title() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Cũ", null));
        deckService.updateMetadata(id, owner.getId(), new DeckForm("Mới", "d2"));
        assertThat(deckService.getDetail(id, owner.getId()).title()).isEqualTo("Mới");
    }

    @Test
    void non_owner_update_denied() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Cũ", null));
        assertThatThrownBy(() -> deckService.updateMetadata(id, member.getId(),
                new DeckForm("Hack", null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void soft_delete_hides_from_list_and_detail() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Sẽ xoá", null));
        deckService.softDelete(id, owner.getId());
        assertThatThrownBy(() -> deckService.getDetail(id, owner.getId()))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(deckService.listForStudent(owner.getId(), 0).ownDecks().getContent())
                .noneMatch(d -> d.id().equals(id));
    }

    @Test
    void outsider_detail_returns_not_found() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Riêng tư", null));
        assertThatThrownBy(() -> deckService.getDetail(id, outsider.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void share_makes_deck_visible_to_enrolled_member_not_outsider() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Chia sẻ", null));
        deckService.share(id, owner.getId(), clazz.getId());

        // Enrolled member can view (shared member).
        DeckDetailView asMember = deckService.getDetail(id, member.getId());
        assertThat(asMember.owner()).isFalse();
        assertThat(asMember.shared()).isTrue();

        // Member appears in shared list; outsider cannot see it.
        StudentDeckList memberList = deckService.listForStudent(member.getId(), 0);
        assertThat(memberList.sharedDecks()).anyMatch(d -> d.id().equals(id));
        assertThatThrownBy(() -> deckService.getDetail(id, outsider.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shared_member_cannot_change_sharing() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Chia sẻ", null));
        deckService.share(id, owner.getId(), clazz.getId());
        assertThatThrownBy(() -> deckService.unshare(id, member.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void unshare_reverts_to_private_and_hides_from_member() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Chia sẻ", null));
        deckService.share(id, owner.getId(), clazz.getId());
        deckService.unshare(id, owner.getId());

        assertThat(deckService.getDetail(id, owner.getId()).shared()).isFalse();
        assertThatThrownBy(() -> deckService.getDetail(id, member.getId()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void share_to_class_owner_not_in_denied() {
        Long id = deckService.createDeck(owner.getId(), new DeckForm("Chia sẻ", null));
        ClassEntity other = saveClass("Other class", "OTHCL");
        assertThatThrownBy(() -> deckService.share(id, owner.getId(), other.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Pagination (own decks, newest-first) ─────────────────────────────
    // A fresh user is created per test so counts are deterministic regardless of
    // any decks pre-existing in the shared dev database for the seeded users.

    @Test
    void own_decks_first_page_is_newest_first_and_reports_total_pages() {
        User fresh = freshStudent();
        int total = DEFAULT_DECK_PAGE_SIZE + 3; // 15 decks → 2 pages (12 + 3)
        for (int i = 0; i < total; i++) {
            deckService.createDeck(fresh.getId(), new DeckForm("Bộ " + i, null));
        }

        Page<DeckSummary> page0 = deckService.listForStudent(fresh.getId(), 0).ownDecks();
        assertThat(page0.getContent()).hasSize(DEFAULT_DECK_PAGE_SIZE);
        assertThat(page0.getTotalElements()).isEqualTo(total);
        assertThat(page0.getTotalPages()).isEqualTo(2);
        assertThat(page0.getNumber()).isZero();
        assertThat(page0.isFirst()).isTrue();
        assertThat(page0.isLast()).isFalse();
        // Newest-first: ids strictly descending (created_at DESC, id DESC tiebreaker).
        assertThat(page0.getContent()).allMatch(DeckSummary::owner);
        for (int i = 1; i < page0.getContent().size(); i++) {
            assertThat(page0.getContent().get(i - 1).id())
                    .isGreaterThan(page0.getContent().get(i).id());
        }
    }

    @Test
    void own_decks_last_page_returns_remainder() {
        User fresh = freshStudent();
        int total = DEFAULT_DECK_PAGE_SIZE + 3;
        for (int i = 0; i < total; i++) {
            deckService.createDeck(fresh.getId(), new DeckForm("Bộ " + i, null));
        }

        Page<DeckSummary> page1 = deckService.listForStudent(fresh.getId(), 1).ownDecks();
        assertThat(page1.getContent()).hasSize(3);
        assertThat(page1.isLast()).isTrue();
        assertThat(page1.getNumber()).isEqualTo(1);
    }

    @Test
    void own_decks_single_page_reports_one_total_page() {
        User fresh = freshStudent();
        for (int i = 0; i < 5; i++) {
            deckService.createDeck(fresh.getId(), new DeckForm("Bộ " + i, null));
        }
        Page<DeckSummary> page = deckService.listForStudent(fresh.getId(), 0).ownDecks();
        assertThat(page.getTotalPages()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(5);
        assertThat(page.isLast()).isTrue();
    }

    @Test
    void own_decks_page_excludes_other_users_decks() {
        User a = freshStudent();
        User b = freshStudent();
        Long mine = deckService.createDeck(a.getId(), new DeckForm("Của tôi", null));
        Long theirs = deckService.createDeck(b.getId(), new DeckForm("Của bạn", null));

        Page<DeckSummary> page = deckService.listForStudent(a.getId(), 0).ownDecks();
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).anyMatch(d -> d.id().equals(mine));
        assertThat(page.getContent()).noneMatch(d -> d.id().equals(theirs));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Persists a brand-new STUDENT so deck counts start from zero. */
    private User freshStudent() {
        String email = "fc-page-" + System.nanoTime() + "@ulp.edu.vn";
        return userRepository.saveAndFlush(UserFactory.newAdminCreated(
                email, "x", "Fresh Student", Role.STUDENT, true, null, null));
    }

    private void enroll(User u) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, clazz.getId(), Enrollment.JoinedVia.CODE, null));
    }

    private ClassEntity saveClass(String name, String code) {
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
