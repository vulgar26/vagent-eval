package com.vagent.eval.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vagent.eval.config.EvalProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetaPersistSanitizerTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void storage_stripsRetrievalHitIdsByDefault() {
        EvalProperties p = new EvalProperties();
        ObjectNode meta = om.createObjectNode();
        meta.put("mode", "EVAL");
        meta.put("hybrid_lexical_mode", "bm25");
        meta.set("retrieval_hit_ids", om.createArrayNode().add("chunk-a"));

        Map<String, Object> out = MetaPersistSanitizer.sanitizeMetaForStorage(meta, om, p);
        assertThat(out).containsEntry("mode", "EVAL");
        assertThat(out).containsEntry("hybrid_lexical_mode", "bm25");
        assertThat(out).doesNotContainKey("retrieval_hit_ids");
    }

    @Test
    void storage_retainsRetrievalHitIdsWhenExplicitlyAllowedAndEvalDebugChat() {
        EvalProperties p = new EvalProperties();
        p.getPersistence().setAllowPlainRetrievalHitIdsInMeta(true);
        p.getRunner().setChatMode("EVAL_DEBUG");
        ObjectNode meta = om.createObjectNode();
        meta.set("retrieval_hit_ids", om.createArrayNode().add("chunk-a"));

        Map<String, Object> out = MetaPersistSanitizer.sanitizeMetaForStorage(meta, om, p);
        assertThat(out).containsKey("retrieval_hit_ids");
    }

    @Test
    void storage_returnsNullWhenSerializedExceedsMaxChars() {
        EvalProperties p = new EvalProperties();
        p.getPersistence().setMaxTargetMetaJsonChars(80);
        ObjectNode meta = om.createObjectNode();
        meta.put("blob", "x".repeat(200));

        assertThat(MetaPersistSanitizer.sanitizeMetaForStorage(meta, om, p)).isNull();
    }

    @Test
    void outbound_stripsPlainHitIdsUnlessEvalDebugHeader() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("retrieve_hit_count", 1);
        m.put("retrieval_hit_ids", List.of("a"));

        assertThat(MetaPersistSanitizer.sanitizeMetaForOutbound(m, false))
                .doesNotContainKey("retrieval_hit_ids")
                .containsEntry("retrieve_hit_count", 1);

        assertThat(MetaPersistSanitizer.sanitizeMetaForOutbound(m, true)).containsKey("retrieval_hit_ids");
    }
}
