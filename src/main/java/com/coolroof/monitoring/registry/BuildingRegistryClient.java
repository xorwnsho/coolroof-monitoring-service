package com.coolroof.monitoring.registry;

import java.io.StringReader;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * 국토교통부_건축HUB_건축물대장정보 서비스 (공공데이터포털) 클라이언트.
 * 법정동(sigunguCd+bjdongCd) 단위로 등록된 건물 표제부 목록을 조회한다.
 */
@Slf4j
@Component
public class BuildingRegistryClient {

    private static final String ENDPOINT = "http://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RestClient restClient = RestClient.create();

    @Value("${building-registry.service-key}")
    private String serviceKey;

    public List<RegistryBuilding> fetchBuildings(LegalDong legalDong, int numOfRows, int pageNo) {
        String uri = ENDPOINT
                + "?serviceKey=" + serviceKey
                + "&sigunguCd=" + legalDong.sigunguCd()
                + "&bjdongCd=" + legalDong.bjdongCd()
                + "&numOfRows=" + numOfRows
                + "&pageNo=" + pageNo;

        String xml = restClient.get().uri(URI.create(uri)).retrieve().body(String.class);
        return parseItems(xml);
    }

    private List<RegistryBuilding> parseItems(String xml) {
        List<RegistryBuilding> result = new ArrayList<>();
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);

                String mainPurpsCdNm = text(item, "mainPurpsCdNm");
                Double totalFloorArea = parseDouble(text(item, "totArea"));
                Integer floorCount = parseInt(text(item, "grndFlrCnt"));
                LocalDate useApprovalDate = parseDate(text(item, "useAprDay"));

                if (mainPurpsCdNm.isBlank() || totalFloorArea == null || totalFloorArea <= 0
                        || floorCount == null || floorCount <= 0 || useApprovalDate == null) {
                    continue; // 표제부 항목 중 필수 값이 비어있는 레코드는 분석에 못 쓰므로 제외
                }

                result.add(new RegistryBuilding(
                        text(item, "platPlc"),
                        text(item, "newPlatPlc"),
                        text(item, "bldNm"),
                        mainPurpsCdNm,
                        totalFloorArea,
                        floorCount,
                        useApprovalDate,
                        text(item, "roofCdNm")));
            }
        } catch (Exception e) {
            log.warn("건축물대장 응답 파싱 실패", e);
        }
        return result;
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0 || nodes.item(0).getTextContent() == null) {
            return "";
        }
        return nodes.item(0).getTextContent().trim();
    }

    private Double parseDouble(String value) {
        try {
            return value.isBlank() ? null : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        try {
            return value.isBlank() ? null : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return value.isBlank() ? null : LocalDate.parse(value, DATE_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }
}
