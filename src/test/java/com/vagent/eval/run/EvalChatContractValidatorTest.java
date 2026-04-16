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
        assertThat(o.violations()).contains("missing_latency_ms");
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
        assertThat(o.violations()).contains("latency_ms_must_be_number");
    }

    @Test
    void emptyRetrievalHitIdHashesArray_passesContract() throws Exception {
        String json = """
                {
                  "answer": "x",
                  "behavior": "clarify",
                  "latency_ms": 1,
                  "capabilities": {"retrieval": {"supported": true, "score": false}},
                  "meta": {"mode": "EVAL", "retrieval_hit_id_hashes": []}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isTrue();
    }

    @Test
    void nonEmptyRetrievalHitIdHashes_invalidHex_contractViolation() throws Exception {
        String json = """
                {
                  "answer": "x",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {"retrieval": {"supported": true, "score": false}},
                  "meta": {"mode": "EVAL", "retrieval_hit_id_hashes": ["not-64-hex"]}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isFalse();
        assertThat(o.violations()).contains("meta_retrieval_hit_id_hashes_must_be_sha256_hex64");
    }
}
