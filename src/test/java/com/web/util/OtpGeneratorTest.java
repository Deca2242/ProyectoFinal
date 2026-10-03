package com.web.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OtpGeneratorTest {

    private OtpGenerator otpGenerator;

    @BeforeEach
    void setUp() {
        otpGenerator = new OtpGenerator();
    }

    @Test
    void shouldGenerate6DigitOtp_AlwaysNumericInRange() {
        // Given
        Set<String> generated = new HashSet<>();

        // When / Then: se repite muchas veces para cubrir la aleatoriedad
        for (int i = 0; i < 5_000; i++) {
            String otp = otpGenerator.generate6DigitOtp();
            assertThat(otp).hasSize(6).matches("\\d{6}");
            assertThat(Integer.parseInt(otp)).isBetween(100_000, 999_999);
            generated.add(otp);
        }

        // Debe haber variedad (no devuelve siempre el mismo valor)
        assertThat(generated.size()).isGreaterThan(1_000);
    }

    @Test
    void shouldValidateOtp_WithSameValue_ReturnTrue() {
        // When / Then
        assertThat(otpGenerator.validateOtp("123456", "123456")).isTrue();
    }

    @Test
    void shouldValidateOtp_WithSurroundingSpaces_ReturnTrue() {
        // When / Then
        assertThat(otpGenerator.validateOtp("  123456 ", "123456")).isTrue();
        assertThat(otpGenerator.validateOtp("123456", " 123456\t")).isTrue();
    }

    @Test
    void shouldValidateOtp_WithDifferentValue_ReturnFalse() {
        // When / Then
        assertThat(otpGenerator.validateOtp("123456", "654321")).isFalse();
        assertThat(otpGenerator.validateOtp("12345", "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("", "123456")).isFalse();
    }

    @Test
    void shouldValidateOtp_WithNullValues_ReturnFalse() {
        // When / Then
        assertThat(otpGenerator.validateOtp(null, "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("123456", null)).isFalse();
        assertThat(otpGenerator.validateOtp(null, null)).isFalse();
    }

    @Test
    void shouldValidateOtp_WithGeneratedValue_ReturnTrue() {
        // Given
        String otp = otpGenerator.generate6DigitOtp();

        // When / Then
        assertThat(otpGenerator.validateOtp(otp, otp)).isTrue();
    }

    @Test
    void shouldValidateOtp_WithSameLengthDifferingInOneDigit_ReturnFalse() {
        // When / Then: la comparación byte a byte no acepta coincidencias parciales
        assertThat(otpGenerator.validateOtp("123457", "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("023456", "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("123 56", "123456")).isFalse();
    }

    @Test
    void shouldValidateOtp_WithPrefixOrLongerValue_ReturnFalse() {
        // When / Then: longitudes distintas nunca coinciden
        assertThat(otpGenerator.validateOtp("1234567", "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("123", "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("123456", "1234567")).isFalse();
    }

    @Test
    void shouldValidateOtp_WithInternalSpaces_NotTrimThem() {
        // When / Then: solo se recortan los espacios de los extremos
        assertThat(otpGenerator.validateOtp("12 34 56", "123456")).isFalse();
        assertThat(otpGenerator.validateOtp("\n123456\n", "123456")).isTrue();
    }

    @Test
    void shouldValidateOtp_WithBlankProvidedAndRealOtp_ReturnFalse() {
        // When / Then
        assertThat(otpGenerator.validateOtp("      ", "123456")).isFalse();
    }

    @Test
    void shouldValidateOtp_WithNonAsciiDigits_ReturnFalse() {
        // When / Then: dígitos de otro alfabeto no equivalen a los ASCII (se compara en UTF-8)
        assertThat(otpGenerator.validateOtp("١٢٣٤٥٦", "123456")).isFalse();
    }

    @Test
    void shouldValidateOtp_WithBlankExpected_ReturnFalse() {
        // Un OTP esperado vacío nunca debe dar una entrega válida, aunque el enviado también esté vacío
        assertThat(otpGenerator.validateOtp("", "")).isFalse();
        assertThat(otpGenerator.validateOtp("  ", " ")).isFalse();
    }

    @Test
    void shouldHashOtp_AsSha256HexSaltedWithParcelCode() {
        // Given / When
        String hash = otpGenerator.hashOtp("PCL-1", "123456");

        // Then: 64 caracteres hex, determinista, distinto del OTP y dependiente del código de la encomienda
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}").doesNotContain("123456");
        assertThat(otpGenerator.hashOtp("PCL-1", " 123456 ")).isEqualTo(hash);
        assertThat(otpGenerator.hashOtp("PCL-2", "123456")).isNotEqualTo(hash);
        // Mismo formato que la migración V16: sha256("PCL-1:123456") en hex
        assertThat(hash).isEqualTo(sha256Hex("PCL-1:123456"));
    }

    @Test
    void shouldMatchOtp_OnlyWithTheRightOtpAndCode() {
        // Given
        String stored = otpGenerator.hashOtp("PCL-1", "123456");

        // When / Then
        assertThat(otpGenerator.matchesOtp("PCL-1", "123456", stored)).isTrue();
        assertThat(otpGenerator.matchesOtp("PCL-1", "000000", stored)).isFalse();
        assertThat(otpGenerator.matchesOtp("PCL-2", "123456", stored)).isFalse();
        // El hash guardado no sirve como OTP
        assertThat(otpGenerator.matchesOtp("PCL-1", stored, stored)).isFalse();
        assertThat(otpGenerator.matchesOtp("PCL-1", null, stored)).isFalse();
        assertThat(otpGenerator.matchesOtp("PCL-1", " ", stored)).isFalse();
        assertThat(otpGenerator.matchesOtp("PCL-1", "123456", null)).isFalse();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
