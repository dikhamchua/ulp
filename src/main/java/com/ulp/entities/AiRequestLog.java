package com.ulp.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entity mapping the {@code ai_request_logs} table (see {@code V38__ai_request_logs.sql}).
 *
 * <p>One row per provider <i>attempt</i>. A fallback chain that fails on the first
 * provider and succeeds on the second writes two rows, so the log shows which
 * endpoint actually served the request.
 *
 * <p>Only metadata and token counts are stored — never the prompt or the response
 * text. {@link #providerName} is a snapshot: {@code ai_providers} rows are hard
 * deleted, so {@link #providerId} may become {@code null} while the name survives.
 *
 * <p>The three token fields are {@link Integer}, not {@code int}: a provider that
 * omits the {@code usage} object leaves them {@code null}, meaning "unknown".
 * Defaulting them to zero would assert the call consumed nothing.
 */
@Entity
@Table(name = "ai_request_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRequestLog {

    /** Provider answered with a usable completion. */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** Provider rejected the call, timed out, or returned an unusable body. */
    public static final String STATUS_FAILED = "FAILED";

    /** Longest error text the {@code error_message} column accepts. */
    public static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_name", nullable = false, length = 100)
    private String providerName;

    @Column(name = "model", nullable = false, length = 150)
    private String model;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Setter
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Setter
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Setter
    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Setter
    @Column(name = "duration_ms")
    private Integer durationMs;

    @Setter
    @Column(name = "error_message", length = MAX_ERROR_LENGTH)
    private String errorMessage;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Setter
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Creates a log row with the fields every attempt always has.
     *
     * <p>Tokens, duration, error text and the actor are optional and set through
     * the setters, because which of them apply depends on how the attempt ended.
     *
     * @param providerName snapshot of the provider name at call time
     * @param model        model identifier that was sent in the payload
     * @param status       {@link #STATUS_SUCCESS} or {@link #STATUS_FAILED}
     * @param source       what triggered the call, e.g. {@code TEST_CONNECTION}
     */
    public AiRequestLog(String providerName, String model, String status, String source) {
        this.providerName = providerName;
        this.model = model;
        this.status = status;
        this.source = source;
    }
}
