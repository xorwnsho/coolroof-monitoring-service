package com.coolroof.monitoring.mock;

import com.coolroof.monitoring.domain.analysis.AnalysisResultRepository;
import com.coolroof.monitoring.domain.building.Building;
import com.coolroof.monitoring.domain.building.BuildingRepository;
import com.coolroof.monitoring.domain.reading.TempReading;
import com.coolroof.monitoring.domain.reading.TempReadingRepository;
import com.coolroof.monitoring.registry.BuildingRegistryClient;
import com.coolroof.monitoring.registry.LegalDong;
import com.coolroof.monitoring.registry.RegistryBuilding;
import com.coolroof.monitoring.registry.SeoulLegalDongs;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 센서·시공 데이터가 아직 없는 상태에서, 배치 분석 로직을 검증할 수 있도록
 * "축적된 상태"의 건물/온도 데이터를 생성한다.
 * 건물 메타데이터(용도·연면적·층수·사용승인일 등)는 건축물대장 API로 실제 값을 가져오고,
 * 쿨루프 관련 값(시공일·인증여부)과 온도 리딩만 임의로 만든다.
 */
@Component
@RequiredArgsConstructor
public class MockDataGenerator {

    private final BuildingRepository buildingRepository;
    private final TempReadingRepository tempReadingRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final BuildingRegistryClient buildingRegistryClient;

    private record CandidateBuilding(RegistryBuilding registryBuilding, LegalDong legalDong) {
    }

    @Transactional
    public MockGenerateResult generate(int buildingCount) {
        Random random = new Random();

        analysisResultRepository.deleteAllInBatch();
        tempReadingRepository.deleteAllInBatch();
        buildingRepository.deleteAllInBatch();

        List<CandidateBuilding> pool = collectCandidates(buildingCount, random);
        if (pool.size() < buildingCount) {
            throw new IllegalStateException(
                    "건축물대장에서 충분한 건물을 가져오지 못했습니다. 요청=" + buildingCount + ", 확보=" + pool.size());
        }
        Collections.shuffle(pool, random);
        List<CandidateBuilding> selected = pool.subList(0, buildingCount);

        int earlyDegradationCount = Math.max(1, buildingCount / 10);
        int repaintImminentCount = Math.max(1, buildingCount / 10);
        int normalCount = buildingCount - earlyDegradationCount - repaintImminentCount;

        List<MockScenario> scenarios = new ArrayList<>();
        scenarios.addAll(Collections.nCopies(normalCount, MockScenario.NORMAL));
        scenarios.addAll(Collections.nCopies(earlyDegradationCount, MockScenario.EARLY_DEGRADATION));
        scenarios.addAll(Collections.nCopies(repaintImminentCount, MockScenario.REPAINT_IMMINENT));
        Collections.shuffle(scenarios, random);

        LocalDate today = LocalDate.now();
        int totalReadings = 0;

        for (int i = 0; i < selected.size(); i++) {
            MockScenario scenario = scenarios.get(i);
            CandidateBuilding candidate = selected.get(i);
            RegistryBuilding registryBuilding = candidate.registryBuilding();
            LegalDong legalDong = candidate.legalDong();

            int totalAgeDays = switch (scenario) {
                case NORMAL -> 365 + random.nextInt(1095); // 1~4년
                case EARLY_DEGRADATION -> 700 + random.nextInt(100); // 약 2년
                case REPAINT_IMMINENT -> 1400 + random.nextInt(120); // 약 4년
            };
            LocalDate coolroofDate = today.minusDays(totalAgeDays);

            String name = registryBuilding.bldNm().isBlank()
                    ? registryBuilding.platPlc()
                    : registryBuilding.bldNm();
            String address = registryBuilding.newPlatPlc().isBlank()
                    ? registryBuilding.platPlc()
                    : registryBuilding.newPlatPlc();

            Building building = Building.builder()
                    .name(name)
                    .address(address)
                    .usageType(registryBuilding.mainPurpsCdNm())
                    .builtYear(registryBuilding.useApprovalDate().getYear())
                    .coolroofDate(coolroofDate)
                    .isVerified(random.nextInt(10) != 0)
                    .lat(legalDong.lat() + (random.nextDouble() - 0.5) * 0.01)
                    .lng(legalDong.lng() + (random.nextDouble() - 0.5) * 0.01)
                    .totalFloorArea(registryBuilding.totalFloorArea())
                    .floorCount(registryBuilding.floorCount())
                    .roofType(registryBuilding.roofType())
                    .build();
            building = buildingRepository.save(building);

            double baseGap = 3.0 + random.nextDouble() * 2.5;
            double peakGap = 18.0 + random.nextDouble() * 4.0;
            int rampStartDay = (int) (totalAgeDays * 0.4);
            int rampEndDay = (int) (totalAgeDays * 0.9);

            List<TempReading> readings = new ArrayList<>();
            for (int day = 0; day <= totalAgeDays; day++) {
                LocalDate date = coolroofDate.plusDays(day);
                int hour = 6 + random.nextInt(16); // 06~21시 사이 임의 측정
                LocalDateTime measuredAt = date.atTime(hour, random.nextInt(60));

                String weather = pickWeather(random);
                boolean isDaytimeClear = "맑음".equals(weather) && hour >= 10 && hour <= 16;

                double seasonalOutdoor = seasonalOutdoorTemp(date, hour, random);
                double gapToday = scenarioGap(scenario, day, baseGap, peakGap, rampStartDay, rampEndDay, random);
                double effectiveGap = isDaytimeClear ? gapToday : gaussianNoise(random, 0, 0.5);

                double outdoorTemp = round1(seasonalOutdoor);
                double surfaceTemp = round1(seasonalOutdoor + effectiveGap);
                double solarRadiation = round1(solarRadiation(hour, weather, random));

                readings.add(TempReading.builder()
                        .building(building)
                        .surfaceTemp(surfaceTemp)
                        .outdoorTemp(outdoorTemp)
                        .solarRadiation(solarRadiation)
                        .weather(weather)
                        .measuredAt(measuredAt)
                        .build());
            }
            tempReadingRepository.saveAll(readings);
            totalReadings += readings.size();
        }

        return new MockGenerateResult(scenarios.size(), totalReadings);
    }

