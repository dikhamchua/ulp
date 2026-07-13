package com.ulp.features.classes.service;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.entities.ClassActivity;
import com.ulp.entities.ClassEntity;
import com.ulp.entities.ClassInviteCode;
import com.ulp.entities.Enrollment;
import com.ulp.features.classes.repository.ClassInviteCodeRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.features.classes.service.JoinClassService.AlreadyJoined;
import com.ulp.features.classes.service.JoinClassService.JoinResult;
import com.ulp.features.classes.service.JoinClassService.PendingRequested;
import com.ulp.features.classes.service.invites.InviteCodeValidationException;
import com.ulp.features.classes.service.invites.InviteRejectionReason;
import com.ulp.features.notifications.entity.NotificationType;
import com.ulp.features.notifications.service.NotificationService;
import com.ulp.security.Role;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link JoinClassService}: join PENDING state machine,
 * leave, and owner approve/reject.
 */
class JoinClassServiceTest {

    private static final Long CLASS_ID = 9L;
    private static final Long USER_ID = 200L;
    private static final Long OWNER_ID = 42L;

    private ClassInviteCodeRepository inviteRepository;
    private EnrollmentRepository enrollmentRepository;
    private ClassRepository classRepository;
    private ClassActivityWriter activityWriter;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private ClassesService classesService;
    private JoinClassService service;

