package com.coolroof.monitoring.search;

import com.coolroof.monitoring.cluster.BuildingThermalModel;
import com.coolroof.monitoring.cluster.ClusterAnalysisResult;
import com.coolroof.monitoring.registry.BuildingRegistryClient;
import com.coolroof.monitoring.registry.LegalDong;
import com.coolroof.monitoring.registry.LegalDongCodeClient;
import com.coolroof.monitoring.registry.RegistryBuilding;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "02. 유사 조건" — 01에서 선택한 실제 건물을 기준으로, 같은 지역의 건물들 중
 * 켜진 토글 조건에 맞는 건물을 건축물대장에서 스캔해 찾는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarBuildingService {

    private static final int MAX_DONGS_TO_SCAN = 20;
    private static final int ROWS_PER_DONG = 20;
    private static final int YEAR_TOLERANCE_YEARS = 5;
    private static final int FLOOR_TOLERANCE_FLOORS = 2;
    private static final double ROOF_AREA_TOLERANCE_RATIO = 0.25;

    private final LegalDongCodeClient legalDongCodeClient;
    private final BuildingRegistryClient buildingRegistryClient;
    private final BuildingThermalModel thermalModel;

    public ClusterAnalysisResult findSimilar(SimilarBuildingRequest req) {
        if (req.region() == null || req.region().isBlank()) {
            throw new IllegalArgumentException("기준 건물의 지역 정보를 확인하지 못했습니다.");
        }

        List<LegalDong> dongs = legalDongCodeClient.resolveByName(req.region());
        if (dongs.isEmpty()) {
            throw new IllegalArgumentException("\"" + req.region() + "\" 지역의 법정동 정보를 찾지 못했습니다.");
        }
        List<LegalDong> scanned = dongs.size() > MAX_DONGS_TO_SCAN ? dongs.subList(0, MAX_DONGS_TO_SCAN) : dongs;

        int buildingsScanned = 0;
        int matchCount = 0;
        for (LegalDong dong : scanned) {
            List<RegistryBuilding> buildings;
            try {
                buildings = buildingRegistryClient.fetchBuildings(dong, ROWS_PER_DONG, 1);
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                log.warn("건축물대장 조회 실패 (건축HUB {}): {}", e.getStatusCode(), dong);
                continue;
            } catch (Exception e) {
                log.warn("건축물대장 조회 실패: {}", dong, e);
                continue;
            }
            buildingsScanned += buildings.size();
            for (RegistryBuilding building : buildings) {
                if (matches(building, req)) {
                    matchCount++;
                }
            }
        }

        String normalizedStructure = thermalModel.normalizeStructure(req.structureType());
        BuildingThermalModel.StructureInfo structInfo = normalizedStructure != null
                ? BuildingThermalModel.STRUCTURE_INFO.get(normalizedStructure)
                : BuildingThermalModel.UNSPECIFIED_STRUCTURE_INFO;

        BuildingThermalModel.FloorBand band = req.floors() != null ? thermalModel.floorBand(req.floors()) : null;
        double floorFactor = band != null ? band.factor() : 1.0;

        BuildingThermalModel.UsageInfo usageInfo = req.usage() != null
                ? BuildingThermalModel.USAGE_INFO.get(req.usage())
                : null;
        double usageFactor = usageInfo != null ? usageInfo.factor() : 1.0;

        BuildingThermalModel.Curve curve = thermalModel.estimate(structInfo, floorFactor, usageFactor);

        String structureLabel = normalizedStructure != null ? normalizedStructure : BuildingThermalModel.UNSPECIFIED_STRUCTURE_LABEL;
        String floorBandLabel = band != null ? band.key() : BuildingThermalModel.UNSPECIFIED_FLOOR_LABEL;
        String usageLabel = usageInfo != null ? usageInfo.label()
                : (req.usage() != null && !req.usage().isBlank() ? req.usage() : BuildingThermalModel.UNSPECIFIED_USAGE_LABEL);

        String aiText = buildAiText(req, structureLabel, floorBandLabel, usageLabel,
                matchCount, scanned.size(), dongs.size(), curve.maxDiff(), curve.avgReduction(), curve.energySavingPct());

        return new ClusterAnalysisResult(
                req.region(), structureLabel, req.floors(), floorBandLabel, usageLabel,
                matchCount, buildingsScanned, scanned.size(), dongs.size(),
                curve.months(), curve.before(), curve.after(),
                curve.maxDiff(), curve.avgReduction(), curve.energySavingPct(), aiText);
    }

    /** 켜져 있는 토글 조건만 필터로 적용한다 — 꺼진 조건은 검사하지 않는다(=전체 포함). */
    private boolean matches(RegistryBuilding building, SimilarBuildingRequest req) {
        if (req.sameUsage() && req.usage() != null) {
            boolean usageMatches = req.usage().equals(building.mainPurpsCdNm())
                    || building.mainPurpsCdNm().contains(req.usage());
            if (!usageMatches) {
                return false;
            }
        }
        if (req.yearTolerance() && req.builtYear() != null) {
            LocalDate approval = building.useApprovalDate();
            if (approval == null || Math.abs(approval.getYear() - req.builtYear()) > YEAR_TOLERANCE_YEARS) {
                return false;
            }
        }
        if (req.floorTolerance() && req.floors() != null) {
            if (Math.abs(building.floorCount() - req.floors()) > FLOOR_TOLERANCE_FLOORS) {
                return false;
            }
        }
        if (req.roofAreaTolerance() && req.roofArea() != null && req.roofArea() > 0) {
            Double buildingRoofArea = building.roofFootprintArea();
            if (buildingRoofArea == null) {
                return false;
            }
            double diffRatio = Math.abs(buildingRoofArea - req.roofArea()) / req.roofArea();
            if (diffRatio > ROOF_AREA_TOLERANCE_RATIO) {
                return false;
            }
        }
        if (req.sameRoofType() && req.roofType() != null && !req.roofType().isBlank()) {
            String buildingRoofType = building.roofType();
            boolean roofTypeMatches = buildingRoofType != null
                    && (buildingRoofType.contains(req.roofType()) || req.roofType().contains(buildingRoofType));
            if (!roofTypeMatches) {
                return false;
            }
        }
        return true;
    }

    private String buildAiText(SimilarBuildingRequest req, String structureLabel, String floorBandLabel, String usageLabel,
                                int matchCount, int dongsScanned, int dongsTotal,
                                double maxDiff, double avgReduction, double energySavingPct) {
        String scanNote = dongsScanned < dongsTotal
                ? String.format("(%s 소속 법정동 %d개 중 %d개 표본 조회)", req.region(), dongsTotal, dongsScanned)
                : String.format("(%s 소속 법정동 %d개 전체 조회)", req.region(), dongsTotal);

        String floorPhrase = req.floors() != null ? String.format("%d층(%s)", req.floors(), floorBandLabel) : floorBandLabel;

        return String.format(
                "선택하신 건물과 유사한 조건(%s / %s / %s)의 건물을 건축물대장에서 확인한 결과 %d건이 확인됐습니다 %s."
                        + " 이 군집은 여름철(7~8월) 표면온도 차이가 최대 %.1f℃까지 벌어지며,"
                        + " 연중 평균 %.1f℃의 저감 효과를 보입니다. 이는 냉방 에너지 사용량을 약 %.1f%% 절감하는 수준입니다.\n\n"
                        + "결론: 이 조건의 건물이라면 쿨루프 시공 효과가 뚜렷하게 나타날 것으로 예상됩니다.",
                structureLabel, floorPhrase, usageLabel,
                matchCount, scanNote, maxDiff, avgReduction, energySavingPct);
    }
}
