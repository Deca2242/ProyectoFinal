package com.web.service.admin;

import com.web.dto.admin.MetricsResponse;

import java.time.LocalDate;

public interface MetricsService {

    MetricsResponse getMetrics(LocalDate startDate, LocalDate endDate);
}
