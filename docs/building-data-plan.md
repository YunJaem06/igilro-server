# 서울숲 건물 데이터 검증과 공공데이터 연계 계획

## 최신 상태: 공공 원본 연동 완료

후속 [PK·누락 높이 검증](building-matching-verification-2026-10-26.md)에서 8,208개 식별자·속성 일치와 충돌 5행을 확인했습니다. 기존 높이 연결 1개를 취소하고 원본으로 복귀했으며 추가 확보 높이는 0개입니다. 최신 활성 버전과 검토 목록은 해당 문서를 따릅니다.

아래 OSM 조사는 첫 단계의 기록입니다. 이후 두 키를 준비하여 [공공 자료 수집·검증](public-building-verification-2026-10-26.md)을 진행했습니다. 현재 활성 버전은 공공 건물 14,075개, 직접 높이 5,585개/UNKNOWN 8,490개입니다. GIS/HUB 로컬 CLI 수집기와 PostGIS 검증기를 구현했으며 실제 자료·진단 수치는 링크 문서를 따릅니다. 전체 경로의 수치형 그늘 비율은 여전히 미제공 상태입니다.

## 2026-10-26 실제 원본 확인

테스트 지역은 기존 보행 그래프와 동일한 서울숲 한 곳입니다. WGS84 조사 bbox는 `[127.027,37.533,127.055,37.558]`입니다. bbox에 걸치는 건물은 전체 도형을 보존하며 자르거나 자동 보정하지 않았습니다. 전국 파일에서 이 범위만 추출했고 전국 건물을 DB에 적재하지 않았습니다.

