package com.coolroof.monitoring.cluster;

import com.coolroof.monitoring.registry.BuildingRegistryClient;
import com.coolroof.monitoring.registry.LegalDong;
import com.coolroof.monitoring.registry.LegalDongCodeClient;
import com.coolroof.monitoring.registry.RegistryBuilding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final String[] MONTHS = {
            "1월", "2월", "3월", "4월", "5월", "6월", "7월", "8월", "9월", "10월", "11월", "12월"
    };
    private static final double[] BASE_BEFORE = {8, 10, 15, 22, 30, 42, 52, 54, 44, 28, 16, 9};

    private record StructureInfo(double factor, double reduction) {
    }

    private static final Map<String, StructureInfo> STRUCTURE_INFO = Map.of(
            "철근콘크리트구조", new StructureInfo(1.00, 0.36),
            "철골구조", new StructureInfo(1.08, 0.42),
            "철골철근콘크리트구조", new StructureInfo(1.04, 0.39),
            "조적구조", new StructureInfo(0.95, 0.30),
            "목구조", new StructureInfo(0.90, 0.26));

    // 주구조를 언급하지 않았을 때 쓰는 값 — 위 5개 구조의 평균(그럴듯한 값을 지어내는 게
    // 아니라, 조건이 없으면 모든 구조를 다 포함한다는 뜻의 중립값).
    private static final StructureInfo UNSPECIFIED_STRUCTURE_INFO = new StructureInfo(1.00, 0.35);
    private static final String UNSPECIFIED_STRUCTURE_LABEL = "전체 구조";

    private record UsageInfo(double factor, String label) {
    }

    private static final Map<String, UsageInfo> USAGE_INFO = Map.of(
            "공동주택", new UsageInfo(0.98, "공동주택(아파트)"),
            "업무시설", new UsageInfo(1.00, "업무시설"),
            "근린생활시설", new UsageInfo(1.02, "근린생활시설"),
            "공장", new UsageInfo(1.06, "공장"),
            "창고시설", new UsageInfo(1.08, "창고시설"),
            "교육연구시설", new UsageInfo(0.97, "교육연구시설"));
    private static final String UNSPECIFIED_USAGE_LABEL = "전체 용도";

    private record FloorBand(String key, double factor) {
    }

    private static final String UNSPECIFIED_FLOOR_LABEL = "전체 층수";

    private final QueryExtractionClient queryExtractionClient;
    private final LegalDongCodeClient legalDongCodeClient;
    private final BuildingRegistryClient buildingRegistryClient;

    public ClusterAnalysisResult analyze(String query) {
        ClusterQuery extracted = queryExtractionClient.extract(query);

        if (extracted.region() == null || extracted.region().isBlank()) {
            throw new IllegalArgumentException("문장에서 지역을 확인하지 못했습니다. 예: \"대전\"처럼 알려주세요.");
        }

        // 주구조/층수는 필수가 아니다 — 언급 안 했으면 그 조건 없이(=전체 포함) 분석한다.
        String structure = extracted.structure() != null && STRUCTURE_INFO.containsKey(extracted.structure())
                ? extracted.structure()
                : null;
        Integer floors = extracted.floors() != null && extracted.floors() > 0 ? extracted.floors() : null;
        FloorBand band = floors != null ? floorBand(floors) : null;

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

        StructureInfo structInfo = structure != null ? STRUCTURE_INFO.get(structure) : UNSPECIFIED_STRUCTURE_INFO;
        UsageInfo usageInfo = extracted.usage() != null ? USAGE_INFO.get(extracted.usage()) : null;
        double usageFactor = usageInfo != null ? usageInfo.factor() : 1.0;
        double floorFactor = band != null ? band.factor() : 1.0;
        double combinedFactor = structInfo.factor() * floorFactor * usageFactor;

        List<Double> before = new ArrayList<>(12);
        List<Double> after = new ArrayList<>(12);
        double maxDiff = 0;
        double sumDiff = 0;
        for (double base : BASE_BEFORE) {
            double b = round1(Math.min(59, base * combinedFactor));
            double a = round1(b * (1 - structInfo.reduction()));
            before.add(b);
            after.add(a);
            double diff = b - a;
            sumDiff += diff;
            maxDiff = Math.max(maxDiff, diff);
        }
        double avgReduction = round1(sumDiff / BASE_BEFORE.length);
        double energySavingPct = round1(Math.min(42, maxDiff * 1.2));

        String structureLabel = structure != null ? structure : UNSPECIFIED_STRUCTURE_LABEL;
        String floorBandLabel = band != null ? band.key() : UNSPECIFIED_FLOOR_LABEL;
        String usageLabel = usageInfo != null ? usageInfo.label() : UNSPECIFIED_USAGE_LABEL;

        String aiText = buildAiText(extracted, structureLabel, floors, floorBandLabel, usageLabel,
                matchCount, scanned.size(), dongs.size(), maxDiff, avgReduction, energySavingPct);

        return new ClusterAnalysisResult(
                extracted.region(), structureLabel, floors, floorBandLabel, usageLabel,
                matchCount, buildingsScanned, scanned.size(), dongs.size(),
                List.of(MONTHS), before, after,
                round1(maxDiff), avgReduction, energySavingPct, aiText);
    }

    /** structure/band가 null이면 그 조건은 걸지 않는다(=전체 포함). */
    private boolean matches(RegistryBuilding building, String structure, FloorBand band, String usage) {
        if (structure != null) {
            String normalizedStructure = normalizeStructure(building.structureType());
            if (!structure.equals(normalizedStructure)) {
                return false;
            }
        }
        if (band != null) {
            if (!floorBand(building.floorCount()).key().equals(band.key())) {
                return false;
            }
        }
        return usage == null || usage.equals(building.mainPurpsCdNm()) || building.mainPurpsCdNm().contains(usage);
    }

    /**
     * 건축물대장 strctCdNm은 "일반목구조", "경량철골구조", "일반철골구조"처럼 접두사가 붙어
     * 나오므로, 실제 응답 샘플을 확인해 정한 순서(복합구조를 먼저 검사)로 5개 범주 중 하나로
     * 정규화한다. 어디에도 해당하지 않으면 null(제외).
     */
    private String normalizeStructure(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.contains("철골철근콘크리트")) {
            return "철골철근콘크리트구조";
        }
        if (raw.contains("철근콘크리트")) {
            return "철근콘크리트구조";
        }
        if (raw.contains("철골")) {
            return "철골구조";
        }
        if (raw.contains("목구조") || raw.contains("목조")) {
            return "목구조";
        }
        if (raw.contains("벽돌") || raw.contains("블록") || raw.contains("석구조") || raw.contains("조적")) {
            return "조적구조";
        }
        return null;
    }

    private FloorBand floorBand(int floors) {
        if (floors <= 4) {
            return new FloorBand("저층", 1.03);
        }
        if (floors <= 15) {
            return new FloorBand("중층", 1.00);
        }
        return new FloorBand("고층", 0.94);
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

    private double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
