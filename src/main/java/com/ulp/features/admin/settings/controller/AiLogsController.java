package com.ulp.features.admin.settings.controller;

import com.ulp.entities.AiRequestLog;
import com.ulp.features.admin.settings.dto.AiLogDtos.AiLogFilter;
import com.ulp.features.admin.settings.service.AiLogQueryService;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

import static com.ulp.common.IConstant.*;

/**
 * Admin controller for the AI request log screen.
 *
 * <p>Exposes a single read-only page, {@code GET /admin/settings/ai/logs}, listing one
 * row per AI provider attempt newest-first, narrowed by an optional provider and status
 * filter and summarised by the token totals for that same filter.
 *
 * <p>There is no write endpoint. The log is append-only from the UI's point of view —
 * rows are written on the AI call path and retention is deliberately out of scope, so
 * neither a delete button nor a purge job exists.
 *
 * <p>Gated by the same {@code system.ai} permission as the provider settings screen, on
 * top of the {@code /admin/**} ADMIN matcher in {@code SecurityConfig}.
 */
@Controller
@RequestMapping(URL_SETTINGS_AI_LOGS)
@PreAuthorize("hasAuthority('PERM_system.ai')")
public class AiLogsController {

    /** Status values the filter accepts; anything else is ignored as if unset. */
    private static final List<String> ALLOWED_STATUSES =
            List.of(AiRequestLog.STATUS_SUCCESS, AiRequestLog.STATUS_FAILED);

    private final AiLogQueryService service;

    public AiLogsController(AiLogQueryService service) {
        this.service = service;
    }

    /**
     * Renders one page of the log.
     *
     * @param provider optional exact provider name to filter on
     * @param status   optional {@code SUCCESS} / {@code FAILED} filter
     * @param page     zero-based page index; negatives are clamped to the first page
     * @param model    view model
     * @return the log view name
     */
    @GetMapping
    public String list(@RequestParam(name = "provider", required = false) String provider,
                       @RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "page", required = false, defaultValue = "0") int page,
                       Model model) {
        AiLogFilter filter = AiLogFilter.of(provider, sanitizeStatus(status));
        int safePage = Math.max(0, page);

        model.addAttribute(ATTR_AI_LOGS_PAGE,
                service.list(filter, PageRequest.of(safePage, DEFAULT_PAGE_SIZE)));
        model.addAttribute(ATTR_AI_LOGS_FILTER, filter);
        model.addAttribute(ATTR_AI_LOGS_TOTALS, service.totals(filter));
        model.addAttribute(ATTR_AI_LOGS_PROVIDERS, service.providerNames());
        model.addAttribute(ATTR_ACTIVE_TAB, TAB_SETTINGS);
        return VIEW_SETTINGS_AI_LOGS;
    }

    /** Whitelist: an unrecognised status is dropped rather than matching zero rows. */
    private static String sanitizeStatus(String status) {
        return status != null && ALLOWED_STATUSES.contains(status) ? status : null;
    }
}
