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
    void evalContractV1OptionalFields_passContract() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "tool",
                  "latency_ms": 12,
                  "capabilities": {"retrieval": {"supported": true, "score": false}},
                  "meta": {
                    "mode": "AGENT",
                    "contract_version": "eval_contract_v1",
                    "workflow_id": "market_data_explain",
                    "workflow_version": "v1",
                    "workflow_family": "finance",
                    "policy_events": [
                      {
                        "policy_type": "finance_guard",
                        "stage": "guard",
                        "behavior": "tool",
                        "decision": "allow",
                        "severity": "info",
                        "rule_id": "market_data_mock_disclosure",
                        "attrs": {
                          "workflow_id": "market_data_explain",
                          "connector": "market_data"
                        }
                      }
                    ],
                    "stage_trace": [],
                    "tool_trace": [
                      {
                        "tool_name": "market_data",
                        "connector": "market_data",
                        "required": true,
                        "used": true,
                        "succeeded": true,
                        "outcome": "ok",
                        "attrs": {"mock_mode": "true"}
                      }
                    ],
                    "evidence_summary": {
                      "retrieval_hit_count": 1,
                      "source_count": 1
                    },
                    "unknown_future_key": {"kept": true}
                  }
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
    void workflowIdWrongType_contractViolation() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {},
                  "meta": {"mode": "EVAL", "workflow_id": 123}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isFalse();
        assertThat(o.errorCode()).isEqualTo(ErrorCode.CONTRACT_VIOLATION);
        assertThat(o.violations()).contains("meta_workflow_id_must_be_string");
    }

    @Test
    void policyEventsWrongType_contractViolation() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {},
                  "meta": {"mode": "EVAL", "policy_events": {"policy_type": "x"}}
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isFalse();
        assertThat(o.errorCode()).isEqualTo(ErrorCode.CONTRACT_VIOLATION);
        assertThat(o.violations()).contains("meta_policy_events_must_be_array");
    }

    @Test
    void policyEventAttrsWrongType_contractViolation() throws Exception {
        String json = """
                {
                  "answer": "ok",
                  "behavior": "answer",
                  "latency_ms": 1,
                  "capabilities": {},
                  "meta": {
                    "mode": "EVAL",
                    "policy_events": [{"policy_type": "x", "attrs": "bad"}]
                  }
                }
                """;
        ContractOutcome o = validator.validate(om.readTree(json));
        assertThat(o.ok()).isFalse();
        assertThat(o.errorCode()).isEqualTo(ErrorCode.CONTRACT_VIOLATION);
        assertThat(o.violations()).contains("meta_policy_events_attrs_must_be_object");
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
