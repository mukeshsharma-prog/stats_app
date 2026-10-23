package com.zenner.datahub.stats.input.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.ProblemDetails;
import com.zenner.datahub.stats.input.ingest.IngestMetrics;
import com.zenner.datahub.stats.input.log.IngestLogContext;
import com.zenner.datahub.stats.input.log.IngestLoggingFilter;
import com.zenner.datahub.stats.input.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The {@code stats.input.auth.mode=oauth2} branch: a Keycloak-issued bearer token, or the
 * request goes no further.
 *
 * <p>The token's signature, issuer, audience and lifetime are checked by the
 * {@link JwtDecoder} (see {@link IngestJwtDecoders}); this filter adds the role check and
 * the answers. Every refusal happens before the body is read, so nothing is validated,
 * published or logged for a caller who is not allowed to send.</p>
 *
 * <table>
 *   <caption>Outcomes</caption>
 *   <tr><th>Request</th><th>Answer</th></tr>
 *   <tr><td>No {@code Authorization: Bearer} header</td><td>401, {@code WWW-Authenticate: Bearer}</td></tr>
 *   <tr><td>A token that fails decoding or validation</td><td>401, {@code error="invalid_token"}</td></tr>
 *   <tr><td>A valid token without the ingest realm role</td><td>403</td></tr>
 *   <tr><td>The issuer's signing keys cannot be fetched</td><td>503 — Heimdal retries</td></tr>
 * </table>
 *
 * <p><strong>Why a key outage is a 503 and not a 401.</strong> A 401 tells the sender its
 * credential is wrong, which is configuration, not something a resend fixes. When Input
 * cannot fetch the keys, the credential may be perfectly good and the fault is ours; 503 is
 * the answer Heimdal already treats as "retry later", exactly as for a broker outage.</p>
 *
 * <p>The role is read from {@code realm_access.roles} directly, as the read API does, rather
 * than through a granted-authority mapping that would be a second configuration surface.
 * No token, claim value or decoder message is logged or returned: a decoder message can
 * quote the token.</p>
 */
public final class BearerTokenIngestFilter extends OncePerRequestFilter {

    /** The same slot as the {@code none} filter: after the size check, before the rate limit. */
    public static final int ORDER = UnauthenticatedIngestFilter.ORDER;

    static final String CHALLENGE = "Bearer realm=\"stats-input\"";
    static final String INVALID_TOKEN_CHALLENGE = CHALLENGE + ", error=\"invalid_token\"";
    static final String INSUFFICIENT_SCOPE_CHALLENGE = CHALLENGE + ", error=\"insufficient_scope\"";

    private static final Logger log = LoggerFactory.getLogger(BearerTokenIngestFilter.class);
    private static final String BEARER_PREFIX = "bearer ";

    private final JwtDecoder decoder;
    private final String requiredRole;
    private final IngestMetrics metrics;
    private final ProblemWriter problemWriter;

    public BearerTokenIngestFilter(
            JwtDecoder decoder, String requiredRole, IngestMetrics metrics, ObjectMapper objectMapper) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.requiredRole = Objects.requireNonNull(requiredRole, "requiredRole");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.problemWriter = new ProblemWriter(Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            refuse(request, response, ErrorCode.UNAUTHORIZED, "unauthenticated", CHALLENGE,
                    "A bearer token is required.");
            return;
        }

        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (BadJwtException e) {
            refuse(request, response, ErrorCode.UNAUTHORIZED, "unauthenticated", INVALID_TOKEN_CHALLENGE,
                    "The bearer token is invalid, expired, or was not issued for this service.");
            return;
        } catch (JwtException e) {
            // Not a verdict on the token: the signing keys could not be obtained.
            refuse(request, response, ErrorCode.SERVICE_UNAVAILABLE, "keys_unavailable", null,
                    "The token could not be verified right now. Retry later.");
            return;
        }

        if (!hasRealmRole(jwt, requiredRole)) {
            refuse(request, response, ErrorCode.FORBIDDEN, "forbidden", INSUFFICIENT_SCOPE_CHALLENGE,
                    "The token does not grant the ingest role.");
            return;
        }

        chain.doFilter(request, response);
    }

    /** The token from an {@code Authorization: Bearer} header, or null when there is none. */
    static String bearerToken(String header) {
        if (header == null || header.length() <= BEARER_PREFIX.length()) {
            return null;
        }
        if (!header.substring(0, BEARER_PREFIX.length()).toLowerCase(Locale.ROOT).equals(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** Whether the token carries {@code role} among its Keycloak realm roles. */
    static boolean hasRealmRole(Jwt jwt, String role) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> access
                && access.get("roles") instanceof Collection<?> roles) {
            return roles.stream().anyMatch(candidate -> Objects.equals(candidate, role));
        }
        return false;
    }

    private void refuse(
            HttpServletRequest request,
            HttpServletResponse response,
            ErrorCode errorCode,
            String reason,
            String challenge,
            String detail)
            throws IOException {
        metrics.authRejected(IngestLoggingFilter.source(request), reason);
        IngestLogContext.errorCode(errorCode);
        log.atWarn()
                .addKeyValue("event", "authentication_refused")
                .addKeyValue("reason", reason)
                .log("Refused an ingest request at the authentication filter; nothing was read and nothing published");
        if (challenge != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        }
        problemWriter.write(
                response,
                ProblemDetails.of(errorCode, detail, request.getRequestURI(), null, IngestLogContext.traceId()));
    }
}
