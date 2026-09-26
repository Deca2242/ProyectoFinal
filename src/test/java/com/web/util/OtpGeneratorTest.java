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
}
