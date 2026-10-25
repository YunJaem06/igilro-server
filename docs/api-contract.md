# API 계약

## 기본 연결 규칙

Flutter의 `API_BASE_URL`에는 origin만 넣습니다. 기본 서버 origin은 `http://localhost:8080`이며 `/api/v1`은 각 endpoint 경로에 포함합니다. 요청과 일반 API 응답은 JSON이며 `Accept: application/json`, 본문이 있는 요청은 `Content-Type: application/json`을 사용합니다.

현재 앱용 endpoint는 `GET /api/v1/health`, `POST /api/v1/routes/search`입니다. 장소 검색은 미구현입니다. [실제 경로 검색 계약과 실행 방법](phase-2-routing.md)을 참고합니다.

DTO 연동용 [요청 JSON](examples/route-search-fixture-request.json), [건물 자료 없는 성공 응답](examples/route-search-fixture-response.json), [그늘 평가 가능한 성공 응답](examples/route-search-fixture-response-shade-available.json)은 현재 서버에서 합성 자료로 실제 캡처한 예시입니다. snapping과 두 attribution의 위치·타입·null 규칙은 [Phase 2의 전체 예시 설명](phase-2-routing.md)에 정리했습니다.

Flutter는 지도 선택·현재 위치로 준비한 WGS84 좌표를 경로 요청의 origin/destination에 직접 전달할 수 있습니다. 장소명·주소·공급자 ID는 현재 경로 요청에 필요하지 않으며 장소 검색 API 구현을 기다리지 않아도 됩니다. JSON 요청에는 정의된 필드만 전달합니다. 최신 Flutter 상태와 서버의 반영 현황은 [전달 내용 검토](flutter-handoff-review.md)를 참고합니다.

## 장소명·주소 검색

`GET /api/v1/places/search?query=서울숲`을 구현했습니다. 필수 파라미터는 `query` 하나이며 정규화 후 1~100 Unicode 문자, 최대 후보 10개입니다. 성공의 `places[]`는 `id`, `name`, 빈 문자열을 허용하는 `address`, WGS84 숫자의 `coordinate.longitude/latitude`입니다. `id`에는 `geoapify:` 접두사가 있으며 opaque 값입니다. 결과가 없으면 정확히 `200 {"places":[]}`를 반환합니다.

결과가 있을 때 출처 표시를 위한 비필수 `attributions: [{text, url}]`을 추가합니다. `url`은 없으면 null입니다. 앱은 출처를 선택 상태와 함께 보관·표시합니다. 공급자 장애·미준비·429·timeout·잘못된 응답은 성공의 빈 배열로 처리하지 않습니다. 키 미설정은 503 `PLACE_SEARCH_NOT_CONFIGURED`, 공급자 인증 실패는 앱 사용자 401 대신 503입니다. 전체 매핑·제한·출처 조건은 [장소 검색 계약](place-search.md)에 명시했습니다.

장소 검색은 대한민국, 경로 검색은 기존 서울숲 pilot입니다. 경로 입력은 좌표만 유지하며 `OUTSIDE_SUPPORTED_AREA` 처리를 변경하지 않습니다.

## 상태 확인

`GET /api/v1/health`는 PostgreSQL 연결과 PostGIS 공간 함수 호출에 성공하면 HTTP 200을 반환합니다.

```json
{
  "status": "UP",
  "checkedAt": "2026-10-10T03:00:00Z"
}
```

`checkedAt`은 검사가 끝난 UTC 시각입니다. 공간 DB를 사용할 수 없으면 HTTP 503과 아래 공통 오류의 `SERVICE_UNAVAILABLE`을 반환합니다. 성공 응답에 업무 오류를 넣지 않습니다.

`GET /actuator/health`와 `/actuator/health/readiness`, `/actuator/health/liveness`는 Spring Boot의 표준 응답을 유지합니다. 예를 들어 종합 상태는 `{"status":"UP"}`이며 DB 장애 시 `{"status":"DOWN"}`과 HTTP 503입니다. liveness는 DB 장애에도 프로세스의 생존 여부만 검사합니다. prod의 Actuator는 별도 관리 포트 8081에서 제공됩니다.

## 최종 공통 오류 형식

