# 서울숲 보행 경로와 모델 기반 그늘 구간 API

2026-10-28 기준. 공공 3D 모델 기반 `POST /api/v1/routes/predictions/search`는 서울숲 주요 산책로와 주변 출입 지점을 검색하고, 전체 예측 비율과 지도에 그릴 그늘 구간을 함께 제공한다. 기존 직접 높이 기반 `/api/v1/routes/search`와 별도 계약이다.

2026-10-29: 기존 응답을 유지하고 `routes[].guidance`에 보행 길안내 단계를 추가했다. 출발·실제 분기·원본 횡단·도착, 누적 거리·geometry 인덱스의 정확한 계약은 [길안내 단계 문서](walking-guidance.md)를 따른다. 기존 shadeSegments와 서로 다른 분할이며, 최신 아래 응답 예시에도 guidance를 포함한다.

## 검색 범위와 연결성

`GET /api/v1/routes/predictions/coverage`를 먼저 호출한다. 앱에 예전 300m 실험 구역 좌표를 고정하지 않는다.

| 항목 | 현재 데이터 |
| --- | --- |
| bounds | `[127.0305, 37.5385, 127.0465, 37.5515]` — 서·남·동·북 |
| 범위 크기 | 약 1.4 × 1.4km |
| 건물 모델 / DEM | 3,064개 / 285개 타일 |
| 출입 지점 | 서울숲역 1~4번 출구, 서울숲입구 교차로, 9·10번 출입구 정류장. 원본 OSM 명칭·좌표 |
| 스냅 | 보행 구간에서 최대 50m. 실제 연결된 후보를 선택하고 응답 `snapping`으로 제공 |
| 보행 연결 | 원본 노드 ID·보행 허용 태그·방향을 유지. 가까운 길끼리 임의로 연결하지 않음 |
| 제공 정책 | `LARGEST_CONNECTED_COMPONENT` — 주요 산책로·출입 지점이 연결된 보행망 제공 |
| 제공 보행망 | 1,113개 노드·1,277개 간선·연결 성분 1개. 원본에서 단절된 649개 노드 제외 |

원본 지상 후보에는 1,762개 노드·1,933개 간선·18개 약연결 성분이 있다. 주변 강변·교량/터널 제외로 단절된 조각 등을 원본 파일에는 남기고, 현재 검색과 coverage의 `walkingNetwork`에서는 주요 연결 성분만 제공한다. 제외한 노드 수와 원본 성분 수를 `connectivity`에 공개한다. 이를 누락 연결을 복구했다거나 서울숲의 모든 길을 확보했다고 해석하지 않는다. 교량·터널·실내·계단을 포함하는 경로는 후속 구조물 모델 검증이 필요하다.

coverage 응답 필드:

| 필드 | 앱 사용 방법 |
| --- | --- |
| `areaId`, `name`, `bounds`, `center` | 지역 이름, 지도 카메라 맞춤 |
| `walkingNetwork` | GeoJSON `MultiLineString` geometry. 실제 검색에 쓰는 보행선 표시 |
| `maximumSnappingDistanceMeters` | 현재 50. 선택 위치가 보행망에서 멀면 위치 선택 안내 |
| `accessPoints[]` | `{id, name, coordinate:{longitude,latitude}}`, 원본 공공 지도 지점 |
| `connectivity` | nodeCount, edgeCount, weakComponentCount, largestComponentNodeCount, componentPolicy, sourceWeakComponentCount, excludedDisconnectedNodeCount |
| `dataVersion`, `predictionDataVersion` | 검색 응답과 같은 그래프·장면 버전 |
| `quality`, `attribution` | 예측 품질 구분과 출처 |

bounds는 카메라·입력 범위이다. 그 안의 잔디밭·연못 등 모든 지점이 50m 안에서 보행망에 붙는다는 뜻은 아니다. 원본 노드 ID로 약연결을 진단하며, 실제 방향별 경로 존재 여부는 검색으로 판정한다.

## 요청과 실제 응답 예시

