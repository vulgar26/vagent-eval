package com.vagent.eval.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalIpv4CidrTest {

    @Test
    void loopback32() {
        assertThat(EvalIpv4Cidr.matches("127.0.0.1/32", "127.0.0.1")).isTrue();
        assertThat(EvalIpv4Cidr.matches("127.0.0.1/32", "127.0.0.2")).isFalse();
    }

    @Test
    void slash24() {
        assertThat(EvalIpv4Cidr.matches("192.168.1.0/24", "192.168.1.99")).isTrue();
        assertThat(EvalIpv4Cidr.matches("192.168.1.0/24", "192.168.2.1")).isFalse();
    }
}
