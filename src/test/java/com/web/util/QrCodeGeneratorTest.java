package com.web.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodeGeneratorTest {

    // Formato esperado: PREFIJO + timestamp en milisegundos + "-" + 6 dígitos aleatorios
    private static final Pattern BAGGAGE_PATTERN = Pattern.compile("^BAG-(\\d{13,})-(\\d{6})$");
    private static final Pattern PARCEL_PATTERN = Pattern.compile("^PCL-(\\d{13,})-(\\d{6})$");
    private static final Pattern TICKET_PATTERN = Pattern.compile("^TKT-(\\d{13,})-(\\d{6})$");
    private static final Pattern BASE64_URL_NO_PADDING = Pattern.compile("^[A-Za-z0-9_-]+$");

    private static final int ITERATIONS = 2_000;

    private QrCodeGenerator qrCodeGenerator;

    @BeforeEach
    void setUp() {
        qrCodeGenerator = new QrCodeGenerator();
    }

    @Test
    void shouldGenerateBaggageTag_WithBagPrefixAndFormat() {
        // Given
        long before = System.currentTimeMillis();

        // When
        String tag = qrCodeGenerator.generateBaggageTag();

        // Then
        long after = System.currentTimeMillis();
        Matcher matcher = BAGGAGE_PATTERN.matcher(tag);
        assertThat(tag).startsWith("BAG-");
        assertThat(matcher.matches()).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isBetween(before, after);
    }

    @Test
    void shouldGenerateParcelCode_WithPclPrefixAndFormat() {
        // Given
        long before = System.currentTimeMillis();

        // When
        String code = qrCodeGenerator.generateParcelCode();

        // Then
        long after = System.currentTimeMillis();
        Matcher matcher = PARCEL_PATTERN.matcher(code);
        assertThat(code).startsWith("PCL-");
        assertThat(matcher.matches()).isTrue();
        assertThat(Long.parseLong(matcher.group(1))).isBetween(before, after);
    }

    @Test
    void shouldGenerateTicketQr_AsUrlSafeBase64WithoutPadding() {
        // When
        String qr = qrCodeGenerator.generateTicketQr();

        // Then
        assertThat(qr).matches(BASE64_URL_NO_PADDING);
        assertThat(qr).doesNotContain("=", "+", "/");
        String decoded = new String(Base64.getUrlDecoder().decode(qr), StandardCharsets.UTF_8);
        assertThat(decoded).startsWith("TKT-");
        assertThat(decoded).matches(TICKET_PATTERN);
    }

    @Test
    void shouldGenerateCodes_AlwaysMatchingFormat() {
        // When / Then: el relleno con ceros debe mantener siempre 6 dígitos
        for (int i = 0; i < ITERATIONS; i++) {
            assertThat(qrCodeGenerator.generateBaggageTag()).matches(BAGGAGE_PATTERN);
            assertThat(qrCodeGenerator.generateParcelCode()).matches(PARCEL_PATTERN);
            String decoded = new String(Base64.getUrlDecoder().decode(qrCodeGenerator.generateTicketQr()),
                    StandardCharsets.UTF_8);
            assertThat(decoded).matches(TICKET_PATTERN);
        }
    }

    @Test
    void shouldGenerateCodes_WithReasonableUniqueness() {
        // Given
        Set<String> tickets = new HashSet<>();
        Set<String> tags = new HashSet<>();
        Set<String> parcels = new HashSet<>();

        // When
        for (int i = 0; i < ITERATIONS; i++) {
            tickets.add(qrCodeGenerator.generateTicketQr());
            tags.add(qrCodeGenerator.generateBaggageTag());
            parcels.add(qrCodeGenerator.generateParcelCode());
        }

        // Then: timestamp + 6 dígitos aleatorios hacen muy improbable una colisión.
        // Se tolera un margen mínimo porque la unicidad es probabilística.
        assertThat(tickets.size()).isGreaterThanOrEqualTo(ITERATIONS - 2);
        assertThat(tags.size()).isGreaterThanOrEqualTo(ITERATIONS - 2);
        assertThat(parcels.size()).isGreaterThanOrEqualTo(ITERATIONS - 2);
    }
}
