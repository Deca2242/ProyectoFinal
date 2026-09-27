package com.web.service.dispatch;

import com.web.dto.dispatch.OverbookingApprovalResponse;

public interface OverbookingService {

    OverbookingApprovalResponse approveExtraSeat(Long tripId);
}
