package com.ulp.features.ai.client;

import com.ulp.entities.AiProvider;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
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
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final String MSG_NOT_CONFIGURED =
            "Chưa cấu hình AI provider nào đang bật. Vào Cài đặt hệ thống → AI để thêm provider.";
    private static final String MSG_ALL_FAILED_PREFIX =
            "Tất cả AI provider đều thất bại: ";

    private final AiProviderRepository repository;
    private final RestClient restClient;

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
    public AiClient(AiProviderRepository repository, RestClient.Builder builder) {
        this(repository, builder, true);
    }

    private AiClient(AiProviderRepository repository, RestClient.Builder builder, boolean applyTimeouts) {
        this.repository = repository;
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
     * @param builder a builder whose request factory is already configured by the caller
     * @return a client wired to that transport
     */
    public static AiClient withPreconfiguredTransport(AiProviderRepository repository,
                                                      RestClient.Builder builder) {
        return new AiClient(repository, builder, false);
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
        List<AiProvider> providers = repository.findEnabledOrdered();
        if (providers.isEmpty()) {
            throw new AiClientException(MSG_NOT_CONFIGURED);
        }

        List<String> failures = new ArrayList<>();
        for (AiProvider provider : providers) {
            try {
                return callProvider(provider, userMessage, maxTokens);
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
        return callProvider(provider, userMessage, maxTokens);
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Performs one chat completion call against a single provider.
     *
     * @throws PermanentProviderException when the provider rejects the request in a way
     *                                    that another provider could not resolve
     */
    private String callProvider(AiProvider provider, String userMessage, int maxTokens) {
        Map<String, Object> payload = Map.of(
                "model", provider.getModel(),
                "max_tokens", maxTokens,
                "messages", List.of(Map.of("role", "user", "content", userMessage))
        );

        Map<?, ?> body = restClient.post()
                .uri(normalizeBaseUrl(provider.getBaseUrl()) + CHAT_COMPLETIONS_PATH)
                .header("Authorization", "Bearer " + provider.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.isError()) {
                        throw classify(status, readErrorBody(response.getBody()));
                    }
                    return response.bodyTo(Map.class);
                });

        return extractContent(body);
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
    @SuppressWarnings("unchecked")
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
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            throw new TransientProviderException("Phản hồi thiếu trường 'message'");
        }
        Object content = msg.get("content");
        return content == null ? "" : content.toString();
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
