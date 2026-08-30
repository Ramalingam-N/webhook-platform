package com.webhook.dispatcher.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureServiceTest {

    private SignatureService signatureService;

    @BeforeEach
    void setUp() {
        signatureService = new SignatureService();
    }

    @Test
    @DisplayName("U1: Deterministic - identical inputs must yield identical signatures")
    void u1_deterministicSigning() {
        String payload = "{\"status\":\"ok\"}";
        String secret = "whsk_test123";
        String timestamp = "1710000000";
        String eventId = "evt_999";

        String sig1 = signatureService.sign(payload, secret, timestamp, eventId);
        String sig2 = signatureService.sign(payload, secret, timestamp, eventId);

        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("U2: Secret sensitivity - same payload, different secrets yield different signatures")
    void u2_secretSensitivity() {
        String payload = "{\"status\":\"ok\"}";
        String timestamp = "1710000000";
        String eventId = "evt_999";

        String sig1 = signatureService.sign(payload, "secret-A", timestamp, eventId);
        String sig2 = signatureService.sign(payload, "secret-B", timestamp, eventId);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("U3: Tamper detection - changing one character of payload yields different signature")
    void u3_tamperDetection() {
        String secret = "whsk_test123";
        String timestamp = "1710000000";
        String eventId = "evt_999";

        String sig1 = signatureService.sign("{\"status\":\"ok\"}", secret, timestamp, eventId);
        String sig2 = signatureService.sign("{\"status\":\"no\"}", secret, timestamp, eventId);

        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("U4: Format - output starts with 'sha256=' and is a valid hex string")
    void u4_formatValidation() {
        String sig = signatureService.sign("payload", "secret", "123", "evt");

        assertThat(sig).startsWith("sha256=");
        assertThat(sig).hasSize(71);
        
        String hexPart = sig.substring(7);
        assertThat(hexPart).matches("^[0-9a-fA-F]+$");
    }

    @Test
    @DisplayName("U5: Empty secret handled without crash")
    void u5_emptySecretHandled() {
        String sig = signatureService.sign("payload", "", "123", "evt");
        
        assertThat(sig).startsWith("sha256=");
        assertThat(sig).hasSize(71);
    }
    
    @Test
    @DisplayName("Edge Case: Null secret throws RuntimeException")
    void nullSecretThrows() {
        assertThatThrownBy(() -> signatureService.sign("payload", null, "123", "evt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Signature generation failed");
    }
}