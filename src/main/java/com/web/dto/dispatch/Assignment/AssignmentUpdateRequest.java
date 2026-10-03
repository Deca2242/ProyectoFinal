package com.web.dto.dispatch.Assignment;

import java.io.Serializable;

public record AssignmentUpdateRequest(
    Long driverId,
    Boolean checklistOk,
    Boolean soatValid,
    Boolean revisionValid
) implements Serializable {}

