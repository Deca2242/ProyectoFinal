package com.web.service.dispatch;

import com.web.dto.notification.PlatformUpdateResponse;

public interface PlatformService {

    // Asigna o cambia el andén de salida y notifica a los pasajeros si cambió
    PlatformUpdateResponse updatePlatform(Long tripId, String platform);
}