Spring MVC 요청 처리 오류와 앱용 상태 API의 장애는 `Content-Type: application/json`으로 응답합니다. 최상위 `code`와 `requestId`는 필수입니다.

```json
{
  "code": "INVALID_REQUEST",
  "message": "요청 형식 또는 입력값이 올바르지 않습니다.",
  "requestId": "cebb12d6-8717-42a6-bb4d-347d6aeb9627",
  "timestamp": "2026-10-10T03:00:00Z",
  "errors": [{"field": "name", "message": "must not be blank"}]
}
```

서버는 진단용 `message`를 제공하지만 Flutter는 화면 문구를 HTTP 상태와 `code`로 결정합니다. `errors`는 입력 검증 상세이며 검증 오류가 없으면 빈 배열입니다. 입력값, 쿼리 문자열, 스택 트레이스는 포함하지 않습니다. `timestamp`는 UTC RFC 3339 문자열입니다.

`requestId`는 UUID이며 `X-Request-Id` 응답 헤더와 서버 로그의 MDC 값이 일치합니다. 클라이언트가 요청 헤더에 유효한 UUID를 전달하면 이를 유지하고, 누락되거나 유효하지 않으면 서버에서 생성합니다.

| HTTP | 현재 공통 code | 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON 형식·필수 입력·타입·검증 오류 |
| 404 | `NOT_FOUND` | 구현된 리소스나 endpoint를 찾을 수 없음 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 HTTP 메서드; Allow 헤더 유지 |
| 406 | `NOT_ACCEPTABLE` | 지원하지 않는 응답 형식 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 본문 형식 |
| 429 | `TOO_MANY_REQUESTS` | 공통 매핑. 장소 검색에는 실제 프로세스별 요청 제한·공급자 429 변환 적용 |
| 500 | `INTERNAL_SERVER_ERROR` | 예상하지 못한 서버 오류 |
| 503 | `SERVICE_UNAVAILABLE` | 현재 상태 API에서 공간 DB 사용 불가 |

그 밖의 처리된 5xx는 `INTERNAL_SERVER_ERROR`, 명시적인 매핑이 없는 4xx는 `INVALID_REQUEST`를 사용하며 HTTP 상태는 유지합니다. 인증 도입 시 401/403 코드와 필터 계층의 JSON 처리를 별도로 추가합니다. Actuator와 서블릿 컨테이너가 MVC 진입 전에 거부한 요청은 각각 해당 계층의 기본 오류 형식을 사용합니다.

## 경로 업무 코드

Phase 2 경로 검색은 아래 업무 코드를 구현했습니다. 요청 제한 기능은 아직 미구현입니다.

| HTTP | code | 적용 범위 |
| --- | --- | --- |
| 400 | `INVALID_ROUTE_QUERY` | 파싱·일반 검증을 통과했지만 경로 조건이 유효하지 않음 |
| 422 | `OUTSIDE_SUPPORTED_AREA` | 유효한 좌표가 확정된 서비스 지역 밖 |
| 404 | `ROUTE_NOT_FOUND` | 사용 가능한 데이터와 지역 안에서 연결 가능한 보행 경로 없음 |
| 429 | `TOO_MANY_REQUESTS` | 요청 제한 도입 시 적용, 재시도 가능 시 Retry-After 제공 |
| 503 | `ROUTING_DATA_UNAVAILABLE` | 경로 그래프·필수 경로 데이터 준비되지 않음 |

모르는 URL의 404 `NOT_FOUND`와 실제 검색 실패인 404 `ROUTE_NOT_FOUND`를 구분합니다. 지원 지역 안에서 최대 50m의 후보 보행 구간에 연결하지 못하거나 경로가 없으면 ROUTE_NOT_FOUND입니다. 지역은 [Phase 2 문서](phase-2-routing.md)의 서울숲 pilot입니다. 장소 검색의 빈 결과는 별개 계약인 200 `places: []`입니다.

## 경로 좌표·단위·시각 규칙

