package com.web.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class OtpGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate6DigitOtp() {
        int otp = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(otp);
    }

    public boolean validateOtp(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        // Comparación en tiempo constante para no filtrar cuántos dígitos coinciden
        return java.security.MessageDigest.isEqual(
                provided.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

