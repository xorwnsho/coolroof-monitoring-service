package com.coolroof.monitoring.sensor;

import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.building.BuildingSource;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

    /**
     * "roof-01" 센서가 실제로는 몇 년째 운영 중이라는 가정하에, 하루 1건씩 과거 표면온도를
     * 채워 넣는다 — 실제 센서 값이 들어오기 전에도 그래프(특히 1주일/한달 구간)를 검증할 수
     * 있게 하기 위함이다. 재실행해도 같은 기간을 덮어쓰도록 기존 값을 지우고 다시 채운다
     * (로컬/daisy 양쪽에서 동일하게 재사용 가능).
     *
     * <p>가정 프로필: 단독주택 / 철근콘크리트구조 / 5층 / 1990년 준공, 쿨루프 시공 후
     * 정상 성능(표면-외기 온도차 약 3.5℃ 내외)을 유지 중인 것으로 시뮬레이션한다.
     */
    @Transactional
    public SensorSeedResult seedHistory(String deviceId, int days) {
        Building building = buildingRepository.findByName(deviceId)
                .orElseGet(() -> Building.builder()
                        .name(deviceId)
                        .isVerified(true)
                        .source(BuildingSource.SENSOR)
                        .build());

        LocalDate today = LocalDate.now();
        LocalDate coolroofDate = today.minusDays(days - 1L);

        building.setUsageType("단독주택");
        building.setBuiltYear(1990);
        building.setFloorCount(5);
        building.setRoofType("철근콘크리트구조");
        building.setCoolroofDate(coolroofDate);
        building.setIsVerified(true);
        building.setSource(BuildingSource.SENSOR);
        building = buildingRepository.save(building);

        tempReadingRepository.deleteByBuildingIdIn(List.of(building.getId()));

        Random random = new Random();
        List<TempReading> readings = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = coolroofDate.plusDays(i);
            LocalDateTime measuredAt = date.atTime(14, 0);
            double outdoor = seasonalOutdoorTemp(date, 14);
            double gap = 3.5 + random.nextGaussian() * 0.6; // 쿨루프 정상 성능 가정
            readings.add(TempReading.builder()
                    .building(building)
                    .surfaceTemp(round1(outdoor + gap))
                    .measuredAt(measuredAt)
                    .build());
        }
        tempReadingRepository.saveAll(readings);

        return new SensorSeedResult(deviceId, days, coolroofDate, today);
    }

    private double seasonalOutdoorTemp(LocalDate date, int hour) {
        int dayOfYear = date.getDayOfYear();
        double angle = 2 * Math.PI * (dayOfYear - 105) / 365.0;
        double base = 14 + 16 * Math.sin(angle);
        double hourAdjust = (hour >= 11 && hour <= 16) ? 3 : (hour <= 6 || hour >= 21) ? -4 : 0;
        return base + hourAdjust;
    }

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
