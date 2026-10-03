package com.web.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;


@Component
public class QrCodeGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TICKET_PREFIX = "TKT-";
    private static final String BAGGAGE_PREFIX = "BAG-";
    private static final String PARCEL_PREFIX = "PCL-";

    // Genera un código QR único para tickets
    public String generateTicketQr() {
        String rawCode = generateUniqueCode(TICKET_PREFIX, 6);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawCode.getBytes());
    }

    // Genera un código único para etiquetas de equipaje
    public String generateBaggageTag() {
        return generateUniqueCode(BAGGAGE_PREFIX, 6);
    }

    // Genera un código único para encomiendas
    public String generateParcelCode() {
        return generateUniqueCode(PARCEL_PREFIX, 6);
    }

    // Genera un código único combinando prefijo, timestamp y número aleatorio
    private String generateUniqueCode(String prefix, int randomDigits) {
        long timestamp = Instant.now().toEpochMilli();
        int randomBound = (int) Math.pow(10, randomDigits);
        int randomPart = RANDOM.nextInt(randomBound);
        String formatPattern = "%0" + randomDigits + "d";
        return prefix + timestamp + "-" + String.format(formatPattern, randomPart);
    }
}