- 원본: [Geofabrik South Korea 2026-10-25 PBF](https://download.geofabrik.de/asia/south-korea-261025.osm.pbf)
- 원본 기준 시각: `2026-10-25T21:21:21Z`
- PBF SHA-256: `395e4e7cdbad3eea862227cb6290433efef274959a6deaf7f15916d17f2f2b2b`
- 추출 원본 GeoJSON SHA-256: `9b6d2f7340728ac64f7f417ef25c905ae50ef89e395388528d150309fe6f9e39`
- 출처: © OpenStreetMap contributors, ODbL 1.0, Geofabrik extract. [OSM 이용 조건](https://www.openstreetmap.org/copyright)

| 항목 | 개수 | 추출 건물 대비 비율 |
| --- | ---: | ---: |
| 조립한 building Polygon/MultiPolygon | 1,324 | 100% |
| building 태그 | 1,324 | 100% |
| height 태그 | 542 | 40.94% |
| height 누락 | 782 | 59.06% |
| building:levels 태그 | 385 | 29.08% |
| height와 building:levels 모두 있음 | 313 | 23.64% |
| 층수만 있고 height 없음 | 72 | 5.44% |
| height와 층수 모두 없음 | 710 | 53.63% |

building 태그 100%는 building으로 선택한 도형 안의 비율입니다. 실제 지역의 모든 건물이 OSM에 등록되었다는 의미가 아닙니다. 높이 값의 존재율도 경로의 평가 커버리지가 아닙니다. 542개 값은 모두 수치로 읽혔으며 원본의 양수 높이를 그대로 보존했습니다. 원본 height 최댓값은 200m지만, 누락된 건물까지 포함한 검증된 최대 높이로 사용하지 않았습니다. 원본 높이의 측량 정확도는 독립 검증하지 않았습니다. 층수에 층고를 곱한 값은 생성하지 않았습니다.

PostGIS로 1,324개 도형을 확인한 결과 유효하지 않거나 빈 도형은 0개였습니다. 모든 적재 형상은 `MultiPolygon, SRID 5179`이고, 각 원본 ID와 높이 종류를 보존합니다. 현재 ACTUAL=0, LEVEL_ESTIMATED=0, OSM=542, UNKNOWN=782입니다.

## 적재 파일과 재현 자료

로컬 파일은 Git에서 제외됩니다.

- 원본 PBF: `data/osm/south-korea-261025.osm.pbf`
- 원본 태그와 도형: `data/osm/seoul-forest-buildings-20261025.raw.geojson`
- 정규화 입력: `data/buildings/seoul-forest-osm-20261025.incomplete-provenance.geojson`
- 정규화 감사 기록: 같은 디렉터리의 `.incomplete-provenance.audit.json`
- 실제 요청/응답: `data/buildings/verification-20261026/`
- 이번 로컬 추출·정규화·적재 보조 스크립트와 로그: `.gradle/local-server-run/`. 공공 공급자 수집 adapter의 구현을 대신하지 않습니다.

최초 OSM 건물 버전은 `buildings-v1-5fb6184338f9abc37b6c5145cd1f059833306e80a77fab06135d04e99a9a94f1`입니다. 현재 공공 버전은 위 최신 문서를 따릅니다. 파일 전체 바이트의 SHA-256이며 파일을 수정하면 새 버전을 만들어야 합니다. 기존 버전은 삭제하지 않고 활성 포인터만 교체합니다. 재적재는 도형·수치·높이 종류·형상/높이 출처와 ID까지 비교합니다.

검증된 최대 높이와 완전한 건물 목록이 없으므로 `inventoryComplete=false`, `maximumHeightMeters=null`로 적재합니다. V4는 이 상태를 보관할 수 있게 하되 평가를 허용하지 않습니다. V5는 건물별 원본 출처를 분리합니다. 과거 숫자만 있는 레코드를 ACTUAL이나 OSM으로 자동 분류하지 않습니다.

예상 낮 응답은 `INCOMPLETE_COVERAGE`, `coverage=0`, `metrics.shadeRatio=null`입니다. OSM 데이터만으로 전체 경로 그늘 평가가 가능하다고 판단하지 않습니다. 실제 서버 검증 결과는 이 문서 하단에 기록합니다.

## 공급자와 높이 분류

형상은 [국토교통부 GIS건물통합정보 WMS/WFS](https://www.data.go.kr/data/15123970/openapi.do)를 우선하고 OSM은 보조·비교용으로 유지합니다. 높이와 건축 속성은 [건축HUB 건축물대장정보 서비스](https://www.data.go.kr/data/15134735/openapi.do)를 결합합니다. 공공데이터포털은 GIS건물통합정보가 건물 공간정보와 건축물대장 속성을 결합한 자료라고 설명하므로, 첫 샘플에서 이미 포함된 직접 높이와 식별자부터 확인합니다. 누락·갱신 차이를 건축HUB로 보완합니다.

ACTUAL은 공공 원본에서 직접 받은 높이, OSM은 원본 height, LEVEL_ESTIMATED는 추후 합의한 추정 정책의 값입니다. UNKNOWN은 높이나 출처가 미확인인 상태입니다. LEVEL_ESTIMATED/UNKNOWN은 가능한 최대 그림자 영역을 미평가로 처리하여 직접 높이 점수와 섞지 않습니다. 추정 정책을 도입할 때는 원본 지상층수·추정식 버전·적용 근거도 별도로 보존해야 하며, 현재는 추정값을 만들지 않습니다.

형상 원본은 `source/sourceId`, 높이 원본은 `heightSourceReference/heightSourceId`에 별도로 저장하므로 GIS 형상과 건축HUB 높이를 합쳐도 두 원본을 보존할 수 있습니다. raw 응답은 공급자·수집 시각·원본 해시와 함께 별도 보관하고, 서비스 적재 파일은 정규화된 값만 사용합니다.

## 좌표계와 SRID 정책

1. 원본 SHP의 `.prj`, WFS의 CRS/축 순서, 공급자 문서를 확인합니다. 미확인 좌표계를 4326이나 5179로 가정하지 않습니다.
2. 수집 adapter는 실제 원본 CRS에서 WGS84(EPSG:4326)의 `[longitude,latitude]`로 변환합니다. 요청한 WFS SRS와 실제 반환 좌표를 표본으로 대조합니다.
3. 서버 적재기의 입력과 coverage는 4326입니다. 건물 형상 저장·면적·거리·IoU·그림자 계산은 EPSG:5179 미터 좌표로 통일합니다. 앱 응답은 4326입니다.
4. `ST_SetSRID`는 좌표계 표시만 바꾸므로 재투영 대신 쓰지 않습니다. 실제 변환에는 [ST_Transform](https://postgis.net/docs/ST_Transform.html)을 사용합니다.
5. 도형 유효성, 좌표 범위, 표본 위치, 4326↔5179 왕복 오차를 검사합니다. 실패 자료를 자동으로 보정하거나 정상 데이터에 섞지 않습니다.

## 건물 매칭 검토

다음은 최초 매칭 계획입니다. 이후 실제 공공 원본에서 PNU·동명·면적의 유일성을 확인한 제한적 매칭을 구현했습니다. 최신 결과와 남은 품질 한계는 위 공공 자료 검증 문서를 따릅니다.

1. 공공 형상과 건축HUB 사이에 공통 건물/대장 식별자가 있는지 원본 스키마로 확인합니다. 건축HUB 공식 안내는 이전 시스템과 PK가 변경되었다고 명시하므로, 기존 PK와 현재 PK를 동일시하지 않고 공식 전환 규칙을 확인합니다.
2. 식별자 일치가 확인된 경우 우선 연계합니다. PNU·주소는 후보를 좁히는 데 사용하며, 한 필지의 여러 동이나 총괄표제부를 개별 건물로 복제하지 않습니다.
3. OSM과 공공 형상의 후보를 같은 5179 좌표계에서 찾고 교차 면적·IoU·면적 비율·중심 거리·주소를 함께 비교합니다. 가장 가까운 건물 하나에 높이를 자동 복사하지 않습니다.
4. 건물 부분/전체, 1:N·N:1, 중복 도형, 신축·철거·기준일 차이는 별도 검토 대상으로 둡니다. 후보가 모호하면 UNKNOWN을 유지합니다.
5. 서울숲의 직접 일치·주소만 일치·형상 차이·공공/OSM 단독 건물을 표본으로 확인한 뒤 임계값을 정합니다. 임의 IoU나 거리 임계값을 검증된 기준으로 확정하지 않습니다.
6. 연계표에는 양쪽 원본 ID, 매칭 방식, 비교 지표, 결정/보류 사유와 검토 시각을 보존합니다. 높이를 덮어쓰는 대신 원본·관측 값과 선택 근거를 남깁니다.

## 공공 원본 수집에 필요한 준비

최초 OSM 조사 당시에는 로컬 공공 건물 파일과 공급자 API 키가 준비되지 않았습니다. 이후 두 키로 공공 자료 연동을 완료했습니다. [브이월드 GIS건물통합정보 다운로드](https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=18)는 로그인 후 이용하도록 안내합니다. 건축HUB Open API는 공공데이터포털 활용신청을 거쳐야 합니다. 인증정보를 코드·커밋·로그에 넣지 않고 수집 전용 환경으로 주입합니다.

이후 키 발급 안내와 로컬 `.env` 자동 로드를 준비했고, `igilro-infra`에는 `building_provider_secrets`로 기존 Secrets Manager ARN을 연결하는 AWS 주입을 구현했습니다. application 태스크에만 `VWORLD_API_KEY`/`BUILDING_HUB_API_KEY`를 전달하며 `VWORLD_DOMAIN`은 선택적인 일반 환경 변수입니다. 실제 키는 Terraform 입력이나 state로 전달하지 않습니다. 등록·적용·갱신 절차는 인프라 저장소의 `docs/building-provider-secrets.md`에 있습니다. AWS 키 주입은 준비 상태이며 실제 AWS 적용은 수행하지 않았습니다. 이후 로컬 공공 수집 CLI는 구현했고 AWS 별도 수집 태스크는 후속 작업입니다.

첫 계획은 서울숲과 그림자 영향을 고려한 주변 범위만 대상으로 하는 것이며, 이후 실제 수집 범위와 한계는 위 최신 문서에 기록했습니다. 전체 목록·직접 높이·누락 높이·검증된 상한을 확보하고 위 매칭을 검증한 뒤 새 버전으로 재적재합니다. API 신청이나 전국 다운로드는 이번에 실행하지 않았습니다.

## 실제 실행 검증

건물 적재를 두 번 실행해 첫 실행 신규 저장, 두 번째 `reused=true`를 확인했습니다. V1~V5 migration이 모두 성공했고, 활성 자료에서 원본 형상 출처/ID 누락 0개, 분류된 높이의 원본 출처/ID 누락 0개, 잘못된 SRID·빈 도형·유효하지 않은 형상 0개를 확인했습니다. 기존 OSM 보행 그래프 버전은 유지했습니다.

실행 중인 로컬 서버에 같은 출발지 `(127.0374,37.5445)`와 도착지 `(127.0430,37.5480)`를 요청했습니다. 네 경우 모두 HTTP 200, WGS84 경로 800.8m / 573초를 반환했고, 건물 적재 전과 경로 좌표·거리·시간이 일치했습니다. evaluatedAt은 각 요청 departureAt의 동일한 순간입니다. requestId도 요청/응답 헤더를 대조했습니다.

| departureAt (KST) | 상태 | coverage | shadeRatio |
| --- | --- | --- | --- |
| 2026-10-26 12:00 | INCOMPLETE_COVERAGE | 0 | null |
| 2026-10-26 00:00 | NIGHT | null | null |
| 2026-03-21 06:45 | LOW_SUN | null | null |
| 2200-01-01 12:00 | UNSUPPORTED_TIME | null | null |

낮의 태양 고도는 약 40.2672도, 건물 modelVersion은 `building-prism-v2-noaa-5179-minsun5-height-source`입니다. 건물 버전과 OSM 출처도 응답에 포함됩니다. `/api/v1/health`와 `/actuator/health`는 UP입니다. 이 검증은 불완전한 실제 자료의 상태 처리가 맞는지 확인한 것이며, 실제 서울숲에서 수치형 그늘 비율을 검증한 결과가 아닙니다.

서버 `test integrationTest bootJar`가 성공했습니다. 단위/API/아키텍처 92개와 실제 PostGIS 통합 29개, 총 121개이며 실패·오류·건너뜀 0개입니다. 직접 높이의 그림자 연산, 추정/미분류 높이의 평가 제외, 원본 출처 저장과 재적재 충돌을 포함합니다. 수치형 AVAILABLE 결과의 통합 검증에는 합성 도형을 사용했고, 합성 자료는 개발 서비스 DB에 넣지 않았습니다.

인프라 테스트는 고정된 migration 개수 대신 제공된 서버 JAR의 버전 목록과 실제 DB 이력을 대조하도록 보완했습니다. CI의 기존 V3 고정 서버와 로컬 V5 서버에 같은 검증을 사용할 수 있습니다. Windows에서 UTF-8 Java 로그를 CP949로 읽는 오류도 발견해 마이그레이션 실행기와 테스트의 로그 읽기를 명시적 UTF-8로 수정했습니다.

수정 후 인프라 Python 단위 테스트 27개와 별도 임시 TLS PostGIS 통합 테스트 7개가 모두 통과했습니다. 후자는 새 V4/V5 migration, 반복 실행, 읽기 전용 앱 계정, migration 권한 없이 prod 앱 실행과 health를 검증합니다. 테스트에는 모의 Secrets Manager와 임시 컨테이너를 사용했습니다. 개발 DB 외 운영 DB·AWS 리소스 변경과 커밋/푸시는 실행하지 않았습니다.
