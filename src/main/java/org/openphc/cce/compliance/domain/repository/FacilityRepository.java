package org.openphc.cce.compliance.domain.repository;

import org.openphc.cce.compliance.domain.entity.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FacilityRepository extends JpaRepository<Facility, UUID> {

    Optional<Facility> findByFacilityId(String facilityId);
}
