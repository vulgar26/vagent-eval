package com.vagent.eval.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.config.EvalProperties;
import com.vagent.eval.observability.EvalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TargetClient#resolveEvalGatewayKey}：per-target 覆盖默认，大小写不敏感 targetId。
 */
class TargetClientEvalGatewayKeyTest {

    @Test
    void resolveEvalGatewayKey_prefersPerTargetOverDefault() {
        EvalProperties p = new EvalProperties();
        p.setDefaultEvalGatewayKey("global-gw");
        EvalProperties.TargetConfig t = new EvalProperties.TargetConfig();
        t.setTargetId("travel-ai");
        t.setEvalGatewayKey("travel-secret");
        p.setTargets(List.of(t));

        TargetClient tc = new TargetClient(p, new ObjectMapper(), new EvalMetrics(new SimpleMeterRegistry()));
        assertThat(tc.resolveEvalGatewayKey("travel-ai")).isEqualTo("travel-secret");
        assertThat(tc.resolveEvalGatewayKey("TRAVEL-AI")).isEqualTo("travel-secret");
        assertThat(tc.resolveEvalGatewayKey("vagent")).isEqualTo("global-gw");
    }

    @Test
    void resolveEvalGatewayKey_blankPerTargetFallsBackToDefault() {
        EvalProperties p = new EvalProperties();
        p.setDefaultEvalGatewayKey("fallback-gw");
        EvalProperties.TargetConfig t = new EvalProperties.TargetConfig();
        t.setTargetId("travel-ai");
        t.setEvalGatewayKey("   ");
        p.setTargets(List.of(t));

        TargetClient tc = new TargetClient(p, new ObjectMapper(), new EvalMetrics(new SimpleMeterRegistry()));
        assertThat(tc.resolveEvalGatewayKey("travel-ai")).isEqualTo("fallback-gw");
    }

    @Test
    void resolveEvalGatewayKey_emptyWhenUnset() {
        EvalProperties p = new EvalProperties();
        TargetClient tc = new TargetClient(p, new ObjectMapper(), new EvalMetrics(new SimpleMeterRegistry()));
        assertThat(tc.resolveEvalGatewayKey("any")).isEmpty();
    }
}
