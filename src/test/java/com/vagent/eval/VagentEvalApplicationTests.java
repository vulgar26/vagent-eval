package com.vagent.eval;

import com.vagent.eval.web.InternalEvalStatusController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day1 冒烟：整容器启动 + 两个 HTTP 探针。
 * <ul>
 *   <li>{@code /actuator/health} — 证明 Spring Boot + Actuator 正常，进程「活着」；</li>
 *   <li>{@code /internal/eval/status} — 证明 {@code EvalProperties} 已注入且默认 targets 与 {@code application.yml} 一致。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class VagentEvalApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads() {
    }

    @Test
    void actuatorHealthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void internalStatusExposesTargets() throws Exception {
        mockMvc.perform(get("/internal/eval/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eval_api_enabled").value(false))
                .andExpect(jsonPath("$.targets[0].target_id").value("vagent"))
                .andExpect(jsonPath("$.targets[0].enabled").value(true))
                .andExpect(jsonPath("$.targets[0].base_url_origin").value("http://localhost:8080"));
    }

    /** 验证 {@link InternalEvalStatusController#safeOrigin} 会剥掉 path，只留 origin。 */
    @Test
    void safeOriginStripsPath() {
        assertThat(InternalEvalStatusController.safeOrigin("https://api.example.com:8443/v1")).isEqualTo("https://api.example.com:8443");
    }

    @Test
    void datasetCreateImportJsonlAndQuery() throws Exception {
        String createJson = """
                {"name":"smoke","version":"v1","description":"day2"}
                """;
        String body = mockMvc.perform(post("/api/v1/eval/datasets")
                        .contentType("application/json")
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataset_id").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 粗暴提取 id：只用于单测。
        String datasetId = body.split("\"dataset_id\"\\s*:\\s*\"")[1].split("\"")[0];

        String jsonl = """
                {"case_id":"c1","question":"q1","expected_behavior":"answer","requires_citations":true,"tags":["t1","t2"]}
                {"case_id":"c2","question":"q2","expected_behavior":"deny","requires_citations":false,"tags":"a|b"}
                """;

        mockMvc.perform(post("/api/v1/eval/datasets/" + datasetId + "/import")
                        .contentType("application/x-ndjson")
                        .content(jsonl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.case_count").value(2));

        mockMvc.perform(get("/api/v1/eval/datasets/" + datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_count").value(2));

        mockMvc.perform(get("/api/v1/eval/datasets/" + datasetId + "/cases?offset=0&limit=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases.length()").value(2))
                .andExpect(jsonPath("$.cases[0].expected_behavior").value("answer"))
                .andExpect(jsonPath("$.cases[0].requires_citations").value(true));
    }
}
