package com.web.service.admin;

import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.entity.Config;
import com.web.entity.User;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.ConfigRepository;
import com.web.repository.UserRepository;
import com.web.exception.BusinessException;
import org.springframework.http.HttpStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuración del sistema editable por ADMIN (tabla "config", clave → valor).
 * <ul>
 *   <li>"overbooking.percentage" (entero 0-100) y "overbooking.max.percentage" (fracción 0-1) son la MISMA política
 *   de sobreventa expresada de dos formas: actualizar cualquiera de las dos sincroniza la otra, y la compra y la
 *   aprobación de overbooking leen la fracción.</li>
 *   <li>Fee de no-show: "no.show.fee.percentage" (porcentaje del precio del ticket) prevalece sobre el monto fijo
 *   "no.show.fee" cuando es mayor que 0; con 0 se cobra el monto fijo (ver computeNoShowFee).</li>
 *   <li>La política de reembolso debe ser monótona no creciente: 48h >= 24h >= 12h >= 6h >= menos de 6h.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private static final List<String> SUPPORTED_DISCOUNT_TYPES = List.of("STUDENT", "SENIOR", "CHILD");

    // Tipo de dato de cada clave numérica o booleana (el resto se guarda como STRING)
    private static final Set<String> INTEGER_KEYS = Set.of(
            "hold.duration.minutes", "overbooking.percentage", "no.show.fee.percentage",
            "parcel.otp.max.attempts", "hold.max.per.user.trip", "no.show.window.minutes",
            "overbooking.window.minutes");
    private static final Set<String> DECIMAL_KEYS = Set.of(
            "baggage.weight.limit", "baggage.price.per.kg", "baggage.weight.max", "no.show.fee",
            "overbooking.max.percentage", "overbooking.min.occupancy",
            "refund.policy.48hours.percentage", "refund.policy.24hours.percentage",
            "refund.policy.12hours.percentage", "refund.policy.6hours.percentage",
            "refund.policy.less.6hours.percentage", "ticket.base.price",
            "ticket.price.multiplier.peak.hours", "ticket.price.multiplier.high.demand",
            "ticket.price.multiplier.medium.demand");
    private static final Set<String> BOOLEAN_KEYS = Set.of("ticket.dynamic.pricing.default");

    private final ConfigRepository configRepository;
    private final UserRepository userRepository;

    //Obtener toda la configuracion del sistema
    @Override
    @Transactional(readOnly = true)
    public ConfigResponse getConfig() {

        Integer holdDuration = getIntegerConfig("hold.duration.minutes", 10);
        // El porcentaje entero y la fracción máxima son la misma política de sobreventa
        Integer overbookingPercentage = (int) Math.round(getOverbookingMaxPercentage() * 100);
        Integer noShowFeePercentage = getIntegerConfig("no.show.fee.percentage", 10);
        BigDecimal baggageWeightLimit = getDecimalConfig("baggage.weight.limit", BigDecimal.valueOf(20.0));
        BigDecimal baggagePricePerKg = getDecimalConfig("baggage.price.per.kg", BigDecimal.valueOf(2500));

        // Configuraciones adicionales
        BigDecimal noShowFee = getNoShowFee();
        Double overbookingMaxPercentage = getOverbookingMaxPercentage();

        // Políticas de Reembolso
        BigDecimal refund48Hours = getRefundPercentage48Hours();
        BigDecimal refund24Hours = getRefundPercentage24Hours();
        BigDecimal refund12Hours = getRefundPercentage12Hours();
        BigDecimal refund6Hours = getRefundPercentage6Hours();
        BigDecimal refundLess6Hours = getRefundPercentageLess6Hours();

        // Precios de Tickets
        BigDecimal ticketBasePrice = getTicketBasePrice();
        BigDecimal ticketMultiplierPeakHours = getTicketPriceMultiplierPeakHours();
        BigDecimal ticketMultiplierHighDemand = getTicketPriceMultiplierHighDemand();
        BigDecimal ticketMultiplierMediumDemand = getTicketPriceMultiplierMediumDemand();

        // Descuentos desde configuración
        Map<String, Integer> discounts = getDiscountPercentages();

        return new ConfigResponse(
                holdDuration,
                noShowFeePercentage,
                overbookingPercentage,
                discounts,
                baggageWeightLimit,
                baggagePricePerKg,
                noShowFee,
                overbookingMaxPercentage,
                refund48Hours,
                refund24Hours,
                refund12Hours,
                refund6Hours,
                refundLess6Hours,
                ticketBasePrice,
                ticketMultiplierPeakHours,
                ticketMultiplierHighDemand,
                ticketMultiplierMediumDemand,
                LocalDateTime.now(),
                getBaggageWeightMax(),
                getParcelOtpMaxAttempts(),
                getMaxActiveHoldsPerUserAndTrip(),
                getNoShowWindowMinutes(),
                getOverbookingMinOccupancy(),
                getOverbookingWindowMinutes(),
                isDynamicPricingDefault());
    }

    //Actualizar la configuracion
    @Override
    @Transactional
    public ConfigResponse updateConfig(ConfigUpdateRequest request, Long adminUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", adminUserId));

        // Antes de escribir nada: la política de reembolso resultante debe seguir siendo monótona
        validateRefundPolicy(request);

        if (request.holdDurationMinutes() != null) {
            updateConfigValue("hold.duration.minutes", String.valueOf(request.holdDurationMinutes()), admin);
        }

        if (request.overbookingPercentage() != null) {
            updateConfigValue("overbooking.percentage", String.valueOf(request.overbookingPercentage()), admin);
            // La compra y la aprobación de overbooking leen la fracción: se mantienen sincronizadas
            updateConfigValue("overbooking.max.percentage",
                    BigDecimal.valueOf(request.overbookingPercentage()).movePointLeft(2).toPlainString(), admin);
        }

        if (request.noShowFeePercentage() != null) {
            updateConfigValue("no.show.fee.percentage", String.valueOf(request.noShowFeePercentage()), admin);
        }

        if (request.baggageWeightLimit() != null) {
            updateConfigValue("baggage.weight.limit", String.valueOf(request.baggageWeightLimit()), admin);
        }

        if (request.baggagePricePerKg() != null) {
            updateConfigValue("baggage.price.per.kg", String.valueOf(request.baggagePricePerKg()), admin);
        }

        // Descuentos
        if (request.discountPercentages() != null && !request.discountPercentages().isEmpty()) {
            for (Map.Entry<String, Integer> entry : request.discountPercentages().entrySet()) {
                // Solo existen las tarifas especiales niño / estudiante / adulto mayor
                if (!SUPPORTED_DISCOUNT_TYPES.contains(entry.getKey().toUpperCase(java.util.Locale.ROOT))) {
                    throw new BusinessException("Tipo de descuento no válido: " + entry.getKey()
                            + " (válidos: " + String.join(", ", SUPPORTED_DISCOUNT_TYPES) + ")",
                            HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_TYPE");
                }
                String discountKey = "discount.percentage." + entry.getKey().toLowerCase();
                updateConfigValue(discountKey, String.valueOf(entry.getValue()), admin);
            }
        }

        // Configuraciones adicionales
        if (request.noShowFee() != null) {
            updateConfigValue("no.show.fee", String.valueOf(request.noShowFee()), admin);
        }

        if (request.overbookingMaxPercentage() != null) {
            updateConfigValue("overbooking.max.percentage", String.valueOf(request.overbookingMaxPercentage()), admin);
            updateConfigValue("overbooking.percentage",
                    String.valueOf(Math.round(request.overbookingMaxPercentage() * 100)), admin);
        }

        // Políticas de Reembolso
        if (request.refundPercentage48Hours() != null) {
            updateConfigValue("refund.policy.48hours.percentage",
                    String.valueOf(request.refundPercentage48Hours()), admin);
        }

        if (request.refundPercentage24Hours() != null) {
            updateConfigValue("refund.policy.24hours.percentage",
                    String.valueOf(request.refundPercentage24Hours()), admin);
        }

        if (request.refundPercentage12Hours() != null) {
            updateConfigValue("refund.policy.12hours.percentage",
                    String.valueOf(request.refundPercentage12Hours()), admin);
        }

        if (request.refundPercentage6Hours() != null) {
            updateConfigValue("refund.policy.6hours.percentage",
                    String.valueOf(request.refundPercentage6Hours()), admin);
        }

        if (request.refundPercentageLess6Hours() != null) {
            updateConfigValue("refund.policy.less.6hours.percentage",
                    String.valueOf(request.refundPercentageLess6Hours()), admin);
        }

        // Precios de Tickets
        if (request.ticketBasePrice() != null) {
            updateConfigValue("ticket.base.price",
                    String.valueOf(request.ticketBasePrice()), admin);
        }

        if (request.ticketPriceMultiplierPeakHours() != null) {
            updateConfigValue("ticket.price.multiplier.peak.hours",
                    String.valueOf(request.ticketPriceMultiplierPeakHours()), admin);
        }

        if (request.ticketPriceMultiplierHighDemand() != null) {
            updateConfigValue("ticket.price.multiplier.high.demand",
                    String.valueOf(request.ticketPriceMultiplierHighDemand()), admin);
        }

        if (request.ticketPriceMultiplierMediumDemand() != null) {
            updateConfigValue("ticket.price.multiplier.medium.demand",
                    String.valueOf(request.ticketPriceMultiplierMediumDemand()), admin);
        }

        // Límites operativos
        if (request.baggageWeightMax() != null) {
            updateConfigValue("baggage.weight.max", String.valueOf(request.baggageWeightMax()), admin);
        }

        if (request.parcelOtpMaxAttempts() != null) {
            updateConfigValue("parcel.otp.max.attempts", String.valueOf(request.parcelOtpMaxAttempts()), admin);
        }

        if (request.maxActiveHoldsPerUserAndTrip() != null) {
            updateConfigValue("hold.max.per.user.trip", String.valueOf(request.maxActiveHoldsPerUserAndTrip()), admin);
        }

        if (request.noShowWindowMinutes() != null) {
            updateConfigValue("no.show.window.minutes", String.valueOf(request.noShowWindowMinutes()), admin);
        }

        if (request.overbookingMinOccupancy() != null) {
            updateConfigValue("overbooking.min.occupancy", String.valueOf(request.overbookingMinOccupancy()), admin);
        }

        if (request.overbookingWindowMinutes() != null) {
            updateConfigValue("overbooking.window.minutes", String.valueOf(request.overbookingWindowMinutes()), admin);
        }

        if (request.dynamicPricingDefault() != null) {
            updateConfigValue("ticket.dynamic.pricing.default", String.valueOf(request.dynamicPricingDefault()), admin);
        }

        return getConfig();
    }

    // Reembolso monótono no creciente (48h >= 24h >= 12h >= 6h >= <6h) con los valores enviados
    // y, para los que no vienen, los vigentes
    private void validateRefundPolicy(ConfigUpdateRequest request) {
        if (request.refundPercentage48Hours() == null && request.refundPercentage24Hours() == null
                && request.refundPercentage12Hours() == null && request.refundPercentage6Hours() == null
                && request.refundPercentageLess6Hours() == null) {
            return;
        }
        List<BigDecimal> policy = List.of(
                request.refundPercentage48Hours() != null ? request.refundPercentage48Hours() : getRefundPercentage48Hours(),
                request.refundPercentage24Hours() != null ? request.refundPercentage24Hours() : getRefundPercentage24Hours(),
                request.refundPercentage12Hours() != null ? request.refundPercentage12Hours() : getRefundPercentage12Hours(),
                request.refundPercentage6Hours() != null ? request.refundPercentage6Hours() : getRefundPercentage6Hours(),
                request.refundPercentageLess6Hours() != null ? request.refundPercentageLess6Hours() : getRefundPercentageLess6Hours());
        for (int i = 1; i < policy.size(); i++) {
            if (policy.get(i).compareTo(policy.get(i - 1)) > 0) {
                throw new BusinessException("La política de reembolso debe ser no creciente: 48h >= 24h >= 12h >= 6h >= menos de 6h"
                        + " (resultado: " + policy.stream().map(BigDecimal::toPlainString).toList() + ")",
                        HttpStatus.BAD_REQUEST, "REFUND_POLICY_NOT_MONOTONIC");
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getHoldDurationMinutes() {
        return getIntegerConfig("hold.duration.minutes", 10);
    }

    @Override
    @Transactional(readOnly = true)
    public Double getBaggageWeightLimit() {
        return getDoubleConfig("baggage.weight.limit", 20.0);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getExcessFeePerKg() {
        return getDecimalConfig("baggage.price.per.kg", BigDecimal.valueOf(2500));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getNoShowFee() {
        return getDecimalConfig("no.show.fee", BigDecimal.valueOf(10000));
    }

    @Override
    @Transactional(readOnly = true)
    public Double getOverbookingMaxPercentage() {
        return getDoubleConfig("overbooking.max.percentage", 0.05);
    }

    // Políticas de Reembolso
    @Override
    @Transactional(readOnly = true)
    public BigDecimal getRefundPercentage48Hours() {
        return getDecimalConfig("refund.policy.48hours.percentage", BigDecimal.valueOf(90));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getRefundPercentage24Hours() {
        return getDecimalConfig("refund.policy.24hours.percentage", BigDecimal.valueOf(70));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getRefundPercentage12Hours() {
        return getDecimalConfig("refund.policy.12hours.percentage", BigDecimal.valueOf(50));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getRefundPercentage6Hours() {
        return getDecimalConfig("refund.policy.6hours.percentage", BigDecimal.valueOf(30));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getRefundPercentageLess6Hours() {
        return getDecimalConfig("refund.policy.less.6hours.percentage", BigDecimal.ZERO);
    }

    // Precios de Tickets
    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTicketBasePrice() {
        return getDecimalConfig("ticket.base.price", BigDecimal.valueOf(50000));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTicketPriceMultiplierPeakHours() {
        return getDecimalConfig("ticket.price.multiplier.peak.hours", BigDecimal.valueOf(1.15));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTicketPriceMultiplierHighDemand() {
        return getDecimalConfig("ticket.price.multiplier.high.demand", BigDecimal.valueOf(1.2));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getTicketPriceMultiplierMediumDemand() {
        return getDecimalConfig("ticket.price.multiplier.medium.demand", BigDecimal.valueOf(1.1));
    }

    @Override
    @Transactional(readOnly = true)
    public Double getBaggageWeightMax() {
        return getDoubleConfig("baggage.weight.max", 50.0);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getParcelOtpMaxAttempts() {
        return getIntegerConfig("parcel.otp.max.attempts", 3);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getMaxActiveHoldsPerUserAndTrip() {
        return getIntegerConfig("hold.max.per.user.trip", 4);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getNoShowWindowMinutes() {
        return getIntegerConfig("no.show.window.minutes", 5);
    }

    @Override
    @Transactional(readOnly = true)
    public Double getOverbookingMinOccupancy() {
        return getDoubleConfig("overbooking.min.occupancy", 0.95);
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getOverbookingWindowMinutes() {
        return getIntegerConfig("overbooking.window.minutes", 30);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDynamicPricingDefault() {
        return configRepository.findByConfigKey("ticket.dynamic.pricing.default")
                .map(config -> Boolean.parseBoolean(config.getConfigValue()))
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal computeNoShowFee(BigDecimal ticketPrice) {
        Integer percentage = getIntegerConfig("no.show.fee.percentage", 0);
        if (percentage != null && percentage > 0 && ticketPrice != null) {
            return ticketPrice.multiply(BigDecimal.valueOf(percentage))
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        }
        return getNoShowFee();
    }

    private Integer getIntegerConfig(String key, Integer fallback) {
        return configRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Integer.parseInt(config.getConfigValue());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private Double getDoubleConfig(String key, Double fallback) {
        return configRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return Double.parseDouble(config.getConfigValue());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private BigDecimal getDecimalConfig(String key, BigDecimal fallback) {
        return configRepository.findByConfigKey(key)
                .map(config -> {
                    try {
                        return new BigDecimal(config.getConfigValue());
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private void updateConfigValue(String key, String value, User updatedBy) {
        Config config = configRepository.findByConfigKey(key)
                .orElseGet(() -> Config.builder()
                        .configKey(key)
                        .build());

        // El tipo depende de la clave (INTEGER/DECIMAL/BOOLEAN); también corrige filas antiguas guardadas como STRING
        config.setDataType(dataTypeOf(key));
        config.setConfigValue(value);
        config.setUpdatedAt(LocalDateTime.now());
        config.setUpdatedBy(updatedBy);

        configRepository.save(config);

    }

    static Config.DataType dataTypeOf(String key) {
        if (INTEGER_KEYS.contains(key) || key.startsWith("discount.percentage.")) {
            return Config.DataType.INTEGER;
        }
        if (DECIMAL_KEYS.contains(key)) {
            return Config.DataType.DECIMAL;
        }
        if (BOOLEAN_KEYS.contains(key)) {
            return Config.DataType.BOOLEAN;
        }
        return Config.DataType.STRING;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> getDiscountPercentages() {
        Map<String, Integer> discounts = new HashMap<>();
        // Valores por defecto
        discounts.put("STUDENT", getDiscountPercentage("STUDENT", 20));
        discounts.put("SENIOR", getDiscountPercentage("SENIOR", 15));
        discounts.put("CHILD", getDiscountPercentage("CHILD", 50));
        return discounts;
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getDiscountPercentage(String discountType, Integer fallback) {
        String key = "discount.percentage." + discountType.toLowerCase();
        return getIntegerConfig(key, fallback);
    }
}
