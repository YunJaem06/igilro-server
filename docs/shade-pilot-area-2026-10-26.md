# 작은 그늘 평가 구역 후보 조사 — 2026-10-26

지상 보행로의 **50m 시험 경로 3곳을 선정하고 실제 API 연결을 검증**했다. 현재 자료로 그늘 수치를 제공하도록 승인한 구역은 **0곳**이다. 우선 반경의 높이 누락이 없어도 다른 원본과의 형상 차이·주변 미확인 건물·높이 상한 문제가 남는다. `inventoryComplete`를 변경하거나 추정 높이를 만들지 않았다.

## 후보 선정과 범위

`scripts/plan-shade-pilot.py`가 원본 OSM XML의 체크섬을 활성 보행 그래프와 대조한다. `bridge/tunnel/covered/indoor`, 0이 아닌 `layer/level`, 고가·지하·옥상·계단 태그가 있는 도로는 제외한다. 1,633개 원본 way 중 1,418개가 태그 조건을 통과했다. 태그가 없다는 사실만으로 실제 지상 환경이 검증되었다고 보지는 않는다.

초기 단순 거리 분석에서 유리했던 구간은 `bridge=yes, layer=2`인 교량 보행로였으므로 제외했다. 최종 조사는 100m 격자에서 가까운 실제 지상 태그 적격 도로를 찾고, 길이 50m 이상인 edge로 한정한 **334개 위치**에 대한 표본 조사다. 서울숲 모든 가능한 위치를 전수 조사한 결과는 아니다.

후보마다 세 경계를 만든다. 핵심 반경 50m는 실제 50m 보행 경로를 검토할 범위, 우선 반경 100m는 첫 건물 대조 범위, 주변 반경 500m는 추가 문제를 찾기 위한 조사 범위다. 어느 거리도 그림자 안전 상한을 보장하지 않는다. 그림자 영향은 시간·방향·높이와 건물 목록에 따라 별도 검증해야 한다.

미확인 높이·충돌이 적은 후보를 우선하고, 같은 조건에서는 직접 높이가 있는 건물의 검증 구역을 먼저 선택한다. 후보 간 중심 거리를 100m 이상 띄웠다. 보행 edge는 핵심 범위 안의 50m 선분으로 자르고 실제 경로 API로 다시 확인했다.

| 후보 | 중심 `[경도,위도]` | 우선 반경 GIS 행 | 그중 높이 미확인 | 주변 반경 높이 미확인 | OSM 재대조 행 |
| --- | --- | ---: | ---: | ---: | ---: |
| 1: 성수중·고등학교 주변 | `[127.0373317,37.5481620]` | 3 | 0 | 301 | 2 |
| 2 | `[127.0296344,37.5414115]` | 0 | 0 | 13 | 0 |
| 3 | `[127.0363893,37.5397444]` | 0 | 0 | 35 | 0 |

첫 후보의 직접 높이는 22.5m, 13.3m, 22.5m다. 후보 2·3은 자료상 가까운 건물이 없는 대조 구역이지만, 빈 조회 결과만으로 실제 전체 건물 목록의 완전성이나 그늘 없음이 확인되는 것은 아니다.

## 첫 후보의 교차 자료 조사

기존 OSM 건물 원본과 GIS의 교차 면적을 비교했다. 첫 후보의 OSM 3행 중 2행은 GIS가 덮는 면적이 검토용 기준인 95%보다 작았다. 원본은 `way/1212822991` 약 2.55%, `way/476442525` 약 83.24%다. 이 95%는 자동 매칭 승인 기준이 아니며, 건물 부분·지붕·증축·갱신 시점의 차이를 대조할 신호다. 두 OSM 원본에도 직접 높이가 없다.

