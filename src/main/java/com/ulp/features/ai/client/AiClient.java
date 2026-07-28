package com.ulp.features.ai.client;

import com.ulp.entities.AiProvider;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
import com.ulp.features.ai.log.AiRequestLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sends chat completion requests to the admin-configured AI providers, falling back
 * down the list when a provider fails in a way another provider could plausibly fix.
 *
 * <p><b>Fallback policy.</b> Providers are tried in {@code display_order} ascending.
 * A failure is either transient or permanent:
 * <ul>
 *   <li><b>Transient</b> — network error, timeout, any 5xx, or 429 rate limit. The
 *       failure is recorded and the next enabled provider is tried.</li>
 *   <li><b>Permanent</b> — 400, 401 or 403. The chain stops immediately and an
 *       {@link AiClientException} is thrown. Trying another provider cannot fix a
 *       malformed request, and for a rejected credential it would only waste quota on
 *       endpoints that will fail the same way.</li>
 * </ul>
 * Any other status is treated as permanent as well, because an unrecognised rejection
 * is more likely a request defect than a provider outage.
 *
 * <p>Disabled providers are excluded by the repository query and are therefore never
 * contacted. When no enabled provider exists at all, the call fails fast with a message
 * telling the admin that AI is not configured.
 *
 * <p>The provider list is read on every call — it is intentionally not cached, so a
 * settings change takes effect on the very next request.
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /**
     * Reasoning models need far longer to answer than the connect handshake does.
     * A trivial one-turn prompt against grok-4.5 measured 20-22s, and the budget scales
     * with {@code max_tokens} (roughly 400 per generated question), so a 30s ceiling
     * aborted calls that were still progressing normally.
     */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final String MSG_NOT_CONFIGURED =
            "Chưa cấu hình AI provider nào đang bật. Vào Cài đặt hệ thống → AI để thêm provider.";
    private static final String MSG_ALL_FAILED_PREFIX =
            "Tất cả AI provider đều thất bại: ";

    private final AiProviderRepository repository;
    private final RestClient restClient;
    private final AiRequestLogger requestLogger;

    /**
     * Builds the client on top of Spring's auto-configured {@link RestClient.Builder}.
     *
     * <p>Spring Boot declares that builder as a {@code prototype}-scoped bean
     * (see {@code RestClientAutoConfiguration#restClientBuilder}), so every injection
     * point already receives its own cloned instance. Calling {@code clone()} here would
     * therefore be redundant — mutating this builder cannot leak into another consumer.
     *
     * <p>The connect/read timeouts are applied through
     * {@link #applyDefaultTimeouts(RestClient.Builder)}, which a test may skip by handing
     * in a builder that already carries a request factory.
     */
    @Autowired
    public AiClient(AiProviderRepository repository, RestClient.Builder builder,
                    AiRequestLogger requestLogger) {
        this(repository, builder, requestLogger, true);
    }

    private AiClient(AiProviderRepository repository, RestClient.Builder builder,
                     AiRequestLogger requestLogger, boolean applyTimeouts) {
        this.repository = repository;
        this.requestLogger = requestLogger;
        this.restClient = (applyTimeouts ? applyDefaultTimeouts(builder) : builder).build();
    }

    /**
     * Builds a client that uses the given builder's request factory as-is.
     *
     * <p>Test-only seam. {@code MockRestServiceServer.bindTo(builder)} installs its mock
     * request factory on the builder, and {@code RestClient.Builder#build()} snapshots that
     * factory — so a client that unconditionally re-set its own factory would silently
     * discard the mock and hit the network instead. This entry point skips the timeout
     * factory so the stubbed transport survives; production code must use the public
     * constructor, which always applies the timeouts.
     *
     * @param builder       a builder whose request factory is already configured by the caller
     * @param requestLogger the logger that records each provider attempt
     * @return a client wired to that transport
     */
    public static AiClient withPreconfiguredTransport(AiProviderRepository repository,
                                                      RestClient.Builder builder,
                                                      AiRequestLogger requestLogger) {
        return new AiClient(repository, builder, requestLogger, false);
    }

    /** Installs the production connect/read timeouts on the given builder. */
    private static RestClient.Builder applyDefaultTimeouts(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return builder.requestFactory(factory);
    }

    /**
     * Sends a single user message through the fallback chain and returns the assistant's
     * reply text.
     *
     * @param userMessage the message to send as the sole {@code user} turn
     * @param maxTokens   upper bound on the generated response length
     * @return the assistant reply from the first provider that answered successfully
     * @throws AiClientException when no provider is enabled, a provider rejects the
     *                           request permanently, or every provider failed
     */
    public String chat(String userMessage, int maxTokens) {
        return chat(userMessage, maxTokens, null);
    }

    /**
     * Same as {@link #chat(String, int)} but attributes the logged attempts to a user.
     *
     * @param userMessage the message to send as the sole {@code user} turn
     * @param maxTokens   upper bound on the generated response length
     * @param userId      the acting user recorded on each log row, or {@code null}
     * @return the assistant reply from the first provider that answered successfully
     */
    public String chat(String userMessage, int maxTokens, Long userId) {
        return chat(null, userMessage, maxTokens, userId, AiRequestLogger.SOURCE_CHAT);
    }

    /**
     * Sends a system+user pair through the fallback chain, tagging the logged attempts
     * with what triggered them.
     *
     * <p>Feature entry point for callers that steer the model with a stored system prompt
     * (see {@code ai_system_prompts}). A blank or {@code null} system prompt degrades to a
     * single {@code user} turn, so this method behaves identically to
     * {@link #chat(String, int, Long)} when no instruction block is supplied.
     *
     * @param systemPrompt the instruction block sent as the {@code system} turn, or
     *                     {@code null}/blank to omit it entirely
     * @param userMessage  the message sent as the {@code user} turn
     * @param maxTokens    upper bound on the generated response length
     * @param userId       the acting user recorded on each log row, or {@code null}
     * @param source       what triggered the call, e.g. {@code QUESTION_GEN}
     * @return the assistant reply from the first provider that answered successfully
     * @throws AiClientException when no provider is enabled, a provider rejects the
     *                           request permanently, or every provider failed
     */
    public String chat(String systemPrompt, String userMessage, int maxTokens, Long userId,
                       String source) {
        List<AiProvider> providers = repository.findEnabledOrdered();
        if (providers.isEmpty()) {
            throw new AiClientException(MSG_NOT_CONFIGURED);
        }

        List<String> failures = new ArrayList<>();
        for (AiProvider provider : providers) {
            try {
                return callProvider(provider, buildMessages(systemPrompt, userMessage),
                        maxTokens, source, userId).content();
            } catch (PermanentProviderException e) {
                // A rejected credential or a malformed request is not fixed by another
                // endpoint — stop the chain instead of burning quota on the rest.
                throw new AiClientException(provider.getName() + ": " + e.getMessage(), e);
            } catch (RuntimeException e) {
                log.warn("AI provider '{}' failed transiently: {}", provider.getName(), e.toString());
                failures.add(provider.getName() + ": " + describe(e));
            }
        }
        throw new AiClientException(MSG_ALL_FAILED_PREFIX + String.join("; ", failures));
    }

    /**
     * Sends one chat message to a single named provider, bypassing the fallback chain.
     *
     * <p>Used by the admin "test connection" button, which must report the failure of
     * exactly the provider under test rather than silently succeeding through a different
     * one. Failures propagate as runtime exceptions carrying a human-readable message.
     *
     * @param provider    the provider to contact
     * @param userMessage the message to send
     * @param maxTokens   upper bound on the generated response length
     * @return the reply text when the call succeeded
     */
    public String callOne(AiProvider provider, String userMessage, int maxTokens) {
        return callOne(provider, userMessage, maxTokens, AiRequestLogger.SOURCE_CHAT, null);
    }

    /**
     * Same as {@link #callOne(AiProvider, String, int)} but tags the logged attempt with
     * what triggered it and who was acting.
     *
     * @param provider    the provider to contact
     * @param userMessage the message to send
     * @param maxTokens   upper bound on the generated response length
     * @param source      what triggered the call, e.g. {@code TEST_CONNECTION}
     * @param userId      the acting user recorded on the log row, or {@code null}
     * @return the reply text when the call succeeded
     */
    public String callOne(AiProvider provider, String userMessage, int maxTokens,
                          String source, Long userId) {
        return callProvider(provider, buildMessages(null, userMessage), maxTokens,
                source, userId).content();
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Builds the OpenAI-compatible {@code messages} array for one turn.
     *
     * <p>The {@code system} entry is emitted only when an instruction block was supplied.
     * Sending an empty system turn is not equivalent to sending none — some providers
     * reject a blank content field, and others count it as a real instruction that
     * silently dilutes the user message.
     *
     * @param systemPrompt the instruction block, or {@code null}/blank to omit
     * @param userMessage  the user turn content
     * @return one or two message maps, system first
     */
    private static List<Map<String, Object>> buildMessages(String systemPrompt, String userMessage) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return List.of(Map.of("role", "user", "content", userMessage));
        }
        return List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage));
    }

    /**
     * Performs one chat completion call against a single provider and records the
     * attempt in {@code ai_request_logs}, whether it succeeded or failed.
     *
     * <p>Exactly one log row is written per invocation. Failures are logged and then
     * rethrown unchanged, so the fallback policy in
     * {@link #chat(String, String, int, Long, String)} is unaffected by the logging.
     *
     * @throws PermanentProviderException when the provider rejects the request in a way
     *                                    that another provider could not resolve
     */
    private AiResult callProvider(AiProvider provider, List<Map<String, Object>> messages,
                                  int maxTokens, String source, Long userId) {
        // "stream" defaults to false per the OpenAI spec, but some self-hosted gateways only
        // honour it when sent explicitly and otherwise label a plain JSON body as
        // text/event-stream — which leaves RestClient without a matching message converter.
        // Sending the flag and an explicit Accept header pins the response to JSON.
        Map<String, Object> payload = Map.of(
                "model", provider.getModel(),
                "max_tokens", maxTokens,
                "stream", false,
                "messages", messages
        );

        long startedAt = System.nanoTime();
        try {
            Map<?, ?> body = restClient.post()
                    .uri(normalizeBaseUrl(provider.getBaseUrl()) + CHAT_COMPLETIONS_PATH)
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            throw classify(status, readErrorBody(response.getBody()));
                        }
                        return response.bodyTo(Map.class);
                    });

            AiResult result = new AiResult(extractContent(body), extractUsage(body));
            requestLogger.logSuccess(provider, result.usage(), elapsedMs(startedAt), source, userId);
            return result;
        } catch (RuntimeException e) {
            requestLogger.logFailure(provider, describe(e), elapsedMs(startedAt), source, userId);
            throw e;
        }
    }

    /** Wall-clock duration since the given {@code System.nanoTime()} reading. */
    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * Maps an error status onto the transient/permanent split that drives fallback.
     * 5xx and 429 are transient; everything else — including 400/401/403 — is permanent.
     */
    private static RuntimeException classify(HttpStatusCode status, String body) {
        String detail = "HTTP " + status.value() + (body.isBlank() ? "" : " — " + body);
        if (status.is5xxServerError() || status.value() == HTTP_TOO_MANY_REQUESTS) {
            return new TransientProviderException(detail);
        }
        return new PermanentProviderException(detail);
    }

    /** Reads at most 300 characters of an error body so a huge HTML page cannot flood the toast. */
    private static String readErrorBody(java.io.InputStream in) {
        try (in) {
            String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return raw.length() > 300 ? raw.substring(0, 300) + "…" : raw;
        } catch (Exception e) {
            return "";
        }
    }

    /** Pulls {@code choices[0].message.content} out of an OpenAI-compatible response. */
    private static String extractContent(Map<?, ?> body) {
        if (body == null) {
            throw new TransientProviderException("Phản hồi rỗng");
        }
        Object choices = body.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new TransientProviderException("Phản hồi không có trường 'choices'");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            throw new TransientProviderException("Phản hồi có định dạng không mong đợi");
        }
        failOnEmbeddedError(choice);
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            throw new TransientProviderException("Phản hồi thiếu trường 'message'");
        }
        Object content = msg.get("content");
        return content == null ? "" : content.toString();
    }

    /**
     * Rejects a choice that carries a provider failure inside an HTTP 200 response.
     *
     * <p>OpenAI-compatible gateways report an upstream failure two ways. A transport-level
     * rejection arrives as an error status and is handled by {@link #classify}, but a
     * failure the gateway itself absorbed comes back as {@code 200 OK} with
     * {@code finish_reason: "error"} and an {@code error} object on the choice. Without this
     * check the caller would receive an empty reply, the attempt would be logged as a
     * success, and the fallback chain would never advance to the next provider.
     *
     * <p>The nested {@code error.code} drives the same transient/permanent split as a real
     * status line, so a 429 or 5xx reported this way still lets the chain fall through.
     *
     * @param choice the first element of the {@code choices} array
     * @throws TransientProviderException when the embedded code is 429 or 5xx
     * @throws PermanentProviderException for any other embedded error
     */
    private static void failOnEmbeddedError(Map<?, ?> choice) {
        Object error = choice.get("error");
        boolean finishedWithError = "error".equals(choice.get("finish_reason"));
        if (!finishedWithError && !(error instanceof Map<?, ?>)) {
            return;
        }

        Map<?, ?> details = (error instanceof Map<?, ?> map) ? map : Map.of();
        Integer code = toInteger(details.get("code"));
        Object message = details.get("message");
        String detail = "Provider báo lỗi"
                + (code == null ? "" : " (code " + code + ")")
                + (message == null ? "" : ": " + message);

        if (code != null && (code == HTTP_TOO_MANY_REQUESTS || (code >= 500 && code <= 599))) {
            throw new TransientProviderException(detail);
        }
        throw new PermanentProviderException(detail);
    }

    /**
     * Pulls the OpenAI-compatible {@code usage} object out of a response.
     *
     * <p>Absent or malformed usage yields a record of three {@code null}s rather than
     * zeros — the call did consume tokens, we simply were not told how many, and
     * recording zero would misreport it as free.
     *
     * @param body the parsed response body
     * @return the token counts, each member possibly {@code null}
     */
    private static AiRequestLogger.TokenUsage extractUsage(Map<?, ?> body) {
        Object usage = body == null ? null : body.get("usage");
        if (!(usage instanceof Map<?, ?> map)) {
            return new AiRequestLogger.TokenUsage(null, null, null);
        }
        return new AiRequestLogger.TokenUsage(
                toInteger(map.get("prompt_tokens")),
                toInteger(map.get("completion_tokens")),
                toInteger(map.get("total_tokens")));
    }

    /**
     * Coerces a JSON number to {@link Integer}, tolerating the {@code Double} that a
     * lenient parser may hand back for an integral value.
     *
     * @return the value as an int, or {@code null} when absent or not numeric
     */
    private static Integer toInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    /**
     * Strips a single trailing slash so {@code baseUrl + path} never yields a double slash.
     *
     * <p>Public because the settings service normalizes the same way before persisting,
     * so a URL saved with a trailing slash and one saved without end up identical in the
     * database rather than differing only at call time.
     *
     * @param baseUrl the configured endpoint, possibly {@code null} or padded with spaces
     * @return the trimmed URL without a trailing slash; empty string when {@code null}
     */
    public static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Renders a failure for the aggregated "all providers failed" message. */
    private static String describe(RuntimeException e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    /**
     * One successful provider answer: the reply text plus whatever token counts the
     * provider reported.
     *
     * <p>Internal to this class. The public {@code chat} and {@code callOne} entry points
     * still return a bare {@link String}, so adding token capture did not change any
     * caller's contract.
     */
    private record AiResult(String content, AiRequestLogger.TokenUsage usage) {
    }

    /** Failure another provider might succeed at — network, timeout, 5xx, 429. */
    static class TransientProviderException extends RuntimeException {
        TransientProviderException(String message) {
            super(message);
        }
    }

    /** Failure no other provider can fix — 400, 401, 403 and unrecognised rejections. */
    static class PermanentProviderException extends RuntimeException {
        PermanentProviderException(String message) {
            super(message);
        }
    }
}
