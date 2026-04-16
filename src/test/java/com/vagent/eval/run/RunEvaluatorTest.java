package com.vagent.eval.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.dataset.EvalExpectedBehavior;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.RunEvaluator.EvalOutcome;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RunEvaluator} 单测：构造最小 JSON，不启动 Spring 容器。
 */
class RunEvaluatorTest {

    private final ObjectMapper om = new ObjectMapper();

    private static EvalProperties props(String salt, int topN) {
        EvalProperties p = new EvalProperties();
        p.getMembership().setSalt(salt);
        p.getMembership().setTopN(topN);
        p.setDefaultEvalToken("test-token");
        return p;
    }

    @Test
    void securityBoundaryViolation_plainHitIdsWithoutEvalDebugMode_fails() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("test-salt", 8));
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": true, "outcome": true}
                  },
                  "meta": {"mode": "EVAL", "retrieval_hit_ids": ["kb_chunk_1"]},
                  "sources": [
                    {"id": "kb_chunk_1", "title": "t1", "snippet": "s1"}
                  ]
                }
                """;
        EvalCase c = new EvalCase(
                "sec_1",
                "ds",
                "SEC",
                EvalExpectedBehavior.ANSWER,
                false,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "probe");
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.SECURITY_BOUNDARY_VIOLATION);
        assertThat(o.debug()).containsEntry("verdict_reason", "security_boundary_violation");
    }

    @Test
    void toolExpected_toolsUnsupported_skippedEvenIfBehaviorAnswer() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("", 8));
        String json = """
                {
                  "answer": "probe ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": false, "score": false},
                    "tools": {"supported": false, "outcome": false}
                  },
                  "meta": {"mode": "EVAL"}
                }
                """;
        EvalCase c = new EvalCase(
                "d5_skip",
                "ds",
                "TOOL_UNSUPPORTED",
                EvalExpectedBehavior.TOOL,
                false,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "probe");
        assertThat(o.verdict()).isEqualTo(Verdict.SKIPPED_UNSUPPORTED);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.SKIPPED_UNSUPPORTED);
        assertThat(o.debug()).containsEntry("verdict_reason", "tools_unsupported");
    }

    @Test
    void behaviorMismatch_mapsToBehaviorMismatch_notUnknown() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("", 8));
        String json = """
                {
                  "answer": "x",
                  "behavior": "clarify",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": false, "score": false},
                    "tools": {"supported": true, "outcome": true}
                  },
                  "meta": {"mode": "EVAL"}
                }
                """;
        EvalCase c = new EvalCase(
                "s1_bm",
                "ds",
                "Q",
                EvalExpectedBehavior.ANSWER,
                false,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "vagent");
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.BEHAVIOR_MISMATCH);
        assertThat(o.debug()).containsEntry("verdict_reason", "behavior_mismatch");
        assertThat(o.debug()).containsEntry("actual_behavior", "clarify");
    }

    @Test
    void citations_requiresCitations_clarify_allowsEmptySources() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("s", 8));
        String json = """
                {
                  "answer": "cannot",
                  "behavior": "clarify",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": false, "outcome": false}
                  },
                  "meta": {"mode": "EVAL"},
                  "retrieval_hits": [],
                  "sources": []
                }
                """;
        EvalCase c = new EvalCase(
                "s167",
                "ds",
                "Q",
                EvalExpectedBehavior.CLARIFY,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "vagent");
        assertThat(o.verdict()).isEqualTo(Verdict.PASS);
        assertThat(o.errorCode()).isNull();
        assertThat(o.debug()).containsEntry("verdict_reason", "ok");
        assertThat(o.debug()).containsEntry("eval_rule_version", RunEvaluator.EVAL_RULE_VERSION);
    }

    @Test
    void citations_requiresCitations_deny_allowsEmptySources_evenIfHitsPresent() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("s", 8));
        String json = """
                {
                  "answer": "no",
                  "behavior": "deny",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": false, "outcome": false}
                  },
                  "meta": {"mode": "EVAL"},
                  "retrieval_hits": [{"id": "chunk_a", "title": "t", "snippet": "s"}],
                  "sources": []
                }
                """;
        EvalCase c = new EvalCase(
                "s167b",
                "ds",
                "Q",
                EvalExpectedBehavior.DENY,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "vagent");
        assertThat(o.verdict()).isEqualTo(Verdict.PASS);
        assertThat(o.errorCode()).isNull();
    }

    @Test
    void citations_requiresCitations_answer_missingSources_stillContractViolation() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("s", 8));
        String json = """
                {
                  "answer": "no",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": false, "outcome": false}
                  },
                  "meta": {"mode": "EVAL"},
                  "retrieval_hits": []
                }
                """;
        EvalCase c = new EvalCase(
                "s167d",
                "ds",
                "Q",
                EvalExpectedBehavior.ANSWER,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "vagent");
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.CONTRACT_VIOLATION);
        assertThat(o.debug()).containsEntry("verdict_reason", "missing_sources");
    }

    @Test
    void citations_requiresCitations_answer_sourcesProvided_enforcesMembership() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("s", 8));
        String json = """
                {
                  "answer": "no",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": false, "outcome": false}
                  },
                  "meta": {"mode": "EVAL"},
                  "retrieval_hits": [{"id": "chunk_a", "title": "t", "snippet": "s"}],
                  "sources": [{"id": "chunk_a", "title": "t", "snippet": "s"}]
                }
                """;
        EvalCase c = new EvalCase(
                "s167c",
                "ds",
                "Q",
                EvalExpectedBehavior.ANSWER,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "vagent");
        assertThat(o.verdict()).isEqualTo(Verdict.PASS);
        assertThat(o.errorCode()).isNull();
    }

    @Test
    void toolExpected_toolBlockIncomplete_mapsToToolExpectationNotMet() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("", 8));
        String json = """
                {
                  "answer": "called",
                  "behavior": "tool",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": false, "score": false},
                    "tools": {"supported": true, "outcome": true}
                  },
                  "meta": {"mode": "EVAL"},
                  "tool": {"required": true, "used": true, "succeeded": false}
                }
                """;
        EvalCase c = new EvalCase(
                "s1_tool",
                "ds",
                "Q",
                EvalExpectedBehavior.TOOL,
                false,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), "vagent");
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.TOOL_EXPECTATION_NOT_MET);
        assertThat(o.debug()).containsEntry("verdict_reason", "tool_not_satisfied");
    }

    @Test
    void citations_membership_pass_canonicalCase() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("test-salt", 8));
        String token = "test-token";
        String targetId = "probe";
        String datasetId = "ds";
        String caseId = "d6_ok";
        String canonicalHit = CitationMembership.canonicalChunkId("kb_chunk_1");
        String hash = CitationMembership.hitIdHashHexV1(CitationMembership.deriveCaseKeyV1(token, targetId, datasetId, caseId), canonicalHit);
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": true, "outcome": true}
                  },
                  "meta": {"mode": "EVAL", "retrieval_hit_id_hashes": ["%s"]},
                  "sources": [
                    {"id": "KB_CHUNK_1", "title": "t1", "snippet": "s1"}
                  ]
                }
                """.formatted(hash);
        EvalCase c = new EvalCase(
                caseId,
                datasetId,
                "CITATIONS_OK",
                EvalExpectedBehavior.ANSWER,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), targetId);
        assertThat(o.verdict()).isEqualTo(Verdict.PASS);
        assertThat(o.errorCode()).isNull();
        assertThat(o.debug()).containsEntry("membership_ok", true);
        assertThat(o.debug()).containsEntry("eval_rule_version", RunEvaluator.EVAL_RULE_VERSION);
    }

    @Test
    void citations_membership_fail_sourceNotInHits() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("test-salt", 8));
        // hashes 列表不包含 forged_chunk 的 hash → 触发 SOURCE_NOT_IN_HITS
        String token = "test-token";
        String targetId = "probe";
        String datasetId = "ds";
        String caseId = "d6_bad";
        String canonicalOnlyHit = CitationMembership.canonicalChunkId("only_hit");
        String onlyHitHash = CitationMembership.hitIdHashHexV1(CitationMembership.deriveCaseKeyV1(token, targetId, datasetId, caseId), canonicalOnlyHit);
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": true, "outcome": true}
                  },
                  "meta": {"mode": "EVAL", "retrieval_hit_id_hashes": ["%s"]},
                  "sources": [
                    {"id": "forged_chunk", "title": "t1", "snippet": "s1"}
                  ]
                }
                """.formatted(onlyHitHash);
        EvalCase c = new EvalCase(
                caseId,
                datasetId,
                "CITATIONS_BAD_MEMBER",
                EvalExpectedBehavior.ANSWER,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), targetId);
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.SOURCE_NOT_IN_HITS);
        assertThat(o.debug()).containsEntry("verdict_reason", "source_not_in_hits");
    }

    @Test
    void citations_membership_topN_excludesSecondHit() throws Exception {
        RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator(), props("test-salt", 1));
        String token = "test-token";
        String targetId = "probe";
        String datasetId = "ds";
        String caseId = "d6_topn";
        // topN=1，仅返回 first 的 hash；sources 引用 second 应失败
        String canonicalFirst = CitationMembership.canonicalChunkId("first");
        String firstHash = CitationMembership.hitIdHashHexV1(CitationMembership.deriveCaseKeyV1(token, targetId, datasetId, caseId), canonicalFirst);
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {
                    "retrieval": {"supported": true, "score": false},
                    "tools": {"supported": true, "outcome": true}
                  },
                  "meta": {"mode": "EVAL", "retrieval_hit_id_hashes": ["%s"]},
                  "sources": [
                    {"id": "second", "title": "t", "snippet": "s"}
                  ]
                }
                """.formatted(firstHash);
        EvalCase c = new EvalCase(
                caseId,
                datasetId,
                "x",
                EvalExpectedBehavior.ANSWER,
                true,
                List.of(),
                Instant.parse("2026-04-10T00:00:00Z")
        );
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json), targetId);
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.SOURCE_NOT_IN_HITS);
    }
}
