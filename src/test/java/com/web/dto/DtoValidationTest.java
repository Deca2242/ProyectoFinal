package com.web.dto;

import com.web.dto.admin.ConfigUpdateRequest;
import com.web.dto.baggage.BaggageCreateRequest;
import com.web.dto.catalog.Bus.BusCreateRequest;
import com.web.dto.catalog.Bus.BusUpdateRequest;
import com.web.dto.parcel.ParcelCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Validaciones declarativas (jakarta.validation) de los DTOs de entrada
class DtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static <T> Set<String> invalidPaths(T dto) {
        Set<ConstraintViolation<T>> violations = validator.validate(dto);
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    // ---------- ConfigUpdateRequest ----------

    private static ConfigUpdateRequest config(Integer holdDuration, Map<String, Integer> discounts, Double overbookingMax) {
        return new ConfigUpdateRequest(holdDuration, null, null, discounts,
                null, null, null, overbookingMax,
                null, null, null, null, null,
                null, null, null, null);
    }

    @Test
    void shouldAcceptConfigUpdate_WithAllFieldsNull() {
        // Given: actualización parcial sin campos
        ConfigUpdateRequest request = config(null, null, null);

        // When/Then
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldAcceptConfigUpdate_WithValuesAtTheLimits() {
        // Given
        Map<String, Integer> discounts = Map.of("STUDENT", 0, "CHILD", 100);
        ConfigUpdateRequest min = config(1, discounts, 0.0);
        ConfigUpdateRequest max = config(120, discounts, 1.0);

        // When/Then
        assertThat(validator.validate(min)).isEmpty();
        assertThat(validator.validate(max)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 150, -1})
    void shouldRejectConfigUpdate_WithDiscountOutOfRange(int discount) {
        // Given
        ConfigUpdateRequest request = config(null, Map.of("STUDENT", discount), null);

        // When
        Set<String> paths = invalidPaths(request);

        // Then
        assertThat(paths).hasSize(1);
        assertThat(paths.iterator().next()).startsWith("discountPercentages");
    }

    @Test
    void shouldRejectConfigUpdate_WithNullDiscountValue() {
        // Given
        Map<String, Integer> discounts = new HashMap<>();
        discounts.put("SENIOR", null);
        ConfigUpdateRequest request = config(null, discounts, null);

        // When/Then
        assertThat(invalidPaths(request)).singleElement().asString().startsWith("discountPercentages");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 121})
    void shouldRejectConfigUpdate_WithHoldDurationOutOfRange(int minutes) {
        // Given
        ConfigUpdateRequest request = config(minutes, null, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("holdDurationMinutes");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.5, -0.01, 1.01, 1.5})
    void shouldRejectConfigUpdate_WithOverbookingMaxPercentageOutOfRange(double percentage) {
        // Given
        ConfigUpdateRequest request = config(null, null, percentage);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("overbookingMaxPercentage");
    }

    @Test
    void shouldRejectConfigUpdate_WithPercentagesAboveHundredAndNegativeAmounts() {
        // Given
        ConfigUpdateRequest request = new ConfigUpdateRequest(null, 101, -1, null,
                new BigDecimal("-1"), null, new BigDecimal("-100"), null,
                new BigDecimal("100.01"), null, null, null, new BigDecimal("-1"),
                new BigDecimal("-0.01"), null, null, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactlyInAnyOrder(
                "noShowFeePercentage", "overbookingPercentage", "baggageWeightLimit", "noShowFee",
                "refundPercentage48Hours", "refundPercentageLess6Hours", "ticketBasePrice");
    }

    // ---------- BusCreateRequest / BusUpdateRequest ----------

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldRejectBusCreate_WithNonPositiveCapacity(int capacity) {
        // Given
        BusCreateRequest request = new BusCreateRequest("ABC123", capacity, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("capacity");
    }

    @Test
    void shouldRejectBusCreate_WithNullCapacityAndBlankPlate() {
        // Given
        BusCreateRequest request = new BusCreateRequest(" ", null, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactlyInAnyOrder("plate", "capacity");
    }

    @Test
    void shouldAcceptBusCreate_WithCapacityOne() {
        // When/Then
        assertThat(validator.validate(new BusCreateRequest("ABC123", 1, null))).isEmpty();
    }

    @Test
    void shouldRejectBusUpdate_WithZeroCapacityButAcceptNull() {
        // When/Then
        assertThat(invalidPaths(new BusUpdateRequest(0, null, null))).containsExactly("capacity");
        assertThat(validator.validate(new BusUpdateRequest(null, null, null))).isEmpty();
    }

    // ---------- BaggageCreateRequest ----------

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1000", "999.999"})
    void shouldRejectBaggageCreate_WithWeightOutOfRange(String weight) {
        // Given
        BaggageCreateRequest request = new BaggageCreateRequest(new BigDecimal(weight), null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("weightKg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "23", "999.99"})
    void shouldAcceptBaggageCreate_WithWeightInRange(String weight) {
        // When/Then
        assertThat(validator.validate(new BaggageCreateRequest(new BigDecimal(weight), null))).isEmpty();
    }

    @Test
    void shouldRejectBaggageCreate_WithNullWeight() {
        // When/Then
        assertThat(invalidPaths(new BaggageCreateRequest(null, null))).containsExactly("weightKg");
    }

    // ---------- ParcelCreateRequest ----------

    @Test
    void shouldRejectParcelCreate_WithNonPositiveWeight() {
        // Given
        ParcelCreateRequest request = new ParcelCreateRequest(1L, "Remitente", "300", "Destinatario", "301",
                1L, null, 2L, null, BigDecimal.TEN, BigDecimal.ZERO, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("weightKg");
    }
}
