package com.vagent.eval.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.run.EvalChatContractValidator.ContractOutcome;
import com.vagent.eval.run.RunModel.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalChatContractValidatorTest {

    private final ObjectMapper om = new ObjectMapper();
    private final EvalChatContractValidator validator = new EvalChatContractValidator();

    @Test
    void validMinimalContract() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 12,
                  "capabilities": {"retrieval": {"supported": false, "score": false}},
                  "meta": {"mode": "EVAL"}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isTrue();
    }

    @Test
    void missingField_contractViolation() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "capabilities": {},
                  "meta": {"mode": "EVAL"}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isFalse();
        assertThat(o.errorCode()).isEqualTo(ErrorCode.CONTRACT_VIOLATION);
        assertThat(o.reason()).contains("latency");
    }

    @Test
    void wrongType_latencyMsString_contractViolation() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": "not-a-number",
                  "capabilities": {},
                  "meta": {"mode": "EVAL"}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isFalse();
        assertThat(o.errorCode()).isEqualTo(ErrorCode.CONTRACT_VIOLATION);
        assertThat(o.reason()).contains("latency_ms");
    }
}
