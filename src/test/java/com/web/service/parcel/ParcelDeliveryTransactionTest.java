package com.web.service.parcel;

import com.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// Con OTP inválido, deliverWithOtp guarda FAILED + incidente y luego lanza BusinessException:
// la transacción no debe revertirse o esos cambios se pierden en la base de datos
class ParcelDeliveryTransactionTest {

    @Test
    void deliverWithOtp_shouldNotRollbackOnBusinessException() throws Exception {
        Transactional tx = ParcelServiceImpl.class
                .getMethod("deliverWithOtp", Long.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(BusinessException.class);
    }
}
