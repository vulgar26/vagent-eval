package com.vagent.eval.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day9：配置 CIDR 后，MockMvc 默认 {@code 127.0.0.1} 不在 {@code 10.0.0.0/8} 内 → 403 + {@link AuditReason#CIDR_DENIED}。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "eval.api.enabled=true",
        "eval.api.token-hash=",
        "eval.api.allow-cidrs[0]=10.0.0.0/8",
        "eval.api.require-https=false"
})
class EvalApiSecurityCidrIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void localhostBlockedByCidr() throws Exception {
        mockMvc.perform(get("/api/v1/eval/datasets"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.audit_reason").value("CIDR_DENIED"));
    }
}
