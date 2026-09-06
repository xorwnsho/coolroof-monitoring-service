package com.coolroof.monitoring.domain.reading;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TempReadingRepository extends JpaRepository<TempReading, Long> {

    List<TempReading> findByBuildingIdAndMeasuredAtBetween(
            Long buildingId, LocalDateTime from, LocalDateTime to);
}
