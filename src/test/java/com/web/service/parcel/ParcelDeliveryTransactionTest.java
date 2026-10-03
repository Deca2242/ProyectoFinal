package com.web.service.parcel;

import com.web.entity.Parcel;
import com.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// Con OTP inválido, deliverWithOtp guarda el intento (y al agotarlos FAILED + incidente) y luego lanza
// BusinessException: la transacción no debe revertirse o esos cambios se pierden en la base de datos.
// updateStatus con DELIVERED delega en deliverWithOtp dentro de su propia transacción
class ParcelDeliveryTransactionTest {

    @Test
    void deliverWithOtp_shouldNotRollbackOnBusinessException() throws Exception {
        Transactional tx = ParcelServiceImpl.class
                .getMethod("deliverWithOtp", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(BusinessException.class);
    }

    @Test
    void updateStatus_shouldNotRollbackOnBusinessException() throws Exception {
        Transactional tx = ParcelServiceImpl.class
                .getMethod("updateStatus", String.class, Parcel.ParcelStatus.class, String.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.noRollbackFor()).contains(BusinessException.class);
    }
}
