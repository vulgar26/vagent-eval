package com.vagent.eval.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalEvalStatusControllerTest {

    @Test
    void maskJdbcUrlStripsUserinfoPassword() {
        assertThat(InternalEvalStatusController.maskJdbcUrl("jdbc:postgresql://u:secret@localhost:5432/eval"))
                .isEqualTo("jdbc:postgresql://u:***@localhost:5432/eval");
    }

    @Test
    void maskJdbcUrlLeavesUrlWithoutEmbeddedPassword() {
        assertThat(InternalEvalStatusController.maskJdbcUrl("jdbc:postgresql://localhost:5432/eval"))
                .isEqualTo("jdbc:postgresql://localhost:5432/eval");
    }

    @Test
    void maskJdbcUrlRedactsPasswordQueryParam() {
        assertThat(InternalEvalStatusController.maskJdbcUrl("jdbc:postgresql://localhost/eval?password=secret&ssl=true"))
                .isEqualTo("jdbc:postgresql://localhost/eval?password=***&ssl=true");
    }
}
