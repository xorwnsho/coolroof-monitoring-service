package com.coolroof.monitoring.search;

import com.coolroof.monitoring.geocode.KakaoAddressClient;
import com.coolroof.monitoring.geocode.KakaoAddressResult;
import com.coolroof.monitoring.registry.BuildingRegistryClient;
import com.coolroof.monitoring.registry.RegistryBuilding;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "건물명, 주소 또는 건물 ID를 입력하세요" 검색 — 카카오로 주소를 해석해 법정동코드+지번을 얻고,
 * 그 지번을 건축HUB에 조회해서 실제 등록된 건물 표제부 정보를 가져온다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingSearchService {

    // 카카오 주소 검색은 여러 후보를 줄 수 있는데, 후보마다 건축HUB를 다시 부르면 느려지므로
    // 응답 시간을 보장하기 위해 앞쪽 몇 개까지만 실제로 조회한다.
    private static final int MAX_ADDRESS_CANDIDATES = 5;

    private final KakaoAddressClient kakaoAddressClient;
    private final BuildingRegistryClient buildingRegistryClient;

    public List<BuildingSearchResult> search(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("건물명, 주소 또는 건물 ID를 입력해 주세요.");
        }

        List<KakaoAddressResult> addresses = kakaoAddressClient.search(query.trim());
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("\"" + query + "\"에 해당하는 주소를 찾지 못했습니다.");
        }

        List<KakaoAddressResult> candidates = addresses.size() > MAX_ADDRESS_CANDIDATES
                ? addresses.subList(0, MAX_ADDRESS_CANDIDATES)
                : addresses;

        List<BuildingSearchResult> results = new ArrayList<>();
        for (KakaoAddressResult address : candidates) {
            List<RegistryBuilding> buildings;
            try {
                buildings = buildingRegistryClient.fetchByAddress(
                        address.sigunguCd(), address.bjdongCd(),
                        address.mainAddressNo(), address.subAddressNo());
            } catch (Exception e) {
                log.warn("건축물대장 조회 실패: {}", address, e);
                continue;
            }
            for (RegistryBuilding building : buildings) {
                results.add(new BuildingSearchResult(address.addressName(), building));
            }
        }

        if (results.isEmpty()) {
            throw new IllegalArgumentException("\"" + query + "\" 주소는 찾았지만, 건축물대장에 등록된 건물 정보가 없습니다.");
        }
        return results;
    }
}
