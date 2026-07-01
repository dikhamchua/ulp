package com.ulp.features.lessons.scheduler;

import com.ulp.features.lessons.service.PublicViewTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges expired {@code public_view_tokens} every 30 minutes so the
 * table does not grow unbounded. Tokens are also deleted on access
 * when found expired, but this scheduled sweep handles tokens that
 * are never accessed after expiry.
 */
@Component
public class TokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupScheduler.class);

    private final PublicViewTokenService tokenService;

    public TokenCleanupScheduler(PublicViewTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanupExpiredTokens() {
        int deleted = tokenService.cleanupExpired();
        if (deleted > 0) {
            log.info("Cleaned up {} expired public view tokens", deleted);
        }
    }
}
