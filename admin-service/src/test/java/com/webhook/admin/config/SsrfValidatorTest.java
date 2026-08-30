package com.webhook.admin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfValidatorTest {

    private SsrfValidator ssrfValidator;

    @BeforeEach
    void setUp() {
        ssrfValidator = new SsrfValidator();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x",
            "http://localhost:8081",
            "http://2130706433/",
            "http://0x7f000001"
    })
    @DisplayName("U1: Blocks loopback and obfuscated loopback addresses")
    void blocksLoopbackAddresses(String url) {
        assertThatThrownBy(() -> ssrfValidator.validateUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target URL resolves to a restricted or internal network address");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.5/",
            "http://172.16.0.1/",
            "http://192.168.1.1/"
    })
    @DisplayName("U2: Blocks site-local (private internal) addresses")
    void blocksSiteLocalAddresses(String url) {
        assertThatThrownBy(() -> ssrfValidator.validateUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target URL resolves to a restricted or internal network address");
    }

    @Test
    @DisplayName("U3: Blocks link-local addresses (Cloud Metadata APIs)")
    void blocksLinkLocal() {
        // Attackers use this to steal AWS/GCP/Azure instance metadata credentials
        assertThatThrownBy(() -> ssrfValidator.validateUrl("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target URL resolves to a restricted or internal network address");
    }

    @Test
    @DisplayName("U4: Blocks non-HTTP/HTTPS schemes")
    void blocksInvalidSchemes() {
        assertThatThrownBy(() -> ssrfValidator.validateUrl("ftp://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only HTTP and HTTPS protocols are supported");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("U5: Blocks empty or blank URLs")
    void blocksEmptyUrls(String url) {
        assertThatThrownBy(() -> ssrfValidator.validateUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Target URL cannot be empty");
    }

    @Test
    @DisplayName("U6: Blocks null URLs")
    void blocksNullUrl() {
        assertThatThrownBy(() -> ssrfValidator.validateUrl(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Target URL cannot be empty");
    }

    @Test
    @DisplayName("U7: Blocks unresolvable dummy hosts")
    void blocksUnresolvableHosts() {
        assertThatThrownBy(() -> ssrfValidator.validateUrl("http://no-such-host-xyz.invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Could not resolve host");
    }

    @Test
    @DisplayName("U8: Passes valid, external internet URLs")
    void passesValidExternalUrls() {
        assertThatCode(() -> ssrfValidator.validateUrl("https://webhook.site/abc"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com:not_a_port",
            "http://bad url with spaces.com",
            "http://[::1:bad_ipv6",
            "htt p://example.com",
            "://missing-scheme.com"
    })
    @DisplayName("U9: Rejects malformed URL syntax causing URI parsing failures")
    void rejectsMalformedUriSyntax(String malformedUrl) {
        assertThatThrownBy(() -> ssrfValidator.validateUrl(malformedUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Malformed URL structure:");
    }
}