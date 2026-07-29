package com.ulp.features.flashcards.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Enrollment;
import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckForm;
import com.ulp.features.flashcards.dto.FlashcardDtos.DeckSummary;
import com.ulp.features.flashcards.entity.FlashcardDeck;
import com.ulp.features.flashcards.repository.FlashcardDeckRepository;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link DeckSummaryAssembler} resolves every target class name per
 * deck and stays batched: the join-row and class-name lookups must not grow
 * with the number of decks.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class DeckSummaryAssemblerTest {

    @Autowired private DeckService deckService;
    @Autowired private DeckShareService deckShareService;
    @Autowired private DeckSummaryAssembler assembler;
    @Autowired private FlashcardDeckRepository deckRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private EnrollmentRepository enrollmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User owner;
    private User lecturer;
    private ClassEntity classA;
    private ClassEntity classB;
    private ClassEntity classC;

    @BeforeEach
    void setUp() {
        owner = userRepository.findByEmailIgnoreCase("student@ulp.edu.vn").orElseThrow();
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();

        classA = saveClass("Asm class A", "ASMA");
        classB = saveClass("Asm class B", "ASMB");
        classC = saveClass("Asm class C", "ASMC");
        enroll(owner, classA);
        enroll(owner, classB);
        enroll(owner, classC);
    }

    @Test
    void every_target_class_name_is_returned_per_deck() {
        Long twoClasses = shareDeck("Hai lớp", List.of(classA.getId(), classB.getId()));
        Long oneClass = shareDeck("Một lớp", List.of(classC.getId()));
        Long noClass = deckService.createDeck(owner.getId(), new DeckForm("Không lớp", null));

        List<DeckSummary> out = assembler.toSummaries(decks(twoClasses, oneClass, noClass),
                owner.getId());

        assertThat(summaryOf(out, twoClasses).classNames())
                .containsExactlyInAnyOrder(classA.getName(), classB.getName());
        assertThat(summaryOf(out, oneClass).classNames())
                .containsExactly(classC.getName());
        assertThat(summaryOf(out, noClass).classNames()).isEmpty();

        // `shared` is derived from having at least one target.
        assertThat(summaryOf(out, twoClasses).shared()).isTrue();
        assertThat(summaryOf(out, noClass).shared()).isFalse();
    }

    @Test
    void class_name_resolution_does_not_grow_with_the_number_of_decks() {
        long forOneDeck = queriesToAssemble(1);
        long forSixDecks = queriesToAssemble(6);

        // Batched: six decks across three classes cost the same as one deck.
        assertThat(forSixDecks).isEqualTo(forOneDeck);
    }

    /**
     * Assembles {@code deckCount} decks (each targeting all three classes) and
     * returns the number of JDBC statements Hibernate executed while doing so.
     */
    private long queriesToAssemble(int deckCount) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < deckCount; i++) {
            ids.add(shareDeck("Đo truy vấn " + i,
                    List.of(classA.getId(), classB.getId(), classC.getId())));
        }
        List<FlashcardDeck> decks = decks(ids.toArray(new Long[0]));

        // Flush pending inserts and clear so the count reflects assembly alone.
        entityManager.flush();
        entityManager.clear();

        Statistics stats = entityManager.unwrap(Session.class)
                .getSessionFactory().getStatistics();
        long before = stats.getPrepareStatementCount();
        List<DeckSummary> out = assembler.toSummaries(decks, owner.getId());
        long after = stats.getPrepareStatementCount();

        assertThat(out).hasSize(deckCount);
        assertThat(out).allSatisfy(s -> assertThat(s.classNames()).hasSize(3));
        return after - before;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private Long shareDeck(String title, List<Long> classIds) {
        Long id = deckService.createDeck(owner.getId(), new DeckForm(title, null));
        deckShareService.share(id, owner.getId(), classIds);
        return id;
    }

    private List<FlashcardDeck> decks(Long... ids) {
        return deckRepository.findAllById(List.of(ids));
    }

    private static DeckSummary summaryOf(List<DeckSummary> all, Long deckId) {
        return all.stream().filter(s -> s.id().equals(deckId)).findFirst().orElseThrow();
    }

    private void enroll(User u, ClassEntity c) {
        enrollmentRepository.saveAndFlush(Enrollment.createFor(
                u, c.getId(), Enrollment.JoinedVia.CODE, null));
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
