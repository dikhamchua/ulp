package com.ulp.features.classes.service.invites;

import com.ulp.security.Role;
import com.ulp.entities.ClassActivity;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.service.ClassActivityWriter;
import com.ulp.features.classes.service.ClassesService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link InviteCodeService}. Covers every
 * scenario in spec class-invite-codes "Regenerate active token".
 */
class InviteCodeServiceTest {

    private static final Long CLASS_ID = 9L;
    private static final Long OWNER_ID = 42L;
    private static final Long OUTSIDER_ID = 99L;

    private ClassInviteCodeRepository inviteRepository;
    private InviteTokenGenerator generator;
    private ClassActivityWriter activityWriter;
    private ClassesService classesService;
    private InviteCodeService service;

    @BeforeEach
    void setUp() {
        inviteRepository = mock(ClassInviteCodeRepository.class);
        generator = mock(InviteTokenGenerator.class);
        activityWriter = mock(ClassActivityWriter.class);
        classesService = mock(ClassesService.class);
        service = new InviteCodeService(inviteRepository, generator,
                activityWriter, classesService, "http://localhost:8080");

        // Default: saveAndFlush returns the same entity (no collision).
        when(inviteRepository.saveAndFlush(any(ClassInviteCode.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ─────────── provisionDefaults ───────────

    @Test
    void provision_defaults_inserts_one_code_row_and_one_link_row() {
        when(generator.generateCode()).thenReturn("AB23CD");
        when(generator.generateLink()).thenReturn("k".repeat(32));

        service.provisionDefaults(CLASS_ID, OWNER_ID);

        ArgumentCaptor<ClassInviteCode> captor = ArgumentCaptor.forClass(ClassInviteCode.class);
        verify(inviteRepository, times(2)).saveAndFlush(captor.capture());

        assertThat(captor.getAllValues()).hasSize(2);
        assertThat(captor.getAllValues().get(0).getType()).isEqualTo(ClassInviteCode.TYPE_CODE);
        assertThat(captor.getAllValues().get(0).getCode()).isEqualTo("AB23CD");
        assertThat(captor.getAllValues().get(0).isActive()).isTrue();
        assertThat(captor.getAllValues().get(0).getUseCount()).isZero();
        assertThat(captor.getAllValues().get(1).getType()).isEqualTo(ClassInviteCode.TYPE_LINK);
        assertThat(captor.getAllValues().get(1).getCode()).hasSize(32);
    }

    // ─────────── regenerateActive ───────────

    @Test
    void regenerate_by_owner_disables_old_and_creates_new() {
        ClassEntity clazz = buildClass(CLASS_ID, OWNER_ID);
        when(classesService.getEditable(eq(CLASS_ID), eq(OWNER_ID), eq(Role.LECTURER)))
                .thenReturn(clazz);

        ClassInviteCode prev = new ClassInviteCode(CLASS_ID, "OLDCDE",
                ClassInviteCode.TYPE_CODE, OWNER_ID);
        ReflectionTestUtils.setField(prev, "id", 5L);

        when(inviteRepository.findByClassIdAndTypeAndActiveTrue(CLASS_ID, ClassInviteCode.TYPE_CODE))
                .thenReturn(Optional.of(prev));
        when(generator.generateCode()).thenReturn("NEWVAL");

        ClassInviteCode fresh = service.regenerateActive(CLASS_ID,
                ClassInviteCode.TYPE_CODE, OWNER_ID, Role.LECTURER);

        assertThat(prev.isActive()).isFalse();
        verify(inviteRepository).save(prev); // disabled persisted

        assertThat(fresh.getCode()).isEqualTo("NEWVAL");
        assertThat(fresh.isActive()).isTrue();
        assertThat(fresh.getUseCount()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCap = ArgumentCaptor.forClass(Map.class);
        verify(activityWriter).write(eq(CLASS_ID), eq(ClassActivity.TYPE_UPDATED),
                any(), metadataCap.capture(), eq(OWNER_ID));
        Map<String, Object> meta = metadataCap.getValue();
        assertThat(meta).containsEntry("action", "regenerate_invite")
                .containsEntry("invite_type", ClassInviteCode.TYPE_CODE);
    }

    @Test
    void regenerate_by_non_owner_lecturer_throws_access_denied() {
        when(classesService.getEditable(eq(CLASS_ID), eq(OUTSIDER_ID), eq(Role.LECTURER)))
                .thenThrow(new AccessDeniedException("not your class"));

        assertThatThrownBy(() -> service.regenerateActive(CLASS_ID,
                ClassInviteCode.TYPE_CODE, OUTSIDER_ID, Role.LECTURER))
                .isInstanceOf(AccessDeniedException.class);

        verify(inviteRepository, never()).save(any(ClassInviteCode.class));
        verify(activityWriter, never()).write(any(), any(), any(), any());
        verify(activityWriter, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    void regenerate_on_missing_class_throws_entity_not_found() {
        when(classesService.getEditable(eq(CLASS_ID), eq(OWNER_ID), eq(Role.LECTURER)))
                .thenThrow(new EntityNotFoundException("nope"));

        assertThatThrownBy(() -> service.regenerateActive(CLASS_ID,
                ClassInviteCode.TYPE_CODE, OWNER_ID, Role.LECTURER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void regenerate_with_invalid_type_throws_illegal_argument() {
        assertThatThrownBy(() -> service.regenerateActive(CLASS_ID,
                "BOGUS", OWNER_ID, Role.LECTURER))
                .isInstanceOf(IllegalArgumentException.class);
        verify(classesService, never()).getEditable(any(), any(), any());
    }

    @Test
    void regenerated_code_differs_from_previous_active_value() {
        ClassEntity clazz = buildClass(CLASS_ID, OWNER_ID);
        when(classesService.getEditable(eq(CLASS_ID), eq(OWNER_ID), eq(Role.LECTURER)))
                .thenReturn(clazz);

        ClassInviteCode prev = new ClassInviteCode(CLASS_ID, "AB23CD",
                ClassInviteCode.TYPE_CODE, OWNER_ID);
        when(inviteRepository.findByClassIdAndTypeAndActiveTrue(CLASS_ID, ClassInviteCode.TYPE_CODE))
                .thenReturn(Optional.of(prev));

        // First two attempts produce the previous value; third
        // produces a fresh value. Service must retry past the
        // collision.
        when(generator.generateCode()).thenReturn("AB23CD", "AB23CD", "XYZ234");

        ClassInviteCode fresh = service.regenerateActive(CLASS_ID,
                ClassInviteCode.TYPE_CODE, OWNER_ID, Role.LECTURER);

        assertThat(fresh.getCode()).isNotEqualTo("AB23CD");
        assertThat(fresh.getCode()).isEqualTo("XYZ234");
    }

    // ─────────── lookups ───────────

    @Test
    void find_active_by_token_uppercases_six_char_input() {
        ClassInviteCode match = new ClassInviteCode(CLASS_ID, "AB23CD",
                ClassInviteCode.TYPE_CODE, OWNER_ID);
        when(inviteRepository.findByCodeAndActiveTrue("AB23CD"))
                .thenReturn(Optional.of(match));

        Optional<ClassInviteCode> found = service.findActiveByToken("ab23cd");

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo("AB23CD");
    }

    @Test
    void find_active_by_token_keeps_link_input_case() {
        String linkToken = "Abc-123_xyz_456-AbcdefghijklmnoP"; // 32 chars
        ClassInviteCode match = new ClassInviteCode(CLASS_ID, linkToken,
                ClassInviteCode.TYPE_LINK, OWNER_ID);
        when(inviteRepository.findByCodeAndActiveTrue(linkToken))
                .thenReturn(Optional.of(match));

        Optional<ClassInviteCode> found = service.findActiveByToken(linkToken);

        assertThat(found).isPresent();
        assertThat(found.get().getCode()).isEqualTo(linkToken);
    }

    // ─────────── buildLinkUrl ───────────

    @Test
    void build_link_url_concatenates_base_url_and_token() {
        ClassInviteCode link = new ClassInviteCode(CLASS_ID, "abc-link-token",
                ClassInviteCode.TYPE_LINK, OWNER_ID);

        assertThat(service.buildLinkUrl(link)).isEqualTo("http://localhost:8080/j/abc-link-token");
    }

    @Test
    void build_link_url_strips_trailing_slash_from_base_url() {
        InviteCodeService localService = new InviteCodeService(inviteRepository, generator,
                activityWriter, classesService, "https://ulp.example.com/");

        ClassInviteCode link = new ClassInviteCode(CLASS_ID, "tok",
                ClassInviteCode.TYPE_LINK, OWNER_ID);

        assertThat(localService.buildLinkUrl(link)).isEqualTo("https://ulp.example.com/j/tok");
    }

    // ─────────── helpers ───────────

    private static ClassEntity buildClass(Long id, Long ownerId) {
        ClassEntity e = new ClassEntity("Demo", ownerId, ownerId, null, null, null, 100);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }
}
