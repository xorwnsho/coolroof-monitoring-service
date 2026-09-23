package com.coolroof.monitoring.domain.building;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    Optional<Building> findByName(String name);

    Optional<Building> findFirstBySource(BuildingSource source);
}
