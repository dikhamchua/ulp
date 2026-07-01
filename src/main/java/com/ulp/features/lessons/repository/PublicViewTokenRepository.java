package com.ulp.features.lessons.repository;

import com.ulp.entities.PublicViewToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for {@link PublicViewToken} — short-lived anonymous
 * access tokens used by MS Office Online Viewer.
 */
public interface PublicViewTokenRepository extends JpaRepository<PublicViewToken, Long> {

    Optional<PublicViewToken> findByToken(String token);

    @Query("SELECT t FROM PublicViewToken t WHERE t.attachmentId = :attachmentId AND t.expiresAt > :now ORDER BY t.expiresAt DESC")
    Optional<PublicViewToken> findLiveTokenByAttachmentId(@Param("attachmentId") Long attachmentId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PublicViewToken t WHERE t.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