```json
{
  "origin": {"longitude": 127.0374, "latitude": 37.5445},
  "destination": {"longitude": 127.0430, "latitude": 37.5480},
  "travelMode": "walking",
  "mode": "shade",
  "departureAt": "2026-10-28T11:00:00+09:00"
}
```

같은 좌표·출발 시각·스냅·장면에서 mode만 변경한 실제 결과:

| 항목 | fastest | shade |
| --- | ---: | ---: |
| 거리 | 800.7m | 865.6m |
| 시간 | 572초 | 619초 |
| fastest 대비 추가 시간 | 0초 | 47초 |
| 모델 예측 그늘 | 31.14% | 54.60% |
| 그늘/햇빛 연속 구간 수 | 4개 | 4개 |

전체 JSON은 [요청](examples/model-route-prediction-request.json), [fastest 응답](examples/model-route-prediction-fastest-response.json), [shade 응답](examples/model-route-prediction-shade-response.json)에 실제 캡처를 저장했다. 좌표·수치는 예시 상수가 아닌 원본 보행 그래프와 모델 계산 결과다.

```bash
curl http://localhost:8080/api/v1/routes/predictions/coverage
curl http://localhost:8080/api/v1/routes/predictions/search \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  --data-binary @docs/examples/model-route-prediction-request.json
```

`routes[].geometry`는 전체 경로 `LineString`이다. `routes[].shadeSegments`는 다음 필드의 배열이다.

| 필드 | 계약 |
| --- | --- |
| `state` | `SHADE` 또는 `SUNLIGHT`. 두 값 모두 **모델 예측** 상태 |
| `geometry` | `{type:"LineString",coordinates:[[longitude,latitude],...]}` |
| `fromDistanceMeters` | 스냅된 출발점부터 구간 시작까지의 누적 거리 |
| `toDistanceMeters` | 구간 끝까지의 누적 거리 |
| `distanceMeters` | 해당 구간 길이. to − from |

구간은 출발→도착 순서이고, 첫 누적 거리는 0이다. 이웃 구간은 같은 끝점·시작점을 공유하며 거리상 빈틈이나 겹침이 없다. 모든 구간을 이어 붙이면 전체 경로를 덮는다. 같은 상태가 이어지면 합치되 **원래 경로의 꺾이는 좌표는 보존**한다. 따라서 Flutter는 SHADE를 진한 색, SUNLIGHT를 기본 색으로 그리면 된다. 전체 비율로 임의 구간을 칠하지 않는다.

`modeledShadeRatio = SHADE 구간 길이 합 / 전체 구간 길이 합`이다. 구간 거리는 반올림하지 않고 전체 `distanceMeters`는 기존처럼 소수 첫째 자리로 반올림한다. 차이는 최대 0.05m 수준이다. 누적 거리에는 선택 핀→스냅 끝점 접근선은 포함하지 않는다.

최대 0.5m 중점 샘플로 판정하므로 색상 경계는 샘플 근사이며 실측 그림자 경계가 아니다. 같은 출발 시각의 태양을 전체 경로에 적용한다.

## 나머지 응답 계약

| 필드 | 의미 |
| --- | --- |
| `prediction.status` | `MODEL_BASED_PREDICTION` |
| `prediction.modeledShadeRatio` | 선택 경로의 포함 모델 예측 비율 0~1 |
| `prediction.baselineModeledShadeRatio` | 같은 조건의 fastest 예측 비율 |
| `prediction.physicalShadeCoverage` | `null` — 실제 전체 그늘 커버리지 미검증 |
| `quality` | `EXPERIMENTAL_PARTIAL_MODEL` |
| `modelVersion` | `public-3d-prism-dem-v2-step0.5m` |
| `dataVersion` | 원본·범위·보행 정책·연결 성분 선택 정책을 반영한 그래프 버전 |
| `predictionDataVersion` | scene.json SHA-256 기반 장면 버전 |
| `baselineDurationSeconds`, `additionalDurationSeconds` | 같은 스냅의 실제 fastest 시간과 추가 시간. 각각 올림한 초 비교 |
| `attribution`, `limitations` | 문자열, 문자열 배열. UI 출처·예측 한계 표시 |