| 항목 | 기준 |
| --- | --- |
| 좌표 | WGS 84/SRID 4326, 유한 숫자, 경도 -180..180 / 위도 -90..90 |
| 입력 좌표 | `{longitude, latitude}` 객체 |
| 응답 경로 | `routes[].geometry`에 GeoJSON `LineString` geometry 직접 제공 |
| GeoJSON 배열 | `[longitude, latitude]`, 최소 두 좌표; 별도 Feature 래퍼 불필요 |
| 거리 | `distanceMeters`, 미터(m), 0 이상의 유한 숫자 |
| 소요·추가 시간 | `durationSeconds`, `additionalDurationSeconds`, 초(s); 전체 예상 시간은 올림 정수, fastest 자체의 추가 시간은 0 |
| 입력 시각 | `departureAt`, RFC 3339, `Z` 또는 오프셋 필수 |
| 평가 시각 | `evaluatedAt`, 실제 적용한 출발·환경 평가 기준 시각, UTC `Z`로 응답 |
| 환경 지표 | shadeRatio는 전체 경로 평가 시 0..1, 미평가 시 null. 다른 세 metric은 null. 향후 점수는 0..1, trafficSignalCount는 개수 |

`evaluatedAt`은 응답 생성 시각이 아닙니다. `departureAt`이 주어지면 같은 순간을 UTC로 정규화합니다. 생략하면 주입된 Clock의 현재 UTC 시각을 사용하여 응답합니다. 향후 이동 중 구간별 시각을 사용하는 모델은 이 출발 기준과 별도 계약으로 확장합니다.

GeoJSON 좌표는 도(degree) 단위이므로 미터 거리 계산에는 `geography` 또는 적절한 투영 좌표계를 사용합니다. 그림자와 보행 구간의 교차 길이도 일관된 미터 단위 좌표계에서 계산합니다. 그늘 비율은 그림자 영역을 합집합 처리한 뒤 `그늘과 겹치는 보행 길이 / 전체 보행 길이`로 평가하며 높이 누락과 자료 품질을 함께 기록합니다.

현재 모델의 거리·시간은 스냅 지점 사이의 네트워크 경로를 기준으로 합니다. 접근 거리와 실제 끝점을 snapping으로 함께 제공합니다. 보행 1.4m/s·계단 0.9m/s의 정적 모델이며 신호 대기는 포함하지 않습니다. travelMode/mode는 walking/fastest만 지원하고 생략하면 기본값을 사용합니다. unknown field는 400으로 거부합니다. dataVersion은 데이터 스냅샷, timeModelVersion은 시간 비용 모델, routingModelVersion은 탐색·snapping 정책 버전입니다. 경로 ID는 응답 내 식별자이며 저장·상세 조회는 미지원입니다.

Flutter 전달 예시에 더해 현재 응답은 baselineDurationSeconds, timeModelVersion, routingModelVersion, snapping, attribution을 제공합니다. 연동 시 Flutter DTO의 추가 필드 처리 정책을 확인하고, 실제 경로 끝점·접근 거리와 OSM 출처 표시를 반영합니다. shadeRatio/quietScore/lightingScore의 향후 0..1 비율·점수 규칙은 개수 필드인 trafficSignalCount에는 적용하지 않습니다.

## 건물 그늘 평가 (Phase 3)

기존 fastest 경로의 metrics.shadeRatio를 건물 도형·높이와 evaluatedAt 기준으로 계산합니다. routes[].shadeEvaluation에 status, coverage, dataVersion, modelVersion, attribution, solarAltitudeDegrees를 추가합니다. 건물 버전·출처는 이 객체에 제공하며 최상위 dataVersion/attribution은 기존 보행 그래프/OSM 계약을 유지합니다.

전체 경로 커버리지가 1인 AVAILABLE에서만 shadeRatio를 제공합니다. DATA_UNAVAILABLE, INCOMPLETE_COVERAGE, NIGHT, LOW_SUN, UNSUPPORTED_TIME에서는 null입니다. 확인된 그늘 없음만 0이며 밤을 1로 표시하지 않습니다. 환경 자료가 없어도 유효한 fastest 경로는 200입니다. 출발 시각에 고정된 한국 pilot의 평면 건물 그림자 모델이며 이동 중 시간·구름·나무·지형은 미반영입니다. 다른 세 metric은 계속 null이고 shade/sunlight mode는 아직 미지원입니다. 상세 상태·커버리지·입력 형식은 [그늘 평가 문서](phase-3-shade.md)를 참고합니다.
