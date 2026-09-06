# 쿨루프 모니터링 플랫폼 — 백엔드

쿨루프 시공 건물의 옥상 표면온도(센서)와 외기 날씨(API)를 수집·저장하고, 주기적인 배치 분석으로 쿨루프 성능 유지 여부를 판정하며 재도장 시점을 안내하는 백엔드 서비스입니다.

## 기술 스택

- Java 17 / Spring Boot 3.x
- Spring Web (REST), Spring Data JPA
- MySQL 8
- Spring Batch / `@Scheduled`
- Redis (분석 결과 캐싱, 선택)
- 외부 연동: 기상청 공공데이터포털 API, OpenAI API

## 데이터 흐름

```
[ESP32] --HTTP POST(표면온도 JSON)--> [수신 API]
                                          ↓ 즉시
                              [기상청 API 호출: 외기온도·일사량]
                                          ↓ 병합(DTO)
                                     [MySQL 원시 적재]
                                          ↓ 주기 실행
                                  [배치 분석: 통계 판정]
                                          ↓
                                  [AI 요약: 행동방향 생성]
                                          ↓
                              [분석결과 저장 → 웹 대시보드 조회]
```

## 실행 준비

### 1. 시크릿 설정

`src/main/resources/application-secret.properties` 파일을 생성하고 아래 키를 채웁니다 (이 파일은 git에 커밋되지 않습니다):

```properties
openai.api-key=
kma.service-key=
```

- `openai.api-key`: OpenAI API 키 (AI 요약 생성용)
- `kma.service-key`: 공공데이터포털 기상청 API 서비스키
  - [기상청_단기예보 조회서비스(기상청API허브 연계)](https://www.data.go.kr/data/15139470/openapi.do) — 실시간 외기온도
  - [기상청_지상(종관, ASOS) 시간자료 조회서비스](https://www.data.go.kr/data/15059218/openapi.do) — 일사량 등 시간 단위 관측값

### 2. 실행

```bash
./gradlew bootRun
```

## REST API (초안)

### 수신
- `POST /api/readings` — ESP32가 표면온도 전송 (수신 즉시 날씨 병합·적재)

### 조회 (대시보드용)
- `GET /api/buildings` — 건물 목록 + 최신 status
- `GET /api/buildings/{id}` — 건물 상세
- `GET /api/buildings/{id}/readings?from=&to=` — 온도 시계열
- `GET /api/buildings/{id}/analysis` — 최신 분석 결과(판정·AI요약)
- `GET /api/dashboard/summary` — 전체 건물 정상/주의/재도장 집계

### 배치 트리거 (내부/수동)
- `POST /api/batch/analyze` — 분석 배치 수동 실행 (시연용)

자세한 도메인 모델, 분석 로직, 판정 규칙은 [backend_claude.md](./backend_claude.md) 참고.

## 팀 구성

| 이름   | 역할       |
|------|----------|
| 오준택  | 백엔드      |
| 임지혁  | 하드웨어(HW) |
| 김태윤  | 디자인      | 
| 김찬우  | 설거지      |
