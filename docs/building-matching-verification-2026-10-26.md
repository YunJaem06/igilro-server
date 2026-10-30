# 서울숲 누락 높이·건물 매칭 검증 — 2026-10-26

[첫 공공 건물 적재](public-building-verification-2026-10-26.md) 후 같은 원본을 재검토했다. 공식 PK 전환과 속성 교차 검증에서 8,208개가 일치했고 충돌 5행을 별도 검토 대상으로 분리했다. 추가 직접 높이는 0개다. 기존 필지·동명·면적 매칭에서 잘못 선택했던 높이 1개를 GIS 원본으로 복귀시켰다. 실제 높이의 현장 검증이나 누락 높이 보완 완료라는 뜻은 아니다.

## 공식 PK와 구현 변경

공식 근거는 [건축HUB PK 전환 공지](https://www.hub.go.kr/portal/bbs/ntc/idx-ntc-detail.do?selectedId=254)와 [공공데이터포털 공식 가이드 ZIP](https://www.data.go.kr/data/15134735/openapi.do)의 첨부 PDF다. 공공데이터포털의 정상 다운로드 한도 확인이 허용된 뒤 ZIP을 받았다. 다음 원본을 로컬 `data/public-buildings/reference/`에 보관했다.

| 자료 | SHA-256 |
| --- | --- |
| `hub-pk-conversion.pdf` | `37f53760fd3d669c5229320c1610f9638e74ee5010ff9831637d511550d23c3a` |
| `hub-city-codes.pdf` | `cdd6b793a5f4d85fb6f58e731b0c7fbc403fcd71981a363505040779218c90f7` |

전환 규칙은 22자리 일련번호를 그대로 사용하고, 짧은 기존 건축물대장 일련번호에는 통합분류코드와 실대장 구분을 결합하는 방식이다. 규칙 PDF 2쪽과 코드표 1쪽을 시각적으로 확인했다. 이번 코드는 실제 시험 자료의 중구·성동구·광진구·동대문구·강남구만 명시적으로 지원한다. 전국 시군구 변환기로 확장하지 않았다. GIS의 `regstr_se_code` 일반/집합 구분을 실대장/폐말소 구분으로 오인하지 않는다. 폐말소 대장은 조회·복사하지 않았다.

`scripts/building_sources.py`에서 변환 PK를 우선 조회하고 PNU·시군구·동명·면적·지상층수·사용승인일을 대조한다. 누락 속성은 같다고 주장하지 않으며 존재하는 양쪽 값이 충돌하면 연결하지 않는다. 같은 PK를 여러 GIS 행이 사용하거나 HUB에서 유일하지 않으면 보류한다. PK가 없거나 충돌해도 다른 대장으로 필지·면적 매칭을 우회하지 않는다. 기존 PK 자체가 없는 경우만 이전의 엄격한 필지·동명·면적 후보 규칙을 허용하며 이번 실자료에서는 이 방식으로 추가 연결된 행이 없었다.

공공 원본의 직접 높이는 ACTUAL, 누락은 UNKNOWN으로 유지한다. 두 직접 높이가 상충하면 원본을 덮어쓰지 않고 검토 대상으로 기록한다. PK 충돌로 HUB 연결을 취소해도 GIS 원본에 직접 높이가 있으면 그 원본 출처로 보존한다. 이 값의 물리적 정확성은 별도 문제이며 불완전한 자료로 전체 경로 점수를 만들지 않는다.

| PK 대조 결과 | 행 수 |
| --- | ---: |
| PK·필지·존재하는 대장 속성 일치 | 8,208 |
| GIS 기존 대장 ID 없음 | 4,368 |
| 변환 PK가 수집된 HUB 표제부에 없음 | 1,494 |
| 여러 GIS 행이 같은 PK 사용 | 2 |
| 면적 / 지상층수 / 사용승인일 충돌 | 1 / 1 / 1 |

위 검증은 식별자와 대장 속성의 일치이며 GIS 형상이 실제 건물을 정확하게 표현한다는 전수 검증은 아니다. 원본 중복·지붕과 법정 건축면적의 차이는 계속 검토해야 한다.

## 누락 사유와 수정 결과

| 새 분류의 미확인 높이 사유 | 건물 수 |
| --- | ---: |
| PK를 연결했으나 직접 높이 없음 | 3,404 |
| 동일 필지 대장에도 직접 높이 없음 | 879 |
| 일치하는 HUB 필지 없음 | 2,448 |
| 필지에 높이 후보가 있지만 건물 식별 불가 | 1,756 |
| 중복 PK / 사용승인일 충돌로 보류 | 2 / 1 |
| 합계 | 8,490 |

추가 높이 0개, 잃은 높이 0개다. 직접 높이 5,585개/UNKNOWN 8,490개를 유지했다. 선택 출처는 HUB 4,804개, GIS 781개다. 기존보다 HUB 출처 선택이 484개 늘어난 것은 검증 근거가 강화된 결과이며 높이가 새로 생긴 것은 아니다.

건물 원본 ID가 `11200:46354:1984204671044483642100000000:…`인 행은 기존 면적 기반 연결에서 HUB 13.3m를 선택했다. 공식 PK 연결은 지상층수가 충돌하므로 취소하고 GIS 직접 높이 10.7m로 되돌렸다. 이 변경은 공공 원본 간 연결 오류 수정이며 10.7m의 현장 실측을 확인했다는 뜻은 아니다. 원래 값·선택 출처·변경 사유를 감사 기록에 보존했다.

`scripts/audit-building-matches.py`는 같은 형상 원본인지 식별자 집합을 비교하고 변경된 높이·출처와 우선순위별 검토 목록을 생성한다. `review-queue.json`에는 충돌 5행(높이 미확인 3행과 직접 높이가 남은 2행), 건물 식별 보류 1,756행, 자료 보완 대상 6,731행, 총 8,492행이 있다. 높이 누락 건수와 검토 목록 건수는 다르다.

## OSM 도형 교차 검토

`scripts/compare-building-footprints.py`가 두 버전을 지정해 읽기 전용 PostGIS에서 교차 면적, IoU, 양쪽 면적 대비 겹침, 중심 거리를 계산한다. 도형 비교는 5179 미터 좌표를 사용한다. 임계값은 검토용 민감도이며 승인된 자동 연결 정책이 아니다. 높이를 복사하거나 추정하지 않는다.

| 최소 IoU | 도형 쌍 | 공공 높이 미확인·OSM 높이 있음 |
| --- | ---: | ---: |
| 0.95 | 9 | 0 |
| 0.90 | 26 | 0 |
| 0.80 | 179 | 3 |
| 0.70 | 424 | 16 |
| 0.50 | 769 | 48 |

IoU ≥0.95, 각 도형의 95% 이상 겹침, 중심 거리 ≤2m인 검토 구간은 9쌍이며 보완 대상은 0개다. 양쪽 높이가 있는 3쌍은 공공/OSM 42.3/42m, 42.3/42m, 37.5/37m였다. 표본이 적어 일반적인 정확도나 높이 오차 분포를 확정하지 않는다.

IoU 0.8 이상으로 완화하면 보완 후보 3쌍이 나오지만 이름·전체 주소 등 독립적인 동일 건물 근거가 부족하다. OSM way ID `1258198276`, `1258198273`, `1258198277`을 검토 후보로 보관하고 값을 적용하지 않았다. 자동 적용 수는 0이다. 불명확한 공간 매칭이나 층수 추정으로 숫자만 채우지 않았다.

## 재현과 실제 활성 버전

기존 원본 캐시를 그대로 재사용하며 `--offline`은 캐시 누락 시 외부 통신을 차단한다. 키가 없어도 검증된 캐시를 읽을 수 있다. 정규화 시각과 원본 관측 시각을 구분하도록 메타데이터도 보완했다. 이번 원본의 관측 구간은 처음 수집한 15:46:54~16:00:55 KST를 유지한다.

```powershell
python scripts/prepare-public-buildings.py --offline --response-cache data/public-buildings/seoul-forest-20261026/responses --output data/public-buildings/seoul-forest-matching-v2-20261026
python scripts/validate-public-buildings.py data/public-buildings/seoul-forest-matching-v2-20261026/buildings.incomplete.geojson --output data/public-buildings/seoul-forest-matching-v2-20261026/buildings.validated.geojson
python scripts/audit-building-matches.py --baseline data/public-buildings/seoul-forest-20261026 --reviewed data/public-buildings/seoul-forest-matching-v2-20261026
```

출력이 이미 있으면 다른 디렉터리를 사용한다. 검증기의 의존성과 JVM 적재 명령은 첫 공공 건물 검증 문서를 따르되 입력 파일을 위 검증 파일로 지정한다. 형상 비교는 다음처럼 명시적 두 버전으로 실행한다.

```powershell
python scripts/compare-building-footprints.py --public-version buildings-v1-a620390ad03e64ca19d2657fac3e20924a54260afe92a557cef373282cf56203 --osm-version buildings-v1-5fb6184338f9abc37b6c5145cd1f059833306e80a77fab06135d04e99a9a94f1 --output data/public-buildings/seoul-forest-matching-v2-20261026/osm-spatial-review.json
```

새 활성 버전은 `buildings-v1-a620390ad03e64ca19d2657fac3e20924a54260afe92a557cef373282cf56203`이다. 신규 적재와 반복 `reused=true`를 확인했다. 14,075개/98,074좌표가 PostGIS 유효성 검사를 통과했고 잘못된 도형·격리는 0개다. 기존 버전은 보존했다.

Python 테스트 27개가 통과했다. 공식 PK 형식, 실대장 구분, 누락 속성을 가진 정확한 PK 연결, 필지·층수·날짜 등 충돌, 중복 PK, 다른 대장으로의 우회 금지, 상충 높이의 원본 보존, 캐시 누락 시 외부 호출 차단을 추가 검증했다. JVM 코드는 이번에 추가 변경하지 않았다.

실제 API 낮·밤·저고도·미지원 시각 네 경우 모두 HTTP 200과 기존 경로 800.8m/573초를 유지했다. 낮 응답의 새 건물 버전, requestId, evaluatedAt도 검증했다. health와 DB 연결은 UP이다. 응답 증거는 `data/public-buildings/matching-verification-20261026/`에 있다.

현재 전체 목록·누락 높이의 상한은 미확인이므로 `inventoryComplete=false`, `maximumHeightMeters=null`, 낮 응답 `INCOMPLETE_COVERAGE`, `coverage=0`, `shadeRatio=null`을 유지한다. 기존 172.5m 부분 그림자 진단은 이전 버전에서의 결과로 보존하며 새 버전의 수치라고 재사용하지 않는다. 다음 보완은 건물 식별 보류 1,756개에 대한 독립 형상·주소 연계 자료와, 원본에 높이가 없는 건물의 추가 측정 자료 확보가 필요하다. 층수 추정 정책은 별도로 합의해야 한다.
