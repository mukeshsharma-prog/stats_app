package com.zenner.datahub.stats.input.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A real key server for the ingest authentication tests: RS256 tokens signed with a key
 * Input fetches from a JWK set over HTTP, exactly as it fetches Keycloak's.
 *
 * <p>Nothing here mocks the decoder. Signature, expiry, issuer and audience are checked by
 * the decoder {@link IngestJwtDecoders} builds in production.</p>
 */
public final class IngestTokens {

    public static final String ISSUER = "https://issuer.test/realms/statsapp";
    public static final String AUDIENCE = "stats-input";
    public static final String INGEST_ROLE = "stats.ingest";

    private static final RSAKey KEY = generate("ingest-test-key");
    /** A key the server never publishes: a token signed with it must fail its signature check. */
    private static final RSAKey FOREIGN_KEY = generate("ingest-test-key");
    private static final HttpServer SERVER = start();

    private IngestTokens() {}

    public static String jwkSetUri() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/certs";
    }

    /** A valid token with the ingest role — what Heimdal's service account would send. */
    public static String heimdal() {
        return token().sign();
    }

    public static Builder token() {
        return new Builder();
    }

    /** Builds claims; everything defaults to a valid ingest token. */
    public static final class Builder {
        private final Map<String, Object> claims = new LinkedHashMap<>();
        private String issuer = ISSUER;
        private Object audience = AUDIENCE;
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private RSAKey signingKey = KEY;

        private Builder() {
            claims.put("realm_access", Map.of("roles", List.of(INGEST_ROLE)));
        }

        public Builder roles(String... roles) {
            claims.put("realm_access", Map.of("roles", List.of(roles)));
            return this;
        }

        public Builder withoutRealmAccess() {
            claims.remove("realm_access");
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(Object audience) {
            this.audience = audience;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder signedWithForeignKey() {
            this.signingKey = FOREIGN_KEY;
            return this;
        }

        public String sign() {
            JWTClaimsSet.Builder set =
                    new JWTClaimsSet.Builder()
                            .issuer(issuer)
                            .subject("service-account-stats-input")
                            .issueTime(Date.from(expiresAt.minusSeconds(600)))
                            .expirationTime(Date.from(expiresAt));
            if (audience instanceof String single) {
                set.audience(single);
            } else if (audience instanceof List<?> many) {
                set.audience(many.stream().map(String::valueOf).toList());
            }
            claims.forEach(set::claim);
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                            set.build());
            try {
                jwt.sign(new RSASSASigner(signingKey));
            } catch (JOSEException failure) {
                throw new IllegalStateException(failure);
            }
            return jwt.serialize();
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static HttpServer start() {
        try {
            HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext(
                    "/certs",
                    exchange -> {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