추가로 [브이월드 WMS/WFS 공식 레퍼런스](https://www.vworld.kr/dev/v4dv_wmsguide2_s001.do)의 도로명주소 건물 `lt_c_spbd`를 반경 약 150m bbox로 조회했다. API 버전은 2.0.0, 응답·bbox 좌표는 3857로 지정했다. 11행 전부를 받아 잘림이 없음을 확인한 뒤 5179에서 반경 100m를 대조했다. 기존 키는 `.env`에서만 읽고, 인증 URL·키를 캐시·로그·지도에 저장하지 않았다.

동일한 100m에 주소 건물 **5행**, GIS **3행**이 있었다. 주소 자료에는 식별자·주소·지상층수 등이 있으나 이번 응답에 직접 높이 필드는 없었다. 원본별 건물 분할 방식이 다를 수 있으므로 숫자 차이만으로 실제 건물 두 개가 빠졌다고 결론내리지 않는다. OSM과 주소 도형 사이에도 차이가 남아 있으며 층수를 높이로 바꾸거나 후보 높이를 복사하지 않았다.

주소 대조 기록은 `address-reference-verified/audit.json`, 인증정보를 제거한 응답은 `address-reference/`에 보관했다. `scripts/check-pilot-address-reference.py`로 같은 확인을 재현할 수 있다. 현재 주소 자료는 검수용 참조이며 서비스 건물 목록에 합치지 않았다.

## 실제 API와 변경 범위

세 후보 모두 실제 `POST /api/v1/routes/search`가 HTTP 200, **50.0m / 36초**를 반환했다. 그래프·건물 버전, requestId, departureAt/evaluatedAt의 동일 순간을 검사했다. 응답은 모두 `INCOMPLETE_COVERAGE`, `shadeRatio=null`이다. 첫 로컬 응답은 약 15~47ms, 최종 반복은 약 15~188ms였다. 부하 검증이나 수치형 그림자 집계 성능으로 해석하지 않는다.

서비스 건물 버전은 계속 `buildings-v1-a620390ad03e64ca19d2657fac3e20924a54260afe92a557cef373282cf56203`, 보행 그래프는 `osm-v1-83740be9358cef8de74cc4039d9835e9f90883e1dd2ba72ede48459c25968795`다. 후보 조사·참조 도형 비교는 조회와 롤백되는 임시 테이블만 사용했다. 활성 데이터와 업무 API 계약은 변경하지 않았다.

## 검수 지도

[로컬 검수 지도](http://127.0.0.1:8090/#pilot=seoul-forest-candidate-1)에서 ‘작은 평가 구역 후보’를 선택한다. 실제 서버 경로, 핵심·우선·주변 경계, OSM 비교 도형과 원본 ID를 표시한다. 첫 후보는 기본 100m에 공공 건물 3개를 보여 준다. ‘주변 미확인 건물 살펴보기’를 누르면 500m의 301개를 표시한다.

지도에는 ‘평가 보류’, OSM 재대조 2행, 주소 5행/GIS 3행과 형상 차이 안내를 함께 표시한다. 범위가 작거나 미확인 높이가 0이라는 이유로 평가 가능 색상을 표시하지 않는다. 지도 검수 메모를 저장해도 평가 허용 플래그와 원본 높이는 바뀌지 않는다.

## 재현

Python 3.12와 `scripts/requirements-public-buildings.txt`, 로컬 PostGIS와 실행 중인 서버가 필요하다. 출력이 이미 있으면 새 폴더나 파일을 사용한다.

```powershell
python scripts/plan-shade-pilot.py --snapshot data/public-buildings/seoul-forest-matching-v2-20261026 --roads-osm data/osm/seoul-forest-20261025.osm --osm-version buildings-v1-5fb6184338f9abc37b6c5145cd1f059833306e80a77fab06135d04e99a9a94f1 --core-radius 50 --audit-radius 100 --output data/public-buildings/pilot-area-final-20261026
python scripts/verify-shade-pilot.py --report data/public-buildings/pilot-area-final-20261026/report.json --output data/public-buildings/pilot-area-final-20261026/report.with-routes.json
python scripts/check-pilot-address-reference.py --report data/public-buildings/pilot-area-final-20261026/report.with-routes.json --candidate 1 --output data/public-buildings/pilot-area-final-20261026/address-reference-verified
```

마지막 명령은 기본적으로 인증된 외부 조회를 수행한다. 이번 원본을 재사용할 때는 `--offline --response-cache data/public-buildings/pilot-area-final-20261026/address-reference`를 추가한다. 키가 HTML에 포함되지는 않는다.

기존 검수 지도 생성 명령에 다음을 추가하면 후보와 비교 도형이 포함된다.

```text
--pilot-plan data/public-buildings/pilot-area-final-20261026/report.with-routes.json
--osm-buildings data/buildings/seoul-forest-osm-20261025.incomplete-provenance.geojson
--reference-audit data/public-buildings/pilot-area-final-20261026/address-reference-verified/audit.json
```

주요 로컬 산출물은 `data/public-buildings/pilot-area-final-20261026/`의 보고서·후보 GeoJSON·실제 요청/응답·주소 참조 기록이다. 지도와 결과 화면은 `data/review/building-map-20261026/index.html`, `review-pilot.png`다. 원본·산출물·메모는 Git에서 제외된다.

## 검증과 다음 조건

Python 데이터 준비·지도·후보 조사 테스트 **40개**, Node 검수 메모 테스트 **6개**가 통과했다. 빈 구역의 자동 승인 방지, 주변 미확인 높이, 잘못된 높이 상한, 경계·OSM 불일치, 교량·터널 제외, 후보 분리, 지도 버전·승인 상태 검증과 키 제거를 포함한다. 브라우저에서 후보 선택·실제 경로·3행 목록·301행 필터를 확인했다. 기존 메모의 저장/내보내기/가져오기 동작도 검증했다.

평가를 실제 허용하려면 첫 후보의 원본별 형상 차이를 해소해 조사 범위의 건물 목록을 확인하고, 추가·변경 건물의 직접 높이 또는 검증된 상한을 확보해야 한다. 주변에서 들어오는 그림자 영향과 교량·실내 등 현장 맥락도 확인한다. 근거가 확보된 구역만 별도 범위로 적재하고 실제 API에서 AVAILABLE/전체 coverage를 검증하는 단계가 남아 있다. 이번 조사로 그 조건을 충족했다고 선언하지 않는다.
