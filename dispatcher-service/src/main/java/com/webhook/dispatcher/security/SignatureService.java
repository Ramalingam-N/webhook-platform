package com.webhook.dispatcher.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Slf4j
@Service
public class SignatureService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public String sign(String payload, String secret, String timestamp, String eventId) {
        
        if (secret == null) {
            log.error("Failed to generate HMAC signature: secret cannot be null");
            throw new IllegalArgumentException("Signature generation failed: secret cannot be null");
        }

        try {
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length == 0) {
                keyBytes = new byte[1];
            }
            
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, HMAC_SHA256));

            String signedContent = eventId + "." + timestamp + "." + payload;
            byte[] hash = mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } 
        catch (Exception e) {
            log.error("Failed to generate HMAC signature", e);
            throw new RuntimeException("Signature generation failed", e);
        }
    }
}