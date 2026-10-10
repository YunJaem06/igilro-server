# API 계약

## 현재 제공하는 API

`GET /api/v1/health`

| 상태 | HTTP | 의미 |
| --- | --- | --- |
| `UP` | 200 | PostgreSQL 연결과 PostGIS 공간 함수 호출 성공 |
| `DOWN` | 503 | 공간 DB 사용 불가 |

```json
{
  "status": "UP",
  "checkedAt": "2026-10-10T03:00:00Z"
}
```

`checkedAt`은 검사가 끝난 시각이며 서버의 UTC 시계 기준 RFC 3339 문자열입니다. 요청과 응답의 `X-Request-Id`는 UUID입니다. DOWN도 같은 DTO를 사용합니다.

## 공통 오류 응답

Spring MVC 요청 처리 오류는 `Content-Type: application/problem+json`으로 응답합니다.

```json
{
  "type": "urn:igilro:error:invalid-request",
  "title": "Bad Request",
  "status": 400,
  "detail": "요청 형식 또는 입력값이 올바르지 않습니다.",
  "instance": "/api/v1/example",
  "code": "INVALID_REQUEST",
  "timestamp": "2026-10-10T03:00:00Z",
  "requestId": "cebb12d6-8717-42a6-bb4d-347d6aeb9627",
  "errors": [{"field": "name", "message": "must not be blank"}]
}
```

`/api/v1/example`은 형식 설명용이며 구현된 API가 아닙니다. `errors`는 필드·파라미터 검증 오류 목록으로, 오류가 없으면 빈 배열입니다. 검증 메시지는 Validator 설정에 따라 달라질 수 있으므로 Flutter의 분기는 `code`와 `status`를 사용합니다. `instance`는 쿼리 문자열을 제외한 요청 경로입니다.

| HTTP | code |
| --- | --- |
| 400 | `INVALID_REQUEST` |
| 404 | `NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 406 | `NOT_ACCEPTABLE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 500 및 기타 처리된 5xx | `INTERNAL_SERVER_ERROR` |

상태 API의 정상적인 DOWN 응답은 위 오류 형식 대신 `HealthResponse`와 HTTP 503을 사용합니다. Actuator는 Spring Boot 표준 상태 형식을 사용합니다. 향후 도메인 오류가 생기면 Domain 오류와 HTTP 매핑을 분리하여 오류 코드를 추가합니다.

## 향후 Flutter 경로 계약의 기준

이 항목은 다음 단계의 설계 기준입니다. 현재 경로 API·DTO·엔티티·계산은 구현하지 않았으며 경로 요청 형식, 메서드와 URI는 이후 확정합니다.

| 항목 | 기준 |
| --- | --- |
| 지도 좌표 | RFC 7946 GeoJSON, WGS 84/SRID 4326 |
| 좌표 배열 | `[longitude, latitude]`, 즉 `[경도, 위도]` |
| 범위 | 경도 -180..180, 위도 -90..90, 유한 숫자 |
| 경로 형상 | GeoJSON `Feature`의 `LineString`, 최소 두 좌표 |
| 거리 | `distanceMeters`, 미터(m), 0 이상의 숫자 |
| 예상 소요 시간 | `durationSeconds`, 초(s), 0 이상의 숫자 |
| 평가 기준 시각 | `evaluatedAt`, 모든 환경 점수 계산에 적용한 하나의 순간 |
| 입력 시각 | RFC 3339, UTC `Z` 또는 오프셋 필수; 오프셋 없는 로컬 날짜·시간은 거부 |
| 응답 시각 | UTC `Z`로 정규화; 생성 시각과 환경 평가 시각을 혼동하지 않음 |

다음은 형식을 설명하기 위한 가상 응답입니다. 실제 거리·시간·추천 결과가 아닙니다.

```json
{
  "route": {
    "type": "Feature",
    "geometry": {
      "type": "LineString",
      "coordinates": [[126.9780, 37.5665], [126.9790, 37.5665]]
    },
    "properties": {}
  },
  "distanceMeters": 88.3,
  "durationSeconds": 70,
  "evaluatedAt": "2026-10-10T06:00:00Z"
}
```

길 모드는 빠른 길, 그늘, 햇빛, 러닝, 조용함, 밝기, 비 회피를 고려합니다. 구체적인 enum 코드와 복수 조건·가중치의 요청 구조는 Phase 2 이후 클라이언트와 함께 확정합니다. 환경 평가는 하나의 `evaluatedAt`을 공유하고 기본 시각을 선택한 경우에도 실제 사용한 시각을 반드시 응답합니다. UTC 순간을 기준으로 태양 위치를 구하고, 필요한 지역 시간대는 좌표와 명시적인 규칙으로 해석합니다.

GeoJSON 좌표 단위는 도(degree)입니다. 거리를 계산할 때는 `geography` 또는 미터 단위의 적절한 투영 좌표계를 사용하며 SRID 4326 geometry의 길이를 그대로 미터로 응답하지 않습니다. 그림자 Polygon과 건물 높이, 보행 구간의 교차 길이도 일관된 미터 단위 좌표계에서 계산합니다. 이후 그늘 비율은 겹치는 그림자 영역을 합집합 처리한 뒤 `그늘과 겹치는 보행 길이 / 전체 보행 길이`로 평가하고, 높이 누락·데이터 품질·시간에 따른 경계 조건을 별도로 정의합니다.