    /**
     * 법정동 하나에 편중되지 않도록, 동네별로 목표치를 균등 배분해서 페이지를 넘겨가며
     * 실제 건물 후보를 모은다.
     */
    private List<CandidateBuilding> collectCandidates(int targetCount, Random random) {
        List<CandidateBuilding> pool = new ArrayList<>();
        int poolTarget = targetCount * 3;
        int perDongTarget = (poolTarget + SeoulLegalDongs.TARGETS.size() - 1) / SeoulLegalDongs.TARGETS.size();

        for (LegalDong legalDong : SeoulLegalDongs.TARGETS) {
            int collectedForDong = 0;
            for (int page = 1; page <= 10 && collectedForDong < perDongTarget; page++) {
                List<RegistryBuilding> items = buildingRegistryClient.fetchBuildings(legalDong, 20, page);
                if (items.isEmpty()) {
                    break;
                }
                for (RegistryBuilding item : items) {
                    pool.add(new CandidateBuilding(item, legalDong));
                    collectedForDong++;
                }
            }
        }
        return pool;
    }

    private String pickWeather(Random random) {
        int r = random.nextInt(100);
        if (r < 55) {
            return "맑음";
        } else if (r < 85) {
            return "흐림";
        } else {
            return "비";
        }
    }

    private double seasonalOutdoorTemp(LocalDate date, int hour, Random random) {
        int dayOfYear = date.getDayOfYear();
        double angle = 2 * Math.PI * (dayOfYear - 105) / 365.0;
        double base = 14 + 16 * Math.sin(angle);
        double hourAdjust = (hour >= 11 && hour <= 16) ? 3 : (hour <= 6 || hour >= 21) ? -4 : 0;
        return base + hourAdjust + gaussianNoise(random, 0, 2.0);
    }

    private double scenarioGap(MockScenario scenario, int day, double baseGap, double peakGap,
                                int rampStartDay, int rampEndDay, Random random) {
        double gap = switch (scenario) {
            case NORMAL -> baseGap;
            case EARLY_DEGRADATION, REPAINT_IMMINENT -> {
                if (day <= rampStartDay) {
                    yield baseGap;
                } else if (day >= rampEndDay) {
                    yield peakGap;
                } else {
                    double progress = (double) (day - rampStartDay) / (rampEndDay - rampStartDay);
                    yield baseGap + (peakGap - baseGap) * progress;
                }
            }
        };
        return gap + gaussianNoise(random, 0, 0.8);
    }

    private double solarRadiation(int hour, String weather, Random random) {
        if (hour < 6 || hour > 19) {
            return 0;
        }
        double daylightCurve = Math.max(0, Math.sin(Math.PI * (hour - 6) / 13.0));
        double weatherMultiplier = switch (weather) {
            case "맑음" -> 1.0;
            case "흐림" -> 0.4;
            default -> 0.1;
        };
        double value = 900 * daylightCurve * weatherMultiplier + gaussianNoise(random, 0, 30);
        return Math.max(0, value);
    }

    private double gaussianNoise(Random random, double mean, double stdDev) {
        return mean + random.nextGaussian() * stdDev;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