좌표는 WGS84 `[경도, 위도]`, 거리 m, 시간 초. `evaluatedAt`은 `departureAt`과 같은 순간의 UTC ISO 8601이다. 생략한 출발 시각은 서버 현재 시각이다. route ID는 응답 내 식별자이며 저장/재조회 기능은 없다. `snapping` 구조는 기존 `{origin:{coordinate:{longitude,latitude},distanceMeters},destination:{...}}`를 유지한다.

기존 `metrics.shadeRatio`·`shadeEvaluation.AVAILABLE`은 모델 API에 추가하지 않는다. 앱의 기존 prediction DTO에 `shadeSegments`를 추가하고 coverage DTO를 별도로 둔다. 출발·도착·출발 시각을 동일하게 유지해 fastest/shade를 비교한다. `/v3/api-docs`, `/swagger-ui/index.html`에도 새 필드와 coverage API를 제공한다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_ROUTE_QUERY | 잘못된 JSON·좌표·mode/travelMode |
| 404 | ROUTE_NOT_FOUND | 50m 스냅 안에 후보가 없거나 합법적인 연결 경로 없음 |
| 422 | OUTSIDE_SUPPORTED_AREA | bounds 밖 |
| 422 | SHADE_TIME_UNSUPPORTED | 태양 고도 5도 미만, 지원 연도 밖 |
| 503 | PREDICTION_DATA_UNAVAILABLE | 장면 미설정·필요한 지형 없음 |
| 503 | ROUTE_SEARCH_LIMIT_EXCEEDED | 샘플·후보·탐색 한도 초과 |

최상위 `code/message/requestId`, `X-Request-Id`, 비정상 HTTP 상태와 application/json 오류 계약은 유지한다.

## 데이터 준비와 실행

기존 [OSM 준비 절차](phase-2-routing.md)로 서울숲 원본 XML을 만든 후 실행한다. 키는 기존 서버/인프라 환경 변수에서 읽고 결과와 원본 캐시는 Git에서 제외한다.

```bash
python -m pip install -r scripts/requirements-spatial-audit.txt
python scripts/prepare-model-prediction.py \
  --bounds 127.0305 37.5385 127.0465 37.5515 \
  --roads data/osm/seoul-forest-20261025.osm \
  --access-points docs/examples/model-route-access-points.json \
  --cache data/public-buildings/public-spatial-pilot-20261027/responses \
  --output data/public-buildings/new-seoul-forest-scene
```

