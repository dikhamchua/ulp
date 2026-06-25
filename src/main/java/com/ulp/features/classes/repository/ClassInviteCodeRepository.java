package com.ulp.features.classes.repository;

import com.ulp.entities.ClassInviteCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ClassInviteCode}.
 *
 * <p>Includes a pessimistic-lock variant of the token lookup
 * ({@link #findByCodeForUpdate}) used by {@code JoinClassService}
 * to serialize concurrent join attempts on the same token. The
 * lock is held until the surrounding transaction commits, so the
 * {@code use_count++} update and the {@code enrollments} write
 * happen with the row exclusively reserved.
 */
public interface ClassInviteCodeRepository extends JpaRepository<ClassInviteCode, Long> {

    /**
     * Returns the active row for the given (classId, type) pair, if
     * any. Used to render the Members tab and resolve a class's
     * "currently visible" invite values.
     */
    Optional<ClassInviteCode> findByClassIdAndTypeAndActiveTrue(Long classId, String type);

    /**
     * Returns all rows (active + disabled) for a given class — used
     * by the backfill runner and tests to audit history.
     */
    List<ClassInviteCode> findAllByClassIdOrderByIdAsc(Long classId);

    /**
     * Returns the unique row whose token value matches {@code code}
     * and whose {@code is_active} flag is true. Tokens are case-
     * sensitive at this layer; CODE callers MUST upper-case the
     * input before calling.
     */
    Optional<ClassInviteCode> findByCodeAndActiveTrue(String code);

    /**
     * Returns the row whose token value matches {@code code}
     * regardless of {@code is_active}. Used by the join pipeline to
     * differentiate "unknown token" from "disabled token" without
     * scanning the whole table.
     */
    Optional<ClassInviteCode> findByCode(String code);

    /**
     * Pessimistic-locked variant of the active-token lookup.
     * Acquires {@code SELECT ... FOR UPDATE} on the matched row so
     * concurrent transactions block until commit. Tokens are case-
     * sensitive; callers MUST upper-case CODE input first.
     *
     * <p>Use ONLY inside an active transaction — outside of one
     * Spring throws {@code TransactionRequiredException}.
     *
     * @param code the token value
     * @return the locked active token row, if any
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM ClassInviteCode c WHERE c.code = :code AND c.active = true")
    Optional<ClassInviteCode> findByCodeForUpdate(@Param("code") String code);
}
