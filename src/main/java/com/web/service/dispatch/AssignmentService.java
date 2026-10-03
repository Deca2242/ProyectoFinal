package com.web.service.dispatch;

import com.web.dto.dispatch.Assignment.AssignmentCreateRequest;
import com.web.dto.dispatch.Assignment.AssignmentResponse;
import com.web.dto.dispatch.Assignment.AssignmentUpdateRequest;

import java.time.LocalDate;
import java.util.List;

public interface AssignmentService {
    
    AssignmentResponse assignTrip(AssignmentCreateRequest request);
    
    AssignmentResponse getAssignmentByTrip(Long tripId);
    
    AssignmentResponse updateChecklist(Long assignmentId, AssignmentUpdateRequest request);
    
    List<AssignmentResponse> getDriverAssignments(Long driverId, LocalDate date);
    
    List<AssignmentResponse> getDispatcherAssignments(Long dispatcherId);

    // Con fecha: solo las asignaciones de los viajes de ese día; sin fecha: desde hoy
    List<AssignmentResponse> getDispatcherAssignments(Long dispatcherId, LocalDate date);
}

