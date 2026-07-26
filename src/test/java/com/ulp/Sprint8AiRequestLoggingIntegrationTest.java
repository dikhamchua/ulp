package com.ulp;

import com.ulp.entities.AiProvider;
import com.ulp.entities.AiRequestLog;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
import com.ulp.features.admin.settings.service.AiProviderService;
import com.ulp.features.ai.client.AiClient;
import com.ulp.features.ai.client.AiClientException;
import com.ulp.features.ai.log.AiRequestLogRepository;
import com.ulp.features.ai.log.AiRequestLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * Row-level assertions for {@code ai_request_logs}: what a provider attempt actually
 * writes, and what it deliberately leaves {@code NULL}.
 *
 * <p><b>This class must NOT be {@code @Transactional}, and that is load-bearing.</b>
 * {@code AiRequestLogWriter} commits each row with {@code REQUIRES_NEW}, on a second
 * connection. A row written there is invisible to an outer test transaction reading on
 * its own connection, so the assertions below would see an empty table no matter what
 * the writer did.
 *
 * <p>Running without an enclosing transaction also matches production: the only real AI
 * call site, {@code AiProviderService.test()}, is deliberately not {@code @Transactional}.
 *
 * <p>The cost is that nothing rolls back, so both tables are cleaned explicitly in
 * {@code @AfterEach}.
 *
 * @see Sprint8AiSettingsIntegrationTest for the transactional CRUD and fallback-policy tests
 */
@SpringBootTest
class Sprint8AiRequestLoggingIntegrationTest {

    /** Distinct base URLs so the mock transport can tell the two providers apart. */
    private static final String FIRST_URL = "https://first.example.test/v1";
    private static final String SECOND_URL = "https://second.example.test/v1";

    private static final String REAL_KEY = "sk-logging-test-abcdef123456";

    /** Name that must never be contacted once a permanent failure has halted the chain. */
    private static final String CANARY = "LogCanaryNeverCalled";

    /**
     * Every provider name this class writes rows under.
     *
     * <p>Cleanup is scoped to these rather than {@code deleteAll()}: the suite runs against
     * the developer database, where a blanket delete would destroy an operator's real
     * provider list and log history.
     */
    private static final Set<String> FIXTURE_NAMES = Set.of(
            "LoggedProvider", "NoUsageProvider", "FlakyProvider", "HealthyProvider",
            "BadKeyProvider", CANARY);

    @Autowired
    private AiProviderRepository repository;

    @Autowired
    private AiProviderService service;

    @Autowired
    private AiRequestLogRepository logRepository;

    @Autowired
    private AiRequestLogger requestLogger;

    /**
     * Real providers this test switched off, to be switched back on in {@code @AfterEach}.
     */
    private final List<AiProvider> suspended = new ArrayList<>();

    /** Next display order to hand out; negative so fixtures always sort ahead. */
    private short nextOrder;

    /**
     * Disables any provider already enabled in the database for the duration of a test.
     *
     * <p>{@code AiClient.chat()} walks {@code findEnabledOrdered()} across the whole table,
     * so a real provider configured by the developer would be contacted first and the mock
     * transport would reject the unexpected host. The sibling test class avoids this by
     * emptying the table, but that only works there because its transaction rolls back —
     * doing the same here would permanently delete an operator's configuration.
     * Suspending and restoring is the non-destructive equivalent.
     */
    @BeforeEach
    void isolateFixtures() {
        removeFixtures();

        nextOrder = -100;
        for (AiProvider provider : repository.findEnabledOrdered()) {
            provider.setEnabled(false);
            repository.save(provider);
            suspended.add(provider);
        }
    }

    /**
     * Removes this class's rows and re-enables the providers it suspended.
     *
     * <p>Mandatory, and mandatory in {@code @AfterEach} specifically: nothing here is
     * rolled back, so cleanup written at the end of a test method would be skipped by the
     * first failing assertion — leaking committed rows and, worse, leaving the developer's
     * real providers switched off.
     */
    @AfterEach
    void removeFixtures() {
        List<AiRequestLog> logs = logRepository.findAll().stream()
                .filter(row -> FIXTURE_NAMES.contains(row.getProviderName()))
                .toList();
        if (!logs.isEmpty()) {
            logRepository.deleteAllInBatch(logs);
        }

        List<AiProvider> providers = repository.findAll().stream()
                .filter(p -> FIXTURE_NAMES.contains(p.getName()))
                .toList();
        if (!providers.isEmpty()) {
            repository.deleteAllInBatch(providers);
        }

        for (AiProvider provider : suspended) {
            provider.setEnabled(true);
            repository.save(provider);
        }
        suspended.clear();
    }

