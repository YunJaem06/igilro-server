# Flutter Phase 1 전달 문서 검토

참고 문서: Flutter 프로젝트의 `docs/server_handoff.md` (2026-10-10 검토). 장소·경로 endpoint와 데이터 수치는 계약 제안이며 현재 구현된 API가 아닙니다.

## 서버 Phase 1 반영 결과

기존 Java 21/Kotlin/Spring Boot, Feature-first 계층, JDBC Repository 계약, ARM64 PostgreSQL/PostGIS, Flyway, 상태 검사, 테스트와 CI 구성을 유지합니다. Flutter와의 차이였던 오류 미디어 타입과 JSON 형식을 조정했습니다.

- 일반 API 오류: `application/json`, 최상위 `code`, `message`, `requestId`, 부가 `timestamp`, `errors`.
- `X-Request-Id` 헤더, 오류 본문과 로그 식별자 연결.
- 앱용 health 장애도 HTTP 503 `SERVICE_UNAVAILABLE` 공통 오류로 통일.
- Actuator의 표준 상태 형식 유지, OpenAPI 오류 스키마를 실제 DTO에서 생성.
- Flutter의 origin 기반 API 주소, emulator·simulator·기기 연결 주소와 환경 매핑 문서화.

실행·환경 변수·포트는 [README](../README.md), 최종 오류 예시와 HTTP/code 표는 [API 계약](api-contract.md)을 참고합니다. 이전 RFC 9457 오류 응답은 Flutter 전달 계약을 반영하면서 교체했습니다.

## 장소 검색 제안

`GET /api/v1/places/search?query=서울숲`과 `places[]` 응답 형식을 수용합니다. 공급자 이름을 포함한 opaque `id`, 장소명·주소와 `{longitude, latitude}`를 API DTO로 제공하고 공급자 응답 변환은 infrastructure adapter에서 담당합니다.

빈 검색 결과는 HTTP 200 `places: []`, 빈 문자열·형식 오류는 HTTP 400 `INVALID_REQUEST`로 처리하는 방향입니다. 페이지네이션, 검색 길이 제한, 후보 수, 공급자 장애 코드, 캐시와 위치·표시 약관은 공급자 선정 후 확정합니다. 지도 렌더링 공급자와 검색 공급자를 같은 것으로 가정하지 않습니다.

## 경로 검색 제안

`POST /api/v1/routes/search`, 좌표 객체, `travelMode: walking`, 초기 `mode: fastest`, `routes[]`와 GeoJSON `LineString` 형식을 수용합니다. 서버의 이전 문서에 있던 단일 `route`/Feature 형식은 Flutter 제안의 `routes[].geometry`로 정리했습니다. 성공은 최소 한 경로이며, 유효한 자료와 지역 안에서 경로가 없으면 404 `ROUTE_NOT_FOUND`입니다.

추가 합의가 필요한 부분:

| 항목 | 검토 및 변경 제안 |
| --- | --- |
| departureAt | 입력 시 오프셋 필수. 생략을 허용한다면 서버가 선택한 기준 시각을 evaluatedAt으로 응답 |
| evaluatedAt | departureAt과 같은 순간을 UTC로 표현. 응답 생성 시각으로 덮어쓰지 않음 |
| additionalDurationSeconds | 동일 데이터·출발 시각·이동 조건의 fastest 기준과 비교. 기준 duration도 제공하는 방안 권장. 비교 불가능하면 null 허용 제안 |
| 예상 시간 | 보행 속도, 신호 대기 적용 여부, 초 단위 반올림 규칙과 시간 모델 버전 명시. 현재 값·성능 측정 결과는 없음 |
| metrics | shadeRatio는 실제 길이 비율, quietScore/lightingScore는 정규화 점수. 높은 점수의 의미와 알고리즘 버전을 명시 |
| metric coverage | 각 metric의 자료 커버리지(0..1 또는 미확인 null)를 함께 제공할 필드 추가 제안. 미계산 값을 0으로 채우지 않음 |
| dataVersion | 그래프와 사용한 환경 자료 스냅샷을 재현할 수 있는 opaque 식별자. 단순 서버 빌드 버전과 구분 |
| route id | 클라이언트가 해석하지 않는 값. 저장·재조회 여부, 유효기간과 필요 시 expiresAt을 상세 API 도입 전에 합의 |
| 검색 제한 | 지원 지역 Polygon, 최대 거리, snapping 거리와 실패 기준, 후보 수를 데이터 품질 검증 후 확정 |
| 타임아웃 | Flutter 연결·송신 10초 / 수신 30초를 수용. 경로 기능 구현 후 실제 부하로 측정하며 초과 시 비동기 계약 별도 합의 |

업무 오류의 400 `INVALID_ROUTE_QUERY`, 422 `OUTSIDE_SUPPORTED_AREA`, 404 `ROUTE_NOT_FOUND`, 503 `ROUTING_DATA_UNAVAILABLE` 제안은 수용합니다. 문법·타입 오류는 공통 `INVALID_REQUEST`, 미구현 URL은 `NOT_FOUND`로 구분합니다. graph가 준비되지 않은 상태를 경로 없음으로 숨기지 않습니다. 인증·요청 제한 기능 자체는 아직 도입하지 않았습니다.

## 첫 지역·데이터 확보 계획

현재 실제 지원 지역과 보행 그래프는 없습니다. 첫 PoC 후보로 서울숲과 주변 성동구 일부를 제안하며, 보행 연결성 검증 후 정확한 Polygon과 snapping·검색 한계를 확정합니다. 예시 좌표만으로 성동구 전체 지원을 선언하지 않습니다.

1. 서버 Phase 2에서 OSM 한국 extract를 가져와 후보 지역을 자르고 보행 가능 여부, 횡단보도·계단·접근 제한, 그래프 단절을 확인합니다. [Geofabrik 한국 extract](https://download.geofabrik.de/asia/south-korea.html)를 수급 후보로 사용하며 출처와 스냅샷 버전을 기록합니다. 이용 조건은 [OSM 공식 라이선스 안내](https://www.openstreetmap.org/copyright)를 확인합니다.
2. 검증된 그래프를 PostGIS에 적재한 뒤 snapping과 fastest 경로 탐색을 구현합니다. 실제 거리·시간·연결 경로를 서버 Phase 2 종료 기준으로 검증합니다. Flutter Phase 3에서 이 실제 API에 연결합니다.
3. 건물 Polygon은 [국토교통부 GIS건물통합정보](https://www.data.go.kr/data/15083092/fileData.do)를 수급 후보로 검토합니다. 공간 도형과 건축물 속성 자료를 제공하는 데이터이며, 실제 높이(m) 필드의 충족률·정확도·갱신 시점은 수급 후 확인해야 합니다. 층수를 검증된 높이로 간주하지 않습니다.
4. 이후 환경 계산 단계에서 검증된 높이·건물 도형·태양 위치·평가 시각으로 그림자를 계산하고 shade/sunlight 모드를 추가합니다. 조용함·밝기·비 회피 자료와 복수 조건·가중치는 별도 모델로 확장합니다.

Phase 1에서는 장소 공급자 호출, 그래프 다운로드·적재, 경로 탐색과 그림자 계산을 수행하지 않았습니다.
