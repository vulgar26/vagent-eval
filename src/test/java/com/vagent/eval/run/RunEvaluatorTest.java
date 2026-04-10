package com.vagent.eval.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.dataset.EvalExpectedBehavior;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.run.RunEvaluator.EvalOutcome;
import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunEvaluatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final RunEvaluator evaluator = new RunEvaluator(new EvalChatContractValidator());

    @Test
    void toolExpected_toolsUnsupported_skippedEvenIfBehaviorAnswer() throws Exception {
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
        EvalOutcome o = evaluator.evaluate(c, om.readTree(json));
        assertThat(o.verdict()).isEqualTo(Verdict.SKIPPED_UNSUPPORTED);
        assertThat(o.errorCode()).isEqualTo(ErrorCode.SKIPPED_UNSUPPORTED);
        assertThat(o.debug()).containsEntry("verdict_reason", "tools_unsupported");
    }
}
