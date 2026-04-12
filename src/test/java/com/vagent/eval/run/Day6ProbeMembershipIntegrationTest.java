package com.vagent.eval.run;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day6 端到端：dataset → import → run(probe) → 拉结果，验证 membership 通过与不通过两条证据链。
 * <p>
 * 依赖 {@code application.yml} 中 {@code eval.probe.enabled=true} 与 probe target 指向本机端口。
 * <p>
 * <strong>为何使用 {@link SpringBootTest.WebEnvironment#DEFINED_PORT}</strong>：默认 {@code MOCK} 环境不会启动真实 Tomcat，
 * 而 {@link RunRunner} 在后台线程里通过 {@link TargetClient}（JDK {@code HttpClient}）向 {@code eval.targets.probe.base-url}
 * 发真实 TCP 请求。若不启嵌入式 Web 容器，将一直 {@code ConnectException} → {@code UPSTREAM_UNAVAILABLE}，本测试永远失败。
 * 因此与 {@code application.yml} 的 {@code server.port} 对齐启动真端口（默认 8099）；跑前请确保该端口未被占用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class Day6ProbeMembershipIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void probeRun_firstCasePass_secondCaseSourceNotInHits() throws Exception {
        String createJson = """
                {"name":"d6_member","version":"v1","description":"day6"}
                """;
        String dsBody = mockMvc.perform(post("/api/v1/eval/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataset_id").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String datasetId = dsBody.split("\"dataset_id\"\\s*:\\s*\"")[1].split("\"")[0];

        String jsonl = """
                {"case_id":"d6_pass","question":"CITATIONS_OK","expected_behavior":"answer","requires_citations":true,"tags":[]}
                {"case_id":"d6_fail","question":"CITATIONS_BAD_MEMBER","expected_behavior":"answer","requires_citations":true,"tags":[]}
                """;

        mockMvc.perform(post("/api/v1/eval/datasets/" + datasetId + "/import")
                        .contentType("application/x-ndjson")
                        .content(jsonl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_count").value(2));

        String runReq = """
                {"dataset_id":"%s","target_id":"probe"}
                """.formatted(datasetId);

        String runBody = mockMvc.perform(post("/api/v1/eval/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String runId = runBody.split("\"run_id\"\\s*:\\s*\"")[1].split("\"")[0];

        Thread.sleep(2000L);

        mockMvc.perform(get("/api/v1/eval/runs/" + runId + "/results"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].case_id").value("d6_pass"))
                .andExpect(jsonPath("$.results[0].verdict").value("PASS"))
                .andExpect(jsonPath("$.results[0].error_code", nullValue()))
                .andExpect(jsonPath("$.results[0].debug.membership_ok").value(true))
                .andExpect(jsonPath("$.results[1].case_id").value("d6_fail"))
                .andExpect(jsonPath("$.results[1].verdict").value("FAIL"))
                .andExpect(jsonPath("$.results[1].error_code").value("SOURCE_NOT_IN_HITS"));
    }
}
