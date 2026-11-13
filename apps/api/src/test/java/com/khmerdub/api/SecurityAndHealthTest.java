package com.khmerdub.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SecurityAndHealthTest {

    private static final String TOKEN = "test-internal-token-0123456789abcdef0123456789";

    @Autowired
    MockMvc mvc;

    @Test
    void internalEndpointsRequireTheToken() throws Exception {
        mvc.perform(get("/internal/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/ping").header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/ping").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void nonLoopbackHostIsRejected() throws Exception {
        mvc.perform(get("/api/health").with(r -> {
            r.setServerName("evil.example");
            return r;
        })).andExpect(status().isForbidden());
    }

    @Test
    void dockerHostNameIsAcceptedOnlyForInternalEndpoints() throws Exception {
        mvc.perform(get("/internal/ping").header("X-Internal-Token", TOKEN).with(r -> {
            r.setServerName("host.docker.internal");
            return r;
        })).andExpect(status().isOk());
        mvc.perform(get("/api/health").with(r -> {
            r.setServerName("host.docker.internal");
            return r;
        })).andExpect(status().isForbidden());
    }

    @Test
    void foreignOriginIsRejectedButFrontendOriginIsAllowed() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/health").header("Origin", "http://localhost:3000")).andExpect(status().isOk());
    }

    @Test
    void healthReportsDatabaseUpAndUnreachableAiServiceAsDown() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WARN"))
                .andExpect(jsonPath("$.version").value("0.1.0"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.database.details.schemaVersion").value("2"))
                .andExpect(jsonPath("$.components.storage.status").value(Matchers.oneOf("UP", "WARN")))
                .andExpect(jsonPath("$.components.storage.details.writable").value(true))
                .andExpect(jsonPath("$.components.aiService.status").value("DOWN"));
    }
}
