# 한국 장소명·주소 검색

2026-10-29 장소 검색을 카카오 Local로 전환했습니다. **현재 설정·계약은 [카카오 장소 검색](kakao-place-search.md)**을 따릅니다. 아래 Geoapify 관련 설명과 검증 기록은 이전 공급자의 이력입니다. 같은 날 실제 키 등록·서버 재시작 후 장소명·주소 검색의 HTTP 200과 휴대폰 LAN 통신을 확인했습니다. 국내 검색 품질·기기 지도 핀 검증은 별도입니다.


2026-10-25 구현. `GET /api/v1/places/search?query=서울숲`은 Geoapify Geocoding API의 실시간 결과를 변환합니다. 필수 요청 파라미터는 `query` 하나입니다. 페이지, 현재 위치, 상세 조회는 제공하지 않습니다. 샘플 장소·DB fallback·검색 결과 캐시는 없습니다.

## 공급자 선정과 확인한 공식 문서

| 검토 대상 | 확인과 결정 |
| --- | --- |
| Geoapify Geocoding | 선택. 전 세계 주소·장소/amenity 검색, 한국어 `ko`, 국가 필터 `countrycode:kr` 지원. HTTP/GeoJSON 방식으로 지도 라이브러리와 분리되고 Mapbox 계열 지도에 결과 표시 예제가 있습니다. 저장·캐시 허용 범위와 출처 조건을 공개합니다. |
| Kakao Local | 한국 키워드 장소/주소 API가 별도로 존재합니다. 이번 구현에서는 외부 지도 사용·표시·데이터 재사용 조건까지 명확히 연결할 수 있는 Geoapify를 선택했습니다. Kakao 사용을 금지한다고 판단한 것은 아니며, 교체 시 계정의 최신 지도/로컬 조건을 별도로 확인합니다. |
| Mapbox Search Box | 현재 공식 지원 지리 범위에 한국이 포함되지 않고, Geocoding은 POI 기능이 다릅니다. 앱의 지도 렌더링 토큰만으로 한국 장소 검색이 가능하다고 가정하지 않았습니다. |

Geoapify는 공개 데이터 기반이므로 국내 모든 상호·신축 건물·도로명/지번의 완전성을 보장하지 않습니다. 한국어 장소와 주소를 검색할 수 있는 문서상 지원과 서울숲/국내 실제 검색 품질 검증을 구분합니다. 현재 키가 없어 공급자 실검색 품질은 아직 확인하지 않았습니다.

공식 근거:

