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

    public List<ProtocolDefinitionDto> toDtoProtocolDefinitionList(List<ProtocolDefinition> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }

    public ActionDefinitionDto toDto(ActionDefinition entity) {
        return ActionDefinitionDto.builder()
                .id(entity.getId())
                .canonicalUrl(entity.getCanonicalUrl())
                .version(entity.getVersion())
                .canonical(entity.getCanonical())
                .name(entity.getName())
                .title(entity.getTitle())
                .status(entity.getStatus().name())
                .actionType(entity.getActionType().name())
                .definition(entity.getDefinition())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public IntelligenceEventLogDto toDto(IntelligenceEventLog entity) {
        return IntelligenceEventLogDto.builder()
                .id(entity.getId())
                .eventPayload(entity.getEventPayload())
                .actionDefinitionId(entity.getActionDefinitionId())
                .protocolInstanceId(entity.getProtocolInstanceId())
                .stepInstanceId(entity.getStepInstanceId())
                .deviationId(entity.getDeviationId())
                .subject(entity.getSubject())
                .actionType(entity.getActionType())
                .intelligenceDestination(entity.getIntelligenceDestination())
                .stepState(entity.getStepState())
                .triggerReason(entity.getTriggerReason())
                .stepActionId(entity.getStepActionId())
                .evaluationExpression(entity.getEvaluationExpression())
                .evaluationContext(entity.getEvaluationContext())
                .published(entity.isPublished())
                .publishedAt(entity.getPublishedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public List<ActionDefinitionDto> toDtoActionDefinitionList(List<ActionDefinition> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }

    public List<IntelligenceEventLogDto> toDtoIntelligenceEventLogList(List<IntelligenceEventLog> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream().map(this::toDto).toList();
    }
}
