package com.vagent.eval.run;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CitationMembership} 的纯函数单测：canonical 与哈希确定性、大小写不敏感。
 */
class CitationMembershipTest {

    @Test
    void canonicalTrimsAndLowercases() {
        assertThat(CitationMembership.canonicalChunkId("  KB_A  ")).isEqualTo("kb_a");
        assertThat(CitationMembership.canonicalChunkId(null)).isEmpty();
    }

    @Test
    void membershipHashHexDeterministicAndCaseSensitiveToTarget() {
        String h1 = CitationMembership.membershipHashHex("s", "probe", CitationMembership.canonicalChunkId("X"));
        String h2 = CitationMembership.membershipHashHex("s", "probe", CitationMembership.canonicalChunkId("X"));
        String h3 = CitationMembership.membershipHashHex("s", "other", CitationMembership.canonicalChunkId("X"));
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).isNotEqualTo(h3);
        assertThat(h1).hasSize(64);
    }
}
