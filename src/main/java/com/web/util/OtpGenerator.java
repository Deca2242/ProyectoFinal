package com.web.util;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Component
public class OtpGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate6DigitOtp() {
        int otp = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(otp);
    }

    public boolean validateOtp(String provided, String expected) {
        // Un OTP esperado vacío nunca valida (evita que "" == "" dé una entrega válida)
        if (provided == null || expected == null || expected.isBlank()) {
            return false;
        }
        // Comparación en tiempo constante para no filtrar cuántos dígitos coinciden
        return MessageDigest.isEqual(
                provided.trim().getBytes(StandardCharsets.UTF_8),
                expected.trim().getBytes(StandardCharsets.UTF_8));
    }

    // Hash del OTP que se guarda en la encomienda: SHA-256 (hex) de "código:otp". El código hace de sal,
    // así el mismo OTP en dos encomiendas no produce el mismo hash. Mismo formato que la migración V16
    public String hashOtp(String parcelCode, String otp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((parcelCode + ":" + otp.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    // Compara el OTP que presenta el destinatario con el hash guardado
    public boolean matchesOtp(String parcelCode, String provided, String storedHash) {
        if (provided == null || provided.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return validateOtp(hashOtp(parcelCode, provided), storedHash);
    }
}
