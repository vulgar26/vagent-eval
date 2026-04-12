package com.vagent.eval.security;

import com.vagent.eval.run.RunModel.ErrorCode;
import com.vagent.eval.run.RunModel.EvalResult;
import com.vagent.eval.run.RunModel.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDebugExposureCheckerTest {

    @Test
    void forbiddenWhenExceptionMessageWithoutDebugHeader() {
        EvalResult r = new EvalResult(
                "run", "ds", "t", "c1", Verdict.FAIL, ErrorCode.UPSTREAM_UNAVAILABLE, 1L,
                Instant.now(),
                Map.of("exception_message", "x")
        );
        Map<String, Object> body = Map.of("results", List.of(r));
        assertThat(EvalDebugExposureChecker.violates(body, null)).isTrue();
        assertThat(EvalDebugExposureChecker.violates(body, "1")).isFalse();
    }

    @Test
    void okWhenNoForbiddenKeys() {
        EvalResult r = new EvalResult(
                "run", "ds", "t", "c1", Verdict.PASS, null, 1L,
                Instant.now(),
                Map.of("verdict_reason", "ok")
        );
        assertThat(EvalDebugExposureChecker.violates(Map.of("results", List.of(r)), null)).isFalse();
    }
}
