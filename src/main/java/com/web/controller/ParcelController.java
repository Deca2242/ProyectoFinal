package com.web.controller;

import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelReopenRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.ParcelStatusUpdateRequest;
import com.web.entity.Parcel;
import com.web.exception.BusinessException;
import com.web.service.parcel.ParcelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;


@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ParcelController {

    private final ParcelService parcelService;

    // Lista las encomiendas con filtros opcionales (from/to: fecha del viaje, inclusivas)
    @GetMapping("/parcels")
    @PreAuthorize("hasAnyRole('CLERK', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<List<ParcelResponse>> getAllParcels(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Parcel.ParcelStatus status) {
        return ResponseEntity.ok(parcelService.searchParcels(from, to, status));
    }

    // Crea una nueva encomienda
    @PostMapping("/parcels")
    @PreAuthorize("hasAnyRole('CLERK', 'ADMIN')")
    public ResponseEntity<ParcelResponse> createParcel(@Valid @RequestBody ParcelCreateRequest request) {
        ParcelResponse response = parcelService.createParcel(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Rastrea una encomienda por su código
    @GetMapping("/parcels/{code}/track")
    public ResponseEntity<ParcelResponse> trackParcel(@PathVariable String code) {
        ParcelResponse response = parcelService.trackParcel(code);
        return ResponseEntity.ok(response);
    }

    // Entrega una encomienda validando OTP y foto de prueba
    @PostMapping("/parcels/{code}/deliver")
    @PreAuthorize("hasAnyRole('DRIVER', 'CLERK')")
    public ResponseEntity<ParcelResponse> deliverParcel(
            @PathVariable String code,
            @RequestBody @Valid ParcelStatusUpdateRequest request) {
        requireMatchingCode(code, request);

        if (isBlank(request.otp()) || isBlank(request.proofPhotoUrl())) {
            throw new BusinessException("La entrega requiere OTP y foto de prueba",
                                       HttpStatus.BAD_REQUEST, "MISSING_DELIVERY_PROOF");
        }

        return ResponseEntity.ok(parcelService.deliverWithOtp(code, request.otp(), request.proofPhotoUrl()));
    }

    // Actualiza el estado de una encomienda: IN_TRANSIT, FAILED o DELIVERED (este último con OTP y foto).
    // POST es el alias del documento (POST /api/parcels/{code}/status)
    @RequestMapping(value = "/parcels/{code}/status", method = {RequestMethod.PUT, RequestMethod.POST})
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER')")
    public ResponseEntity<ParcelResponse> updateParcelStatus(
            @PathVariable String code,
            @RequestBody @Valid ParcelStatusUpdateRequest request) {
        requireMatchingCode(code, request);

        ParcelResponse response = parcelService.updateStatus(code, request.status(), request.otp(),
                request.proofPhotoUrl());
        return ResponseEntity.ok(response);
    }

    // Reabre una encomienda FAILED (reinicia intentos de OTP; tripId opcional para reasignarla)
    @PostMapping("/parcels/{code}/reopen")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public ResponseEntity<ParcelResponse> reopenParcel(
            @PathVariable String code,
            @RequestBody(required = false) ParcelReopenRequest request) {
        Long tripId = request == null ? null : request.tripId();
        return ResponseEntity.ok(parcelService.reopenParcel(code, tripId));
    }

    // Encomiendas de un viaje (el conductor solo las de los viajes que tiene asignados)
    @GetMapping("/trips/{tripId}/parcels")
    @PreAuthorize("hasAnyRole('DRIVER', 'CLERK', 'DISPATCHER', 'ADMIN')")
    public ResponseEntity<List<ParcelResponse>> getTripParcels(
            @PathVariable Long tripId,
            @RequestParam(required = false) Parcel.ParcelStatus status) {
        return ResponseEntity.ok(parcelService.getTripParcels(tripId, status));
    }

    // El código del body es opcional; si viene debe ser el mismo de la URL
    private static void requireMatchingCode(String code, ParcelStatusUpdateRequest request) {
        if (request.code() != null && !request.code().isBlank() && !request.code().trim().equals(code)) {
            throw new BusinessException("El código del cuerpo (" + request.code() + ") no coincide con el de la URL ("
                    + code + ")", HttpStatus.BAD_REQUEST, "PARCEL_CODE_MISMATCH");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
