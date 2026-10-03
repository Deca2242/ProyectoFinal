package com.web.dto.parcel;

import java.io.Serializable;

// Reapertura de una encomienda FAILED; tripId opcional para reasignarla a otro viaje de la misma ruta
public record ParcelReopenRequest(
    Long tripId
) implements Serializable {}
