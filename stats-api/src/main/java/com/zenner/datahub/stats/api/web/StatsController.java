package com.zenner.datahub.stats.api.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.api.security.CallerTenant;
import com.zenner.datahub.stats.api.security.SecurityProperties;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.model.StatType;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read API, v1 (STA-30…STA-38; openapi.yaml). GET only — every other method never
 * gets here ({@link ReadOnlyMethods}).
 *
 * <p><strong>Routing is by segment count, never by value</strong> (openapi.yaml): three
 * segments after {@code /stats} is a {@code uid}, four or five is a gateway. A
 * {@code CLSCON-}-prefixed {@code uid} and a 12-hex {@code clsbox} are only ever read from
 * the position the path puts them in.</p>
 *
 * <p><strong>The tenant is resolved here, from the token, once per request</strong> and
 * passed down. No parameter of any handler is a tenant: a {@code tenant} query parameter,
 * header or path value is not bound, so it is ignored (STA-34A).</p>
 */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final StatsReadService reads;
    private final SecurityProperties security;

    public StatsController(StatsReadService reads, SecurityProperties security) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.security = Objects.requireNonNull(security, "security");
    }

    @GetMapping("/{type}")
    public StatsPage list(
            Authentication authentication,
            @PathVariable("type") String type,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return reads.list(tenant(authentication), type, from, to, limit, cursor);
    }

    @GetMapping("/{type}/{uid}")
    public ObjectNode record(
            Authentication authentication,
            @PathVariable("type") String type,
            @PathVariable("uid") String uid) {
        return reads.byUid(tenant(authentication), type, uid)
                .orElseThrow(
                        () ->
                                ApiProblemException.of(
                                        ErrorCode.RECORD_NOT_FOUND,
                                        StatType.fromWireName(type),
                                        "No record with that uid exists for this tenant."));
    }

    @GetMapping("/{type}/{clsbox}/{from}")
    public StatsPage deviceFrom(
            Authentication authentication,
            @PathVariable("type") String type,
            @PathVariable("clsbox") String clsbox,
            @PathVariable("from") String from,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return reads.device(tenant(authentication), type, clsbox, from, null, limit, cursor);
    }

    @GetMapping("/{type}/{clsbox}/{from}/{to}")
    public StatsPage deviceRange(
            Authentication authentication,
            @PathVariable("type") String type,
            @PathVariable("clsbox") String clsbox,
            @PathVariable("from") String from,
            @PathVariable("to") String to,
            @RequestParam(name = "limit", required = false) String limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return reads.device(tenant(authentication), type, clsbox, from, to, limit, cursor);
    }

    private String tenant(Authentication authentication) {
        return CallerTenant.require(authentication, security.tenantClaim());
    }
}
