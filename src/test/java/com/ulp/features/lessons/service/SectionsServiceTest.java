package com.ulp.features.lessons.service;

import com.ulp.entities.ClassEntity;
import com.ulp.entities.Section;
import com.ulp.entities.SectionActivity;
import com.ulp.entities.User;
import com.ulp.entities.UserFactory;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.lessons.dto.SectionDtos.SectionRow;
import com.ulp.features.lessons.repository.SectionActivityRepository;
import com.ulp.features.lessons.repository.SectionRepository;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link SectionsService}.
 *
 * <p>Uses seeded users from {@code V5__seed_test_users.sql}. Each test gets
 * a fresh class owned by {@code lecturer@ulp.edu.vn} so the section state
 * does not bleed across tests.
 */
@SpringBootTest
@Transactional
class SectionsServiceTest {

    @Autowired private SectionsService sectionsService;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private SectionActivityRepository activityRepository;
    @Autowired private ClassRepository classRepository;
    @Autowired private UserRepository userRepository;

    private User lecturer;
    private User otherLecturer;
    private ClassEntity clazz;

    @BeforeEach
    void setUp() {
        lecturer = userRepository.findByEmailIgnoreCase("lecturer@ulp.edu.vn").orElseThrow();
        // The seed data contains a single LECTURER. Use HEAD as a "different
        // role" proxy for the negative-auth test below; an ad-hoc LECTURER
        // user is created locally so we can also exercise the LECTURER vs
        // LECTURER ownership path.
        otherLecturer = ensureExtraLecturer();
        clazz = saveClass("Sections IT class", lecturer.getId(), "SECIT");
    }

