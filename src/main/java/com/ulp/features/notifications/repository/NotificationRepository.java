package com.ulp.features.notifications.repository;

import com.ulp.features.notifications.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Notification}.
 *
 * <p>All queries are owner-scoped ({@code user_id = :userId}) so the service
 * never reads or writes another user's rows. The index {@code idx_noti_user_read}
 * on {@code (user_id, is_read, created_at)} covers both the list and count queries.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Returns a page of the user's notifications, newest first.
     *
     * @param userId   the recipient's user id
     * @param pageable page request (order is enforced by the query)
     * @return a page of notifications
     */
    @Query("SELECT n FROM Notification n WHERE n.userId = :userId ORDER BY n.createdAt DESC")
    Page<Notification> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId,
                                                        Pageable pageable);

    /**
     * Returns the caller's total unread notification count for the header badge.
     *
     * @param userId the recipient's user id
     * @return number of unread notifications
     */
    long countByUserIdAndIsReadFalse(Long userId);

    /**
     * Owner-scoped lookup by id. Returns empty when the notification does not
     * exist or belongs to a different user — supports the no-leak mark-read contract.
     *
     * @param id     the notification id
     * @param userId the caller's user id
     * @return the notification if it exists and belongs to the caller
     */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);
}
