package com.web.controller;

import com.web.dto.parcel.ParcelCreateRequest;
import com.web.dto.parcel.ParcelResponse;
import com.web.dto.parcel.ParcelStatusUpdateRequest;
import com.web.entity.Parcel;
import com.web.exception.BusinessException;
import com.web.service.parcel.ParcelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/api/v1/parcels")
@RequiredArgsConstructor
public class ParcelController {
    
    private final ParcelService parcelService;
    
    // Obtiene todas las encomiendas
    @GetMapping
    @PreAuthorize("hasAnyRole('CLERK', 'ADMIN')")
    public ResponseEntity<List<ParcelResponse>> getAllParcels() {
        List<ParcelResponse> response = parcelService.getAllParcels();
        return ResponseEntity.ok(response);
    }
    
    // Crea una nueva encomienda
    @PostMapping
    @PreAuthorize("hasAnyRole('CLERK', 'ADMIN')")
    public ResponseEntity<ParcelResponse> createParcel(@Valid @RequestBody ParcelCreateRequest request) {
        ParcelResponse response = parcelService.createParcel(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    // Rastrea una encomienda por su código
    @GetMapping("/{code}/track")
    public ResponseEntity<ParcelResponse> trackParcel(@PathVariable String code) {
        ParcelResponse response = parcelService.trackParcel(code);
        return ResponseEntity.ok(response);
    }
    
    // Entrega una encomienda validando OTP y foto de prueba
    @PostMapping("/{code}/deliver")
    @PreAuthorize("hasAnyRole('DRIVER', 'CLERK')")
    public ResponseEntity<ParcelResponse> deliverParcel(
            @PathVariable String code,
            @RequestBody @Valid ParcelStatusUpdateRequest request) {
        
        if (request.otp() == null || request.proofPhotoUrl() == null) {
            throw new BusinessException("La entrega requiere OTP y foto de prueba", 
                                       HttpStatus.BAD_REQUEST, "MISSING_DELIVERY_PROOF");
        }
        
        ParcelResponse parcel = parcelService.trackParcel(code);
        ParcelResponse response = parcelService.deliverWithOtp(parcel.id(), request.otp(), request.proofPhotoUrl());
        return ResponseEntity.ok(response);
    }
    
    // Actualiza el estado de una encomienda
    @PutMapping("/{code}/status")
    @PreAuthorize("hasAnyRole('CLERK', 'DRIVER')")
    public ResponseEntity<ParcelResponse> updateParcelStatus(
            @PathVariable String code,
            @RequestBody @Valid ParcelStatusUpdateRequest request) {
        
        if (request.status() == null) {
            throw new BusinessException("El estado es requerido", 
                                       HttpStatus.BAD_REQUEST, "MISSING_STATUS");
        }
        
        ParcelResponse parcel = parcelService.trackParcel(code);
        ParcelResponse response = parcelService.updateStatus(parcel.id(), request.status());
        return ResponseEntity.ok(response);
    }
}

