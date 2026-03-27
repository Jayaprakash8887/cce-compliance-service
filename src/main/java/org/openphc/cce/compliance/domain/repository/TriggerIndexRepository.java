package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.TriggerIndex;
import org.openphc.cce.compliance.domain.entity.TriggerIndexId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TriggerIndexRepository extends JpaRepository<TriggerIndex, TriggerIndexId> {

    @Query("""
            SELECT t.id.protocolDefinitionId, t.id.actionId
            FROM TriggerIndex t
            WHERE t.id.resourceType = :resourceType
              AND CONCAT(t.id.path, '|', t.id.codeSystem, '|', t.id.codeValue) IN :codeTriples
            GROUP BY t.id.protocolDefinitionId, t.id.actionId
            HAVING COUNT(DISTINCT t.id.path) = (
                SELECT COUNT(DISTINCT t2.id.path)
                FROM TriggerIndex t2
                WHERE t2.id.protocolDefinitionId = t.id.protocolDefinitionId
                  AND t2.id.actionId = t.id.actionId
                  AND t2.id.resourceType = :resourceType
            )
            """)
    List<Object[]> findStructuralMatches(
            @Param("resourceType") String resourceType,
            @Param("codeTriples") List<String> codeTriples);

    @Modifying
    @Query("DELETE FROM TriggerIndex t WHERE t.id.protocolDefinitionId = :protocolDefinitionId")
    void deleteByProtocolDefinitionId(@Param("protocolDefinitionId") UUID protocolDefinitionId);

    List<TriggerIndex> findByIdProtocolDefinitionId(UUID protocolDefinitionId);
}
