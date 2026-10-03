package com.web.service.admin;

import com.web.dto.admin.PunctualityReportResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Job diario de puntualidad: genera el reporte del día anterior
@ExtendWith(MockitoExtension.class)
class PunctualityReportSchedulerTest {

    @Mock
    private MetricsService metricsService;

    @InjectMocks
    private PunctualityReportScheduler scheduler;

    @Test
    void shouldGenerateDailyReport_ForYesterday() {
        // Given
        when(metricsService.generatePunctualityReport(any(LocalDate.class))).thenReturn(List.of(
                new PunctualityReportResponse(LocalDate.now().minusDays(1), 1L, "R1", 3, 100.0, 66.7, 1.0, 4.0, null)));
        LocalDate yesterdayBefore = LocalDate.now().minusDays(1);

        // When
        scheduler.generateDailyReport();

        // Then: el día anterior a la ejecución (tolerando el cambio de día durante la prueba)
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(metricsService).generatePunctualityReport(captor.capture());
        assertThat(captor.getValue()).isBetween(yesterdayBefore, LocalDate.now().minusDays(1));
    }

    @Test
    void shouldRunAtFiveMinutesPastMidnight() throws Exception {
        // When
        Scheduled scheduled = PunctualityReportScheduler.class.getMethod("generateDailyReport")
                .getAnnotation(Scheduled.class);

        // Then
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 5 0 * * *");
    }
}
