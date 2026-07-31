package com.ulp.features.ai.client;

import com.ulp.entities.AiProvider;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
import com.ulp.features.ai.log.AiRequestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link AiClient}'s handling of an HTTP 200 that is not actually a usable
 * answer, plus the happy path that must keep working.
 *
 * <p><b>Why this class exists.</b> A reply cut off at the {@code max_tokens} ceiling comes
 * back as {@code 200 OK} with {@code finish_reason: "length"} and a partial body. That
 * signal was previously ignored, so the fragment was returned as a normal answer, the
 * attempt was recorded as a success, and the fallback chain never advanced — the caller's
 * JSON parser then failed and reported it to the user as invalid AI data. Nothing covered
 * {@code AiClient} at the time, which is exactly why the defect survived.
 *
 * <p>Deliberately not a {@code @SpringBootTest}: the class only needs a provider list and a
 * logger, both of which are mocked, so the whole suite runs without a database or an
 * application context. The transport is stubbed with {@link MockRestServiceServer} through
 * {@link AiClient#withPreconfiguredTransport}, the seam that keeps the mock request factory
 * from being overwritten by the production timeouts.
 */
class AiClientTest {

    /** Distinct base URLs so the mock transport can tell the two providers apart. */
    private static final String FIRST_URL = "https://first.example.test/v1";
    private static final String SECOND_URL = "https://second.example.test/v1";

    private static final String FIRST_ENDPOINT = FIRST_URL + "/chat/completions";
    private static final String SECOND_ENDPOINT = SECOND_URL + "/chat/completions";

    /** Reply the provider intended to send, cut off at the token ceiling. */
    private static final String TRUNCATED_BODY = """
            {"choices":[{"finish_reason":"length",
            "message":{"content":"{\\"cards\\":[{\\"front\\":\\"Sóng dừ"}}],
            "usage":{"prompt_tokens":300,"completion_tokens":2400,"total_tokens":2700}}""";

    private static final String COMPLETE_BODY = """
            {"choices":[{"finish_reason":"stop","message":{"content":"pong"}}],
            "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}""";

    private AiProviderRepository repository;
    private AiRequestLogger requestLogger;
    private RestClient.Builder builder;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        repository = mock(AiProviderRepository.class);
        requestLogger = mock(AiRequestLogger.class);
        builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
    }

    /**
     * A truncated reply is a failure, not an answer.
     *
     * <p>With only one provider configured the chain has nowhere to fall through to, so the
     * transient failure surfaces as the aggregated {@link AiClientException}. The message
     * has to name the token limit rather than blame the model's output, because that is
     * what the user ultimately reads.
     */
    @Test
    void finish_reason_length_is_rejected_instead_of_returned_as_content() {
        givenProviders(provider("OnlyProvider", FIRST_URL));
        expectOnce(FIRST_ENDPOINT, TRUNCATED_BODY);

        assertThatThrownBy(() -> client().chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("OnlyProvider")
                .hasMessageContaining("giới hạn token");

        mockServer.verify();
    }

    /**
     * The truncated attempt is logged as a failure, never as a success.
     *
     * <p>This is the assertion that actually pins the bug. Returning the fragment and
     * writing a SUCCESS row is what made the defect invisible in {@code ai_request_logs}:
     * the operator saw a healthy call that had in fact produced nothing usable. A
     * regression here would restore that blind spot even if the exception above still fired.
     */
    @Test
    void truncated_attempt_is_logged_as_a_failure_and_not_as_a_success() {
        givenProviders(provider("OnlyProvider", FIRST_URL));
        expectOnce(FIRST_ENDPOINT, TRUNCATED_BODY);

        assertThatThrownBy(() -> client().chat("hello", 5))
                .isInstanceOf(AiClientException.class);

        verify(requestLogger).logFailure(any(AiProvider.class), anyString(), anyLong(),
                eq(AiRequestLogger.SOURCE_CHAT), eq(null));
        verify(requestLogger, never()).logSuccess(any(), any(), anyLong(), anyString(), any());
    }

    /**
     * Truncation must let the chain advance, which is why it is transient and not permanent.
     *
     * <p>The cause is a verbose model, not a defective request: providers differ by an order
     * of magnitude in tokens spent on the same task, so the next one may finish within
     * budget. Classifying it as permanent would halt the chain on a failure another provider
     * can fix — this test would then see the second endpoint go uncontacted.
     */
    @Test
    void truncated_first_provider_falls_through_to_the_next_one() {
        givenProviders(provider("VerboseProvider", FIRST_URL),
                provider("ConciseProvider", SECOND_URL));
        expectOnce(FIRST_ENDPOINT, TRUNCATED_BODY);
        expectOnce(SECOND_ENDPOINT, COMPLETE_BODY);

        assertThat(client().chat("hello", 5)).isEqualTo("pong");
        mockServer.verify();

        verify(requestLogger, times(1)).logFailure(any(), anyString(), anyLong(), anyString(), any());
        verify(requestLogger, times(1)).logSuccess(any(), any(), anyLong(), anyString(), any());
    }

    /**
     * A reply that finished on its own is untouched by the new check.
     *
     * <p>Regression guard for the ordinary path: only the exact value {@code "length"} may
     * fail a call, so {@code "stop"} still returns its content and still logs a success.
     */
    @Test
    void finish_reason_stop_still_succeeds() {
        givenProviders(provider("HealthyProvider", FIRST_URL));
        expectOnce(FIRST_ENDPOINT, COMPLETE_BODY);

        assertThat(client().chat("hello", 5)).isEqualTo("pong");
        mockServer.verify();

        verify(requestLogger).logSuccess(any(), any(), anyLong(), anyString(), any());
        verify(requestLogger, never()).logFailure(any(), anyString(), anyLong(), anyString(), any());
    }

    /**
     * An unrecognised {@code finish_reason} keeps the previous behaviour.
     *
     * <p>Gateways report non-standard reasons, and treating anything unknown as truncation
     * would reject perfectly good replies. Only {@code "length"} is a truncation signal.
     */
    @Test
    void unknown_finish_reason_is_not_treated_as_truncation() {
        givenProviders(provider("QuirkyProvider", FIRST_URL));
        expectOnce(FIRST_ENDPOINT,
                "{\"choices\":[{\"finish_reason\":\"eos\",\"message\":{\"content\":\"pong\"}}]}");

        assertThat(client().chat("hello", 5)).isEqualTo("pong");
        mockServer.verify();

        verify(requestLogger, never()).logFailure(any(), anyString(), anyLong(), anyString(), any());
    }

    // ─────────────────────────────────────────────────────────────────

    /** Builds the client under test against the stubbed transport. */
    private AiClient client() {
        return AiClient.withPreconfiguredTransport(repository, builder, requestLogger);
    }

    /** Stubs the fallback chain with the given providers, in order. */
    private void givenProviders(AiProvider... providers) {
        when(repository.findEnabledOrdered()).thenReturn(List.of(providers));
    }

    /** Queues exactly one JSON response for the given endpoint. */
    private void expectOnce(String endpoint, String body) {
        mockServer.expect(ExpectedCount.once(), requestTo(endpoint))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** An enabled provider fixture; never persisted, the repository is mocked. */
    private static AiProvider provider(String name, String baseUrl) {
        AiProvider provider = new AiProvider(name, baseUrl, "test-model", "sk-test-key");
        provider.setEnabled(true);
        return provider;
    }
}
