# Phase 3: fastest 경로의 건물 그늘 평가

## 구현 범위

기존 `POST /api/v1/routes/search`의 fastest 경로에 출발 시각 기준의 건물 그늘 비율을 추가합니다. 건물 도형·실측 높이·자료 범위를 GeoJSON으로 적재한 경우에 계산합니다. 기본 경로의 형상·거리·시간과 mode 계약은 유지하며, shade/sunlight 경로 최적화는 다음 단계입니다.

실제 서울숲 건물·높이 자료를 이 저장소에 포함하거나 적재한 상태는 아닙니다. 새 DB에는 건물 자료가 없으므로 낮의 shadeRatio는 null이며 상태는 DATA_UNAVAILABLE입니다. 테스트 fixture는 합성 도형이며 실제 건물·서비스 품질의 근거가 아닙니다.

## 응답 추가

`routes[].metrics.shadeRatio`는 전체 경로의 그늘 길이 / 전체 길이입니다. 전체 길이를 평가할 수 있을 때만 제공합니다. `routes[].shadeEvaluation`에 아래 필드를 추가합니다.

| 필드 | 의미 |
| --- | --- |
| status | 아래 평가 상태 |
| coverage | 전체 경로 중 건물 목록·높이·주변 범위를 확인할 수 있는 길이 비율(0..1). 미평가/자료 없음은 null |
| dataVersion | 건물 파일 SHA-256으로 만든 버전. 최상위 dataVersion은 보행 그래프 버전을 유지 |
| modelVersion | `building-prism-v1-noaa-departure-5179-minsun5` |
| attribution | 건물 자료 출처. 최상위 OSM attribution과 함께 앱에서 표시 |
| solarAltitudeDegrees | 기준 위치·출발 시각의 기하학적 태양 고도(도) |

| status | shadeRatio / coverage |
| --- | --- |
| AVAILABLE | 전체 경로를 평가한 비율 / 1.0. 그늘 없음이 확인된 경우에만 ratio=0 |
| DATA_UNAVAILABLE | 자료 없음·무결성 오류·공간 DB 조회/계산 실패: null / null |
| INCOMPLETE_COVERAGE | 불완전한 목록·미확인 높이·경계 인접: null / 확인 가능한 경로 비율 |
| NIGHT | 태양 고도 0도 이하: null / null |
| LOW_SUN | 태양 고도 0도 초과 5도 미만: null / null |
| UNSUPPORTED_TIME | 지원 연도 2000..2100 밖: null / null |

quietScore/lightingScore/trafficSignalCount는 계속 null입니다. 그늘 자료가 없어도 fastest 경로는 200으로 반환합니다. 밤을 건물 그늘 100%로 표현하지 않습니다. 평가 시각은 기존 evaluatedAt과 같으며 departureAt이 없으면 주입된 Clock을 사용합니다.

## 계산과 커버리지

