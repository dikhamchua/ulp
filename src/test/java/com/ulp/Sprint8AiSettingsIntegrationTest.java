package com.ulp;

import com.ulp.entities.AiProvider;
import com.ulp.features.admin.settings.dto.AiSettingsDtos.AiProviderForm;
import com.ulp.features.admin.settings.repository.AiProviderRepository;
import com.ulp.features.admin.settings.service.AiProviderService;
import com.ulp.features.ai.client.AiClient;
import com.ulp.features.ai.client.AiClientException;
import com.ulp.features.ai.log.AiRequestLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.ulp.common.IConstant.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Sprint 8 integration test for the AI provider settings screen.
 *
 * <p>Covers CRUD through MockMvc (so the {@code system.ai} permission gate, the flash
 * contract the template reads, and the redirect targets are all exercised as a browser
 * would hit them), the permission gate for non-admin roles, the on-demand reveal-key
 * endpoint, and the {@code AiClient} fallback chain.
 *
 * <p>Most fallback assertions point providers at an unroutable address rather than mocking
 * the transport: the classification under test is exactly what happens to a real connection
 * failure, and a mock would assert the stub instead of the policy.
 *
 * <p>The two HTTP-status tests are the exception. A specific status code cannot be produced
 * by an unroutable host, so they bind {@code MockRestServiceServer} to the builder the
 * {@code AiClient} under test is constructed with. That is the only way to prove the
 * permanent/transient split — in particular that a 401 stops the chain before the next
 * provider is contacted at all.
 *
 * <p>Every test is {@code @Transactional} and rolled back, so no provider row survives —
 * including the {@code @BeforeEach} that empties the table to isolate each test from
 * whatever providers already exist in the target database.
 *
 * <p>Seed users from {@code V5__seed_test_users.sql}:
 * {@code admin@ulp.edu.vn}, {@code lecturer@ulp.edu.vn}, {@code student@ulp.edu.vn}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Sprint8AiSettingsIntegrationTest {

    /**
     * Reserved TEST-NET-1 address (RFC 5737) — guaranteed not to route anywhere, so a
     * call against it fails on connect rather than reaching a real endpoint.
     */
    private static final String UNREACHABLE_URL = "http://192.0.2.1:9";

    private static final String REAL_KEY = "sk-integration-test-abcdef123456";

    /** Distinct base URLs so the mock transport can tell the two providers apart. */
    private static final String FIRST_URL = "https://first.example.test/v1";
    private static final String SECOND_URL = "https://second.example.test/v1";

    /** Name that must never appear in a failure message once the chain has halted. */
    private static final String CANARY = "CanaryNeverCalled";

    /**
     * Every provider name the logging tests write rows under.
     *
     * <p>Drives the {@code @AfterEach} cleanup. Scoped on purpose: a blanket
     * {@code deleteAll()} would wipe an operator's real log history when the suite runs
     * against a local database, and log rows escape the test rollback.
     *
     * <p>Must list every provider name any test here drives an AI call under, including
     * the fallback-chain fixtures — those attempts write rows too, and a name missing
     * from this set leaks a committed row into the developer database.
     */
    private static final Set<String> LOG_FIXTURE_PROVIDERS = Set.of(
            "LoggedProvider", "NoUsageProvider", "FlakyProvider", "HealthyProvider",
            "BadKeyProvider", CANARY, "Unreachable", "Alpha", "Beta", "Tried");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiProviderRepository repository;

    @Autowired
    private AiProviderService service;

    @Autowired
    private AiClient aiClient;

    @Autowired
    private AiRequestLogger requestLogger;

    /** Used to read committed rows outside this class's rolled-back test transaction. */
    @Autowired
    private DataSource dataSource;

    /**
     * Clears {@code ai_providers} so every test starts from a known-empty table.
     *
     * <p>These tests assert on absolute state — {@code repository.count()},
     * {@code singleElement()}, the exact display-order a new row is appended at, and the
     * whole fallback chain — so any row an operator created through the UI would break
     * them. The delete runs inside the test transaction and is rolled back with it, so
     * real rows are restored when the test ends.
     */
    @BeforeEach
    void clearProviders() {
        repository.deleteAll();
        repository.flush();
    }

    /**
     * Deletes any {@code ai_request_logs} row a test in this class committed.
     *
     * <p>The AI calls here still reach {@link AiRequestLogger}, and its write runs with
     * {@code REQUIRES_NEW} — so those rows commit on their own connection and survive the
     * rollback of the surrounding test. They are the one thing the class-level
     * {@code @Transactional} does not clean up.
     *
     * <p>Kept in {@code @AfterEach} rather than at the end of each test so it still runs
     * when an assertion fails; that omission is how a previous change leaked committed
     * rows into the developer database. Scoped to the fixture provider names so a real
     * operator's log history is never destroyed by a local test run.
     *
     * <p>Runs on its own JDBC connection, deliberately. Going through
     * {@code AiRequestLogRepository} cannot work here for two compounding reasons: the read joins
     * this test's REPEATABLE READ snapshot and so never sees a row the writer committed
     * afterwards, and the delete would join the test transaction and be discarded by its
     * rollback. Cleanup has to happen on the same terms as the write — its own
     * connection, committed.
     */
    @AfterEach
    void clearCommittedLogs() throws Exception {
        String placeholders = String.join(",",
                Collections.nCopies(LOG_FIXTURE_PROVIDERS.size(), "?"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM ai_request_logs WHERE provider_name IN ("
                             + placeholders + ")")) {
            int index = 1;
            for (String name : LOG_FIXTURE_PROVIDERS) {
                statement.setString(index++, name);
            }
            statement.executeUpdate();
        }
    }

    // ───────── Access control ────────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_can_open_ai_settings() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI))
                .andExpect(model().attributeExists(ATTR_AI_PROVIDERS, ATTR_FORM));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_cannot_open_ai_settings() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithUserDetails("student@ulp.edu.vn")
    void student_cannot_reveal_a_key() throws Exception {
        AiProvider provider = persist("Reveal guard", true);

        mockMvc.perform(get(URL_SETTINGS_AI + "/" + provider.getId() + "/key"))
                .andExpect(status().isForbidden());
    }

    // ───────── Create / update ───────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_persists_provider_and_appends_it_to_the_chain() throws Exception {
        persist("Existing", true);

        mockMvc.perform(post(URL_SETTINGS_AI).with(csrf())
                        .param("name", "OpenAI")
                        .param("baseUrl", "https://api.openai.com/v1/")
                        .param("model", "gpt-4o-mini")
                        .param("apiKey", REAL_KEY)
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(URL_SETTINGS_AI))
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_CREATED));

        AiProvider saved = repository.findByName("OpenAI").orElseThrow();
        assertThat(saved.getApiKey()).isEqualTo(REAL_KEY);
        // Trailing slash is stripped so the same endpoint is stored one way only.
        assertThat(saved.getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        // Appended after the pre-existing provider rather than colliding with it.
        assertThat(saved.getDisplayOrder()).isEqualTo((short) 2);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void create_without_api_key_reports_an_inline_field_error() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI).with(csrf())
                        .param("name", "No key")
                        .param("baseUrl", "https://api.example.com/v1")
                        .param("model", "m")
                        .param("apiKey", "")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI))
                .andExpect(model().attributeHasFieldErrors(ATTR_FORM, "apiKey"));

        assertThat(repository.findByName("No key")).isEmpty();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void duplicate_name_is_an_inline_field_error_not_a_flash() throws Exception {
        persist("Duplicate", true);

        mockMvc.perform(post(URL_SETTINGS_AI).with(csrf())
                        .param("name", "Duplicate")
                        .param("baseUrl", "https://api.example.com/v1")
                        .param("model", "m")
                        .param("apiKey", "sk-another")
                        .param("enabled", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI))
                .andExpect(model().attributeHasFieldErrors(ATTR_FORM, "name"))
                // A field problem must not surface as a toast.
                .andExpect(flash().attributeCount(0));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void update_with_a_blank_key_keeps_the_stored_key() throws Exception {
        AiProvider provider = persist("Keep key", true);

        mockMvc.perform(post(URL_SETTINGS_AI).with(csrf())
                        .param("id", String.valueOf(provider.getId()))
                        .param("name", "Keep key renamed")
                        .param("baseUrl", "https://api.example.com/v2")
                        .param("model", "new-model")
                        .param("apiKey", "")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_UPDATED));

        AiProvider reloaded = repository.findById(provider.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Keep key renamed");
        assertThat(reloaded.getModel()).isEqualTo("new-model");
        assertThat(reloaded.getApiKey()).isEqualTo(REAL_KEY);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void update_with_the_masked_sentinel_keeps_the_stored_key() throws Exception {
        AiProvider provider = persist("Sentinel", true);

        mockMvc.perform(post(URL_SETTINGS_AI).with(csrf())
                        .param("id", String.valueOf(provider.getId()))
                        .param("name", "Sentinel")
                        .param("baseUrl", "https://api.example.com/v1")
                        .param("model", "m")
                        .param("apiKey", "********")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findById(provider.getId()).orElseThrow().getApiKey())
                .isEqualTo(REAL_KEY);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void update_with_a_new_key_replaces_the_stored_key() throws Exception {
        AiProvider provider = persist("Rotate", true);

        mockMvc.perform(post(URL_SETTINGS_AI).with(csrf())
                        .param("id", String.valueOf(provider.getId()))
                        .param("name", "Rotate")
                        .param("baseUrl", "https://api.example.com/v1")
                        .param("model", "m")
                        .param("apiKey", "sk-rotated-value")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.findById(provider.getId()).orElseThrow().getApiKey())
                .isEqualTo("sk-rotated-value");
    }

    // ───────── Key exposure ──────────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void list_page_never_contains_the_real_key() throws Exception {
        persist("Secret holder", true);

        mockMvc.perform(get(URL_SETTINGS_AI))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(REAL_KEY))))
                // Not even a fragment of it may leak into the markup.
                .andExpect(content().string(not(containsString("sk-integration"))));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void reveal_endpoint_returns_the_real_key_as_json() throws Exception {
        AiProvider provider = persist("Reveal me", true);

        mockMvc.perform(get(URL_SETTINGS_AI + "/" + provider.getId() + "/key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.apiKey").value(REAL_KEY));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void reveal_endpoint_reports_a_missing_provider() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI + "/999999/key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(MSG_AI_PROVIDER_NOT_FOUND));
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void list_rows_carry_a_mask_instead_of_the_key() {
        persist("Masked row", true);

        assertThat(service.listRows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.maskedKey()).doesNotContain(REAL_KEY);
                    assertThat(row.index()).isEqualTo(1);
                });
    }

    // ───────── Toggle / delete ───────────────────────────────────────

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void toggle_flips_the_enabled_flag() throws Exception {
        AiProvider provider = persist("Toggle me", true);

        mockMvc.perform(post(URL_SETTINGS_AI + "/" + provider.getId() + "/toggle").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_DISABLED));

        assertThat(repository.findById(provider.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_removes_the_row_and_leaves_survivors_untouched() throws Exception {
        AiProvider first = persist("First", true);
        AiProvider second = persist("Second", true);
        AiProvider third = persist("Third", true);

        mockMvc.perform(post(URL_SETTINGS_AI + "/" + second.getId() + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_SUCCESS, MSG_AI_PROVIDER_DELETED));

        assertThat(repository.findById(second.getId())).isEmpty();
        // Deleting a middle provider must not renumber the rest.
        assertThat(repository.findById(first.getId()).orElseThrow().getDisplayOrder())
                .isEqualTo((short) 1);
        assertThat(repository.findById(third.getId()).orElseThrow().getDisplayOrder())
                .isEqualTo((short) 3);
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void delete_of_a_missing_provider_reports_an_error_flash() throws Exception {
        mockMvc.perform(post(URL_SETTINGS_AI + "/999999/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute(ATTR_FLASH_ERROR, MSG_AI_PROVIDER_NOT_FOUND));
    }

    // ───────── Fallback chain ────────────────────────────────────────

    @Test
    void chat_without_any_provider_says_ai_is_not_configured() {
        assertThat(repository.findEnabledOrdered()).isEmpty();

        assertThatThrownBy(() -> aiClient.chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("Chưa cấu hình AI provider");
    }

    @Test
    void chat_with_only_disabled_providers_says_ai_is_not_configured() {
        persist("Disabled one", false);

        assertThatThrownBy(() -> aiClient.chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("Chưa cấu hình AI provider");
    }

    @Test
    void chat_tries_every_provider_and_reports_each_failure() {
        persist("Alpha", true, UNREACHABLE_URL);
        persist("Beta", true, UNREACHABLE_URL);

        assertThatThrownBy(() -> aiClient.chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                // Requirement: the aggregated message names every provider that failed.
                .hasMessageContaining("Alpha")
                .hasMessageContaining("Beta");
    }

    @Test
    void disabled_providers_are_skipped_by_the_chain() {
        persist("Skipped", false, UNREACHABLE_URL);
        persist("Tried", true, UNREACHABLE_URL);

        assertThatThrownBy(() -> aiClient.chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("Tried")
                .hasMessageNotContaining("Skipped");
    }

    @Test
    void enabled_providers_are_ordered_by_display_order() {
        persist("Third one", true);
        persist("First one", true);

        List<AiProvider> ordered = repository.findEnabledOrdered();
        assertThat(ordered).extracting(AiProvider::getName)
                .containsExactly("Third one", "First one");
        assertThat(ordered.get(0).getDisplayOrder()).isEqualTo((short) 1);
        assertThat(ordered.get(1).getDisplayOrder()).isEqualTo((short) 2);
    }

    /**
     * A rejected credential (401) must STOP the chain dead.
     *
     * <p>This is the most consequential rule in the feature. If 401 were ever reclassified
     * as transient, one broken API key would be fired at every configured provider on every
     * single call — burning paid quota and risking account lockout for repeated auth
     * failures. The canary provider below must never be contacted.
     */
    @Test
    void permanent_401_halts_the_chain_and_never_contacts_the_next_provider() {
        persist("BadKeyProvider", true, FIRST_URL);
        persist(CANARY, true, SECOND_URL);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        AiClient client = AiClient.withPreconfiguredTransport(repository, builder, requestLogger);

        // Exactly one request is programmed. A second call has no expectation left and
        // fails the exchange, and verify() below re-checks the recorded count.
        mockServer.expect(ExpectedCount.once(), requestTo(FIRST_URL + "/chat/completions"))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> client.chat("hello", 5))
                .isInstanceOf(AiClientException.class)
                .hasMessageContaining("BadKeyProvider")
                // The decisive assertion: the second provider was never reached, so it
                // cannot appear in the aggregated failure message.
                .hasMessageNotContaining(CANARY);

        // Fails if a second (unexpected) request was issued to the canary.
        mockServer.verify();
    }

    /**
     * A transient failure (503) must fall through to the next provider.
     *
     * <p>Locks the other half of the policy: an outage at one endpoint is exactly the case
     * fallback exists for, so the chain has to keep going and return the later success.
     */
    @Test
    void transient_503_continues_the_chain_to_the_next_provider() {
        persist("FlakyProvider", true, FIRST_URL);
        persist("HealthyProvider", true, SECOND_URL);

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
    }

    // ───────── Request logging ───────────────────────────────────────
    // Full row-level logging assertions live in Sprint8AiRequestLoggingIntegrationTest,
    // which must run without an enclosing transaction. See that class for why.

    /**
     * A log write still succeeds while the caller's transaction holds the provider row.
     *
     * <p>Regression guard. {@code ai_request_logs.provider_id} used to carry an FK to
     * {@code ai_providers}, which made the insert take a shared lock on the parent row.
     * This class is {@code @Transactional}, so {@link #persist} left that row locked
     * exclusively for the whole test — every log write here then blocked, timed out, and
     * was silently dropped by the swallow-and-warn path. The suite stayed green because
     * nothing asserted the row existed.
     *
     * <p>The count is read on a separate JDBC connection, not through
     * {@code AiRequestLogRepository}. The repository would run inside this test's transaction,
     * whose REPEATABLE READ snapshot was taken before the writer committed and therefore
     * cannot see the new row however well the write went — the assertion would fail even
     * on correct behaviour. A fresh connection takes its own snapshot and sees the
     * committed row, which is exactly what production readers do.
     */
    @Test
    void a_log_row_is_written_even_while_the_test_transaction_holds_the_provider_row()
            throws Exception {
        AiProvider provider = persist("Unreachable", true, UNREACHABLE_URL);

        assertThat(service.test(provider.getId(), null).ok()).isFalse();

        assertThat(committedLogCount("Unreachable")).isEqualTo(1);
    }

    /** Counts committed log rows for one provider, outside the test transaction. */
    private int committedLogCount(String providerName) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ai_request_logs WHERE provider_name = ?")) {
            statement.setString(1, providerName);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @WithUserDetails("admin@ulp.edu.vn")
    void admin_can_open_the_ai_logs_screen() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI_LOGS))
                .andExpect(status().isOk())
                .andExpect(view().name(VIEW_SETTINGS_AI_LOGS))
                .andExpect(model().attributeExists(ATTR_AI_LOGS_PAGE, ATTR_AI_LOGS_FILTER,
                        ATTR_AI_LOGS_TOTALS, ATTR_AI_LOGS_PROVIDERS));
    }

    @Test
    @WithUserDetails("lecturer@ulp.edu.vn")
    void lecturer_cannot_open_the_ai_logs_screen() throws Exception {
        mockMvc.perform(get(URL_SETTINGS_AI_LOGS))
                .andExpect(status().isForbidden());
    }

    @Test
    void base_url_is_normalized_the_same_way_with_or_without_a_trailing_slash() {
        assertThat(AiClient.normalizeBaseUrl("https://api.example.com/v1/"))
                .isEqualTo(AiClient.normalizeBaseUrl("https://api.example.com/v1"))
                .isEqualTo("https://api.example.com/v1");
    }

    // ───────── Service-level state ───────────────────────────────────

    @Test
    void all_disabled_flag_is_only_set_when_rows_exist_but_none_is_enabled() {
        assertThat(service.hasProvidersButAllDisabled()).isFalse();

        persist("Off", false);
        assertThat(service.hasProvidersButAllDisabled()).isTrue();

        persist("On", true);
        assertThat(service.hasProvidersButAllDisabled()).isFalse();
    }

    @Test
    void load_form_masks_the_key_so_an_edit_round_trip_cannot_leak_it() {
        AiProvider provider = persist("Editable", true);

        Optional<AiProviderForm> form = service.loadForm(provider.getId());
        assertThat(form).isPresent();
        assertThat(form.get().apiKey()).isEqualTo("********");
    }

    @Test
    void test_endpoint_reports_a_failure_instead_of_throwing() {
        AiProvider provider = persist("Unreachable", true, UNREACHABLE_URL);

        var result = service.test(provider.getId(), null);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────────

    private AiProvider persist(String name, boolean enabled) {
        return persist(name, enabled, "https://api.example.com/v1");
    }

    /** Saves a provider at the next free display order, mirroring the service's rule. */
    private AiProvider persist(String name, boolean enabled, String baseUrl) {
        AiProvider provider = new AiProvider(name, baseUrl, "test-model", REAL_KEY);
        provider.setEnabled(enabled);
        provider.setDisplayOrder(repository.findMaxDisplayOrder()
                .map(max -> (short) (max + 1))
                .orElse((short) 1));
        return repository.save(provider);
    }
}
