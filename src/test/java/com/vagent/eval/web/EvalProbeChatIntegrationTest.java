package com.vagent.eval.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day4：探针开启时，本机即可产出「合法 eval/chat 响应」与「缺字段响应」样例（无需外网 target）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "eval.probe.enabled=true")
class EvalProbeChatIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void probeReturnsValidContractShape() throws Exception {
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"hello\",\"mode\":\"EVAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").exists())
                .andExpect(jsonPath("$.behavior").value("answer"))
                .andExpect(jsonPath("$.latency_ms").value(1))
                .andExpect(jsonPath("$.capabilities").exists())
                .andExpect(jsonPath("$.meta.mode").value("EVAL"));
    }

    @Test
    void probeBadContractMissingLatency() throws Exception {
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"BAD_CONTRACT\",\"mode\":\"EVAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latency_ms").doesNotExist());
    }
}
