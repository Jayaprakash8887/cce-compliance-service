package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.FacilityReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FacilityReferenceRepository extends JpaRepository<FacilityReference, UUID> {

    boolean existsByFacilityId(String facilityId);
}
