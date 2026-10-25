# 장소 검색 구현 검증 — 2026-10-25

Mac Apple Silicon, Java 21.0.12.1, Spring Boot 4.1.1, Gradle 9.7.1에서 검증했습니다. Docker Desktop이 꺼져 있어 시작한 뒤 기존 ARM64 `igilro-postgis:local` 이미지와 개발 DB를 확인했습니다. 테스트용 DB는 별도의 Testcontainers로 생성·정리했습니다.

## 자동 테스트

```sh
./gradlew clean build --no-daemon --console=plain
```

최종 실행 **BUILD SUCCESSFUL**, JVM 단위/API/아키텍처 **90개**, PostgreSQL/PostGIS 통합 **27개**, 합계 **117개**가 통과했습니다. 실패·오류·건너뜀은 0개입니다.

이번 추가는 장소 검색 단위/API **28개**와 기존 경로 통합 클래스의 키 미설정 회귀 테스트 **1개**입니다. 기존 88개 테스트도 유지했습니다. 기존 ServerIntegrationTest에는 장소 OpenAPI operation·단일 필수 query·429/504 문서 검증을 추가했습니다.

- UseCase 5: 한글/Unicode whitespace(NBSP·NEL 포함)/특수문자, 빈 입력·제어 문자·원본/정규화 길이, Unicode code point 상한, 후보 상한·빈 결과, 장애 전파.
- 공급자 HTTP adapter 13: UTF-8 query 전송, 외부 DTO/출처 변환, 숫자 문자열과 경도/위도 순서, 주소 누락, 빈 결과, 손상/불완전/과대 응답, 401/403·5xx·잘못된 상태와 redirect 차단, 안전한 429 Retry-After, 헤더와 body 지연 timeout, 연결 실패, 키 미설정·로컬 제한, JSON media type 검증.
- 제한 2: monotonic 경계 복구와 동시 요청에서 한도 우회 방지.
- MockMvc/도메인 8: query만으로 응답, 정확한 빈 배열 JSON, 누락/공백/길이 400, 필수 주소 문자열, 429/502/503/504와 공통 오류·requestId, Retry-After, 406, 유한 WGS84 좌표.
- 실제 DB 회귀: 키 없이 장소 요청은 미준비 503이지만 좌표 기반 경로는 200, 지역 밖은 기존 422 OUTSIDE_SUPPORTED_AREA. 기존 경로/그늘/health/Flyway 테스트도 통과.

자동 테스트는 실제 검색 공급자에 요청하지 않습니다. HTTP는 loopback의 로컬 합성 공급자만 사용하며 키도 유효하지 않은 fixture 값입니다. test 프로필의 키는 빈 값입니다. Domain/Application 순수성과 API/Infrastructure 분리 ArchUnit 검사도 통과했습니다. 별도 DB 스키마나 외부 의존 라이브러리는 추가하지 않았습니다.

위 수치는 로컬 검증 결과입니다. 기존 GitHub CI의 `clean build`에 이 테스트들이 자동 포함되며, 원격 실행 결과는 해당 커밋의 [GitHub Actions](https://github.com/YunJaem06/igilro-server/actions)에서 별도로 확인합니다.

## 실제 로컬 HTTP

최종 JAR을 `local` 프로필, `127.0.0.1:18080`, 명시적으로 빈 `GEOAPIFY_API_KEY`로 임시 실행했습니다. 기존 개발 DB를 읽었으며 Flyway V1~V3가 이미 적용되어 추가 migration은 없었습니다.

| 요청 | 실제 결과 |
| --- | --- |
| GET /api/v1/health | 200 / UP |
| GET /api/v1/places/search?query=서울숲 | 503 / PLACE_SEARCH_NOT_CONFIGURED |
| query 누락 또는 공백 | 400 / INVALID_REQUEST |
| 기존 서울숲 좌표 POST /api/v1/routes/search | 200 / 경로 1개 |
| 지역 밖 좌표 POST /api/v1/routes/search | 422 / OUTSIDE_SUPPORTED_AREA |
| GET /v3/api-docs | 200, 장소 검색 operation과 오류 응답 포함 |

모든 응답은 application/json이고 전달한 X-Request-Id를 보존했습니다. 오류 본문의 requestId도 같은 값입니다. 실제로 받은 키 미설정 응답:

```json
{
  "code": "PLACE_SEARCH_NOT_CONFIGURED",
  "message": "장소 검색 서비스가 준비되지 않았습니다.",
  "requestId": "4914f88f-07a8-4f8b-b2c0-58fcf268f311",
  "timestamp": "2026-10-25T03:27:37.891878Z",
  "errors": []
}
```

검증용 18080 서버는 정상 종료했습니다. 사용자 개발 DB는 유지했습니다. 기본 실행 포트는 기존 8080이며 local/dev의 OpenAPI JSON `/v3/api-docs`, UI `/swagger-ui/index.html`을 유지합니다. prod에서는 기존대로 비활성화합니다.

## 실제 공급자 검증은 미실시

`GEOAPIFY_API_KEY`가 프로세스 환경과 로컬 `.env`에 설정되지 않아 실제 Geoapify 검색 성공·빈 결과·서울숲/한국 주소 품질은 검증하지 않았습니다. 합성 테스트의 후보를 실제 공급자 검색 결과로 보고하지 않습니다.

[발급·실행 절차](place-search.md)에 따라 키를 서버 환경에 설정하고 서버를 재시작한 뒤 서울숲과 한국 주소의 curl 결과를 확인해야 합니다. Flutter에서 추가 `attributions` 처리와 Mapbox 핀 위치를 검증하고, 운영 ECS에서는 별도 secret과 execution role 읽기 권한을 구성해야 합니다. 실제 공급자 응답 캡처는 그 후 추가합니다. 다중 태스크의 공유 quota/일일 예산 제한도 후속 범위입니다.
