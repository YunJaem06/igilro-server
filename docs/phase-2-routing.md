# Phase 2: 실제 보행 경로

## 구현 범위

`POST /api/v1/routes/search`가 실제 OSM 보행 그래프에서 `walking` / `fastest` 경로 한 개를 계산합니다. Domain/Application은 순수 Kotlin과 JDK를 사용하며 OSM 해석·XML·JDBC·Spring·HTTP는 외부 계층에 둡니다. 이후 [건물 그늘 평가](phase-3-shade.md), 장소 검색과 [shade 경로 최적화](phase-4-shade-routing.md)를 추가했습니다. 실제 자료의 그늘 평가 구역 확보, 다른 환경 점수, 인증과 요청 제한은 후속 작업입니다.

현재 pilot 범위는 서울숲 주변 직사각형 `[서쪽 127.027, 남쪽 37.533, 동쪽 127.055, 북쪽 37.558]`입니다. 범위 내부라도 50m 안에 연결 가능한 보행 구간이 없으면 404입니다. 이는 모든 장소의 출입구와 현장 통행 가능 여부를 검증한 서비스 영역이라는 뜻은 아닙니다.

## 실행

```bash
docker compose up --build -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

처음 실행하면 Flyway V1/PostGIS, V2/그래프 테이블과 V3/건물 그늘 스키마를 적용합니다. 아직 그래프를 적재하지 않은 DB의 경로 요청은 HTTP 503 `ROUTING_DATA_UNAVAILABLE`입니다. 2026-10-10 Mac 검증 환경의 개발 DB에는 실제 스냅샷을 적재했습니다. 데이터 파일과 DB 볼륨은 Git에 포함되지 않으므로 새 환경·새 DB나 볼륨 초기화 후에는 아래 준비·적재를 다시 수행합니다.

## OSM 데이터 준비

[Geofabrik 한국 PBF](https://download.geofabrik.de/asia/south-korea.html)를 사용합니다. 공개 Overpass 조회 서버는 실제 작업에서 504/500을 반환하여 정적 extract 방식으로 검증했습니다. 운영 경로 요청은 외부 데이터 제공자를 호출하지 않습니다.

```bash
mkdir -p data/osm
curl --fail --location --output data/osm/south-korea-latest.osm.pbf \
  https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
python3 -m venv .venv-data
.venv-data/bin/python -m pip install -r scripts/requirements-osm.txt
.venv-data/bin/python scripts/prepare-osm.py \
  --pbf data/osm/south-korea-latest.osm.pbf \
  --output data/osm/seoul-forest.osm
```

Python은 데이터 준비 도구에만 필요합니다. JVM 서버에는 추가 OSM 런타임 라이브러리를 넣지 않았습니다. pyosmium 4.3.1의 Apple Silicon wheel로 변환을 검증했고, CI는 Python 3.12와 작은 합성 PBF fixture를 사용합니다.

변환 도구는 bounding box 안의 highway way를 선택하고, 경계 밖 노드도 포함해 참조를 완성합니다. 태그·노드 ID를 보존하며 source timestamp와 PBF/XML SHA-256을 metadata JSON에 기록합니다. 체크섬과 두 파일의 내용을 모두 준비한 뒤 같은 파일시스템의 임시 디렉터리에서 메타데이터, XML 순으로 공개합니다. 기존 XML·메타데이터는 덮어쓰지 않으며, 동시 실행 중 같은 이름이 생겨도 보존합니다. 저장 오류 시 이번 실행이 만든 파일만 정리합니다. 새 스냅샷에는 다른 출력 이름을 사용합니다. 원본 PBF·XML·로컬 metadata는 `data/`에 두며 Git에는 포함하지 않습니다.

## 원자적 적재

```bash
./gradlew bootJar
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --spring.main.web-application-type=none \
  --igilro.graph-import.file=data/osm/seoul-forest.osm
