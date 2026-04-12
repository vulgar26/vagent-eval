package com.vagent.eval.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day9：配置了 {@code token-hash} 时，错误/缺失 token → 401 + {@link AuditReason#INVALID_TOKEN}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EvalApiSecurityTokenIntegrationTest {

    private static final String SECRET = "day9-test-secret";

    @DynamicPropertySource
    static void tokenHash(DynamicPropertyRegistry r) {
        r.add("eval.api.enabled", () -> "true");
        r.add("eval.api.token-hash", () -> EvalApiTokenVerifier.sha256Hex(SECRET));
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/eval/datasets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.audit_reason").value("INVALID_TOKEN"));
    }

    @Test
    void validTokenAllowsRequest() throws Exception {
        mockMvc.perform(get("/api/v1/eval/datasets")
                        .header(EvalSecurityConstants.HDR_API_TOKEN, SECRET))
                .andExpect(status().isOk());
    }
}
