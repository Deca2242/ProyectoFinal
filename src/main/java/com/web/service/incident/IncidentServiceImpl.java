package com.web.service.incident;

import com.web.dto.incident.IncidentCreateRequest;
import com.web.dto.incident.IncidentResponse;
import com.web.dto.incident.mapper.IncidentMapper;
import com.web.entity.Incident;
import com.web.entity.User;
import com.web.exception.BusinessException;
import com.web.exception.InvalidStateTransitionException;
import com.web.exception.ResourceNotFoundException;
import com.web.repository.IncidentRepository;
import com.web.repository.ParcelRepository;
import com.web.repository.TicketRepository;
import com.web.repository.TripRepository;
import com.web.repository.UserRepository;
import com.web.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// Reporte y consulta de incidentes (seguridad, vehículo, entrega fallida, sobreventa).
// Un incidente no cambia el estado del viaje, tiquete o encomienda: solo queda registrado
@Service
@RequiredArgsConstructor
public class IncidentServiceImpl implements IncidentService {

    private static final String ME = "me";

    private final IncidentRepository incidentRepository;
    private final TripRepository tripRepository;
    private final TicketRepository ticketRepository;
    private final ParcelRepository parcelRepository;
    private final UserRepository userRepository;
    private final IncidentMapper incidentMapper;

    @Override
    @Transactional
    public IncidentResponse reportIncident(IncidentCreateRequest request) {
        requireEntityExists(request.entityType(), request.entityId());

        User reporter = currentUser();

        Incident incident = incidentMapper.toEntity(request);
        incident.setDescription(request.description().trim());
        incident.setReportedBy(reporter);
        incident.setCreatedAt(LocalDateTime.now());

        return incidentMapper.toResponse(incidentRepository.save(incident));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentResponse> searchIncidents(Incident.IncidentType type, Incident.EntityType entityType,
                                                  Long entityId, LocalDate from, LocalDate to,
                                                  Incident.IncidentStatus status, String reportedBy) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException("La fecha inicial no puede ser posterior a la final",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }
        if (reportedBy != null && !ME.equalsIgnoreCase(reportedBy.trim())) {
            throw new BusinessException("El filtro reportedBy solo admite el valor 'me'",
                    HttpStatus.BAD_REQUEST, "INVALID_REPORTED_BY");
        }
        boolean onlyMine = reportedBy != null;
        if (!onlyMine && !canSeeAllIncidents()) {
            throw new BusinessException("Solo puedes consultar los incidentes que reportaste (reportedBy=me)",
                    HttpStatus.FORBIDDEN, "INCIDENTS_ONLY_OWN");
        }

        Specification<Incident> spec = (root, query, cb) -> cb.conjunction();
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("incidentType"), type));
        }
        if (entityType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("entityId"), entityId));
        }
        if (from != null) {
            LocalDateTime start = from.atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start));
        }
        if (to != null) {
            LocalDateTime end = to.plusDays(1).atStartOfDay();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), end));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (onlyMine) {
            Long reporterId = currentUser().getId();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("reportedBy").get("id"), reporterId));
        }

        List<Incident> incidents = incidentRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));
        return incidentMapper.toResponseList(incidents);
    }

    @Override
    @Transactional(readOnly = true)
    public IncidentResponse getIncident(Long id) {
        Incident incident = findIncident(id);
        if (!canSeeAllIncidents()) {
            String username = SecurityUtils.currentUsername().orElse("");
            User reporter = incident.getReportedBy();
            if (reporter == null || !username.equalsIgnoreCase(reporter.getEmail())) {
                throw new BusinessException("Solo puedes consultar los incidentes que reportaste",
                        HttpStatus.FORBIDDEN, "INCIDENTS_ONLY_OWN");
            }
        }
        return incidentMapper.toResponse(incident);
    }

    @Override
    @Transactional
    public IncidentResponse resolveIncident(Long id) {
        Incident incident = findIncident(id);
        if (incident.getStatus() == Incident.IncidentStatus.RESOLVED) {
            throw new InvalidStateTransitionException(Incident.IncidentStatus.RESOLVED.name(),
                    Incident.IncidentStatus.RESOLVED.name());
        }
        incident.setStatus(Incident.IncidentStatus.RESOLVED);
        incident.setResolvedAt(LocalDateTime.now());
        incident.setResolvedBy(currentUser());
        return incidentMapper.toResponse(incidentRepository.save(incident));
    }

    private Incident findIncident(Long id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incidente", id));
    }

    private User currentUser() {
        return SecurityUtils.currentUsername()
                .flatMap(userRepository::findByEmail)
                .orElseThrow(() -> new BusinessException("Se requiere un usuario autenticado",
                        HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
    }

    private static boolean canSeeAllIncidents() {
        return SecurityUtils.hasRole("ADMIN") || SecurityUtils.hasRole("DISPATCHER");
    }

    // El incidente debe referirse a un viaje, tiquete o encomienda existente
    private void requireEntityExists(Incident.EntityType entityType, Long entityId) {
        boolean exists = switch (entityType) {
            case TRIP -> tripRepository.existsById(entityId);
            case TICKET -> ticketRepository.existsById(entityId);
            case PARCEL -> parcelRepository.existsById(entityId);
        };
        if (!exists) {
            String label = switch (entityType) {
                case TRIP -> "Viaje";
                case TICKET -> "Tiquete";
                case PARCEL -> "Encomienda";
            };
            throw new ResourceNotFoundException(label, entityId);
        }
    }
}