1. [NOAA 공개 근사식](https://gml.noaa.gov/grad/solcalc/solareqns.PDF)으로 UTC 시각·경로 bounding box 중심의 태양 고도와 진북 기준 방위각을 계산합니다. 윤년·초·나노초를 반영하며 외부 태양 API는 호출하지 않습니다.
2. 일정 높이의 수직 건물에 `높이 / tan(태양 고도)`를 적용해 태양 반대 방향으로 그림자를 만듭니다. EPSG:5179 미터 좌표계에서 기준 위치의 진북/진동 방향을 지도 격자 방향으로 변환합니다.
3. 원래 도형, 이동한 도형, 경계 선분의 이동 면을 합칩니다. [ST_DumpSegments](https://postgis.net/docs/ST_DumpSegments.html)와 [ST_UnaryUnion](https://postgis.net/docs/ST_UnaryUnion.html)으로 오목한 도형·안뜰을 보존하고 중복 그림자는 한 번만 계산합니다. convex hull로 빈 공간을 그늘로 채우지 않습니다.
4. 완전한 건물 목록의 coverage를 최대 그림자 길이만큼 안쪽으로 축소합니다. 이 길이는 검증된 최대 높이 상한과 태양 고도로 계산하며 투영 오차를 위한 0.1% 여유를 둡니다. 경계 밖 미확인 건물의 그림자를 무시하지 않기 위한 보수적인 범위입니다.
5. 높이가 null인 건물은 최대 높이 상한으로 가능한 그림자를 계산하고 평가 범위에서 제외합니다. 건물 목록이 불완전하면 커버리지 0입니다. 전체 경로를 평가할 때만 shadeRatio를 제공합니다.
6. 경로를 선분별로 교차시켜 같은 좌표를 다시 통과하는 경로의 길이도 보존합니다. 1μm 또는 전체 길이의 1e-9 이내 오차만 전체 커버리지로 간주합니다.

한국 pilot의 평평한 지면, 출발 시각에 고정된 태양, 수직 건물과 평평한 지붕을 가정합니다. 이동 중 시간 변화, 지형·기저 고도, 교량·터널·실내, 나무, 구름과 실제 일사량, 지붕 모양, 대기 굴절은 미반영입니다. 한국 범위(경도 124..132, 위도 33..39), 각 축 0.1도 이하 자료만 적재하며 현장 관측 정확도를 주장하지 않습니다.

## 건물 입력 형식

UTF-8 GeoJSON FeatureCollection, 20MB·25,000개 건물·전체 좌표 250,000개 이하입니다. 좌표는 WGS84 2차원 `[경도, 위도]`, 도형은 유효한 Polygon/MultiPolygon입니다. 닫히지 않은 링, 위경도 오류, 자가 교차, 중복 ID, 0·음수·상한 초과 높이는 거부하며 도형을 자동 보정하지 않습니다.

최상위 metadata:

| 필드 | 입력 조건 |
| --- | --- |
| areaId | 보행 그래프의 지역 ID. 서울숲은 `seoul-forest` |
| source | 실제 자료 출처 URL 또는 식별자 |
| sourceTimestamp | 자료 기준 시각, 오프셋 필수 |
| attribution | 공급자 조건에 맞는 출처 표시 문구 |
| inventoryComplete | coverage 안의 **전체 건물 목록**을 확인한 경우에만 true. false도 적재하지만 ratio는 미제공 |
| maximumHeightMeters | 자료 범위 안 모든 건물 높이의 검증된 상한(m), 0 초과 500 이하. 알려진 건물의 최댓값만으로 미확인 건물의 상한을 보장하지 않음 |
| coverage | 건물 목록 조사 범위의 GeoJSON Polygon/MultiPolygon |

각 Feature는 문자열 id, geometry, properties.heightMeters를 가집니다. 높이를 확인하지 못하면 null 또는 생략합니다. 층수에 임의 층고를 곱하지 않습니다. 실제 건물 기저부터 상단까지의 미터 단위 높이를 사용합니다. 원본 자료의 필드·단위·도형·목록 완전성·최대 높이 상한을 검증한 뒤 변환해야 합니다. 공급자별 수집·변환과 실제 서울숲 자료의 품질 검증은 별도 작업입니다.

전체 입력 형식은 [합성 테스트 fixture](../src/test/resources/shade/buildings.geojson)를 참고합니다. 이 fixture를 실제 서비스 자료로 사용하지 않습니다.

## 적재와 실행

Java 21과 Docker가 필요합니다. 검증·정규화한 실제 자료를 data/buildings/verified-buildings.geojson에 준비한 뒤 실행합니다.

```bash
docker compose up --build -d --wait
./gradlew bootJar
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --spring.main.web-application-type=none \
  --igilro.building-import.file=data/buildings/verified-buildings.geojson
```

파일 전체 SHA-256이 건물 버전입니다. 같은 파일은 메타데이터·도형·높이를 검증하고 재사용하고, 다른 파일은 새 버전으로 적재합니다. 저장과 활성 포인터 교체는 하나의 트랜잭션이며 오류 시 이전 활성 자료를 유지합니다. advisory lock으로 동시 적재를 직렬화하고 요청마다 활성 자료를 확인합니다. 보행 그래프 적재와 건물 적재는 각각 실행합니다.

서버는 기존 명령으로 실행하고 departureAt을 포함한 fastest 요청을 전송합니다. 보행 그래프는 [Phase 2 절차](phase-2-routing.md)로 먼저 준비합니다.

## 검증

```bash
./gradlew test
docker compose build postgres
./gradlew integrationTest
```

단위 테스트는 태양 위치의 물리적 기준·오전/오후 방향·그림자 길이·시각 오프셋·윤년과 입력 검증을 확인합니다. 통합 테스트는 실제 PostGIS의 중복 그림자·오목한 도형·안뜰·자료 누락·경계·확인된 0점·원자적 적재·버전 교체·재적재 충돌·HTTP 응답을 확인합니다.

## 2026-10-19 검증 결과

Windows에서 공식 Temurin Java 21을 프로젝트의 무시되는 .gradle 임시 폴더에 준비하고 Docker Desktop의 Linux 엔진을 실행했습니다. `docker compose build postgres`가 성공했고 실제 PostGIS 테스트 이미지를 사용했습니다. 기존 전역 JAVA_HOME 설정은 변경하지 않았습니다.

`./gradlew.bat build --no-daemon --console=plain --warning-mode all`이 성공했습니다. JVM 단위·아키텍처 62개, 실제 PostgreSQL/PostGIS 통합 26개로 **88개 성공 / 실패 0 / 오류 0 / 건너뜀 0**입니다. 실행 가능한 JAR도 생성했습니다. Python 데이터 준비 코드는 변경하지 않았으며 이번에는 Python 테스트를 재실행하지 않았습니다.

기본 Windows 임시 경로의 Java Unix-domain socket 연결이 실패해 테스트 프로세스에만 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp`를 적용했습니다. 프로젝트의 Java 21 toolchain·서버 실행 설정은 유지했습니다.

검증에는 합성 건물·보행 도형만 사용했습니다. 서울숲 실제 건물 자료 적재, 현장 정확도, 실제 자료 크기에서의 성능과 shade/sunlight 경로 최적화는 아직 검증 범위에 포함되지 않습니다.
