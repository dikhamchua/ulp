package com.ulp.features.messaging.support;

import com.ulp.entities.User;
import com.ulp.features.auth.repository.UserRepository;
import com.ulp.features.classes.repository.ClassRepository;
import com.ulp.features.classes.repository.EnrollmentRepository;
import com.ulp.security.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Recipient eligibility gate for direct messaging (Epic #13, ULP-8.3).
 *
 * <p>The gate is intentionally NARROW: it decides whether one user may START a
 * new conversation with another, and it powers recipient search. It is NOT
 * consulted once a conversation exists — {@code send} / {@code openConversation}
 * check only membership (see design decision D2). The rules:
 *
 * <ul>
 *   <li>Only student ↔ lecturer (LECTURER or HEAD) pairs are allowed.</li>
 *   <li>A student may reach a lecturer who teaches any class the student is
 *       ACTIVE-enrolled in.</li>
 *   <li>A lecturer may reach a student ACTIVE-enrolled in any class they teach.</li>
 *   <li>Student↔student, lecturer↔lecturer, and anything involving ADMIN are
 *       blocked.</li>
 * </ul>
 */
@Component
public class MessagingAccess {

    private final EnrollmentRepository enrollmentRepository;
    private final ClassRepository classRepository;
    private final UserRepository userRepository;

    public MessagingAccess(EnrollmentRepository enrollmentRepository,
                           ClassRepository classRepository,
                           UserRepository userRepository) {
        this.enrollmentRepository = enrollmentRepository;
        this.classRepository = classRepository;
        this.userRepository = userRepository;
    }

    /**
     * Whether {@code meId} (with role {@code meRole}) may start a conversation
     * with {@code otherId}. Resolves the other user's role from the DB and
     * applies the student↔lecturer sharing rule in whichever direction fits.
     *
     * @param meId    the initiating user's id
     * @param meRole  the initiating user's role
     * @param otherId the prospective peer's id
     * @return {@code true} when the pair is eligible to start a conversation
     */
    public boolean canStartConversation(Long meId, Role meRole, Long otherId) {
        if (meId == null || otherId == null || meId.equals(otherId)) return false;
        User other = userRepository.findById(otherId).orElse(null);
        if (other == null) return false;
        return eligible(meId, meRole, otherId, other.getRole());
    }

    /**
     * Returns the users the caller may start a conversation with, optionally
     * filtered by a case-insensitive name/email substring. Students see the
     * lecturers of their ACTIVE-enrollment classes; lecturers see the students
     * ACTIVE-enrolled in the classes they teach. Any other role gets an empty
     * list (messaging is student↔lecturer only).
     *
     * @param meId   the caller's id
     * @param meRole the caller's role
     * @param q      optional case-insensitive name/email filter; null/blank disables
     * @return eligible recipient users
     */
    public List<User> eligibleRecipients(Long meId, Role meRole, String q) {
        String filter = (q == null) ? null : q.trim();
        if (isStudent(meRole)) {
            List<Long> classIds = enrollmentRepository.findActiveClassIdsForUser(meId);
            if (classIds.isEmpty()) return List.of();
            List<Long> lecturerIds = classRepository.findLecturerIdsForClasses(classIds);
            if (lecturerIds.isEmpty()) return List.of();
            // Load the lecturer users, then filter to LECTURER/HEAD and the query.
            return userRepository.findAllById(lecturerIds).stream()
                    .filter(u -> isLecturer(u.getRole()))
                    .filter(u -> matches(u, filter))
                    .sorted((a, b) -> {
                        String na = a.getFullName() != null ? a.getFullName() : a.getEmail();
                        String nb = b.getFullName() != null ? b.getFullName() : b.getEmail();
                        return String.CASE_INSENSITIVE_ORDER.compare(
                                na != null ? na : "", nb != null ? nb : "");
                    })
                    .toList();
        }
        if (isLecturer(meRole)) {
            List<Long> classIds = classRepository.findClassIdsForLecturer(meId);
            if (classIds.isEmpty()) return List.of();
            // Full ACTIVE student roster for the lecturer's classes; optional
            // filter is only used by server callers that still pass q.
            return enrollmentRepository.findActiveStudentsInClasses(classIds, filter).stream()
                    .filter(u -> isStudent(u.getRole()))
                    .sorted((a, b) -> {
                        String na = a.getFullName() != null ? a.getFullName() : a.getEmail();
                        String nb = b.getFullName() != null ? b.getFullName() : b.getEmail();
                        return String.CASE_INSENSITIVE_ORDER.compare(
                                na != null ? na : "", nb != null ? nb : "");
                    })
                    .toList();
        }
        // ADMIN or any other role is out of scope for messaging.
        return List.of();
    }

    /** Core rule: student↔lecturer only, sharing an ACTIVE-enrollment class. */
    private boolean eligible(Long meId, Role meRole, Long otherId, Role otherRole) {
        if (isStudent(meRole) && isLecturer(otherRole)) {
            return studentSharesClassWithLecturer(meId, otherId);
        }
        if (isLecturer(meRole) && isStudent(otherRole)) {
            return studentSharesClassWithLecturer(otherId, meId);
        }
        // Same-side pairs (student↔student, lecturer↔lecturer) and ADMIN: blocked.
        return false;
    }

    /**
     * Whether {@code studentId} is ACTIVE-enrolled in at least one class taught
     * by {@code lecturerId}.
     */
    private boolean studentSharesClassWithLecturer(Long studentId, Long lecturerId) {
        List<Long> studentClassIds = enrollmentRepository.findActiveClassIdsForUser(studentId);
        if (studentClassIds.isEmpty()) return false;
        List<Long> lecturerIds = classRepository.findLecturerIdsForClasses(studentClassIds);
        return lecturerIds.contains(lecturerId);
    }

    private static boolean isStudent(Role role) {
        return role == Role.STUDENT;
    }

    /** LECTURER and HEAD both teach classes; HEAD inherits lecturer abilities. */
    private static boolean isLecturer(Role role) {
        return role == Role.LECTURER || role == Role.HEAD;
    }

    /** Case-insensitive match of the query against the user's name or email. */
    private static boolean matches(User u, String q) {
        if (q == null || q.isEmpty()) return true;
        String needle = q.toLowerCase();
        return (u.getFullName() != null && u.getFullName().toLowerCase().contains(needle))
                || (u.getEmail() != null && u.getEmail().toLowerCase().contains(needle));
    }
}
