# 카카오 장소명·주소 검색

2026-10-29: 기존 Geoapify 공급자를 Kakao Local REST API로 교체했다. Flutter endpoint와 응답의 기본 필드는 유지한다. 개발 환경의 키 등록 후 현재 8080 서버에서 실제 장소명·도로명 주소 검색을 확인했다.

## Mapbox와 함께 사용

카카오의 2026-05-27 [공식 답변](https://devtalk.kakao.com/t/local-api-sdk/149943/2)은 타사 API와 함께 사용하는 것을 별도로 제한하지 않는다고 안내한다. 이를 근거로 카카오에서 실시간 검색한 장소를 Mapbox의 목록·핀으로 표시하는 구성을 사용한다. Mapbox를 특정해서 승인한 별도 계약이라는 의미는 아니다. [운영정책](https://developers.kakao.com/terms/ko/site-policies)과 [공식 저장 범위 안내](https://devtalk.kakao.com/t/local-api/151263)를 함께 확인했다.

검색 결과를 별도 DB·파일·캐시나 로그에 저장하지 않는다. 앱의 검색·출발/도착 선택에는 현재 실행 중 메모리 상태만 사용한다. 응답은 `Cache-Control: no-store`이며 원본 응답, 검색어와 키는 로그에 남기지 않는다. 실제 공급자 응답을 fixture 파일로 보관하지 않으며 테스트는 합성 자료를 사용한다. 즐겨찾기·검색 이력·공유 장소 저장은 이 구현 범위에 포함하지 않는다.

## 설정

1. [카카오디벨로퍼스](https://developers.kakao.com/)에서 앱을 생성한다.
2. 앱의 **카카오맵 → 사용 설정**을 ON으로 설정하고 REST API 키를 준비한다. 현재 계정에 적용되는 활성화·쿼터 조건은 [공식 사용 방법](https://developers.kakao.com/docs/ko/kakaomap/common)을 따른다.
3. 서버 프로젝트의 기존 `.env`에 다음 항목을 추가한다. `local` 프로필과 프로젝트 루트 Working directory에서 자동으로 읽는다. OS 환경 변수는 파일보다 우선한다.

```dotenv
KAKAO_REST_API_KEY=발급받은_실제_REST_API_키
```

키는 서버에서만 사용한다. Flutter 설정, Git, URL, 오류 본문에 넣지 않는다. `GEOAPIFY_API_KEY`는 더 이상 읽지 않으며 자동 fallback하지 않는다. 키가 비어 있으면 장소 검색만 503 `PLACE_SEARCH_NOT_CONFIGURED`이고 기존 경로 검색은 계속 동작한다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

## 호출·변환 계약

앱은 기존처럼 `GET /api/v1/places/search?query=서울숲`을 호출한다. 공백 정규화 후 1~100 Unicode 문자, 최대 10개를 반환하며 입력 상한과 오류 형식을 유지한다.

- 서버는 먼저 `/v2/local/search/keyword.json`을 호출한다. 정상 빈 결과인 경우에만 `/v2/local/search/address.json`을 한 번 호출한다. 주소 검색으로 전환하는 것과 장애 재시도는 구분한다. 공급자 오류나 잘못된 응답에는 다른 공급자·빈 성공으로 대체하지 않는다.
- 두 요청 모두 `query`, `size`, `page=1`만 전달한다. 키는 `Authorization: KakaoAK ...` 헤더에 넣는다. 카카오 경로 API는 호출하지 않는다.
- 키워드 결과의 `place_name`과 `x/y`를 기존 `name`과 WGS84 `coordinate.longitude/latitude`로 변환한다. 도로명 주소를 우선하고 없으면 지번 주소, 둘 다 없으면 빈 문자열이다.
- 주소 결과는 건물명이 있으면 표시 이름으로 사용하고 없으면 주소를 사용한다. 이 API에는 장소 ID가 없으므로 응답용 식별자를 생성한다.
- `id`는 `kakao:place:...` 또는 `kakao:address:...`인 opaque 문자열이다. 앱은 파싱하거나 이 값을 조회 키로 사용하지 않는다. 주소 식별자는 카카오의 장소 ID가 아니다.
- 숫자·숫자 문자열을 모두 처리하며 유한 WGS84 좌표를 검증한다. 잘못된 후보를 버려 빈 성공으로 만들지 않는다. 알 수 없는 추가 필드는 허용한다.
- `attributions`는 결과가 있을 때 `[{"text":"장소 정보: 카카오맵","url":"https://map.kakao.com/"}]`이다. 기존 Flutter 출처 표시를 사용한다. 장소 출처에 Geoapify·OSM을 잘못 붙이지 않으며 지도·경로 자체의 Mapbox·OSM 출처는 별도로 유지한다.
- 두 API 모두 빈 경우에는 정확히 `200 {"places":[]}`이다. 장소 검색 범위와 서울숲 경로 검색 범위는 별개다. 경로 요청에는 기존 정의된 좌표·mode·시각만 보낸다.

## 제한·오류

`PLACES_CONNECT_TIMEOUT=2s`, `PLACES_RESPONSE_TIMEOUT=3s`를 유지한다. 키워드와 주소 요청의 헤더·본문 수신이 하나의 전체 응답 제한 시간을 공유한다. 각 응답은 최대 256 KiB이다. `PLACES_REQUESTS_PER_SECOND=4`는 JVM별 사용자 검색 요청의 진입 간격이며 한 검색당 공급자 호출은 최대 두 번이다. 계정의 쿼터·복제 수·다른 서비스 사용량까지 포함한 전체 예산 제어는 별도로 검토한다.

| HTTP | code | 처리 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 기존 검색어 입력 검증 |
| 429 | TOO_MANY_REQUESTS | 로컬 진입 제한 또는 공급자 429 |
| 502 | PLACE_SEARCH_UNAVAILABLE | 공급자 5xx·연결 실패 |
| 502 | PLACE_SEARCH_BAD_RESPONSE | 손상된 JSON·잘못된 좌표·응답 크기 초과·예상 밖 응답 |
| 503 | PLACE_SEARCH_NOT_CONFIGURED | 서버 키 미설정 |
| 503 | PLACE_SEARCH_PROVIDER_AUTH_FAILED | 공급자 401/403; 앱 사용자 인증 오류로 바꾸지 않음 |
| 504 | PLACE_SEARCH_TIMEOUT | 두 호출에 공유하는 전체 응답 시간 초과 |

기존 `code`, `requestId`, `X-Request-Id`와 안전한 `Retry-After` 처리도 유지한다. 카카오의 진단용 오류 본문과 키는 앱에 전달하지 않는다.

## 검증

로컬 HTTP 서버의 합성 응답으로 UTF-8 검색어·헤더 인증·빈 결과의 주소 검색 전환·좌표 변환·출처·잘못된 응답·크기·인증·제한·전체 timeout을 검사한다. 기존 UseCase·Controller·RateLimiter 테스트도 유지한다.

실제 장소명·도로명 주소 검색의 HTTP 200, 카카오 식별자·출처, WGS84 좌표, `Cache-Control: no-store`, `X-Request-Id`를 확인했다. 공원 출입구처럼 모호한 이름은 다른 시설의 후보가 나올 수 있으므로 앱에서 주소와 지도 위치를 확인하고 선택해야 한다. 실제 검색 결과는 fixture나 로그 파일로 보관하지 않았다. 외부 API 요청은 단위 테스트에 포함하지 않는다. [REST API 규격](https://developers.kakao.com/docs/ko/kakaomap/rest-api), [쿼터](https://developers.kakao.com/docs/ko/getting-started/quota)를 참고한다.

검증 결과: 키 등록 후 장소 검색 관련 테스트 33개와 bootJar 빌드를 다시 실행해 통과했다. 실행 중인 개발 서버에 카카오 설정이 적용되었고 health 및 DB·PostGIS를 포함한 readiness가 모두 HTTP 200 / UP이었다. 빈 검색어의 400 `INVALID_REQUEST`와 요청 간격 제한의 429 `TOO_MANY_REQUESTS`도 확인했다. Windows Java 임시 소켓 경로 문제는 검증 프로세스의 TEMP/TMP와 java.io.tmpdir를 영문 경로로 지정해 해결했으며 시스템 전역 설정은 바꾸지 않았다. `.env`의 Git 제외와 커밋 대상 파일에 키 원문이 없음을 확인했다.
