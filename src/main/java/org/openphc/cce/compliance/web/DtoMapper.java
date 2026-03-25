package org.openphc.cce.compliance.web;

import org.openphc.cce.compliance.domain.entity.*;
import org.openphc.cce.compliance.web.dto.*;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class DtoMapper {

    public ProtocolDefinitionDto toDto(ProtocolDefinition entity) {
        return ProtocolDefinitionDto.builder()
                .id(entity.getId())
                .url(entity.getUrl())
                .version(entity.getVersion())
                .canonical(entity.getCanonical())
                .status(entity.getStatus().name())
                .loadedAt(entity.getLoadedAt())
                .definition(entity.getDefinition())
                .build();
    }

    public ProtocolInstanceDto toDto(ProtocolInstance entity) {
        return ProtocolInstanceDto.builder()
                .id(entity.getId())
                .patientId(entity.getPatientId())
                .protocolCanonical(entity.getProtocolCanonical())
                .protocolDefinitionId(entity.getProtocolDefinition().getId())
                .status(entity.getStatus().name())
                .enrolledAt(entity.getEnrolledAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .steps(toDtoStepList(entity.getSteps()))
                .deviations(toDtoDeviationList(entity.getDeviations()))
                .build();
    }

    public StepInstanceDto toDto(StepInstance entity) {
        return StepInstanceDto.builder()
                .id(entity.getId())
                .actionId(entity.getActionId())
                .repeatIndex(entity.getRepeatIndex())
                .state(entity.getState().name())
                .dueDate(entity.getDueDate())
                .overdueDate(entity.getOverdueDate())
                .missedDate(entity.getMissedDate())
                .completedAt(entity.getCompletedAt())
                .completedBySource(entity.getCompletedBySource())
                .completionStatus(entity.getCompletionStatus() != null ? entity.getCompletionStatus().name() : null)
                .matchedEventId(entity.getMatchedEventId())
                .requiredBehavior(entity.getRequiredBehavior())
                .build();
    }

    public DeviationDto toDto(Deviation entity) {
        return DeviationDto.builder()
                .id(entity.getId())
                .deviationType(entity.getDeviationType().name())
                .detectedAt(entity.getDetectedAt())
                .intelligenceEventId(entity.getIntelligenceEventId())
                .metadata(entity.getMetadata())
                .build();
    }

    public EventLogDto toDto(EventLog entity) {
        return EventLogDto.builder()
                .id(entity.getId())
                .cloudeventsId(entity.getCloudeventsId())
                .source(entity.getSource())
                .subject(entity.getSubject())
                .type(entity.getType())
                .eventTime(entity.getEventTime())
                .receivedAt(entity.getReceivedAt())
                .processingStatus(entity.getProcessingStatus().name())
                .data(entity.getData())
                .protocolInstanceId(entity.getProtocolInstanceId())
                .actionId(entity.getActionId())
                .facilityId(entity.getFacilityId())
                .build();
    }

    public List<ProtocolDefinitionDto> toDtoProtocolDefinitionList(List<ProtocolDefinition> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }

    public List<ProtocolInstanceDto> toDtoProtocolInstanceList(List<ProtocolInstance> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }

    public List<StepInstanceDto> toDtoStepList(List<StepInstance> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }

    public List<DeviationDto> toDtoDeviationList(List<Deviation> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }

    public List<EventLogDto> toDtoEventLogList(List<EventLog> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }
}
