package com.coolroof.monitoring.registry;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * 행정안전부_행정표준코드_법정동코드 서비스(공공데이터포털) 클라이언트.
 *
 * <p>지역명(예: "대전", "대전광역시 유성구")을 넣으면 그 지역명을 포함하는 모든 법정동의
 * 시군구코드(sigunguCd)+법정동코드(bjdongCd)를 실시간으로 조회한다. 특정 지역을 코드에
 * 직접 나열(하드코딩)하지 않기 위한 용도 — 어떤 지역명이 들어와도 동작하며, 반환된
 * {@link LegalDong} 목록은 그대로 {@link BuildingRegistryClient#fetchBuildings}에
 * 넘겨 쓸 수 있다.
 *
 * <p>이 서비스는 위경도를 제공하지 않으므로 {@link LegalDong#lat()}/{@link LegalDong#lng()}는
 * 항상 0.0으로 채워진다 (지도 표시용이 아니라 건축물대장 조회 키로만 쓰기 위함).
 */
@Slf4j
@Component
public class LegalDongCodeClient {

    private static final String ENDPOINT = "https://apis.data.go.kr/1741000/StanReginCd/getStanReginCdList";
    private static final String ABOLISHED_BJDONG_CD = "00000"; // 시군구 대표행(동 단위 아님) — 건축물대장 조회 불가
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 20; // 안전장치 (최대 2만 행)

    private final RestClient restClient = RestClient.create();

    @Value("${legal-dong.service-key}")
    private String serviceKey;

    /**
     * 지역명으로 법정동을 전부 조회한다. "경기도"처럼 결과가 페이지 크기(1000)를 넘는
     * 지역도 있어(2,000행 이상 확인됨) totalCount를 보고 필요한 만큼 자동으로 다음
     * 페이지까지 이어서 가져온다.
     */
    public List<LegalDong> resolveByName(String regionName) {
        List<LegalDong> result = new ArrayList<>();
        int pageNo = 1;
        while (pageNo <= MAX_PAGES) {
            ParsedPage page = fetchPage(regionName, pageNo, PAGE_SIZE);
            result.addAll(page.rows());
            if ((long) pageNo * PAGE_SIZE >= page.totalCount() || page.rows().isEmpty()) {
                break;
            }
            pageNo++;
        }
        return result;
    }

    private ParsedPage fetchPage(String regionName, int pageNo, int numOfRows) {
        String uri = ENDPOINT
                + "?serviceKey=" + serviceKey
                + "&type=xml"
                + "&pageNo=" + pageNo
                + "&numOfRows=" + numOfRows
                + "&locatadd_nm=" + URLEncoder.encode(regionName, StandardCharsets.UTF_8);

        String xml = restClient.get().uri(URI.create(uri)).retrieve().body(String.class);
        return parsePage(xml);
    }

    private record ParsedPage(List<LegalDong> rows, int totalCount) {
    }

    private ParsedPage parsePage(String xml) {
        List<LegalDong> result = new ArrayList<>();
        int totalCount = 0;
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));

            NodeList totalCountNodes = doc.getElementsByTagName("totalCount");
            if (totalCountNodes.getLength() > 0) {
                totalCount = parseIntOrZero(totalCountNodes.item(0).getTextContent());
            }

            NodeList rows = doc.getElementsByTagName("row"); // 이 API는 item이 아니라 row로 응답한다
            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);

                String regionCd = text(row, "region_cd");
                String locatAddNm = text(row, "locatadd_nm");

                if (regionCd.length() != 10) {
                    continue; // 코드 형식이 다르면(비정상 응답) 스킵
                }

                String sigunguCd = regionCd.substring(0, 5);
                String bjdongCd = regionCd.substring(5, 10);

                if (ABOLISHED_BJDONG_CD.equals(bjdongCd)) {
                    continue;
                }

                result.add(new LegalDong(sigunguCd, bjdongCd, locatAddNm, 0.0, 0.0));
            }
        } catch (Exception e) {
            log.warn("법정동코드 응답 파싱 실패", e);
        }
        return new ParsedPage(result, totalCount);
    }

    private int parseIntOrZero(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }
}
