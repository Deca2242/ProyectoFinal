package com.web.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private static final String SECRET = "clave-secreta-de-pruebas-suficientemente-larga-para-hmac512";
    private static final long EXPIRATION_MS = 3_600_000L;
    private static final String EMAIL = "pasajero@test.com";
    private static final String ROLE = "PASSENGER";

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        // Los valores normalmente llegan por @Value; aquí se inyectan a mano
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", EXPIRATION_MS);
    }

    @Test
    void shouldGenerateToken_AndExtractEmail() {
        // Given
        String token = jwtTokenProvider.generateToken(EMAIL, ROLE);

        // When
        String email = jwtTokenProvider.extractEmail(token);

        // Then
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(email).isEqualTo(EMAIL);
    }

    @Test
    void shouldGenerateToken_WithRoleClaimAndExpiration() {
        // Given
        long before = System.currentTimeMillis();

        // When
        String token = jwtTokenProvider.generateToken(EMAIL, ROLE);
        DecodedJWT decoded = JWT.decode(token);

        // Then
        assertThat(decoded.getSubject()).isEqualTo(EMAIL);
        assertThat(decoded.getClaim("role").asString()).isEqualTo(ROLE);
        assertThat(decoded.getAlgorithm()).isEqualTo("HS512");
        assertThat(decoded.getIssuedAt()).isNotNull();
        assertThat(decoded.getExpiresAt()).isNotNull();
        // La expiración debe ser aproximadamente issuedAt + expiration (precisión de segundos en JWT)
        long expectedExpiry = before + EXPIRATION_MS;
        assertThat(decoded.getExpiresAt().getTime()).isBetween(expectedExpiry - 2_000, expectedExpiry + 2_000);
    }

    @Test
    void shouldValidateToken_WithValidToken_ReturnTrue() {
        // Given
        String token = jwtTokenProvider.generateToken(EMAIL, ROLE);

        // When
        boolean valid = jwtTokenProvider.validateToken(token);

        // Then
        assertThat(valid).isTrue();
    }

    @Test
    void shouldValidateToken_WithTamperedPayload_ReturnFalse() {
        // Given: se reemplaza el payload por uno con otro rol manteniendo la firma original
        String token = jwtTokenProvider.generateToken(EMAIL, ROLE);
        String[] parts = token.split("\\.");
        String forgedToken = JWT.create()
                .withSubject(EMAIL)
                .withClaim("role", "ADMIN")
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .sign(Algorithm.HMAC512(SECRET));
        String forgedPayload = forgedToken.split("\\.")[1];
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        // When / Then
        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
        assertThat(jwtTokenProvider.extractEmail(tampered)).isNull();
    }

    @Test
    void shouldValidateToken_WithTamperedSignature_ReturnFalse() {
        // Given
        String token = jwtTokenProvider.generateToken(EMAIL, ROLE);
        char last = token.charAt(token.length() - 5);
        char replacement = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 5) + replacement + token.substring(token.length() - 4);

        // When / Then
        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
    }

    @Test
    void shouldValidateToken_SignedWithOtherSecret_ReturnFalse() {
        // Given
        String foreignToken = JWT.create()
                .withSubject(EMAIL)
                .withClaim("role", "ADMIN")
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .sign(Algorithm.HMAC512("otro-secreto-completamente-distinto-al-del-servidor"));

        // When / Then
        assertThat(jwtTokenProvider.validateToken(foreignToken)).isFalse();
        assertThat(jwtTokenProvider.extractEmail(foreignToken)).isNull();
    }

    @Test
    void shouldValidateToken_WithExpiredToken_ReturnFalse() {
        // Given: una expiración negativa genera un token ya vencido
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", -60_000L);
        String expiredToken = jwtTokenProvider.generateToken(EMAIL, ROLE);
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", EXPIRATION_MS);

        // When / Then
        assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
        assertThat(jwtTokenProvider.extractEmail(expiredToken)).isNull();
    }

    @Test
    void shouldValidateToken_WithGarbageText_ReturnFalse() {
        // When / Then
        assertThat(jwtTokenProvider.validateToken("esto-no-es-un-jwt")).isFalse();
        assertThat(jwtTokenProvider.validateToken("aaa.bbb.ccc")).isFalse();
    }

    @Test
    void shouldValidateToken_WithEmptyString_ReturnFalse() {
        // When / Then
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    void shouldExtractEmail_WithInvalidToken_ReturnNull() {
        // When / Then
        assertThat(jwtTokenProvider.extractEmail("token.invalido.xyz")).isNull();
        assertThat(jwtTokenProvider.extractEmail("")).isNull();
    }
}
