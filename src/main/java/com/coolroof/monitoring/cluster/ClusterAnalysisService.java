package com.coolroof.monitoring.cluster;

import com.coolroof.monitoring.registry.BuildingRegistryClient;
import com.coolroof.monitoring.registry.LegalDong;
import com.coolroof.monitoring.registry.LegalDongCodeClient;
import com.coolroof.monitoring.registry.RegistryBuilding;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 자연어 문장 → {지역, 주구조, 층수, 주용도} 추출 → 그 지역의 법정동을 실시간 조회 →
 * 건축물대장에서 조건에 맞는 건물 표본을 실측 → 표면온도 전후 비교 곡선(추정 모델)까지
 * 한 번에 만들어주는 서비스.
 *
 * <p>지역은 어떤 이름이 들어와도 {@link LegalDongCodeClient}가 실시간으로 법정동을 찾아주므로
 * 특정 지역을 코드에 나열(하드코딩)하지 않는다. 다만 응답 시간을 보장하기 위해 한 지역에
 * 법정동이 아주 많을 경우(예: 도 단위) 앞쪽 {@value #MAX_DONGS_TO_SCAN}개까지만 실제로
 * 스캔하고, 그 사실을 결과에 {@code dongsScanned}/{@code dongsTotal}로 그대로 노출한다.
 *
 * <p><b>필수 조건은 지역뿐이다.</b> 사용자가 주구조·층수·주용도를 말하지 않아도(애매하게
 * 물어봐도) 언급된 조건만으로 군집을 잡는다 — 나머지는 "전체"로 두고 그 사실을 결과에
 * 그대로 드러낸다(값을 지어내 채우지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterAnalysisService {

    private static final int MAX_DONGS_TO_SCAN = 20;
    private static final int ROWS_PER_DONG = 20;

    private final QueryExtractionClient queryExtractionClient;
    private final LegalDongCodeClient legalDongCodeClient;
    private final BuildingRegistryClient buildingRegistryClient;
    private final BuildingThermalModel thermalModel;

    public ClusterAnalysisResult analyze(String query) {
        ClusterQuery extracted = queryExtractionClient.extract(query);

        if (extracted.region() == null || extracted.region().isBlank()) {
            throw new IllegalArgumentException("문장에서 지역을 확인하지 못했습니다. 예: \"대전\"처럼 알려주세요.");
        }

        // 주구조/층수는 필수가 아니다 — 언급 안 했으면 그 조건 없이(=전체 포함) 분석한다.
        String structure = extracted.structure() != null && BuildingThermalModel.STRUCTURE_INFO.containsKey(extracted.structure())
                ? extracted.structure()
                : null;
        Integer floors = extracted.floors() != null && extracted.floors() > 0 ? extracted.floors() : null;
        BuildingThermalModel.FloorBand band = floors != null ? thermalModel.floorBand(floors) : null;

        List<LegalDong> dongs = legalDongCodeClient.resolveByName(extracted.region());
        if (dongs.isEmpty()) {
            throw new IllegalArgumentException("\"" + extracted.region() + "\" 지역의 법정동 정보를 찾지 못했습니다.");
        }

        List<LegalDong> scanned = dongs.size() > MAX_DONGS_TO_SCAN ? dongs.subList(0, MAX_DONGS_TO_SCAN) : dongs;

        int buildingsScanned = 0;
        int matchCount = 0;
        for (LegalDong dong : scanned) {
            List<RegistryBuilding> buildings;
            try {
                buildings = buildingRegistryClient.fetchBuildings(dong, ROWS_PER_DONG, 1);
            } catch (org.springframework.web.client.HttpStatusCodeException e) {
                // 건축HUB API 쪽 일시적 오류(타임아웃 등) — 이 법정동만 건너뛰고 계속 진행.
                log.warn("건축물대장 조회 실패 (건축HUB {}): {}", e.getStatusCode(), dong);
                continue;
            } catch (Exception e) {
                log.warn("건축물대장 조회 실패: {}", dong, e);
                continue;
            }
            buildingsScanned += buildings.size();
            for (RegistryBuilding building : buildings) {
                if (matches(building, structure, band, extracted.usage())) {
                    matchCount++;
                }
            }
        }

        BuildingThermalModel.StructureInfo structInfo = structure != null
                ? BuildingThermalModel.STRUCTURE_INFO.get(structure)
                : BuildingThermalModel.UNSPECIFIED_STRUCTURE_INFO;
        BuildingThermalModel.UsageInfo usageInfo = extracted.usage() != null
                ? BuildingThermalModel.USAGE_INFO.get(extracted.usage())
                : null;
        double usageFactor = usageInfo != null ? usageInfo.factor() : 1.0;
        double floorFactor = band != null ? band.factor() : 1.0;

        BuildingThermalModel.Curve curve = thermalModel.estimate(structInfo, floorFactor, usageFactor);

        String structureLabel = structure != null ? structure : BuildingThermalModel.UNSPECIFIED_STRUCTURE_LABEL;
        String floorBandLabel = band != null ? band.key() : BuildingThermalModel.UNSPECIFIED_FLOOR_LABEL;
        String usageLabel = usageInfo != null ? usageInfo.label() : BuildingThermalModel.UNSPECIFIED_USAGE_LABEL;

        String aiText = buildAiText(extracted, structureLabel, floors, floorBandLabel, usageLabel,
                matchCount, scanned.size(), dongs.size(), curve.maxDiff(), curve.avgReduction(), curve.energySavingPct());

        return new ClusterAnalysisResult(
                extracted.region(), structureLabel, floors, floorBandLabel, usageLabel,
                matchCount, buildingsScanned, scanned.size(), dongs.size(),
                curve.months(), curve.before(), curve.after(),
                curve.maxDiff(), curve.avgReduction(), curve.energySavingPct(), aiText);
    }

    /** structure/band가 null이면 그 조건은 걸지 않는다(=전체 포함). */
    private boolean matches(RegistryBuilding building, String structure, BuildingThermalModel.FloorBand band, String usage) {
        if (structure != null) {
            String normalizedStructure = thermalModel.normalizeStructure(building.structureType());
            if (!structure.equals(normalizedStructure)) {
                return false;
            }
        }
        if (band != null) {
            if (!thermalModel.floorBand(building.floorCount()).key().equals(band.key())) {
                return false;
            }
        }
        return usage == null || usage.equals(building.mainPurpsCdNm()) || building.mainPurpsCdNm().contains(usage);
    }

    private String buildAiText(ClusterQuery q, String structureLabel, Integer floors, String floorBandLabel,
                                String usageLabel, int matchCount, int dongsScanned, int dongsTotal,
                                double maxDiff, double avgReduction, double energySavingPct) {
        String scanNote = dongsScanned < dongsTotal
                ? String.format("(%s 소속 법정동 %d개 중 %d개 표본 조회)", q.region(), dongsTotal, dongsScanned)
                : String.format("(%s 소속 법정동 %d개 전체 조회)", q.region(), dongsTotal);

        String floorPhrase = floors != null ? String.format("%d층(%s)", floors, floorBandLabel) : floorBandLabel;

        return String.format(
                "%s / %s / %s / %s 조건과 실제로 일치하는 건물을 건축물대장에서 확인한 결과 %d건이 확인됐습니다 %s."
                        + " 이 군집은 여름철(7~8월) 표면온도 차이가 최대 %.1f℃까지 벌어지며,"
                        + " 연중 평균 %.1f℃의 저감 효과를 보입니다. 이는 냉방 에너지 사용량을 약 %.1f%% 절감하는 수준입니다.\n\n"
                        + "결론: 이 조건의 건물이라면 쿨루프 시공 효과가 뚜렷하게 나타날 것으로 예상됩니다.",
                q.region(), structureLabel, floorPhrase, usageLabel,
                matchCount, scanNote, maxDiff, avgReduction, energySavingPct);
    }
}