    @Test
    void create_two_sections_returns_rows_with_increasing_display_order() {
        SectionRow first = sectionsService.create(
                clazz.getId(), "Chương 1", lecturer.getId(), Role.LECTURER);
        SectionRow second = sectionsService.create(
                clazz.getId(), "Chương 2", lecturer.getId(), Role.LECTURER);

        assertThat(first.displayOrder()).isEqualTo((short) 0);
        assertThat(second.displayOrder()).isEqualTo((short) 1);

        List<SectionRow> listed = sectionsService.listForClass(
                clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).title()).isEqualTo("Chương 1");
        assertThat(listed.get(1).title()).isEqualTo("Chương 2");
    }

    @Test
    void rename_section_updates_title() {
        SectionRow row = sectionsService.create(
                clazz.getId(), "Tạm", lecturer.getId(), Role.LECTURER);
        SectionRow renamed = sectionsService.rename(
                clazz.getId(), row.id(), "Chương đã đổi tên",
                lecturer.getId(), Role.LECTURER);
        assertThat(renamed.title()).isEqualTo("Chương đã đổi tên");

        Section reloaded = sectionRepository.findById(row.id()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Chương đã đổi tên");
    }

    @Test
    void delete_section_marks_is_deleted_and_hides_from_list() {
        SectionRow keep = sectionsService.create(
                clazz.getId(), "Giữ lại", lecturer.getId(), Role.LECTURER);
        SectionRow drop = sectionsService.create(
                clazz.getId(), "Xoá đi", lecturer.getId(), Role.LECTURER);

        sectionsService.delete(clazz.getId(), drop.id(), lecturer.getId(), Role.LECTURER);

        List<SectionRow> listed = sectionsService.listForClass(
                clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(listed).extracting(SectionRow::id).containsExactly(keep.id());
    }

    /**
     * Regression test for the soft-delete unique-key trap: after deleting
     * the last section, the next {@code create} call must succeed even
     * though the freed row still lives in the table. The migration sets
     * {@code display_order} nullable + {@link Section#markDeleted()} clears
     * it, so the unique key {@code uk_section_class_order} stops fighting
     * the soft-delete row.
     */
    @Test
    void create_after_delete_last_section_does_not_collide() {
        SectionRow first = sectionsService.create(
                clazz.getId(), "Chương 1", lecturer.getId(), Role.LECTURER);
        SectionRow second = sectionsService.create(
                clazz.getId(), "Chương 2", lecturer.getId(), Role.LECTURER);

        sectionsService.delete(clazz.getId(), second.id(),
                lecturer.getId(), Role.LECTURER);

        // The recreated section should reclaim display_order = 1 without
        // hitting the unique key against the soft-deleted row.
        SectionRow recreated = sectionsService.create(
                clazz.getId(), "Chương 2 mới", lecturer.getId(), Role.LECTURER);

        assertThat(recreated.displayOrder()).isEqualTo((short) 1);

        List<SectionRow> listed = sectionsService.listForClass(
                clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(listed).extracting(SectionRow::id)
                .containsExactly(first.id(), recreated.id());
    }

    @Test
    void reorder_sections_updates_display_order_atomically() {
        SectionRow a = sectionsService.create(
                clazz.getId(), "A", lecturer.getId(), Role.LECTURER);
        SectionRow b = sectionsService.create(
                clazz.getId(), "B", lecturer.getId(), Role.LECTURER);
        SectionRow c = sectionsService.create(
                clazz.getId(), "C", lecturer.getId(), Role.LECTURER);

        // Reverse order: C, B, A
        sectionsService.reorder(clazz.getId(),
                Arrays.asList(c.id(), b.id(), a.id()),
                lecturer.getId(), Role.LECTURER);

        List<SectionRow> listed = sectionsService.listForClass(
                clazz.getId(), lecturer.getId(), Role.LECTURER);
        assertThat(listed).extracting(SectionRow::id)
                .containsExactly(c.id(), b.id(), a.id());
        assertThat(listed).extracting(SectionRow::displayOrder)
                .containsExactly((short) 0, (short) 1, (short) 2);
    }

    @Test
    void reorder_with_wrong_ids_throws() {
        SectionRow a = sectionsService.create(
                clazz.getId(), "A", lecturer.getId(), Role.LECTURER);
        SectionRow b = sectionsService.create(
                clazz.getId(), "B", lecturer.getId(), Role.LECTURER);

        // Send only one id when two exist → mismatch.
        assertThatThrownBy(() -> sectionsService.reorder(
                clazz.getId(), List.of(a.id()),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);

        // Send an unknown id (use existing id + 999 to stay out of range).
        Long bogus = b.id() + 999_999L;
        assertThatThrownBy(() -> sectionsService.reorder(
                clazz.getId(), Arrays.asList(a.id(), bogus),
                lecturer.getId(), Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_owner_lecturer_cannot_create() {
        assertThatThrownBy(() -> sectionsService.create(
                clazz.getId(), "Chương lậu",
                otherLecturer.getId(), Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── Activity logging ──────────────────────────────────────────────

    @Test
    void create_writes_created_activity_row() {
        SectionRow row = sectionsService.create(
                clazz.getId(), "Chương 1", lecturer.getId(), Role.LECTURER);

        var page = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);
        SectionActivity activity = page.getContent().get(0);
        assertThat(activity.getType()).isEqualTo(SectionActivity.TYPE_CREATED);
        assertThat(activity.getDescription()).contains("Chương 1");
        assertThat(activity.getCreatedBy()).isEqualTo(lecturer.getId());
    }

    @Test
    void rename_writes_renamed_activity_with_diff_metadata() {
        SectionRow row = sectionsService.create(
                clazz.getId(), "Cũ", lecturer.getId(), Role.LECTURER);
        sectionsService.rename(clazz.getId(), row.id(), "Mới",
                lecturer.getId(), Role.LECTURER);

        var page = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        // newest first: RENAMED then CREATED
        assertThat(page.getContent()).hasSize(2);
        SectionActivity renamed = page.getContent().get(0);
        assertThat(renamed.getType()).isEqualTo(SectionActivity.TYPE_RENAMED);
        assertThat(renamed.getDescription()).contains("Cũ").contains("Mới");
        assertThat(renamed.getMetadata()).contains("\"old\":\"Cũ\"")
                .contains("\"new\":\"Mới\"");
    }

    @Test
    void rename_with_same_title_does_not_write_activity() {
        SectionRow row = sectionsService.create(
                clazz.getId(), "Unchanged", lecturer.getId(), Role.LECTURER);
        sectionsService.rename(clazz.getId(), row.id(), "Unchanged",
                lecturer.getId(), Role.LECTURER);

        var page = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        // Only the CREATED row — no RENAMED entry because the title did not change.
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getType())
                .isEqualTo(SectionActivity.TYPE_CREATED);
    }

    @Test
    void delete_writes_deleted_activity() {
        SectionRow row = sectionsService.create(
                clazz.getId(), "Bỏ", lecturer.getId(), Role.LECTURER);
        sectionsService.delete(clazz.getId(), row.id(),
                lecturer.getId(), Role.LECTURER);

        var page = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                row.id(), PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getType())
                .isEqualTo(SectionActivity.TYPE_DELETED);
    }

    @Test
    void reorder_writes_reordered_activity_only_for_moved_sections() {
        SectionRow a = sectionsService.create(
                clazz.getId(), "A", lecturer.getId(), Role.LECTURER);
        SectionRow b = sectionsService.create(
                clazz.getId(), "B", lecturer.getId(), Role.LECTURER);
        SectionRow c = sectionsService.create(
                clazz.getId(), "C", lecturer.getId(), Role.LECTURER);

        // Swap A and C, keep B in the middle. Only A and C should get a
        // REORDERED activity row.
        sectionsService.reorder(clazz.getId(),
                Arrays.asList(c.id(), b.id(), a.id()),
                lecturer.getId(), Role.LECTURER);

        var aPage = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                a.id(), PageRequest.of(0, 10));
        assertThat(aPage.getContent()).extracting(SectionActivity::getType)
                .containsExactly(SectionActivity.TYPE_REORDERED,
                                 SectionActivity.TYPE_CREATED);

        var bPage = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                b.id(), PageRequest.of(0, 10));
        // B didn't move — only the CREATED row, no REORDERED.
        assertThat(bPage.getContent()).extracting(SectionActivity::getType)
                .containsExactly(SectionActivity.TYPE_CREATED);

        var cPage = activityRepository.findBySectionIdOrderByCreatedAtDesc(
                c.id(), PageRequest.of(0, 10));
        assertThat(cPage.getContent()).extracting(SectionActivity::getType)
                .containsExactly(SectionActivity.TYPE_REORDERED,
                                 SectionActivity.TYPE_CREATED);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private ClassEntity saveClass(String name, Long lecturerId, String code) {
        ClassEntity entity = new ClassEntity(name, lecturerId, lecturerId,
                null, null, null, 100);
        entity.setCode(code);
        try {
            return classRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            entity.setCode(code + "x");
            return classRepository.saveAndFlush(entity);
        }
    }

    /**
     * Returns a second LECTURER user, creating one if the seed data only
     * contains a single lecturer account.
     */
    private User ensureExtraLecturer() {
        String email = "lecturer-other@ulp.edu.vn";
        return userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User u = UserFactory.newAdminCreated(
                    email,
                    "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
                    "Lecturer Other",
                    Role.LECTURER,
                    true,
                    null,
                    null);
            return userRepository.saveAndFlush(u);
        });
    }
}