```

CLI는 적재 결과를 출력하고 종료합니다. 같은 파일·지역·보행 정책 버전은 같은 `dataVersion`이 되어 중복 적재하지 않습니다. 기존 버전을 재사용할 때 지역 ID·출처·시각·체크섬·개수와 노드 좌표·구간의 방향/종류를 대조합니다. 내용이 다르면 적재를 실패시키고 활성 데이터는 유지합니다. 새 데이터셋의 노드·구간·거리 저장과 활성 포인터 교체를 하나의 트랜잭션으로 처리하며, 실패하면 기존 활성 데이터를 유지합니다. import advisory lock을 사용해 동시 적재를 직렬화합니다.

다른 작은 지역을 준비할 경우 Python의 `--bbox WEST SOUTH EAST NORTH`와 JVM의 `--igilro.graph-import.west/south/east/north`, `area-id`, `area-name`을 일치시킵니다. 각 축 0.1도 이하의 pilot만 허용하고 입력 XML 20MB, 원본 노드 150,000/way 50,000, 적재 노드 100,000/구간 250,000 제한을 적용합니다. 현재 운영 검증 범위는 서울숲 pilot 하나입니다.

## 경로 요청

서버를 실행한 다음:

```bash
curl -i http://localhost:8080/api/v1/routes/search \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  --data-binary @docs/examples/route-search.json
```

검증한 스냅샷에서는 위 요청이 약 800.8m, 예상 573초, 40개 좌표의 경로를 반환했습니다. 값은 샘플 상수가 아닌 그래프 계산 결과이며 다른 스냅샷에서는 달라집니다. 전체 응답에는 `routes`, `evaluatedAt`, `dataVersion`, `timeModelVersion`, `routingModelVersion`, `baselineDurationSeconds`, `snapping`, `attribution`이 있습니다.

- GeoJSON은 `routes[].geometry`의 `LineString`, WGS84 `[경도, 위도]`입니다.
- `departureAt`은 선택 사항이며 오프셋 필수입니다. 생략하면 주입된 Clock의 UTC 현재 시각, 제공하면 같은 순간을 UTC `evaluatedAt`으로 응답합니다.
- `travelMode`와 `mode`는 생략 시 walking/fastest입니다. 지원하지 않는 값은 400 `INVALID_ROUTE_QUERY`, 모르는 필드는 400 `INVALID_REQUEST`로 거부합니다.
- 거리와 시간은 **스냅된 두 지점 사이의 그래프 경로** 기준입니다. 입력 위치에서 구간까지의 접근선은 그래프와 통행 가능 여부가 확인되지 않았으므로 경로 형상·거리·시간에 추가하지 않습니다. 앱은 `snapping`의 실제 끝점과 접근 거리를 표시해야 합니다.
- 네트워크 거리는 PostGIS geography 기반 미터이며, 구간 중간 스냅은 작은 지역의 로컬 투영으로 분할 비율을 근사합니다. 응답 거리는 소수 한 자리, 전체 시간 비용은 초 단위 올림입니다.
- 기본 보행 속도 1.4m/s, 계단 0.9m/s의 정적 모델입니다. 신호 대기·실시간 통제·개인 보행 속도를 계산하지 않습니다.
- fastest 자체가 기준 경로이므로 `additionalDurationSeconds`는 계산한 기준 시간과의 차이 0입니다. Phase 2 검증 당시 환경 metric은 모두 null이었으며, Phase 3에서는 건물 자료와 전체 커버리지가 있을 때 shadeRatio를 계산합니다.
- 경로 `id`는 응답 안에서만 식별하며 저장·재조회와 상세 API는 미지원입니다.

### DTO 연동용 요청·성공 응답 JSON

위의 서울숲 실제 데이터 요청은 [route-search.json](examples/route-search.json)입니다. 아래 예시는 **2026-10-19에 현재 서버 JAR와 격리된 임시 PostGIS에서 합성 보행·건물 fixture로 실제 HTTP 200 응답을 캡처한 것**입니다. 실제 서울숲의 800.8m 경로와는 다른 자료·좌표이며, DTO 파싱과 화면 개발용입니다. 실제 서비스 API도 적재한 그래프로 계산하며 이 예시 값을 고정 반환하지 않습니다. 캡처용 서버와 DB는 종료·정리했습니다.

대응하는 요청([JSON 파일](examples/route-search-fixture-request.json)):

```json
{
  "origin": {
    "longitude": 127.0302,
    "latitude": 37.54002
  },
  "destination": {
    "longitude": 127.0318,
    "latitude": 37.54002
  },
  "travelMode": "walking",
  "mode": "fastest",
  "departureAt": "2026-10-10T14:00:00+09:00"
}
```

건물 자료를 적재하기 전 성공 응답([전체 JSON 파일](examples/route-search-fixture-response.json)):

```json
{
  "routes": [
    {
      "id": "72b79fc7-641c-4f81-9249-d63b0b56ddb0",
      "mode": "fastest",
      "geometry": {
        "type": "LineString",
        "coordinates": [
          [127.0302, 37.54],
          [127.031, 37.54],
          [127.0318, 37.54]
        ]
      },
      "distanceMeters": 141.4,
      "durationSeconds": 102,
      "additionalDurationSeconds": 0,
      "metrics": {
        "shadeRatio": null,
        "quietScore": null,
        "lightingScore": null,
        "trafficSignalCount": null
      },
      "shadeEvaluation": {
        "status": "DATA_UNAVAILABLE",
        "coverage": null,
        "dataVersion": null,
        "modelVersion": "building-prism-v1-noaa-departure-5179-minsun5",
        "attribution": null,
        "solarAltitudeDegrees": 40.28185900153385
      }
    }
  ],
  "evaluatedAt": "2026-10-10T05:00:00Z",
  "dataVersion": "osm-v1-580853896cebd0ebdc882105337f858779876e6e855472aff3afda0d3e0156e0",
  "timeModelVersion": "walking-v1-flat-1.4-steps-0.9",
  "routingModelVersion": "dijkstra-v2-distinct8-snap50-direct5km",
  "baselineDurationSeconds": 102,
  "snapping": {
    "origin": {
      "coordinate": {
        "longitude": 127.0302,
        "latitude": 37.54
      },
      "distanceMeters": 2.2
    },
    "destination": {
      "coordinate": {
        "longitude": 127.0318,
        "latitude": 37.54
      },
      "distanceMeters": 2.2
    }
  },
  "attribution": "© OpenStreetMap contributors"
}
```

### snapping과 attribution의 정확한 구조

| JSON 경로 | 타입 / 의미 |
| --- | --- |
| snapping | 최상위 필수 객체. routes[] 안에 들어가지 않음 |
| snapping.origin / snapping.destination | 각각 실제 경로 시작점 / 끝점의 스냅 객체 |
| snapping.*.coordinate | {longitude: number, latitude: number}. GeoJSON 배열과 달리 좌표 객체 |
| snapping.*.distanceMeters | 입력 핀에서 스냅 지점까지의 접근 거리(m), JSON number |
| attribution | 최상위 문자열. 현재 값은 © OpenStreetMap contributors이며 객체·배열이 아님 |
| routes[].shadeEvaluation.attribution | 건물 자료의 별도 출처 문자열 또는 null. 최상위 OSM 출처와 별도로 표시 |

경로 geometry의 첫/끝 좌표는 위 예시의 스냅 좌표입니다. 입력 핀의 latitude=37.54002와 경로의 latitude=37.54 사이 2.2m 접근 거리는 geometry·distanceMeters·durationSeconds에 더하지 않습니다. 반올림·좌표 근사에 따른 작은 수치 오차는 허용해야 합니다.

### shadeEvaluation을 포함한 성공 응답

같은 서버가 실행 중인 상태에서 [합성 건물 fixture](../src/test/resources/shade/buildings.geojson)를 적재하고 같은 요청을 보냈습니다. 경로·거리·시간·snapping·최상위 attribution은 동일하며 경로 id는 새로 생성됩니다. 달라진 metrics와 shadeEvaluation은 아래와 같습니다. 복사 가능한 [전체 성공 응답 JSON](examples/route-search-fixture-response-shade-available.json)도 제공합니다.

```json
{
  "metrics": {
    "shadeRatio": 0.21382726924445294,
    "quietScore": null,
    "lightingScore": null,
    "trafficSignalCount": null
  },
  "shadeEvaluation": {
    "status": "AVAILABLE",
    "coverage": 1,
    "dataVersion": "buildings-v1-deda27f20bc4b24de1f0efac3d31ea88691d727f631aad58079a55c9d038721a",
    "modelVersion": "building-prism-v1-noaa-departure-5179-minsun5",
    "attribution": "Synthetic test geometry; not real buildings",
    "solarAltitudeDegrees": 40.28185900153385
  }
}
```

- shadeEvaluation은 routes[]의 각 경로 아래에 있습니다. 현재 Mapper는 객체를 항상 제공하며 DTO 타입은 nullable입니다.
- shadeRatio는 0..1 비율, coverage는 평가 가능한 경로 비율입니다. 이 예시는 coverage=1로 전체를 평가해 약 21.38%를 반환했습니다. 다른 세 metric은 계속 null입니다.
- status와 modelVersion은 문자열입니다. coverage, dataVersion, attribution, solarAltitudeDegrees는 null을 허용합니다. DATA_UNAVAILABLE에서도 태양 위치를 계산했다면 위 예시처럼 solarAltitudeDegrees가 숫자일 수 있습니다.
- status는 AVAILABLE / DATA_UNAVAILABLE / INCOMPLETE_COVERAGE / NIGHT / LOW_SUN / UNSUPPORTED_TIME입니다. 전체 평가가 가능한 AVAILABLE에서만 shadeRatio가 숫자이며 나머지는 null입니다. 상태별 규칙은 [Phase 3 문서](phase-3-shade.md)를 따릅니다.
- 최상위 dataVersion은 보행 그래프 버전, shadeEvaluation.dataVersion은 건물 버전입니다. 앱은 버전 문자열을 해석하지 않습니다.
- durationSeconds·additionalDurationSeconds·baselineDurationSeconds는 정수, 거리·비율·고도는 JSON number입니다. Dart의 double 필드는 num.toDouble() 방식으로 정수 표기와 소수 표기를 모두 수용합니다. trafficSignalCount의 향후 값은 정수이며 현재 null입니다.
- 이 JSON은 성공 본문 전체이며 별도 data/result 래퍼는 없습니다. 응답의 X-Request-Id는 헤더로 제공하고 성공 본문에는 requestId를 넣지 않습니다. 실패 본문에는 최상위 code/requestId가 있습니다.


## 데이터·탐색 설계

`route/domain`에 좌표·그래프·시간 모델·snapping·Dijkstra를, `route/application`에 검색/적재 UseCase를 둡니다. API DTO/Mapper/경로 오류 변환은 `route/api`, XML reader·OSM 보행 규칙·JDBC 구현·CLI 조립은 `route/infrastructure`입니다. 공통 오류 생성은 shared API의 responder를 재사용하여 HTTP, code, requestId와 로그 연결을 일치시킵니다.

OSM의 연속 노드 쌍을 구간으로 만들며 **노드 ID를 공유할 때만** 연결합니다. 좌표가 같거나 그림에서 교차한다는 이유로 연결하지 않습니다. 사유·금지 통행, 공사, 해석하지 못한 조건부 통행·운영시간·이동 계단, 막힌 barrier 등을 보수적으로 제외합니다. 차량 일방통행을 보행 일방통행으로 간주하지 않고 명시적인 foot 방향과 일반 방향별 접근 제한을 해석합니다. 주요 도로는 foot/sidewalk 근거가 있는 구간만 포함합니다.

snapping은 각 위치의 50m 안 최근접 위치 최대 8개를 확인합니다. 같은 노드에 모이는 여러 구간의 끝점은 후보 하나로 취급하고, 서로 다른 노드 ID는 좌표가 같아도 합치지 않습니다. 연결 가능한 후보 쌍 가운데 접근 거리 합이 가장 작은 쌍을 선택하며, 같은 접근 거리에서는 예상 경로 시간이 작은 결과를 선택합니다. 구간 중간점은 가상 시작·도착 노드로 연결해 끝점까지 우회하지 않습니다. 서로 다른 단절 성분 사이에 구간을 만들어 넣지 않습니다. 이 후보 선택 변경은 `routingModelVersion=dijkstra-v2-distinct8-snap50-direct5km`으로 구분합니다.

JDBC reader는 요청마다 활성 데이터셋 metadata를 확인하고 노드·간선·인접 목록은 불변 캐시로 재사용합니다. 캐시 키에는 버전뿐 아니라 지역 ID·범위·노드/구간 개수도 포함하므로 활성 포인터 또는 서비스 영역 metadata가 바뀌면 다음 요청에 반영합니다. 다른 지역의 데이터셋이 활성화되었거나 저장된 그래프가 도메인 제약을 위반한 경우도 503 `ROUTING_DATA_UNAVAILABLE`입니다. DB를 읽을 수 없거나 데이터가 준비되지 않은 경우와 같은 계약을 사용합니다.

## 테스트·CI

```bash
./gradlew test
docker compose build postgres
./gradlew clean build
.venv-data/bin/python -m unittest discover -s scripts/tests -v
```

JVM 테스트는 손으로 결과를 확인할 수 있는 합성 그래프, OSM fixture와 실제 PostgreSQL/PostGIS 컨테이너를 사용합니다. Python 테스트는 합성 PBF를 생성해 경계 참조 완성·태그 보존·재현성·기존 파일 보존을 검증합니다. CI에서 실제 한국 extract를 다운로드하거나 공개 Overpass에 요청하지 않습니다. 실제 데이터 검증은 [검증 기록](phase-2-verification.md)을 참고합니다.

## 다음 단계

실제 스냅샷은 34개의 무방향 연결 성분을 갖습니다. 최근접 구간이 단절 성분에 속할 수 있어 후보 snapping과 명시적 ROUTE_NOT_FOUND가 필요합니다. 지도 현장 대조, 보행 진입점, relation 기반 규칙과 운영시간, 경계 데이터, 신호·경사·개인 속도 모델은 후속 데이터 품질 작업입니다. 서버의 통계·태그·공간 검증은 현장 통행 검증을 대체하지 않습니다.

Flutter는 지도 선택·현재 위치로 준비한 WGS84 출발지/도착지 좌표로 현재 fastest API를 바로 연결할 수 있습니다. 장소 검색 공급자/캐시 정책 확정과 place feature 추가는 별도 후속 작업이며 경로 API 연결의 선행 조건이 아닙니다. 건물·높이·태양 위치·그림자·환경 점수와 복수 가중치는 이후 단계입니다. 데이터와 파생 데이터의 이용 조건은 [OSM 공식 ODbL 안내](https://www.openstreetmap.org/copyright)를 따르며, 응답 attribution의 앱 표시 방식도 함께 반영합니다.