    /**
     * A successful call records the token counts the provider reported.
     *
     * <p>The three numbers are deliberately different so a wiring mistake that swapped
     * prompt for completion, or copied {@code total_tokens} into all three columns, fails
     * here instead of passing by coincidence.
     */
    @Test
    void successful_call_logs_one_row_with_the_reported_token_counts() {
        persist("LoggedProvider", FIRST_URL);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        AiClient client = AiClient.withPreconfiguredTransport(repository, builder, requestLogger);

        mockServer.expect(ExpectedCount.once(), requestTo(FIRST_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"pong\"}}],"
                                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,"
                                + "\"total_tokens\":18}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.chat("hello", 5)).isEqualTo("pong");
        mockServer.verify();

        assertThat(fixtureLogs()).singleElement().satisfies(row -> {
            assertThat(row.getProviderName()).isEqualTo("LoggedProvider");
            assertThat(row.getModel()).isEqualTo("test-model");
            assertThat(row.getStatus()).isEqualTo(AiRequestLog.STATUS_SUCCESS);
            assertThat(row.getSource()).isEqualTo(AiRequestLogger.SOURCE_CHAT);
            assertThat(row.getPromptTokens()).isEqualTo(11);
            assertThat(row.getCompletionTokens()).isEqualTo(7);
            assertThat(row.getTotalTokens()).isEqualTo(18);
            assertThat(row.getErrorMessage()).isNull();
            assertThat(row.getDurationMs()).isNotNull();
        });
    }

    /**
     * A provider that omits {@code usage} leaves the token columns NULL, never 0.
     *
     * <p>The two are not interchangeable. NULL reads as "the provider did not tell us";
     * 0 asserts the call consumed nothing — a claim the response never made, and one that
     * would silently understate every total computed over these rows.
     */
    @Test
    void response_without_a_usage_object_leaves_token_columns_null() {
        persist("NoUsageProvider", FIRST_URL);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        AiClient client = AiClient.withPreconfiguredTransport(repository, builder, requestLogger);

        mockServer.expect(ExpectedCount.once(), requestTo(FIRST_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.chat("hello", 5)).isEqualTo("pong");
        mockServer.verify();

        assertThat(fixtureLogs()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AiRequestLog.STATUS_SUCCESS);
            assertThat(row.getPromptTokens()).isNull();
            assertThat(row.getCompletionTokens()).isNull();
            assertThat(row.getTotalTokens()).isNull();
        });
    }

    /**
     * A fallback writes one row per attempt, not one row per logical request.
     *
     * <p>This is the whole point of logging at the attempt level: an operator looking at
     * a slow or expensive day needs to see that the first provider was tried and failed,
     * not just that something eventually answered.
     */
    @Test
    void transient_failure_then_success_writes_one_row_per_attempt_in_order() {
        persist("FlakyProvider", FIRST_URL);
        persist("HealthyProvider", SECOND_URL);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        AiClient client = AiClient.withPreconfiguredTransport(repository, builder, requestLogger);

        mockServer.expect(ExpectedCount.once(), requestTo(FIRST_URL + "/chat/completions"))
                .andRespond(withServerError().body("upstream down"));
        mockServer.expect(ExpectedCount.once(), requestTo(SECOND_URL + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.chat("hello", 5)).isEqualTo("pong");
        mockServer.verify();

        List<AiRequestLog> logs = fixtureLogs();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getProviderName()).isEqualTo("FlakyProvider");
        assertThat(logs.get(0).getStatus()).isEqualTo(AiRequestLog.STATUS_FAILED);
        assertThat(logs.get(0).getErrorMessage()).isNotBlank();
        assertThat(logs.get(1).getProviderName()).isEqualTo("HealthyProvider");
        assertThat(logs.get(1).getStatus()).isEqualTo(AiRequestLog.STATUS_SUCCESS);
    }

    /**
     * A permanent failure logs the one attempt made and nothing for the halted remainder.
     *
     * <p>Guards against the log implying providers were contacted when they were not — the
     * canary never receives a request, so it must never appear as a row.
     */
    @Test
    void permanent_401_writes_a_single_failed_row_and_stops_the_chain() {
        persist("BadKeyProvider", FIRST_URL);
        persist(CANARY, SECOND_URL);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        AiClient client = AiClient.withPreconfiguredTransport(repository, builder, requestLogger);

        mockServer.expect(ExpectedCount.once(), requestTo(FIRST_URL + "/chat/completions"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> client.chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("BadKeyProvider")
                .hasMessageNotContaining(CANARY);

        mockServer.verify();

        assertThat(fixtureLogs()).singleElement().satisfies(row -> {
            assertThat(row.getProviderName()).isEqualTo("BadKeyProvider");
            assertThat(row.getStatus()).isEqualTo(AiRequestLog.STATUS_FAILED);
            assertThat(row.getErrorMessage()).isNotBlank();
            // No usage is reported on a failure, so the token columns stay unknown.
            assertThat(row.getTotalTokens()).isNull();
        });
    }

    /** The admin "test connection" button is a real call, so it logs under its own source. */
    @Test
    void test_connection_logs_under_the_test_connection_source() {
        AiProvider provider = persist("BadKeyProvider", FIRST_URL);

        var result = service.test(provider.getId(), null);
        assertThat(result.ok()).isFalse();

        assertThat(fixtureLogs()).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(AiRequestLog.STATUS_FAILED);
            assertThat(row.getSource()).isEqualTo(AiRequestLogger.SOURCE_TEST_CONNECTION);
        });
    }

    // ─────────────────────────────────────────────────────────────────

    /**
     * Reads back this class's log rows, oldest first.
     *
     * <p>Sorted by id, not {@code created_at}: the column is second-resolution in MySQL,
     * so both rows of a fast fallback land in the same second and a timestamp sort would
     * be non-deterministic — the ordering assertion would then pass or fail at random.
     */
    private List<AiRequestLog> fixtureLogs() {
        return logRepository.findAll().stream()
                .filter(row -> FIXTURE_NAMES.contains(row.getProviderName()))
                .sorted(Comparator.comparing(AiRequestLog::getId))
                .toList();
    }

    /**
     * Saves an enabled fixture provider, ordered ahead of anything already in the table.
     *
     * <p>Display orders count up from a negative base rather than from
     * {@code findMaxDisplayOrder() + 1}: fixtures must be first in the fallback chain, and
     * appending would put them last.
     */
    private AiProvider persist(String name, String baseUrl) {
        AiProvider provider = new AiProvider(name, baseUrl, "test-model", REAL_KEY);
        provider.setEnabled(true);
        provider.setDisplayOrder(nextOrder++);
        return repository.save(provider);
    }
}