기존 출력 파일을 덮어쓰지 않는다. `--offline`으로 저장된 캐시만 사용한다. 출입 지점 예시는 OpenStreetMap contributors의 OSM 2026-10-25 원본을 기준으로 추출했다. 실제 운영 중 출입구 변경·공사·폐쇄를 보장하지 않는다. 공원 공간 구성은 [서울숲 공식 안내](https://seoulforest.or.kr/info/park-info)도 참고한다.

로컬 `.env`에는 현재 다음 설정이 적용되어 있다.

```dotenv
SHADE_PREDICTION_FILE=data/public-buildings/seoul-forest-navigation-20261028/scene/scene.json
```

scene.json과 같은 폴더의 roads.osm이 필요하고 도로 SHA-256을 대조한다. 변경 후 서버 재시작이 필요하다. 설정이 비면 예측 API만 503, 잘못된 파일을 지정하면 기동 검증에서 실패한다. 기존 v1 장면 파일도 읽을 수 있고 계산 엔진 버전은 v2로 응답한다. 로컬 DB는 `docker compose up -d postgres`, 서버는 Java 21과 local profile, 8080 포트를 사용한다.

새 스키마 2는 DEM을 65×65 little-endian float32의 Base64로 저장한다. 약 10.2MB 장면이며 크기·개수·지리 범위 제한을 유지한다. EPSG:5179 국소 2차 근사는 독립 33×33 격자로 확인했고 이번 범위에서 최대 오차 약 0.000005m다. 이것은 투영 근사 오차이며 원본 데이터의 실측 정확도를 뜻하지 않는다.

공식 LoD1 높이 속성은 `VWORLD_MODEL_ATTRIBUTE`로만 저장한다. DEM 내부 지점을 기저로 한 수직 prism에 태양 광선을 대조한다. 안뜰·분리 도형, 수광점 표고와 건물 기저 차이를 보존하며 공간 인덱스는 정확한 ray/prism 판정의 후보만 줄인다. ACTUAL 높이나 층수 추정으로 바꾸지 않는다.

나무·구름·지형 지평선·정밀 지붕·이동 중 태양 변화는 미반영이다. 특히 **공원 안에서 모델 예측 0%가 나와도 나무 그늘이 없다는 뜻은 아니다.** 그래프 주위 약 150m 장면 버퍼 밖의 높은 건물도 빠질 수 있다. 원본 취득일·수직 기준계는 미확인이고 DEM 일관성을 가정한다. 별도 직접 높이 DB/검증 허용 목록은 변경하지 않는다.

shade는 기준 시간 30% 이내에서 모델상 햇빛 노출 초를 최소화한다. 비율은 길이 기준이다. 한 측정 배치 250,000개 샘플·약 8초, 최종 구간 추적 배치도 같은 한도를 적용한다. 기존 후보 12,000개·label 100,000개·탐색 약 2초 제한을 유지하며 초과하면 503이다. 단일 로컬 측정은 동시 부하 성능 보장이 아니다.

## 검증 재현

```bash
python scripts/verify-model-navigation.py \
  --base-url http://127.0.0.1:8080 \
  --cases docs/examples/model-route-navigation-cases.json \
  --bidirectional \
  --output data/public-buildings/navigation-verification
```

서울숲역 출구·출입구 정류장·공원 안 남북/동서 지점으로 검색한다. 경로 모든 선분이 원본 간선 위에 있고, 색상 구간이 경로를 벗어나지 않으며, 구간 합계와 전체 비율·동일 스냅·30% 시간 한도가 맞는지 검사한다. 50m 밖 선택점 두 곳, 지역 밖, 야간은 명시한 404/422 오류를 기대한다. source tag 기반 보행 가능성 검증이며 현장 답사 결과가 아니다.

2026-10-28 검증 결과:

- JVM 단위/API/아키텍처 129개 + PostGIS 통합 34개 = **163개 통과**, 실패·오류·건너뜀 0. Python **63개 통과**.
- 지도 좌표 10쌍을 양방향 fastest/shade로 검색한 **40건 모두 200**. 보행로 거리·지역 밖·야간 **8건은 예상 404/422·code** 확인. 성공 응답 약 **0.016~0.406초**, 단일 로컬 요청 측정.
- 원본 간선 일치, 그늘 구간의 연속성·전체 경로 포함, 누적 거리·비율, 동일 스냅, 30% 시간 한도를 확인했다.
- 공공 모델·지형·보행망·출입 지점을 네트워크 요청 **0회**로 재현했다. 관측 시각의 UTC/KST 표기를 같은 순간으로 정규화한 뒤 입력이 동일함을 대조했다.
- health/DB UP, OpenAPI 새 필드, 공통 오류 Content-Type·requestId/헤더 일치 확인. 공유 대상 소스·문서·장면의 키 원문 검사 통과.
- 기존 직접 높이 API는 fastest 800.8m·shadeRatio null, shade 503 SHADE_DATA_UNAVAILABLE를 유지한다.

파일은 `data/public-buildings/seoul-forest-navigation-20261028/` 아래에 보관한다. `initial-map-check/`는 최초 거리 제한 실패도 남긴 기록이고, 최종 요청/응답·시간·합계는 `final-verification/`, 재현/키/API 검사 합계는 `final-audit.json`이다. `route-comparison.png`는 실제 응답에서 그린 서버 계산 비교 지도이며 앱 화면 캡처가 아니다.
