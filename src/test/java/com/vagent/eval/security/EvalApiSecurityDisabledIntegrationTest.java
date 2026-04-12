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
 * Day9：{@code eval.api.enabled=false} 时管理 API 不可发现性 + 审计 reason。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "eval.api.enabled=false")
class EvalApiSecurityDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void managementApiReturns404AndAuditDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/eval/datasets"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.audit_reason").value("DISABLED"));
    }
}
