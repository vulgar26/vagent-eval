package com.vagent.eval.web;

import org.junit.jupiter.api.Test;
import com.vagent.eval.run.TargetClient;
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

    @Test
    void probeCitationsOkIncludesSources() throws Exception {
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TargetClient.HDR_MEMBERSHIP_TOP_N, "8")
                        .content("{\"query\":\"CITATIONS_OK\",\"mode\":\"EVAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.retrieval.supported").value(true))
                .andExpect(jsonPath("$.sources[0].id").value("KB_CHUNK_1"))
                .andExpect(jsonPath("$.retrieval_hits[0].id").value("kb_chunk_1"));
    }

    @Test
    void probeCitationsBadMemberReturnsMismatchedSource() throws Exception {
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"CITATIONS_BAD_MEMBER\",\"mode\":\"EVAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieval_hits[0].id").value("only_hit"))
                .andExpect(jsonPath("$.sources[0].id").value("forged_chunk"));
    }
}
