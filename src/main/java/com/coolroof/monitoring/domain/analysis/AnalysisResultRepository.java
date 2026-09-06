package com.coolroof.monitoring.domain.analysis;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    Optional<AnalysisResult> findTopByBuildingIdOrderByCreatedAtDesc(Long buildingId);

    void deleteByPeriod(String period);
}