- [Geocoding API와 저장 FAQ](https://www.geoapify.com/geocoding-api/): 국가/주소/amenity 검색, 결과 저장 허용과 재사용 시 데이터 출처 유지.
- [API 파라미터·언어·응답](https://apidocs.geoapify.com/docs/geocoding/forward-geocoding/): `text`, `filter`, `lang=ko`, `limit`, `place_id`, `lon`/`lat`, `formatted`, `datasource`.
- [지도와 독립된 HTTP 통합](https://www.geoapify.com/), [Mapbox 계열 지도에서 Geocoding/Places 결과 표시 예제](https://apidocs.geoapify.com/samples/geojson/geojson-points-layer-mapbox/). Mapbox 지도 위에 결과를 표시할 수 있다는 판단은 이 문서와 공개 데이터 조건을 함께 근거로 합니다. 현재 Mapbox SDK와 기본 지도 출처는 유지합니다.
- [Geoapify 이용약관](https://www.geoapify.com/terms-and-conditions/): 모든 요금제의 OSM 출처, Free의 Geoapify 표시, API별 추가 출처, 사용 제한 준수. 무료 운영 관련 약관의 제한 문구와 가격 FAQ의 상업용 운영 허용 문구를 함께 확인했으며, 실제 운영은 가입한 계정/플랜의 조건을 기준으로 합니다.
- [요금/쿼터](https://www.geoapify.com/pricing/), [호출별 credit](https://www.geoapify.com/pricing-details/): 확인일 기준 Free 3,000 credits/day, 최대 5 requests/s, Geocoding 1회 1 credit. API 10 플랜은 월 결제 기준 10,000 credits/day, 최대 12 requests/s, USD 59/month입니다. 세금·초과 과금·서비스 조건은 계정과 변경된 가격을 확인합니다. 무료 쿼터 소진 후 무제한 무료라고 가정하지 않습니다.
- [Kakao Local 공식 API](https://developers.kakao.com/docs/ko/local/dev-guide), [Mapbox Search Box 지원 범위/제한](https://docs.mapbox.com/api/search/search-box/), [Mapbox 검색 제품 비교](https://docs.mapbox.com/help/getting-started/search/).

## 키 발급과 로컬 실행

1. [Geoapify MyProjects](https://myprojects.geoapify.com/)에 가입합니다.
2. 프로젝트를 만들고 **API Keys**에서 서버용 키를 발급합니다. Mapbox 공개 토큰과 별도입니다.
3. 필요하면 서버의 실제 고정 egress IP로 키 사용을 제한합니다. 브라우저 referrer/origin 제한을 서버 호출에 그대로 적용하지 않습니다.
4. `.env.example`의 항목을 로컬 `.env`에 추가하거나 서버 프로세스 환경 변수로 설정합니다. 실제 키를 커밋·로그·Flutter 코드/빌드 옵션에 넣지 않습니다.

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| `GEOAPIFY_API_KEY` | 빈 값 | 공급자 키. 빈 값/공백 키는 장소 요청만 503으로 처리 |
| `PLACES_CONNECT_TIMEOUT` | `2s` | 공급자 연결 제한 시간 |
| `PLACES_RESPONSE_TIMEOUT` | `3s` | 연결과 전체 응답 body 수신을 포함한 제한 시간 |
| `PLACES_REQUESTS_PER_SECOND` | `4` | 프로세스당 요청 간격 제한. 기본 250ms당 한 요청, 대기열 없음 |

두 timeout은 1ms~10s, 요청 제한은 1~1,000 범위의 설정을 받습니다. 유효하지 않은 timeout/숫자 설정은 시작 시 거부합니다. **키 미설정은 시작을 막지 않습니다.** 장소 공급자는 health/readiness의 필수 의존성이 아니며 기존 경로·건물·Flyway와 분리합니다.

프로젝트 루트에서 Java 21과 Docker를 준비하고, 기본 DB 설정을 사용할 때:

```sh
# 기존 .env가 있으면 내용을 유지하고 새 항목만 추가합니다.
test -f .env || cp .env.example .env
# 편집기에서 GEOAPIFY_API_KEY를 설정합니다. 키를 명령행 인자로 넣지 않습니다.
set -a
source .env
set +a
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

Spring Boot는 `.env`를 자동 로드하지 않습니다. 위 source는 본인이 작성한 로컬 파일을 대상으로 합니다. DB 이름·포트·비밀번호를 변경했다면 README의 `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` 설정도 동일하게 적용합니다. CI는 키를 설정하지 않으며 테스트 프로필은 빈 키로 고정합니다.

```sh
curl --get --include 'http://localhost:8080/api/v1/places/search' \
  --header 'Accept: application/json' \
  --data-urlencode 'query=서울숲'

curl --get --include 'http://localhost:8080/api/v1/places/search' \
  --header 'Accept: application/json' \
  --data-urlencode 'query=서울특별시 성동구 뚝섬로 273'
```

이 명령은 서버에만 요청하며 공급자 키를 URL/argv에 넣지 않습니다. 서버는 공급자가 지정한 `apiKey` query parameter로 HTTPS 요청합니다. 서버는 그 URI·키·검색어·공급자 본문을 로그에 남기지 않으며 원본 예외 cause도 API 오류에 연결하지 않습니다. HTTP wire/JDK HTTP client 디버그 로깅, outbound 프록시 URL 기록은 키가 포함될 수 있으므로 활성화하지 않습니다.

ECS에서는 별도 Secrets Manager secret을 `GEOAPIFY_API_KEY` 환경에 주입하고 app execution role에 해당 secret만 읽는 권한을 추가해야 합니다. Terraform 변수/일반 environment 값에 실제 키를 기록하지 않습니다. 기존 인프라의 DB secret 권한만으로 이 새 secret을 읽을 수 없습니다. migration/bootstrap 작업에는 검색 키가 필요하지 않습니다. 이 서버 작업에서는 AWS secret이나 인프라 권한을 실제 변경하지 않았습니다.

## 확정한 API 계약

요청은 UTF-8 query 한 개입니다. 서버는 Unicode whitespace를 ASCII 공백 하나로 접고 앞뒤를 제거합니다. 정규화 후 **1~100 Unicode code points**를 허용합니다. 별도로 원본 입력은 **1,024 UTF-16 code units** 이하여야 하며 남은 제어 문자는 거부합니다. 한글·특수문자는 UTF-8 query 값 하나로 인코딩해 전달합니다. 서버 debounce는 없습니다.

상위 공급자 호출은 `text=<정규화 검색어>&format=json&filter=countrycode:kr&bias=countrycode:none&lang=ko&limit=10`입니다. 국가 범위는 대한민국 전체이며 서울숲 pilot 경계로 필터링하지 않습니다. 사용자 현재 위치, 서버 egress 국가에 의한 암묵적 bias, 페이지 넘김, 자동 재시도는 사용하지 않습니다.

결과가 없을 때 응답은 정확히 다음과 같습니다.

```json
{"places":[]}
```

결과가 있으면 `places[]`의 기존 필드는 그대로입니다. 아래는 **테스트 공급자로 검증한 형식이며 실검색 결과가 아닙니다**.

```json
{
  "places": [{
    "id": "geoapify:fixture-1",
    "name": "합성 테스트 장소",
    "address": "합성 테스트 주소",
    "coordinate": {"longitude": 127.0374, "latitude": 37.5445}
  }],
  "attributions": [
    {"text": "Powered by Geoapify", "url": "https://www.geoapify.com/"},
    {"text": "© OpenStreetMap contributors", "url": "https://www.openstreetmap.org/copyright"}
  ]
}
```

- `id`는 `geoapify:` + 원본 `place_id`입니다. opaque 값으로 취급하며 내부 구조·영구 안정성이나 상세 조회를 보장하지 않습니다.
- 이름은 공급자 `name`, `address_line1`, `formatted` 중 첫 비공백 값을 사용합니다. 주소 검색 결과도 표시 이름이 필요하기 때문입니다. 유효한 이름이 없으면 502입니다. 주소는 `formatted`, 없거나 null이면 `""`입니다.
- `lon`→`longitude`, `lat`→`latitude`. 공급자의 숫자 또는 숫자 문자열을 유한 JSON 숫자로 변환하고 WGS84 경도 -180..180/위도 -90..90을 검증합니다. ID·이름·좌표가 잘못된 후보를 버리고 빈 성공으로 위장하지 않고 전체 응답을 502로 처리합니다.
- 최대 10개, 원본 순서 유지, 응답 안에서 같은 ID는 중복 제거합니다. 공급자가 요청한 후보 수보다 많이 반환하면 잘못된 응답입니다. 전체 공급자 body는 최대 256 KiB입니다.
- 결과가 있으면 **비필수 응답 필드 `attributions`**를 추가합니다. Geoapify·OSM 기본 표시와 실제 `datasource.attribution/url`을 중복 제거해 전달합니다. `url`이 없으면 null이며 텍스트만 표시합니다. HTTP(S) 링크만 허용합니다. 기존 `places` 필드·요청 필수값 변경은 없습니다.
- 성공과 feature 오류에 `Cache-Control: no-store`를 설정합니다. 현재 서버는 검색어·응답을 저장하거나 캐시하지 않습니다. 공급자는 저장을 허용하지만 저장/재사용 시 표시 출처도 유지해야 합니다. Flutter가 선택 정보를 보관할 때 attribution도 함께 보존합니다. bulk 수집/재배포는 별도 라이선스 검토가 필요합니다.

## 오류와 요청 제한

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | query 누락, 정규화 후 공백/길이/제어 문자 오류 |
| 429 | `TOO_MANY_REQUESTS` | 로컬 요청 간격 제한 또는 공급자 429 |
| 502 | `PLACE_SEARCH_UNAVAILABLE` | 공급자 5xx, 연결/전송 장애 |
| 502 | `PLACE_SEARCH_BAD_RESPONSE` | 손상된 JSON/필수 필드·좌표 오류, body 초과, 200 이외 예상 밖 상태/형식 |
| 503 | `PLACE_SEARCH_NOT_CONFIGURED` | 공급자 키 미설정 |
| 503 | `PLACE_SEARCH_PROVIDER_AUTH_FAILED` | 공급자의 401/403. 앱 사용자 인증 오류로 전달하지 않음 |
| 504 | `PLACE_SEARCH_TIMEOUT` | 연결/전체 응답 제한 시간 초과 |

기존 최상위 `code/message/requestId/timestamp/errors`, `application/json`, `X-Request-Id` 규칙을 유지합니다. 로컬 429는 `Retry-After: 1`입니다. 공급자 429는 1~86,400초의 숫자 헤더만 전달하고, 없거나 안전하게 해석할 수 없는 값이면 헤더를 생략합니다. 일일 쿼터 소진의 해제 시간을 추측해 만들지 않습니다. `message`는 진단용이며 앱 표시 문구로 직접 사용하지 않습니다.

요청 제한은 **한 JVM 전체**에 적용하며 사용자별/IP별 제한 또는 일일 과금 한도가 아닙니다. 기본 4rps를 태스크 2개에서 사용하면 키/프로젝트 전체 5rps 제한을 넘을 수 있습니다. 복제 수·같은 키의 다른 사용량에 맞춰 각 태스크의 한도를 낮추거나 공유 quota 제어를 추가해야 합니다. 앱의 350ms debounce도 여러 사용자·악의적 호출을 막지는 않습니다. 분산 제한과 일일 예산 제어는 후속 작업입니다.

## Flutter에서 반영할 점

1. 기존 장소 DTO·query 요청·좌표 핀·경로 요청을 유지합니다. `attributions`를 선택적 목록으로 읽고 결과 목록과 선택 장소 상태에 보존합니다. 모르는 추가 필드를 거부하는 설정이면 이 필드를 허용합니다.
2. 검색 화면과 선택된 핀/장소 정보를 표시하는 화면에 출처 텍스트와 링크를 함께 표시합니다. Free의 Geoapify follow-link와 OSM copyright 링크, 실제 datasource 추가 출처를 보존합니다. 확인한 공식 조건에서는 별도 Geoapify 이미지 로고 강제 대신 텍스트 링크를 요구합니다. 기존 Mapbox 로고/attribution은 계속 표시합니다.
3. 검색 범위는 대한민국 전체입니다. 검색 결과를 서울숲으로 임의 필터링하지 않고 기존 경로 요청의 422 `OUTSIDE_SUPPORTED_AREA`를 유지합니다. `POST /api/v1/routes/search`에는 **기존 좌표만** 전달합니다.
4. 429, 502/503/504와 알려지지 않은 code를 안전하게 처리하고 code/requestId를 보존합니다. 취소된 요청은 앱의 기존 취소 정책을 적용합니다. 서버가 외부 호출을 이미 전송한 경우 클라이언트 취소만으로 공급자 과금이 취소되지는 않습니다.

## 검증 경계

자동 테스트는 합성 공급자 DTO/로컬 HTTP 서버와 임시 PostGIS만 사용하며 외부 API를 호출하지 않습니다. 한국어·공백·예약 문자, 입력 검증, 좌표/주소/ID 변환, 출처, 빈 결과, 인증·제한·장애·timeout, 잘못된 응답, 키 미설정과 기존 경로 회귀를 검사합니다. [검증 기록](place-search-verification.md)에 실행 결과를 남깁니다.

**현재 실제 키가 없어 실제 검색 성공 응답은 없습니다.** 키 설정 후 위 두 curl로 장소명/주소 응답의 후보·좌표·출처를 캡처하고 Mapbox 핀을 확인해야 합니다. 로컬 테스트 성공을 공급자 품질 검증이나 실제 장소 응답으로 보고하지 않습니다.
