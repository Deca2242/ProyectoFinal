package com.web.service.admin;

import com.web.dto.admin.ConfigResponse;
import com.web.dto.admin.ConfigUpdateRequest;
import com.web.entity.Config;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.ConfigRepository;
import com.web.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class ConfigServiceImplTest {

    @Mock
    private ConfigRepository configRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ConfigServiceImpl configService;

    private User admin;
    private Config config;

    @BeforeEach
    void setUp() {
        admin = User.builder()
                .id(1L)
                .name("Admin")
                .email("admin@example.com")
                .role(User.Role.ADMIN)
                .build();

        config = Config.builder()
                .id(1L)
                .configKey("hold.duration.minutes")
                .configValue("10")
                .dataType(Config.DataType.INTEGER)
                .build();
    }

    @Test
    void shouldGetConfig_ReturnAllConfigValues() {
        // Given
        when(configRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());

        // When
        ConfigResponse result = configService.getConfig();

        // Then
        assertThat(result).isNotNull();
        assertThat(result.holdDurationMinutes()).isEqualTo(10);
        assertThat(result.overbookingMaxPercentage()).isEqualTo(0.05);
    }

    @Test
    void shouldUpdateConfig_WithValidRequest_UpdateValues() {
        // Given
        ConfigUpdateRequest request = new ConfigUpdateRequest(
                15, 10, 5, new HashMap<>(), BigDecimal.valueOf(25.0),
                BigDecimal.valueOf(6000), BigDecimal.valueOf(15000), 0.1,
                BigDecimal.valueOf(95), BigDecimal.valueOf(75), BigDecimal.valueOf(55),
                BigDecimal.valueOf(35), BigDecimal.ZERO,
                BigDecimal.valueOf(55000), BigDecimal.valueOf(1.2),
                BigDecimal.valueOf(1.3), BigDecimal.valueOf(1.15), null, null, null, null, null, null, null
        );

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(configRepository.findByConfigKey(anyString())).thenReturn(Optional.of(config));
        when(configRepository.save(any(Config.class))).thenReturn(config);

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then
        assertThat(result).isNotNull();
        verify(configRepository, atLeastOnce()).save(any(Config.class));
    }

    @Test
    void shouldGetRefundPercentage48Hours_ReturnConfiguredValue() {
        // Given
        Config refundConfig = Config.builder()
                .configKey("refund.policy.48hours.percentage")
                .configValue("95")
                .dataType(Config.DataType.DECIMAL)
                .build();

        when(configRepository.findByConfigKey("refund.policy.48hours.percentage"))
                .thenReturn(Optional.of(refundConfig));

        // When
        BigDecimal result = configService.getRefundPercentage48Hours();

        // Then
        assertThat(result).isEqualTo(BigDecimal.valueOf(95));
    }

    @Test
    void shouldGetTicketBasePrice_ReturnConfiguredValue() {
        // Given
        Config priceConfig = Config.builder()
                .configKey("ticket.base.price")
                .configValue("55000")
                .dataType(Config.DataType.DECIMAL)
                .build();

        when(configRepository.findByConfigKey("ticket.base.price"))
                .thenReturn(Optional.of(priceConfig));

        // When
        BigDecimal result = configService.getTicketBasePrice();

        // Then
        assertThat(result).isEqualTo(BigDecimal.valueOf(55000));
    }

    @Test
    void shouldGetOverbookingMaxPercentage_ReturnConfiguredValue() {
        // Given
        Config overbookingConfig = Config.builder()
                .configKey("overbooking.max.percentage")
                .configValue("0.1")
                .dataType(Config.DataType.DECIMAL)
                .build();

        when(configRepository.findByConfigKey("overbooking.max.percentage"))
                .thenReturn(Optional.of(overbookingConfig));

        // When
        Double result = configService.getOverbookingMaxPercentage();

        // Then
        assertThat(result).isEqualTo(0.1);
    }

    // ------------------------------------------------------------------
    // Getters individuales: valor configurado, clave ausente y valor no numérico
    // ------------------------------------------------------------------

    // Casos: clave, getter, valor guardado, valor esperado al parsear, valor por defecto
    static Stream<Arguments> getterCases() {
        return Stream.of(
                Arguments.of("hold.duration.minutes",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getHoldDurationMinutes,
                        "15", 15, 10),
                Arguments.of("baggage.weight.limit",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getBaggageWeightLimit,
                        "25.5", 25.5, 20.0),
                Arguments.of("baggage.price.per.kg",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getExcessFeePerKg,
                        "3000", new BigDecimal("3000"), BigDecimal.valueOf(2500)),
                Arguments.of("no.show.fee",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getNoShowFee,
                        "12000", new BigDecimal("12000"), BigDecimal.valueOf(10000)),
                Arguments.of("overbooking.max.percentage",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getOverbookingMaxPercentage,
                        "0.1", 0.1, 0.05),
                Arguments.of("refund.policy.48hours.percentage",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getRefundPercentage48Hours,
                        "95", new BigDecimal("95"), BigDecimal.valueOf(90)),
                Arguments.of("refund.policy.24hours.percentage",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getRefundPercentage24Hours,
                        "75", new BigDecimal("75"), BigDecimal.valueOf(70)),
                Arguments.of("refund.policy.12hours.percentage",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getRefundPercentage12Hours,
                        "55", new BigDecimal("55"), BigDecimal.valueOf(50)),
                Arguments.of("refund.policy.6hours.percentage",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getRefundPercentage6Hours,
                        "35", new BigDecimal("35"), BigDecimal.valueOf(30)),
                Arguments.of("refund.policy.less.6hours.percentage",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getRefundPercentageLess6Hours,
                        "5", new BigDecimal("5"), BigDecimal.ZERO),
                Arguments.of("ticket.base.price",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getTicketBasePrice,
                        "60000", new BigDecimal("60000"), BigDecimal.valueOf(50000)),
                Arguments.of("ticket.price.multiplier.peak.hours",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getTicketPriceMultiplierPeakHours,
                        "1.25", new BigDecimal("1.25"), BigDecimal.valueOf(1.15)),
                Arguments.of("ticket.price.multiplier.high.demand",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getTicketPriceMultiplierHighDemand,
                        "1.3", new BigDecimal("1.3"), BigDecimal.valueOf(1.2)),
                Arguments.of("ticket.price.multiplier.medium.demand",
                        (Function<ConfigServiceImpl, Object>) ConfigServiceImpl::getTicketPriceMultiplierMediumDemand,
                        "1.15", new BigDecimal("1.15"), BigDecimal.valueOf(1.1))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("getterCases")
    void shouldGetConfigValue_WithConfiguredValue_ReturnParsedValue(
            String key, Function<ConfigServiceImpl, Object> getter, String rawValue, Object expected, Object fallback) {
        // Given
        when(configRepository.findByConfigKey(key)).thenReturn(Optional.of(configWith(key, rawValue)));

        // When
        Object result = getter.apply(configService);

        // Then
        assertThat(result).isEqualTo(expected);
        verify(configRepository).findByConfigKey(key);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("getterCases")
    void shouldGetConfigValue_WithMissingKey_ReturnFallback(
            String key, Function<ConfigServiceImpl, Object> getter, String rawValue, Object expected, Object fallback) {
        // Given
        when(configRepository.findByConfigKey(key)).thenReturn(Optional.empty());

        // When
        Object result = getter.apply(configService);

        // Then
        assertThat(result).isEqualTo(fallback);
        verify(configRepository).findByConfigKey(key);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("getterCases")
    void shouldGetConfigValue_WithNonNumericValue_ReturnFallback(
            String key, Function<ConfigServiceImpl, Object> getter, String rawValue, Object expected, Object fallback) {
        // Given: un valor corrupto no debe romper el servicio, se usa el valor por defecto
        when(configRepository.findByConfigKey(key)).thenReturn(Optional.of(configWith(key, "no-numerico")));

        // When
        Object result = getter.apply(configService);

        // Then
        assertThat(result).isEqualTo(fallback);
    }

    @Test
    void shouldGetHoldDurationMinutes_WithDecimalValue_ReturnFallback() {
        // Given: un decimal no es un entero válido para Integer.parseInt
        when(configRepository.findByConfigKey("hold.duration.minutes"))
                .thenReturn(Optional.of(configWith("hold.duration.minutes", "12.5")));

        // When
        Integer result = configService.getHoldDurationMinutes();

        // Then
        assertThat(result).isEqualTo(10);
    }

    // ------------------------------------------------------------------
    // getConfig
    // ------------------------------------------------------------------

    @Test
    void shouldGetConfig_WithoutStoredValues_ReturnAllDefaults() {
        // Given
        stubConfigs(Map.of());

        // When
        ConfigResponse result = configService.getConfig();

        // Then
        assertThat(result.holdDurationMinutes()).isEqualTo(10);
        assertThat(result.overbookingPercentage()).isEqualTo(5);
        assertThat(result.noShowFeePercentage()).isEqualTo(10);
        assertThat(result.baggageWeightLimit()).isEqualByComparingTo("20");
        assertThat(result.baggagePricePerKg()).isEqualByComparingTo("2500");
        assertThat(result.noShowFee()).isEqualByComparingTo("10000");
        assertThat(result.overbookingMaxPercentage()).isEqualTo(0.05);
        assertThat(result.refundPercentage48Hours()).isEqualByComparingTo("90");
        assertThat(result.refundPercentage24Hours()).isEqualByComparingTo("70");
        assertThat(result.refundPercentage12Hours()).isEqualByComparingTo("50");
        assertThat(result.refundPercentage6Hours()).isEqualByComparingTo("30");
        assertThat(result.refundPercentageLess6Hours()).isEqualByComparingTo("0");
        assertThat(result.ticketBasePrice()).isEqualByComparingTo("50000");
        assertThat(result.ticketPriceMultiplierPeakHours()).isEqualByComparingTo("1.15");
        assertThat(result.ticketPriceMultiplierHighDemand()).isEqualByComparingTo("1.2");
        assertThat(result.ticketPriceMultiplierMediumDemand()).isEqualByComparingTo("1.1");
        assertThat(result.discountPercentages())
                .containsExactlyInAnyOrderEntriesOf(Map.of("STUDENT", 20, "SENIOR", 15, "CHILD", 50));
        assertThat(result.lastUpdated()).isNotNull();
    }

    @Test
    void shouldGetConfig_WithStoredValues_ReturnConfiguredValues() {
        // Given
        Map<String, String> values = new HashMap<>();
        values.put("hold.duration.minutes", "12");
        // Misma política que overbooking.max.percentage (0.08): el entero se deriva de la fracción
        values.put("overbooking.percentage", "8");
        values.put("no.show.fee.percentage", "15");
        values.put("baggage.weight.limit", "25");
        values.put("baggage.price.per.kg", "3000");
        values.put("no.show.fee", "20000");
        values.put("overbooking.max.percentage", "0.08");
        values.put("refund.policy.48hours.percentage", "95");
        values.put("refund.policy.24hours.percentage", "75");
        values.put("refund.policy.12hours.percentage", "55");
        values.put("refund.policy.6hours.percentage", "35");
        values.put("refund.policy.less.6hours.percentage", "5");
        values.put("ticket.base.price", "60000");
        values.put("ticket.price.multiplier.peak.hours", "1.25");
        values.put("ticket.price.multiplier.high.demand", "1.3");
        values.put("ticket.price.multiplier.medium.demand", "1.15");
        values.put("discount.percentage.student", "25");
        values.put("discount.percentage.senior", "10");
        values.put("discount.percentage.child", "40");
        values.put("baggage.weight.max", "45.5");
        values.put("parcel.otp.max.attempts", "5");
        values.put("hold.max.per.user.trip", "6");
        values.put("no.show.window.minutes", "7");
        values.put("overbooking.min.occupancy", "0.9");
        values.put("overbooking.window.minutes", "45");
        values.put("ticket.dynamic.pricing.default", "true");
        stubConfigs(values);

        // When
        ConfigResponse result = configService.getConfig();

        // Then
        assertThat(result.holdDurationMinutes()).isEqualTo(12);
        assertThat(result.overbookingPercentage()).isEqualTo(8);
        assertThat(result.baggageWeightMax()).isEqualTo(45.5);
        assertThat(result.parcelOtpMaxAttempts()).isEqualTo(5);
        assertThat(result.maxActiveHoldsPerUserAndTrip()).isEqualTo(6);
        assertThat(result.noShowWindowMinutes()).isEqualTo(7);
        assertThat(result.overbookingMinOccupancy()).isEqualTo(0.9);
        assertThat(result.overbookingWindowMinutes()).isEqualTo(45);
        assertThat(result.dynamicPricingDefault()).isTrue();
        assertThat(result.noShowFeePercentage()).isEqualTo(15);
        assertThat(result.baggageWeightLimit()).isEqualByComparingTo("25");
        assertThat(result.baggagePricePerKg()).isEqualByComparingTo("3000");
        assertThat(result.noShowFee()).isEqualByComparingTo("20000");
        assertThat(result.overbookingMaxPercentage()).isEqualTo(0.08);
        assertThat(result.refundPercentage48Hours()).isEqualByComparingTo("95");
        assertThat(result.refundPercentage24Hours()).isEqualByComparingTo("75");
        assertThat(result.refundPercentage12Hours()).isEqualByComparingTo("55");
        assertThat(result.refundPercentage6Hours()).isEqualByComparingTo("35");
        assertThat(result.refundPercentageLess6Hours()).isEqualByComparingTo("5");
        assertThat(result.ticketBasePrice()).isEqualByComparingTo("60000");
        assertThat(result.ticketPriceMultiplierPeakHours()).isEqualByComparingTo("1.25");
        assertThat(result.ticketPriceMultiplierHighDemand()).isEqualByComparingTo("1.3");
        assertThat(result.ticketPriceMultiplierMediumDemand()).isEqualByComparingTo("1.15");
        assertThat(result.discountPercentages())
                .containsExactlyInAnyOrderEntriesOf(Map.of("STUDENT", 25, "SENIOR", 10, "CHILD", 40));
    }

    // ------------------------------------------------------------------
    // updateConfig
    // ------------------------------------------------------------------

    @Test
    void shouldUpdateConfig_WithNonExistentAdmin_ThrowResourceNotFoundException() {
        // Given
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.holdDurationMinutes = 15).build();
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        // When/Then
        assertThatThrownBy(() -> configService.updateConfig(request, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(configRepository);
    }

    @Test
    void shouldUpdateConfig_WithAllNullRequest_NotSaveAnything() {
        // Given
        ConfigUpdateRequest request = new RequestBuilder().build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        stubConfigs(Map.of());

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then: no se persiste nada y se devuelve la configuración vigente
        verify(configRepository, never()).save(any(Config.class));
        assertThat(result.holdDurationMinutes()).isEqualTo(10);
        assertThat(result.discountPercentages()).containsEntry("STUDENT", 20);
    }

    // Casos: clave persistida, campo del request a modificar, valor esperado como texto y tipo de dato.
    // Las dos claves de overbooking se prueban aparte porque se sincronizan entre sí
    static Stream<Arguments> singleFieldUpdateCases() {
        return Stream.of(
                Arguments.of("hold.duration.minutes", field(b -> b.holdDurationMinutes = 15), "15", Config.DataType.INTEGER),
                Arguments.of("no.show.fee.percentage", field(b -> b.noShowFeePercentage = 12), "12", Config.DataType.INTEGER),
                Arguments.of("baggage.weight.limit", field(b -> b.baggageWeightLimit = new BigDecimal("25.5")), "25.5", Config.DataType.DECIMAL),
                Arguments.of("baggage.price.per.kg", field(b -> b.baggagePricePerKg = new BigDecimal("3000")), "3000", Config.DataType.DECIMAL),
                Arguments.of("no.show.fee", field(b -> b.noShowFee = new BigDecimal("15000")), "15000", Config.DataType.DECIMAL),
                Arguments.of("refund.policy.48hours.percentage", field(b -> b.refund48 = new BigDecimal("95")), "95", Config.DataType.DECIMAL),
                Arguments.of("refund.policy.24hours.percentage", field(b -> b.refund24 = new BigDecimal("75")), "75", Config.DataType.DECIMAL),
                Arguments.of("refund.policy.12hours.percentage", field(b -> b.refund12 = new BigDecimal("55")), "55", Config.DataType.DECIMAL),
                Arguments.of("refund.policy.6hours.percentage", field(b -> b.refund6 = new BigDecimal("35")), "35", Config.DataType.DECIMAL),
                Arguments.of("refund.policy.less.6hours.percentage", field(b -> b.refundLess6 = new BigDecimal("5")), "5", Config.DataType.DECIMAL),
                Arguments.of("ticket.base.price", field(b -> b.ticketBasePrice = new BigDecimal("55000")), "55000", Config.DataType.DECIMAL),
                Arguments.of("ticket.price.multiplier.peak.hours", field(b -> b.peak = new BigDecimal("1.25")), "1.25", Config.DataType.DECIMAL),
                Arguments.of("ticket.price.multiplier.high.demand", field(b -> b.high = new BigDecimal("1.3")), "1.3", Config.DataType.DECIMAL),
                Arguments.of("ticket.price.multiplier.medium.demand", field(b -> b.medium = new BigDecimal("1.15")), "1.15", Config.DataType.DECIMAL),
                Arguments.of("baggage.weight.max", field(b -> b.baggageWeightMax = 40.0), "40.0", Config.DataType.DECIMAL),
                Arguments.of("parcel.otp.max.attempts", field(b -> b.parcelOtpMaxAttempts = 4), "4", Config.DataType.INTEGER),
                Arguments.of("hold.max.per.user.trip", field(b -> b.maxActiveHolds = 2), "2", Config.DataType.INTEGER),
                Arguments.of("no.show.window.minutes", field(b -> b.noShowWindowMinutes = 10), "10", Config.DataType.INTEGER),
                Arguments.of("overbooking.min.occupancy", field(b -> b.overbookingMinOccupancy = 0.9), "0.9", Config.DataType.DECIMAL),
                Arguments.of("overbooking.window.minutes", field(b -> b.overbookingWindowMinutes = 20), "20", Config.DataType.INTEGER),
                Arguments.of("ticket.dynamic.pricing.default", field(b -> b.dynamicPricingDefault = true), "true", Config.DataType.BOOLEAN)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleFieldUpdateCases")
    void shouldUpdateConfig_WithSingleField_CreateNewConfigForThatKey(
            String expectedKey, Consumer<RequestBuilder> field, String expectedValue, Config.DataType expectedType) {
        // Given: la clave aún no existe en BD, así que se crea una Config nueva
        ConfigUpdateRequest request = new RequestBuilder().with(field).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        stubConfigs(Map.of());
        ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);

        // When
        configService.updateConfig(request, 1L);

        // Then: se guarda exactamente una configuración con la clave y valor esperados
        verify(configRepository).save(captor.capture());
        Config saved = captor.getValue();
        assertThat(saved.getId()).isNull();
        assertThat(saved.getConfigKey()).isEqualTo(expectedKey);
        assertThat(saved.getConfigValue()).isEqualTo(expectedValue);
        // El tipo de dato depende de la clave (antes todas las claves nuevas se guardaban como STRING)
        assertThat(saved.getDataType()).isEqualTo(expectedType);
        assertThat(saved.getUpdatedBy()).isSameAs(admin);
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldUpdateConfig_WithOverbookingPercentage_SyncMaxPercentageFraction() {
        // Given: porcentaje entero y fracción son la misma política
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.overbookingPercentage = 8).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        useInMemoryStore();

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then
        ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);
        verify(configRepository, times(2)).save(captor.capture());
        Map<String, Config> saved = captor.getAllValues().stream()
                .collect(Collectors.toMap(Config::getConfigKey, Function.identity()));
        assertThat(saved.get("overbooking.percentage").getConfigValue()).isEqualTo("8");
        assertThat(saved.get("overbooking.percentage").getDataType()).isEqualTo(Config.DataType.INTEGER);
        assertThat(saved.get("overbooking.max.percentage").getConfigValue()).isEqualTo("0.08");
        assertThat(saved.get("overbooking.max.percentage").getDataType()).isEqualTo(Config.DataType.DECIMAL);
        assertThat(result.overbookingPercentage()).isEqualTo(8);
        assertThat(result.overbookingMaxPercentage()).isEqualTo(0.08);
    }

    @Test
    void shouldUpdateConfig_WithOverbookingMaxPercentage_SyncIntegerPercentage() {
        // Given
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.overbookingMaxPercentage = 0.1).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        useInMemoryStore();

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then
        verify(configRepository, times(2)).save(any(Config.class));
        assertThat(result.overbookingMaxPercentage()).isEqualTo(0.1);
        assertThat(result.overbookingPercentage()).isEqualTo(10);
    }

    @Test
    void shouldUpdateConfig_WithExistingStringRow_FixDataType() {
        // Given: una fila antigua guardada como STRING para una clave numérica
        Config legacy = configWith("baggage.weight.max", "50.0");
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.baggageWeightMax = 35.0).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(configRepository.findByConfigKey(anyString())).thenAnswer(inv ->
                "baggage.weight.max".equals(inv.getArgument(0)) ? Optional.of(legacy) : Optional.empty());

        // When
        configService.updateConfig(request, 1L);

        // Then
        verify(configRepository).save(legacy);
        assertThat(legacy.getConfigValue()).isEqualTo("35.0");
        assertThat(legacy.getDataType()).isEqualTo(Config.DataType.DECIMAL);
    }

    @Test
    void shouldResolveDataType_ByKey() {
        // When/Then
        assertThat(ConfigServiceImpl.dataTypeOf("hold.duration.minutes")).isEqualTo(Config.DataType.INTEGER);
        assertThat(ConfigServiceImpl.dataTypeOf("discount.percentage.student")).isEqualTo(Config.DataType.INTEGER);
        assertThat(ConfigServiceImpl.dataTypeOf("refund.policy.6hours.percentage")).isEqualTo(Config.DataType.DECIMAL);
        assertThat(ConfigServiceImpl.dataTypeOf("ticket.dynamic.pricing.default")).isEqualTo(Config.DataType.BOOLEAN);
        assertThat(ConfigServiceImpl.dataTypeOf("seed.hardened.at")).isEqualTo(Config.DataType.STRING);
    }

    // ------------------------------------------------------------------
    // Política de reembolso monótona
    // ------------------------------------------------------------------

    @Test
    void shouldUpdateConfig_WithRefundAboveCurrentLongerWindow_ThrowNotMonotonic() {
        // Given: 24h = 95 % supera al 48h vigente (90 % por defecto)
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.refund24 = new BigDecimal("95")).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        stubConfigs(Map.of());

        // When/Then
        assertThatThrownBy(() -> configService.updateConfig(request, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("REFUND_POLICY_NOT_MONOTONIC");
                });
        verify(configRepository, never()).save(any(Config.class));
    }

    @Test
    void shouldUpdateConfig_WithRefundBelowStoredShorterWindow_ThrowNotMonotonic() {
        // Given: en BD el 6h vale 40 %; bajar el 12h a 35 % rompe 12h >= 6h
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.refund12 = new BigDecimal("35")).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        stubConfigs(Map.of("refund.policy.6hours.percentage", "40"));

        // When/Then
        assertThatThrownBy(() -> configService.updateConfig(request, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no creciente");
        verify(configRepository, never()).save(any(Config.class));
    }

    @Test
    void shouldUpdateConfig_WithWholeRefundPolicyEqualValues_Accept() {
        // Given: valores iguales cumplen la regla (no creciente) aunque los vigentes no la cumplieran
        ConfigUpdateRequest request = new RequestBuilder().with(b -> {
            b.refund48 = new BigDecimal("50");
            b.refund24 = new BigDecimal("50");
            b.refund12 = new BigDecimal("50");
            b.refund6 = new BigDecimal("50");
            b.refundLess6 = new BigDecimal("50");
        }).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        useInMemoryStore();

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then
        verify(configRepository, times(5)).save(any(Config.class));
        assertThat(result.refundPercentageLess6Hours()).isEqualByComparingTo("50");
    }

    @Test
    void shouldUpdateConfig_WithExistingKey_UpdateExistingConfig() {
        // Given: la clave ya existe, se debe reutilizar la misma entidad
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.holdDurationMinutes = 20).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(configRepository.findByConfigKey(anyString())).thenAnswer(inv ->
                "hold.duration.minutes".equals(inv.getArgument(0)) ? Optional.of(config) : Optional.empty());
        ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then
        verify(configRepository).save(captor.capture());
        Config saved = captor.getValue();
        assertThat(saved).isSameAs(config);
        assertThat(saved.getId()).isEqualTo(1L);
        assertThat(saved.getConfigValue()).isEqualTo("20");
        assertThat(saved.getDataType()).isEqualTo(Config.DataType.INTEGER);
        assertThat(saved.getUpdatedBy()).isSameAs(admin);
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(result.holdDurationMinutes()).isEqualTo(20);
    }

    @Test
    void shouldUpdateConfig_WithDiscounts_SaveLowercaseKeys() {
        // Given
        Map<String, Integer> discounts = new LinkedHashMap<>();
        discounts.put("STUDENT", 25);
        discounts.put("Senior", 10);
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.discounts = discounts).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        useInMemoryStore();
        ArgumentCaptor<Config> captor = ArgumentCaptor.forClass(Config.class);

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then
        verify(configRepository, times(2)).save(captor.capture());
        Map<String, String> saved = captor.getAllValues().stream()
                .collect(Collectors.toMap(Config::getConfigKey, Config::getConfigValue));
        assertThat(saved).containsExactlyInAnyOrderEntriesOf(Map.of(
                "discount.percentage.student", "25",
                "discount.percentage.senior", "10"));
        assertThat(result.discountPercentages())
                .containsExactlyInAnyOrderEntriesOf(Map.of("STUDENT", 25, "SENIOR", 10, "CHILD", 50));
    }

    @Test
    void shouldUpdateConfig_WithEmptyDiscounts_NotSaveDiscounts() {
        // Given
        ConfigUpdateRequest request = new RequestBuilder().with(b -> b.discounts = new HashMap<>()).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        stubConfigs(Map.of());

        // When
        configService.updateConfig(request, 1L);

        // Then
        verify(configRepository, never()).save(any(Config.class));
    }

    @Test
    void shouldUpdateConfig_WithAllFields_ReturnUpdatedConfig() {
        // Given: request completo contra un almacén en memoria (lo que se escribe se vuelve a leer)
        ConfigUpdateRequest request = new RequestBuilder().with(b -> {
            b.holdDurationMinutes = 15;
            b.noShowFeePercentage = 12;
            b.overbookingPercentage = 8;
            b.discounts = Map.of("CHILD", 40);
            b.baggageWeightLimit = new BigDecimal("25");
            b.baggagePricePerKg = new BigDecimal("3000");
            b.noShowFee = new BigDecimal("15000");
            b.overbookingMaxPercentage = 0.1;
            b.refund48 = new BigDecimal("95");
            b.refund24 = new BigDecimal("75");
            b.refund12 = new BigDecimal("55");
            b.refund6 = new BigDecimal("35");
            b.refundLess6 = new BigDecimal("5");
            b.ticketBasePrice = new BigDecimal("55000");
            b.peak = new BigDecimal("1.25");
            b.high = new BigDecimal("1.3");
            b.medium = new BigDecimal("1.15");
            b.baggageWeightMax = 40.0;
            b.parcelOtpMaxAttempts = 4;
            b.maxActiveHolds = 2;
            b.noShowWindowMinutes = 10;
            b.overbookingMinOccupancy = 0.9;
            b.overbookingWindowMinutes = 20;
            b.dynamicPricingDefault = true;
        }).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        useInMemoryStore();

        // When
        ConfigResponse result = configService.updateConfig(request, 1L);

        // Then: 23 campos simples + 1 descuento + 2 escrituras de sincronización de las claves de overbooking
        verify(configRepository, times(26)).save(any(Config.class));
        assertThat(result.holdDurationMinutes()).isEqualTo(15);
        assertThat(result.noShowFeePercentage()).isEqualTo(12);
        // overbookingMaxPercentage se aplica después de overbookingPercentage y ambas claves son la misma política
        assertThat(result.overbookingPercentage()).isEqualTo(10);
        assertThat(result.baggageWeightMax()).isEqualTo(40.0);
        assertThat(result.parcelOtpMaxAttempts()).isEqualTo(4);
        assertThat(result.maxActiveHoldsPerUserAndTrip()).isEqualTo(2);
        assertThat(result.noShowWindowMinutes()).isEqualTo(10);
        assertThat(result.overbookingMinOccupancy()).isEqualTo(0.9);
        assertThat(result.overbookingWindowMinutes()).isEqualTo(20);
        assertThat(result.dynamicPricingDefault()).isTrue();
        assertThat(result.discountPercentages())
                .containsExactlyInAnyOrderEntriesOf(Map.of("STUDENT", 20, "SENIOR", 15, "CHILD", 40));
        assertThat(result.baggageWeightLimit()).isEqualByComparingTo("25");
        assertThat(result.baggagePricePerKg()).isEqualByComparingTo("3000");
        assertThat(result.noShowFee()).isEqualByComparingTo("15000");
        assertThat(result.overbookingMaxPercentage()).isEqualTo(0.1);
        assertThat(result.refundPercentage48Hours()).isEqualByComparingTo("95");
        assertThat(result.refundPercentage24Hours()).isEqualByComparingTo("75");
        assertThat(result.refundPercentage12Hours()).isEqualByComparingTo("55");
        assertThat(result.refundPercentage6Hours()).isEqualByComparingTo("35");
        assertThat(result.refundPercentageLess6Hours()).isEqualByComparingTo("5");
        assertThat(result.ticketBasePrice()).isEqualByComparingTo("55000");
        assertThat(result.ticketPriceMultiplierPeakHours()).isEqualByComparingTo("1.25");
        assertThat(result.ticketPriceMultiplierHighDemand()).isEqualByComparingTo("1.3");
        assertThat(result.ticketPriceMultiplierMediumDemand()).isEqualByComparingTo("1.15");
    }

    // ------------------------------------------------------------------
    // Descuentos
    // ------------------------------------------------------------------

    @Test
    void shouldGetDiscountPercentages_WithMixedValues_UseConfiguredOrFallback() {
        // Given: STUDENT configurado, SENIOR ausente y CHILD con valor corrupto
        stubConfigs(Map.of(
                "discount.percentage.student", "30",
                "discount.percentage.child", "abc"));

        // When
        Map<String, Integer> result = configService.getDiscountPercentages();

        // Then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of("STUDENT", 30, "SENIOR", 15, "CHILD", 50));
    }

    @Test
    void shouldGetDiscountPercentage_WithConfiguredValue_UseLowercaseKey() {
        // Given
        when(configRepository.findByConfigKey("discount.percentage.senior"))
                .thenReturn(Optional.of(configWith("discount.percentage.senior", "12")));

        // When
        Integer result = configService.getDiscountPercentage("Senior", 7);

        // Then
        assertThat(result).isEqualTo(12);
        verify(configRepository).findByConfigKey("discount.percentage.senior");
    }

    @Test
    void shouldGetDiscountPercentage_WithMissingValue_ReturnGivenFallback() {
        // Given
        when(configRepository.findByConfigKey("discount.percentage.veteran")).thenReturn(Optional.empty());

        // When
        Integer result = configService.getDiscountPercentage("VETERAN", 7);

        // Then
        assertThat(result).isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // Utilidades de prueba
    // ------------------------------------------------------------------

    private static Config configWith(String key, String value) {
        return Config.builder()
                .configKey(key)
                .configValue(value)
                .dataType(Config.DataType.STRING)
                .build();
    }

    // Simula la tabla de configuración con un mapa fijo de solo lectura
    private void stubConfigs(Map<String, String> values) {
        when(configRepository.findByConfigKey(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Optional.ofNullable(values.get(key)).map(value -> configWith(key, value));
        });
    }

    // Simula la tabla de configuración en memoria: lo guardado se puede volver a leer
    private void useInMemoryStore() {
        Map<String, Config> store = new HashMap<>();
        when(configRepository.findByConfigKey(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        when(configRepository.save(any(Config.class))).thenAnswer(inv -> {
            Config c = inv.getArgument(0);
            store.put(c.getConfigKey(), c);
            return c;
        });
    }

    private static Consumer<RequestBuilder> field(Consumer<RequestBuilder> setter) {
        return setter;
    }

    // Constructor auxiliar para armar requests con solo algunos campos informados
    static final class RequestBuilder {
        Integer holdDurationMinutes;
        Integer noShowFeePercentage;
        Integer overbookingPercentage;
        Map<String, Integer> discounts;
        BigDecimal baggageWeightLimit;
        BigDecimal baggagePricePerKg;
        BigDecimal noShowFee;
        Double overbookingMaxPercentage;
        BigDecimal refund48;
        BigDecimal refund24;
        BigDecimal refund12;
        BigDecimal refund6;
        BigDecimal refundLess6;
        BigDecimal ticketBasePrice;
        BigDecimal peak;
        BigDecimal high;
        BigDecimal medium;
        Double baggageWeightMax;
        Integer parcelOtpMaxAttempts;
        Integer maxActiveHolds;
        Integer noShowWindowMinutes;
        Double overbookingMinOccupancy;
        Integer overbookingWindowMinutes;
        Boolean dynamicPricingDefault;

        RequestBuilder with(Consumer<RequestBuilder> setter) {
            setter.accept(this);
            return this;
        }

        ConfigUpdateRequest build() {
            return new ConfigUpdateRequest(
                    holdDurationMinutes, noShowFeePercentage, overbookingPercentage, discounts,
                    baggageWeightLimit, baggagePricePerKg, noShowFee, overbookingMaxPercentage,
                    refund48, refund24, refund12, refund6, refundLess6,
                    ticketBasePrice, peak, high, medium,
                    baggageWeightMax, parcelOtpMaxAttempts, maxActiveHolds, noShowWindowMinutes,
                    overbookingMinOccupancy, overbookingWindowMinutes, dynamicPricingDefault);
        }
    }
}

