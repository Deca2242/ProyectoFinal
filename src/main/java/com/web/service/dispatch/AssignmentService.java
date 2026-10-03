package com.web.service.dispatch;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;

import java.time.LocalDate;
import java.util.List;

public interface AssignmentService {
    
    AssignmentResponse assignTrip(AssignmentCreateRequest request);
    
    // Un DRIVER solo puede consultar la asignación de sus propios viajes (403)
    AssignmentResponse getAssignmentByTrip(Long tripId);

    // Si quien llama es DRIVER debe ser el conductor asignado al viaje (403); el resto de roles no se restringe
    void requireAssignedDriver(Long tripId);
    
    AssignmentResponse updateChecklist(Long assignmentId, AssignmentUpdateRequest request);
    
    List<AssignmentResponse> getDriverAssignments(Long driverId, LocalDate date);
    
    List<AssignmentResponse> getDispatcherAssignments(Long dispatcherId);

    // Con fecha: solo las asignaciones de los viajes de ese día; sin fecha: desde hoy
    List<AssignmentResponse> getDispatcherAssignments(Long dispatcherId, LocalDate date);
}

