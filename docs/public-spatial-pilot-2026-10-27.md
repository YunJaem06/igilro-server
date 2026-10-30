# 서울숲 공공 지형·3D 건물 대조 — 2026-10-27

아래는 직접 높이 기반 계약의 원본 대조 기록이다. 이후 사용자가 별도 모델 기반 예측을 선택했고 [새 예측 API](model-based-shade-prediction.md)에서 실제 서울숲 연결 경로의 200과 최적화를 확인했다. ACTUAL 높이 미이전·기존 직접 높이 API 보류는 그대로다.

공공자료만으로 서울숲 소구역의 누락 높이와 지형·보행 구조를 추가 조사했다. **원본 수집·복원·대조는 구현했지만 실제 자료의 AVAILABLE 및 shade 경로 검색 성공은 아직 확보하지 못했다.** 현장 답사는 필수 조건이 아니다. 남은 것은 높이·형상 식별과 지형을 반영한 계산 및 그 근거다.

## 확보한 원본과 범위

기존 학교 서측 50m 보행 구간과 대안 검토용 약 300×300m 그래프 bounds `(127.0356273, 37.5468106) ~ (127.0390361, 37.5495134)`를 대상으로 제한 수집했다. 전국 건물 mesh를 내려받지 않았다.

브이월드 [WebGL 3.0 안내](https://www.vworld.kr/dev/v4dv_opnws3dmap3guide_s001.do), [공식 레이어 설정](https://map.vworld.kr/js/ws3dmap/WS3DRelease3/json/vworld.json), [공식 terrain provider 구현](https://map.vworld.kr/js/ws3dmap/WS3DRelease3/WSViewerStartup.js)을 확인했다. 현재 사용자의 키로 정상 초기화와 SDK의 데이터 키 교환을 확인했다. SDK에 포함된 공용·예제 키는 수집 인증에 사용하지 않았다.

| 자료 | 실제 수집·복원 결과 |
| --- | --- |
| DEM | 최대 level 15의 65×65 float32 타일 16개 |
| LoD1 건물 | 소구역과 겹치는 타일 10개, 원본 모델 34개 중 범위와 교차하는 형상 26개 |
| LoD4 건물 | 타일 9개, 원본 모델 10개 중 범위와 교차하는 형상 9개 |
| 기존 GIS 건물 | 그래프 bounds와 교차하는 12개를 새 모델과 대조 |
| 도로·보행 구조 | 원본 OSM 61개 way의 잘린 선형 63개에 약 5m 간격 DEM 표고 프로필 생성 |

각 타일의 관측 시각, 공급자 경로, SHA-256과 원본 속성을 보관한다. 이 관측 시각이나 건물의 사용승인일은 **촬영·측량 시각이 아니다**. DEM 격자의 계산상 간격은 동서 약 1.52m, 남북 약 1.90m이며 공급자가 보장하는 정확도를 뜻하지 않는다. 별도의 수직 기준계·취득 연도는 미확인으로 기록했다.

3D mesh는 [3D Tiles b3dm 명세](https://github.com/CesiumGS/3d-tiles/tree/main/specification/TileFormats/Batched3DModel)에 따라 glTF node 행렬, y-up→z-up 변환, RTC_CENTER를 적용했다. 원본 ECEF를 EPSG:4978로 읽고 EPSG:5179에서 분석, GeoJSON 출력은 EPSG:4326이다. Draco 압축과 비압축 accessor를 모두 복원하고 투영 삼각형을 합쳤다. convex hull로 빈 공간을 채우지 않았다. 이 형상은 **3D 모델의 수평 투영**이며 지적·실측 footprint와 같다고 간주하지 않는다.

## 누락 높이 대조

정보센터(B5, GIS 원본 ID `11200:51126:2002203390444494749900000000:…`, 건축물대장 PK `1005119492`)는 기존 GIS/HUB 높이가 모두 없었다.

- LoD1 후보 `BLD01000000GPWLPL`의 PNU `1120011400106850143`, 사용승인일 `20020928`이 기존 원본과 일치한다.
- GIS 도형의 약 82.57%, 모델 도형의 약 84.46%가 겹치며 IoU는 약 0.7168이다.
- 모델의 `BLDH_HT`는 **8.61**이다. 이 필드의 구축·일반화 정책이 확인되지 않아 ACTUAL 높이로 승격하지 않았다.
- LoD4 후보의 mesh 정점 고도 범위는 약 **11.434~25.989m**이며 IoU는 약 0.6535다. 최댓값과 최솟값의 차이를 건물 높이로 저장하지 않았다. 서로 다른 위치의 정점과 지붕·기저 형상을 포함할 수 있다.

높이가 이미 있는 교사동도 GIS/HUB 22.5m에 대해 LoD1 속성 20.07m, 중학교 건물은 22.5m에 대해 13.13m 등 차이가 있다. 다른 건물은 도형이 분할되거나 합쳐져 있어 같은 필지라는 이유로 높이를 옮길 수 없다. 이번 높이 자동 이전은 **0건**이다. `heightMeters=null`, `heightSource=UNKNOWN`을 유지하고 모델 속성은 별도 필드에 보존한다. 층수 기반 추정은 만들지 않았다.

## 지형과 보행 구조

A0~A5 보행 구간의 DEM 표고는 다음과 같다. 같은 위치의 level 13/14/15 표고도 별도로 대조했다.

| 위치 | 모델 표고(m) |
| --- | ---: |
| A0 | 20.508 |
| A1 | 20.451 |
| A2 | 20.544 |
| A3 | 20.302 |
| A4 | 20.257 |
| A5 | 20.222 |

A0의 level 13/14/15 값은 각각 약 20.568/20.521/20.508m다. 해상도 대조에 사용했으며 누락한 최대 해상도를 낮은 해상도로 대체하지 않았다.

같은 DEM에서 B1 교사동 footprint 중심은 약 **11.645m**다. 보행 구간과 약 **8.6~8.9m** 차이가 있다. 서로 다른 지점을 비교한 것이므로 이를 수직 기준계 불일치로 단정하지 않는다. 다만 건물과 보행로를 하나의 같은 높이 평면으로 두는 현재 `building-prism-v2` 가정을 이 근거로 승인할 수는 없다. footprint 중심의 DEM 값도 건물 전체 기저면의 확정값이 아니다.

이 차이를 다루는 ray/prism 진단 함수를 추가했다. 건물의 직접 높이를 바꾸지 않고 건물 기저와 수광점의 표고를 분리한다. B1 한 동, 기존 08:00 태양 조건, 0.5m 중점 샘플에서 평면 가정과 표고를 분리한 진단 모두 약 41.0m의 교차를 보였다. 기존 정확한 2D 교차값 약 40.81m와 샘플 방식의 차이도 있다. **이 특정 구간의 일치가 전체 후보 그래프·건물에 대한 평면 가정을 검증하지는 않는다.** 지형 자체의 차폐·모든 건물·수목을 평가하지 않았고 운영 모델을 교체하지 않았다.

원본 도로에는 교량/터널 및 `layer`가 명시된 way 6개가 이 bounds와 교차한다. 이 수는 자동차 도로도 포함한다. 기존 보행 그래프 미리보기에서 제외한 구조 구간 3개와 구분한다. 원본 태그와 미리보기 포함 여부를 각각 보관했다. DEM에서 완만한 선형이 나오는 것만으로 교량·터널·지붕 있는 통로를 지상으로 확정하지 않는다. 기존 119노드/111edge 미리보기는 계속 미활성·미검증 상태다.

## 실행과 산출물

새 도구는 별도의 원본 수집·검증 작업이다. 서버 자동 수집이나 DB 적재 작업이 아니다. 기존 `.env`의 VWorld 키를 사용한다. 키 교환 결과와 인증 URL은 저장하지 않고, 외부 요청은 고정된 공식 HTTPS endpoint와 소구역 타일·횟수·크기 제한을 사용한다. 캐시 무결성, 빈 DEM/0 높이 대체, 잘못된 형식, 외부 buffer, 미지원 좌표 변환은 실패 처리한다.

```bash
python -m pip install -r scripts/requirements-spatial-audit.txt
python scripts/audit-public-spatial.py \
  --pilot-audit data/public-buildings/pilot-field-review-20261027/routing-preview-150m/audit.json \
  --buildings data/public-buildings/pilot-field-review-20261027/buildings.pending-review.geojson \
  --roads-osm data/osm/seoul-forest-20261025.osm \
  --eligible-roads-osm data/public-buildings/pilot-field-review-20261027/routing-preview-150m/roads.pending-review.osm \
  --cache data/public-buildings/public-spatial-pilot-20261027/responses \
  --output data/public-buildings/new-spatial-audit \
  --offline
```

첫 수집은 `--offline`을 생략한다. 이전 report.json이 있는 폴더에는 덮어쓰지 않는다. 이 명령은 이미 확보한 기존 입력 파일을 필요로 하며 새 checkout에 데이터가 자동 포함되지는 않는다. 운영 중인 그래프·건물·검증 허용 설정은 변경하지 않는다.

이번 최종 산출물은 `data/public-buildings/public-spatial-pilot-20261027/verified-report/`다.

- `report.json`: 범위·원본 hash·제공 시각·제한·준비 상태
- `lod1/lod4-footprints.geojson`: 원본 모델 형상과 별도 모델 속성
- `lod1/lod4-building-matches.json`: GIS와 겹침·식별자·높이 차이·자동 이전 보류
- `building-ground-references.json`, `road-terrain-profiles.json`: 건물 중심 및 도로 표고 근거
- `known-building-terrain-diagnostic.json`: B1 한 동의 표고 분리 진단, 전체 점수 아님
- `live-fastest-response.json`, `live-shade-response.json`, `live-verification.json`: 실제 서버 재확인

상위 폴더의 `terrain-level-crosscheck.json`은 해상도 대조 원본이다. 타일/파생 자료는 Git에서 제외하는 `data/` 아래에 두고 재현 도구·검증·문서만 버전 관리한다.

## 실제 API 결과와 남은 조건

`GET /api/v1/health`, `/actuator/health`는 모두 **200 UP**이다. A0→A5, `2026-10-28T08:00:00+09:00`을 다시 요청했다.

| 요청 | 최종 결과 |
| --- | --- |
| fastest | 200, 실제 연결 경로 50.0m / 36초, INCOMPLETE_COVERAGE, coverage=0, shadeRatio=null |
| shade | 503 SHADE_DATA_UNAVAILABLE, code/requestId/응답 헤더 일치 |

합성 fixture의 성공을 실제 서울숲 결과로 표시하지 않는다. 이번 조사에서도 건물 목록 완전성·미확인 높이 상한·전체 후보 보행 구조를 승인하거나 새 자료를 활성화하지 않았다.

공공자료만으로 진행할 다음 필수 조건은 (1) B5 및 분할·합쳐진 건물의 형상/직접 높이 식별, (2) 주변 건물 목록과 상한의 근거, (3) 건물 기저·보행로 표고 및 구조를 반영하는 평가 모델이다. [국토정보맵 건물높이 DB](https://map.ngii.go.kr/bl/map/buldEncView.do)는 공개제한 자료로 안내하며 [온라인 제공 신청](https://map.ngii.go.kr/bl/map/bldgOnlineRequestInfo.do)에 별도 신청·승인 절차가 있다. 기존 VWorld/HUB 키만으로 그 원본을 받지는 못했다. 수집된 3D 속성을 직접 높이로 바꿔 이 절차나 품질 문제를 대신하지 않는다. 현장 자료 없이도 공식 제공 원본을 확보해 이 조건을 충족할 수 있다.

Python **60개** 테스트와 16개 DEM·19개 건물 타일의 캐시 기반 재현을 확인했다. 새 검증은 타일 행/열 방향, SDK 삼각형 보간, 축 변환, mesh 출처, 높이 분류 보존, 표고가 다른 수광점의 ray 판정, 범위/호출 제한, 비밀값 비저장과 캐시 변조 차단을 포함한다. JVM 서버 코드는 이번 추가 조사에서 변경하지 않았으며 기존 JVM 142개 통과 기록을 새 테스트 실행으로 표시하지 않는다. CI에는 새 검증용 의존성을 추가했다.
