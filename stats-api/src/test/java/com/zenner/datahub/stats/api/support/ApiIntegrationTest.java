package com.zenner.datahub.stats.api.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The full API in one shared context: the real filter chain, the real resource server
 * against {@link TestJwks}, and PostgreSQL through {@link ApiDatabase} as {@code stats_ro}.
 * Every subclass uses the same configuration, so Spring caches one context for all of
 * them. Subclasses keep to their own tenants, so no test needs to truncate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:api-test.properties")
public abstract class ApiIntegrationTest {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ApiDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> ApiDatabase.READ_ONLY);
        registry.add("spring.datasource.password", () -> ApiDatabase.READ_ONLY_PASSWORD);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwks::jwkSetUri);
    }

    protected static MockHttpServletRequestBuilder get(String token, String path, Object... variables) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path, variables);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
