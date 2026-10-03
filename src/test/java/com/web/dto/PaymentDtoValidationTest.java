package com.web.dto;

import com.web.dto.payment.CashCloseRequest;
import com.web.dto.payment.PaymentConfirmRequest;
import com.web.entity.Ticket;
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
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Validaciones declarativas de los DTOs de pagos y cierre de caja
class PaymentDtoValidationTest {

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
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    // ---------- PaymentConfirmRequest ----------

    @Test
    void shouldAcceptPaymentConfirm_WithOnlyRequiredFields() {
        // Given: efectivo sin referencia ni monto
        PaymentConfirmRequest request = new PaymentConfirmRequest(1L, Ticket.PaymentMethod.CASH, null, null, null);

        // When/Then
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldAcceptPaymentConfirm_WithAllFields() {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(1L, Ticket.PaymentMethod.TRANSFER,
                "BANCO-123", new BigDecimal("50000"), "https://comprobantes/1.png");

        // When/Then
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectPaymentConfirm_WithoutTicketAndMethod() {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(null, null, "REF", null, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactlyInAnyOrder("ticketId", "paymentMethod");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-50000"})
    void shouldRejectPaymentConfirm_WithNonPositiveAmount(String amount) {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(1L, Ticket.PaymentMethod.CASH, null,
                new BigDecimal(amount), null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("amount");
    }

    @Test
    void shouldRejectPaymentConfirm_WithTooLongReferenceAndProofUrl() {
        // Given
        PaymentConfirmRequest request = new PaymentConfirmRequest(1L, Ticket.PaymentMethod.QR,
                "R".repeat(101), null, "https://" + "x".repeat(500));

        // When/Then
        assertThat(invalidPaths(request)).containsExactlyInAnyOrder("transactionReference", "proofImageUrl");
    }

    // ---------- CashCloseRequest ----------

    @Test
    void shouldAcceptCashClose_WithTodayAndZeroAmount() {
        // Given
        CashCloseRequest request = new CashCloseRequest(LocalDate.now(), null, BigDecimal.ZERO, "Sin ventas");

        // When/Then
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void shouldRejectCashClose_WithoutDateAndActualAmount() {
        // Given
        CashCloseRequest request = new CashCloseRequest(null, null, null, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactlyInAnyOrder("date", "actualAmount");
    }

    @Test
    void shouldRejectCashClose_WithFutureDate() {
        // Given: no se puede cerrar una caja de un día que no ha llegado
        CashCloseRequest request = new CashCloseRequest(LocalDate.now().plusDays(1), null, BigDecimal.TEN, null);

        // When/Then
        assertThat(invalidPaths(request)).containsExactly("date");
    }

    @Test
    void shouldRejectCashClose_WithNegativeActualAmountAndLongNotes() {
        // Given: el efectivo contado no puede ser negativo
        CashCloseRequest request = new CashCloseRequest(LocalDate.now(), null, new BigDecimal("-1"), "n".repeat(501));

        // When/Then
        assertThat(invalidPaths(request)).containsExactlyInAnyOrder("actualAmount", "notes");
    }
}
