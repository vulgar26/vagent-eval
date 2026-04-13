package com.vagent.eval.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.config.EvalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TargetClient#resolveEvalToken}：per-target 覆盖默认，大小写不敏感 targetId。
 */
class TargetClientEvalTokenTest {

    @Test
    void resolveEvalToken_prefersPerTargetOverDefault() {
        EvalProperties p = new EvalProperties();
        p.setDefaultEvalToken("global-default");
        EvalProperties.TargetConfig v = new EvalProperties.TargetConfig();
        v.setTargetId("vagent");
        v.setEvalToken("secret-vagent");
        p.setTargets(List.of(v));

        TargetClient tc = new TargetClient(p, new ObjectMapper());
        assertThat(tc.resolveEvalToken("vagent")).isEqualTo("secret-vagent");
        assertThat(tc.resolveEvalToken("VAGENT")).isEqualTo("secret-vagent");
        assertThat(tc.resolveEvalToken("travel-ai")).isEqualTo("global-default");
    }

    @Test
    void resolveEvalToken_blankPerTargetFallsBackToDefault() {
        EvalProperties p = new EvalProperties();
        p.setDefaultEvalToken("fallback");
        EvalProperties.TargetConfig t = new EvalProperties.TargetConfig();
        t.setTargetId("probe");
        t.setEvalToken("   ");
        p.setTargets(List.of(t));

        TargetClient tc = new TargetClient(p, new ObjectMapper());
        assertThat(tc.resolveEvalToken("probe")).isEqualTo("fallback");
    }
}
