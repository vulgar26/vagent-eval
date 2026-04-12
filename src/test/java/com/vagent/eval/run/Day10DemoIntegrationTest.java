package com.vagent.eval.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Day10：把「一键演示脚本」固化成集成测试，防止回归。
 * <p>
 * <strong>测什么</strong>：创建数据集 → 导入与脚本相同的两条 JSONL → 对 {@code probe} 跑两轮 run →
 * 断言 {@code GET .../report} 为 {@link RunReportService#REPORT_VERSION} 且 pass/fail 计数符合探针行为 →
 * 断言 {@code GET /api/v1/eval/compare} 为 {@link RunCompareService#COMPARE_VERSION} 且同数据集下无回归/改进（两跑一致时）。
 * <p>
 * <strong>怎么跑</strong>：{@code mvn test} 中带本类；需 {@code src/test/resources/application.yml} 里 {@code eval.api.enabled=true}。
 * <p>
 * <strong>注意</strong>：与 {@link Day6ProbeMembershipIntegrationTest} 相同使用 {@link SpringBootTest.WebEnvironment#DEFINED_PORT}，
 * {@link RunRunner} 通过真实 HTTP 回环本进程；{@code server.port}（默认 8099）须空闲，且勿与手动 {@code spring-boot:run} 抢端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class Day10DemoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void twoProbeRuns_thenReportAndCompare() throws Exception {
        String createJson = """
                {"name":"d10_demo","version":"v1","description":"day10"}
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
                {"case_id":"d10_ok","question":"plain smoke","expected_behavior":"answer","requires_citations":false,"tags":["day10"]}
                {"case_id":"d10_bad","question":"BAD_CONTRACT","expected_behavior":"answer","requires_citations":false,"tags":["day10"]}
                """;

        mockMvc.perform(post("/api/v1/eval/datasets/" + datasetId + "/import")
                        .contentType("application/x-ndjson")
                        .content(jsonl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_count").value(2));

        String runReq = """
                {"dataset_id":"%s","target_id":"probe"}
                """.formatted(datasetId);

        String baseRunId = postRun(runReq);
        awaitFinished(baseRunId);

        mockMvc.perform(get("/api/v1/eval/runs/" + baseRunId + "/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report_version").value(RunReportService.REPORT_VERSION))
                .andExpect(jsonPath("$.pass_count").value(1))
                .andExpect(jsonPath("$.fail_count").value(1))
                .andExpect(jsonPath("$.markdown_summary").exists());

        String candRunId = postRun(runReq);
        awaitFinished(candRunId);

        mockMvc.perform(get("/api/v1/eval/compare")
                        .param("base_run_id", baseRunId)
                        .param("cand_run_id", candRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compare_version").value(RunCompareService.COMPARE_VERSION))
                .andExpect(jsonPath("$.dataset_id").value(datasetId))
                .andExpect(jsonPath("$.regressions.length()").value(0))
                .andExpect(jsonPath("$.improvements.length()").value(0))
                .andExpect(jsonPath("$.markdown_summary").exists());
    }

    /** 创建 run 并解析响应中的 {@code run_id}。 */
    private String postRun(String runReqJson) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/eval/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(runReqJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").exists())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        return body.split("\"run_id\"\\s*:\\s*\"")[1].split("\"")[0];
    }

    /** 轮询 run 状态直至 {@code FINISHED}，避免固定 {@code Thread.sleep} 过长或过短。 */
    private void awaitFinished(String runId) throws Exception {
        for (int i = 0; i < 450; i++) {
            String json = mockMvc.perform(get("/api/v1/eval/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            JsonNode n = objectMapper.readTree(json);
            String st = n.get("status").asText();
            if ("FINISHED".equals(st)) {
                return;
            }
            if ("CANCELLED".equals(st)) {
                fail("run " + runId + " CANCELLED: " + n.get("cancel_reason"));
            }
            Thread.sleep(200L);
        }
        fail("timeout waiting for FINISHED: " + runId);
    }
}
