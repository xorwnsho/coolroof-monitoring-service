package com.coolroof.monitoring.sensor;

import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.building.BuildingSource;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 센서 원시 측정값을 기존 Building/TempReading 스키마에 적재한다.
 * deviceId를 건물 이름으로 써서, 처음 보는 deviceId면 건물을 자동 생성한다
 * (센서가 여러 개로 늘어나도 미리 DB에 건물을 등록해둘 필요가 없다).
 */
@Service
@RequiredArgsConstructor
public class SensorIngestService {

    private final BuildingRepository buildingRepository;
    private final TempReadingRepository tempReadingRepository;

    @Transactional
    public void ingest(SensorReadingRequest request) {
        if (request.deviceId() == null || request.deviceId().isBlank()) {
            throw new IllegalArgumentException("deviceId가 비어 있습니다.");
        }
        if (request.temperature() == null) {
            throw new IllegalArgumentException("temperature가 비어 있습니다.");
        }

        Building building = buildingRepository.findByName(request.deviceId())
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name(request.deviceId())
                        .isVerified(false)
                        .source(BuildingSource.SENSOR)
                        .build()));

        tempReadingRepository.save(TempReading.builder()
                .building(building)
                .surfaceTemp(request.temperature())
                .measuredAt(LocalDateTime.now())
                .build());
    }

    public LatestReadingResponse getLatest() {
        return buildingRepository.findFirstBySource(BuildingSource.SENSOR)
                .flatMap(building -> tempReadingRepository.findTopByBuildingIdOrderByMeasuredAtDesc(building.getId()))
                .map(reading -> new LatestReadingResponse(reading.getSurfaceTemp(), reading.getMeasuredAt()))
                .orElse(new LatestReadingResponse(null, null));
    }
}