    @BeforeEach
    void setUp() {
        inviteRepository = mock(ClassInviteCodeRepository.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        classRepository = mock(ClassRepository.class);
        activityWriter = mock(ClassActivityWriter.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        classesService = mock(ClassesService.class);
        service = new JoinClassService(inviteRepository, enrollmentRepository,
                classRepository, activityWriter, userRepository, notificationService,
                classesService);
    }

    // ───────── invalid token ─────────

    @Test
    void unknown_token_throws_invalid() {
        when(inviteRepository.findByCodeForUpdate("ZZZZZZ")).thenReturn(Optional.empty());
        when(inviteRepository.findByCode("ZZZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join("ZZZZZZ", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.INVALID);

        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void disabled_token_throws_disabled() {
        ClassInviteCode disabled = activeToken("AB23CD");
        disabled.disable();
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.empty());
        when(inviteRepository.findByCode("AB23CD")).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.DISABLED);
    }

    @Test
    void expired_token_throws_expired() {
        ClassInviteCode token = activeToken("AB23CD");
        ReflectionTestUtils.setField(token, "expiresAt", LocalDateTime.now().minusDays(1));
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.EXPIRED);
    }

    @Test
    void over_max_uses_token_throws_exhausted() {
        ClassInviteCode token = activeToken("AB23CD");
        ReflectionTestUtils.setField(token, "maxUses", 10);
        ReflectionTestUtils.setField(token, "useCount", 10);
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.EXHAUSTED);
    }

    // ───────── class status ─────────

    @Test
    void soft_deleted_class_throws_not_joinable() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.CLASS_NOT_JOINABLE);
    }

    @Test
    void cancelled_class_throws_not_joinable() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        ReflectionTestUtils.setField(clazz, "status", "CANCELLED");
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.CLASS_NOT_JOINABLE);
    }

    @Test
    void full_class_throws_class_full() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        ReflectionTestUtils.setField(clazz, "maxStudents", 30);
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(30L);

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.CLASS_FULL);

        verify(enrollmentRepository, never()).save(any());
        assertThat(token.getUseCount()).isZero();
    }

    // ───────── enrollment lifecycle ─────────

    @Test
    void duplicate_active_returns_already_joined_without_writes() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));

        Enrollment existing = buildEnrollment(Enrollment.STATUS_ACTIVE);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(existing));

        JoinResult result = service.join("AB23CD", USER_ID);

        assertThat(result).isInstanceOf(AlreadyJoined.class);
        verify(enrollmentRepository, never()).save(any());
        verify(inviteRepository, never()).save(any());
        assertThat(token.getUseCount()).isZero();
    }

    @Test
    void already_pending_is_idempotent_without_use_count_or_re_notify() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));
        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));

        Enrollment pending = buildEnrollment(Enrollment.STATUS_PENDING);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));

        JoinResult result = service.join("AB23CD", USER_ID);

        assertThat(result).isInstanceOf(PendingRequested.class);
        assertThat(((PendingRequested) result).alreadyPending()).isTrue();
        verify(enrollmentRepository, never()).save(any());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        assertThat(token.getUseCount()).isZero();
    }

    @Test
    void revive_removed_row_becomes_pending_without_use_count() {
        ClassInviteCode token = activeToken("AB23CD");
        ReflectionTestUtils.setField(token, "id", 77L);
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(0L);

        Enrollment removed = buildEnrollment(Enrollment.STATUS_REMOVED);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(removed));

        JoinResult result = service.join("AB23CD", USER_ID);

        assertThat(result).isInstanceOf(PendingRequested.class);
        assertThat(((PendingRequested) result).alreadyPending()).isFalse();
        assertThat(removed.getStatus()).isEqualTo(Enrollment.STATUS_PENDING);
        assertThat(removed.getInviteCodeId()).isEqualTo(77L);
        assertThat(token.getUseCount()).isZero();
        verify(enrollmentRepository).save(removed);
        verify(notificationService).create(eq(OWNER_ID), any(), any(),
                eq(NotificationType.JOIN_REQUEST), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
    }

    @Test
    void rejected_re_request_becomes_pending_and_notifies_owner() {
        ClassInviteCode token = activeToken("AB23CD");
        ReflectionTestUtils.setField(token, "id", 88L);
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));
        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(0L);

        Enrollment rejected = buildEnrollment(Enrollment.STATUS_REJECTED);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(rejected));

        JoinResult result = service.join("AB23CD", USER_ID);

        assertThat(result).isInstanceOf(PendingRequested.class);
        assertThat(rejected.getStatus()).isEqualTo(Enrollment.STATUS_PENDING);
        assertThat(token.getUseCount()).isZero();
        verify(notificationService).create(eq(OWNER_ID), any(), any(),
                eq(NotificationType.JOIN_REQUEST), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
    }

    @Test
    void completed_user_re_join_throws_already_completed() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));

        Enrollment completed = buildEnrollment(Enrollment.STATUS_COMPLETED);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.join("AB23CD", USER_ID))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.ALREADY_COMPLETED);
    }

    @Test
    void fresh_join_inserts_pending_enrollment_without_use_count_or_class_enrolled() {
        ClassInviteCode token = activeToken("AB23CD");
        ReflectionTestUtils.setField(token, "id", 5L);
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(0L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        JoinResult result = service.join("AB23CD", USER_ID);

        assertThat(result).isInstanceOf(PendingRequested.class);
        assertThat(((PendingRequested) result).alreadyPending()).isFalse();

        ArgumentCaptor<Enrollment> enrCap = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollmentRepository).save(enrCap.capture());
        Enrollment row = enrCap.getValue();
        assertThat(row.getStatus()).isEqualTo(Enrollment.STATUS_PENDING);
        assertThat(row.getJoinedVia()).isEqualTo("CODE");
        assertThat(row.getInviteCodeId()).isEqualTo(5L);

        assertThat(token.getUseCount()).isZero();
        verify(inviteRepository, never()).save(any());

        verify(notificationService).create(eq(OWNER_ID), any(), any(),
                eq(NotificationType.JOIN_REQUEST), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
        verify(notificationService, never()).create(eq(USER_ID), any(), any(),
                eq(NotificationType.CLASS_ENROLLED), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCap = ArgumentCaptor.forClass(Map.class);
        verify(activityWriter).write(eq(CLASS_ID), eq(ClassActivity.TYPE_MEMBER_JOINED),
                any(), metadataCap.capture(), eq(USER_ID));
        assertThat(metadataCap.getValue()).containsEntry("user_id", USER_ID)
                .containsEntry("joined_via", "CODE");
    }

    @Test
    void link_join_records_joined_via_link_as_pending() {
        ClassInviteCode token = activeToken("k".repeat(32));
        ReflectionTestUtils.setField(token, "type", ClassInviteCode.TYPE_LINK);
        ReflectionTestUtils.setField(token, "id", 6L);
        when(inviteRepository.findByCodeForUpdate("k".repeat(32))).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(0L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        service.join("k".repeat(32), USER_ID);

        ArgumentCaptor<Enrollment> enrCap = ArgumentCaptor.forClass(Enrollment.class);
        verify(enrollmentRepository).save(enrCap.capture());
        assertThat(enrCap.getValue().getJoinedVia()).isEqualTo("LINK");
        assertThat(enrCap.getValue().getStatus()).isEqualTo(Enrollment.STATUS_PENDING);
        assertThat(token.getUseCount()).isZero();
    }

    @Test
    void lowercase_six_char_code_is_normalized_to_upper_case_for_lookup() {
        ClassInviteCode token = activeToken("AB23CD");
        when(inviteRepository.findByCodeForUpdate("AB23CD")).thenReturn(Optional.of(token));

        ClassEntity clazz = buildClass();
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(clazz));
        when(enrollmentRepository.findByUserIdAndClassId(eq(USER_ID), eq(CLASS_ID)))
                .thenReturn(Optional.empty());
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(0L);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(buildUser()));

        JoinResult result = service.join("ab23cd", USER_ID);

        assertThat(result).isInstanceOf(PendingRequested.class);
    }

    // ───────── leave ─────────

    @Test
    void leave_happy_path_marks_removed_and_writes_audit() {
        Enrollment active = buildEnrollment(Enrollment.STATUS_ACTIVE);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(active));
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.of(buildClass()));

        service.leave(CLASS_ID, USER_ID);

        assertThat(active.getStatus()).isEqualTo(Enrollment.STATUS_REMOVED);
        verify(enrollmentRepository).save(active);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCap = ArgumentCaptor.forClass(Map.class);
        verify(activityWriter).write(eq(CLASS_ID), eq(ClassActivity.TYPE_MEMBER_LEFT),
                any(), metadataCap.capture(), eq(USER_ID));
        assertThat(metadataCap.getValue()).containsEntry("user_id", USER_ID);
    }

    @Test
    void leave_when_not_enrolled_throws_not_found() {
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(CLASS_ID, USER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void leave_when_already_removed_throws_not_found() {
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(buildEnrollment(Enrollment.STATUS_REMOVED)));

        assertThatThrownBy(() -> service.leave(CLASS_ID, USER_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void leave_when_completed_throws_illegal_state() {
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(buildEnrollment(Enrollment.STATUS_COMPLETED)));

        assertThatThrownBy(() -> service.leave(CLASS_ID, USER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    // ───────── approve / reject ─────────

    @Test
    void approve_pending_activates_increments_use_count_and_notifies() {
        ClassEntity clazz = buildClass();
        when(classesService.getEditable(CLASS_ID, OWNER_ID, Role.LECTURER)).thenReturn(clazz);

        Enrollment pending = buildEnrollment(Enrollment.STATUS_PENDING);
        ReflectionTestUtils.setField(pending, "inviteCodeId", 5L);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(0L);

        ClassInviteCode invite = activeToken("AB23CD");
        ReflectionTestUtils.setField(invite, "id", 5L);
        when(inviteRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(invite));

        service.approve(CLASS_ID, USER_ID, OWNER_ID, Role.LECTURER);

        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_ACTIVE);
        assertThat(invite.getUseCount()).isEqualTo(1);
        verify(inviteRepository).save(invite);
        verify(notificationService).create(eq(USER_ID), any(), any(),
                eq(NotificationType.JOIN_APPROVED), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
        verify(notificationService).create(eq(USER_ID), any(), any(),
                eq(NotificationType.CLASS_ENROLLED), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
    }

    @Test
    void approve_when_full_leaves_pending() {
        ClassEntity clazz = buildClass();
        ReflectionTestUtils.setField(clazz, "maxStudents", 1);
        when(classesService.getEditable(CLASS_ID, OWNER_ID, Role.LECTURER)).thenReturn(clazz);

        Enrollment pending = buildEnrollment(Enrollment.STATUS_PENDING);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));
        when(enrollmentRepository.countActiveByClassId(CLASS_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.approve(CLASS_ID, USER_ID, OWNER_ID, Role.LECTURER))
                .isInstanceOf(InviteCodeValidationException.class)
                .extracting(ex -> ((InviteCodeValidationException) ex).getReason())
                .isEqualTo(InviteRejectionReason.CLASS_FULL);

        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_PENDING);
        verify(inviteRepository, never()).save(any());
    }

    @Test
    void approve_non_owner_denied() {
        ClassEntity clazz = buildClass();
        // getEditable allows HEAD, but requireOwner still checks lecturerId.
        when(classesService.getEditable(CLASS_ID, 999L, Role.HEAD)).thenReturn(clazz);

        assertThatThrownBy(() -> service.approve(CLASS_ID, USER_ID, 999L, Role.HEAD))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reject_pending_marks_rejected_without_use_count() {
        ClassEntity clazz = buildClass();
        when(classesService.getEditable(CLASS_ID, OWNER_ID, Role.LECTURER)).thenReturn(clazz);

        Enrollment pending = buildEnrollment(Enrollment.STATUS_PENDING);
        when(enrollmentRepository.findByUserIdAndClassId(USER_ID, CLASS_ID))
                .thenReturn(Optional.of(pending));

        service.reject(CLASS_ID, USER_ID, OWNER_ID, Role.LECTURER);

        assertThat(pending.getStatus()).isEqualTo(Enrollment.STATUS_REJECTED);
        verify(inviteRepository, never()).save(any());
        verify(notificationService).create(eq(USER_ID), any(), any(),
                eq(NotificationType.JOIN_REJECTED), eq(NotificationType.REF_CLASS), eq(CLASS_ID));
        verify(notificationService, never()).create(eq(USER_ID), any(), any(),
                eq(NotificationType.CLASS_ENROLLED), any(), any());
    }

    // ───────── helpers ─────────

    private static ClassInviteCode activeToken(String code) {
        ClassInviteCode ic = new ClassInviteCode(CLASS_ID, code,
                ClassInviteCode.TYPE_CODE, OWNER_ID);
        ReflectionTestUtils.setField(ic, "id", 1L);
        return ic;
    }

    private static ClassEntity buildClass() {
        ClassEntity c = new ClassEntity("Demo", OWNER_ID, OWNER_ID, null, null, null, 100);
        ReflectionTestUtils.setField(c, "id", CLASS_ID);
        return c;
    }

    private static Enrollment buildEnrollment(String status) {
        Enrollment e = new Enrollment(buildUser(), CLASS_ID, "CODE", 1L);
        ReflectionTestUtils.setField(e, "status", status);
        return e;
    }

    private static User buildUser() {
        User u = new User() {};
        ReflectionTestUtils.setField(u, "id", USER_ID);
        ReflectionTestUtils.setField(u, "email", "x@u.vn");
        ReflectionTestUtils.setField(u, "fullName", "X");
        ReflectionTestUtils.setField(u, "passwordHash", "x");
        return u;
    }
}
