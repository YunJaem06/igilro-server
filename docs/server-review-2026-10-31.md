# 2026-10-31 서버 변경 점검과 실행 검증

대상은 `e3833dc..a40f222`의 7개 커밋·113개 파일이다. 카카오 장소 검색, 공공 건물 수집·매칭·검수 지도, 그늘 최적화, 모델 예측 API, 보행 길안내와 Flyway V4~V6를 확인했다. 이 문서의 실행 결과는 현재 Apple Silicon Mac에서 재검증한 결과다. 기존 문서에 남은 다른 환경의 검증 기록과 구분한다.

## 발견·수정한 문제

- DB 진단 CLI 5개가 비밀번호 미설정 시 Compose와 다른 기본값을 사용했다. `validate-public-buildings.py`, `check-pilot-address-reference.py`, `compare-building-footprints.py`, `audit-shade-pilot.py`, `plan-shade-pilot.py`를 Compose 및 Spring local의 `igilro_local`과 일치시켰다. 환경 변수와 `.env`의 명시적 설정은 계속 우선한다.
- `.env` 없는 상태에서 합성 건물 도형을 `validate-public-buildings.py`에 전달해 수정 전 연결 실패를 재현했다. 수정 후 실제 로컬 PostGIS에서 1개 도형 검증과 4326↔5179 변환이 성공했다. 임시 테이블과 rollback만 사용했고 앱의 건물 자료는 변경하지 않았다.
- README의 현재 migration 범위를 V1~V6로 수정하고 다음 번호를 V7로 안내했다. 모델 API 문서에는 기존 PC의 장면 경로가 Git으로 전달되지 않는다는 설명을 추가했다.

## 자동 테스트와 빌드

| 검사 | 결과 |
| --- | --- |
| Java 21 단위·MVC·아키텍처 테스트 | 149개 통과 |
| 별도 Testcontainers PostgreSQL/PostGIS 통합 테스트 | 37개 통과 |
| JVM 합계 | 186개, 실패·오류·건너뜀 0 |
| Python 데이터 준비·공공 공급자·검수 지도 오프라인 테스트 | 63개 통과, DB 기본값 수정 후 재실행 통과 |
| Node 검수 메모 테스트 | 6개 통과 |
| Python 전체 스크립트 compileall / 검수 지도 JS 구문 검사 | 통과 |
| 실행 JAR / 전체 Gradle build | 통과 |
| PostgreSQL/PostGIS 이미지 / 서버 Docker 이미지 | ARM64 빌드 통과 |

실행 순서는 `./gradlew clean test bootJar --no-daemon --console=plain` 후 `./gradlew build --no-daemon --console=plain`이다. 두 번째 실행은 첫 실행의 단위 테스트 결과를 재사용하고 통합 테스트를 실행했다. Python 3.12 임시 가상환경에 저장소 requirements 3개를 설치했고 Node 24에서 검증했다. CI의 Python 3.12 / Node 22 설정도 확인했으며 원격 Actions나 AWS 배포를 새로 실행하지 않았다.

Docker Desktop 29.7.2, Linux ARM64, PostgreSQL 17.11, PostGIS 3.6.4를 확인했다. 공개 이미지 빌드가 macOS Docker credential helper에서 대기하여 해당 빌드만 종료하고 임시 익명 Docker 설정으로 성공했다. 사용자의 Docker 로그인 설정은 수정하지 않았다. 서버 컨테이너 사용자는 `10001:10001`이다. 이번 Mac 검증은 ARM64이며 AMD64 ECS 실행이나 실제 AWS 권한은 검증하지 않았다.

## 키 설정 전 실제 JAR의 HTTP 검증

현재 JAR를 loopback 18080에 별도로 실행했다. 기존 8080 서버 세션은 유지했다.

| 요청 | 확인 결과 |
| --- | --- |
| `GET /api/v1/health` | 200 UP |
| `GET /actuator/health/readiness`, `/liveness` | 200 UP |
| `POST /api/v1/routes/search`, 기존 서울숲 fastest 예제 | 200, 800.8m / 573초, 길안내 19단계 |
| 동일 요청 `mode=shade` | 503 SHADE_DATA_UNAVAILABLE, 이 DB에는 건물 자료 없음 |
| 지원 지역 밖 좌표 | 422 OUTSIDE_SUPPORTED_AREA |
| 잘못된 좌표 | 400 INVALID_REQUEST |
| `GET /api/v1/places/search?query=서울숲` | 503 PLACE_SEARCH_NOT_CONFIGURED |
| 장소 query 누락·공백 | 400 INVALID_REQUEST |
| 모델 coverage / search | 503 PREDICTION_DATA_UNAVAILABLE |
| 존재하지 않는 API | 404 NOT_FOUND |
| `GET /v3/api-docs` | 200, 새 경로 API와 장소 query 계약 확인 |
| `GET /swagger-ui/index.html` | 200 text/html |

JSON 응답의 Content-Type과 `X-Request-Id`를 확인했다. 오류는 `code`, `message`, `requestId`, `timestamp`, `errors`를 유지하고 헤더와 본문의 requestId가 일치했다. Swagger를 제외한 표의 14개 요청을 자동 assertion으로 확인했다.

Flyway V1~V6가 모두 성공 상태이고 검증·재기동 시 추가 migration 없이 시작했다. 로컬 DB 백업을 임시 작업 폴더에 확보했다. 기존 서울숲 OSM 파일의 체크섬·원본 시각·지역 범위가 DB와 같음을 대조한 후 같은 원본을 CLI로 재사용했다. `reused=true`, 노드 5,733개·간선 6,268개를 유지하면서 길안내 노드 태그 162개·간선 태그 6,268개를 보완했다. 8080 및 18080 서버에서 `guidance.metadataStatus=OSM_TAGS`와 19단계 안내를 확인했다. 매 요청 새 ID와 guidance를 제외한 기존 경로 응답 전체가 보완 전과 같았다.

## 컨테이너의 모델 API 검사

별도 ARM64 서버 컨테이너를 loopback 18081에서 실행하고 임시 합성 `scene.json`·`roads.osm`을 읽기 전용으로 마운트했다. 공개 공급자에는 요청하지 않았고 합성 자료를 개발 DB에 적재하지 않았다.

- coverage, 양방향 fastest/shade 검색 성공 4건, 범위 밖·50m 스냅 밖·야간 오류 6건을 확인했다.
- 저장소의 `verify-model-navigation.py`로 보행선 일치, 그늘 구간 연속성·거리·전체 비율, 동일 스냅, 추가 시간 한도와 길안내를 확인했다.
- 10건 모두 예상 상태였다. 이것은 파일 로딩부터 실제 HTTP 계산까지의 연결 검사이며 실제 서울숲 모델의 품질·성능이나 문서의 실제 예측 비율을 재현한 결과가 아니다.

검증 후 임시 JAR 서버와 컨테이너는 종료했다. 기존 8080 서버와 개발 PostgreSQL 컨테이너는 유지한다.

## 현재 필요한 키와 자료

최초 점검 시 이 작업의 OS 환경 변수와 프로젝트 루트에는 키가 없고 `.env`도 없었다. 이후 사용자가 제공한 카카오 키를 로컬 `.env`에 설정하고 아래 후속 검증을 완료했다. Git pull은 `.env`, 건물 DB 자료와 모델 장면 파일을 전달하지 않는다. 다른 PC의 설정·성공 기록이 현재 Mac에도 적용됐다고 가정하지 않는다.

| 설정 | 용도·준비 방법 |
| --- | --- |
| `KAKAO_REST_API_KEY` | 실제 장소 검색. [카카오디벨로퍼스](https://developers.kakao.com/) 앱에서 카카오맵 사용 설정 ON 후 **REST API 키**를 사용한다. JavaScript·네이티브·Admin 키나 Mapbox 공개 토큰을 사용하지 않는다. [공식 활성화 안내](https://developers.kakao.com/docs/ko/kakaomap/common), [Local REST 규격](https://developers.kakao.com/docs/ko/local/dev-guide). |
| `VWORLD_API_KEY` | 브이월드 GIS 건물 수집, 3D 모델·DEM 장면의 신규 준비에 사용하는 서버/수집용 인증키. [브이월드](https://www.vworld.kr/)의 OpenAPI 인증키를 발급한다. 사용 중인 서비스·도메인 조건을 맞춘다. 기존 준비 장면의 조회에는 키를 사용하지 않는다. |
| `VWORLD_DOMAIN` | 브이월드 키 발급 시 등록한 URL이 요구되는 경우 설정한다. 비밀 키가 아니다. |
| `BUILDING_HUB_API_KEY` | [건축HUB 건축물대장정보 서비스](https://www.data.go.kr/data/15134735/openapi.do) 활용신청 후 승인된 인증키. 수집 코드가 URL 인코딩하므로 Decoding 원문을 설정한다. 표제부의 직접 높이·식별자 수집용이며 서버 health/fastest 실행에는 필요 없다. |
| `SHADE_PREDICTION_FILE` | 비밀 키가 아닌 실제 `scene.json` 파일 경로. 같은 폴더에 체크섬이 일치하는 `roads.osm`이 필요하다. 기존 준비 PC에서 두 파일을 복사하거나 [장면 준비 절차](model-based-shade-prediction.md#데이터-준비와-실행)를 실행한다. |
| `SHADE_GROUND_VERIFIED_DATASETS` | 직접 높이 shade 최적화에 사용할 지상 보행 맥락 검증 완료 그래프 버전 목록. 비밀 키가 아니며 임의로 채우지 않는다. 완전한 건물 목록·높이 출처·상한·평가 범위 검증도 필요하다. |

서버 health와 이미 적재한 fastest 경로는 외부 키 없이 동작한다. 키가 있어도 실제 건물 적재 및 장면 준비는 별도 절차다. 이 Mac에는 기존 보행 그래프만 있으며 활성 건물 데이터셋과 실제 모델 장면은 없다. 전체 건물 수집·건축HUB 연계 및 서울숲 실데이터 모델 예측 재현은 현재 미검증이다. 자동 테스트의 공급자 응답은 합성 자료다.

## 후속 카카오 키 설정·실제 검색 검증

2026-10-31 사용자가 제공한 REST API 키를 `.env`에 설정했다. 파일 권한은 소유자만 읽고 쓰는 `0600`이며 Git 제외를 확인했다. 키는 프로세스 인자·문서·테스트·로그에 기록하지 않았다.

- 새 local JAR에서 `서울숲` 장소명 검색은 HTTP 200 / 10개, `서울특별시 성동구 뚝섬로 273` 주소 검색은 HTTP 200 / 8개를 반환했다. 이는 당시 검색 결과 수이며 고정 응답 계약이 아니다.
- 실제 응답을 메모리에서 검사하여 카카오 ID, 장소명·주소 형식, 유한 WGS84 숫자 좌표, 카카오 출처, `Cache-Control: no-store`, `X-Request-Id`를 확인했다. 공급자 실제 응답 본문은 로그나 fixture 파일로 저장하지 않았다.
- 8080 서버에서도 장소 검색 HTTP 200 / 10개, health/readiness 200 UP, 기존 경로 200 / 800.8m·573초를 최종 확인했다. 검증 중 8080 세션이 새 PID로 실행되어 중복 프로세스를 시작하지 않았다.
- 18080 임시 검증 JAR는 종료했다. 8080 서버와 개발 PostgreSQL은 유지한다.

## 후속 브이월드 키 설정·제한 조회 검증

2026-10-31 사용자가 제공한 브이월드 인증키를 기존 `.env`의 `VWORLD_API_KEY`에 저장했다. 카카오 설정을 보존하고 파일 권한 `0600`과 Git 제외를 확인했다.

- 기존 `PublicBuildingClient`로 서울숲 주변의 작은 bbox에서 건물 WFS 한 건을 조회했다. 실제 FeatureCollection과 건물 1개 반환을 확인했다.
- 기존 `SpatialClient`로 3D 인증키 교환과 DEM 타일 1개 조회를 확인했다. 4,225개 유한 표고 샘플 검증에 성공했다.
- 공급자 호출은 합계 3회였다. 현재 키는 `VWORLD_DOMAIN`이 비어 있는 상태에서 이 요청들이 성공했다. 다른 사용 환경에도 도메인이 불필요하다는 의미는 아니다.
- 원본 키·교환 키·인증 URL은 출력하지 않았으며 검증용 임시 응답 캐시는 종료 시 삭제했다. 전체 건물 수집·DB 적재·실제 모델 장면 생성은 실행하지 않았다. 이 검증 시점에는 건축HUB 키가 없었으며 이후 설정·확인은 아래 기록을 따른다.

## 후속 건축HUB 키 설정·표제부 조회 검증

2026-10-31 사용자가 제공한 건축물대장 인증키를 `.env`의 `BUILDING_HUB_API_KEY`에 저장했다. 기존 카카오·브이월드 설정과 파일 권한 `0600`을 유지하고 Git 제외를 확인했다.

- 기존 `PublicBuildingClient`로 서울숲 지역의 건축HUB `getBrTitleInfo`를 `numOfRows=1`, `pageNo=1`로 한 번 호출했다.
- 실제 공급자 성공 코드 `00`과 표제부 1건을 확인했다. 건축물대장 식별자 `mgmBldrgstPk`와 높이 `heit` 필드의 존재도 확인했다. 필드 존재가 지역 전체 높이 완전성·정확성을 검증했다는 의미는 아니다.
- 인증키·인증 URL·원본 응답은 출력하지 않았고 임시 응답 캐시는 종료 시 삭제했다. DB 적재나 전체 수집은 실행하지 않았다.
- 현재 로컬의 세 공급자 키 설정과 제한적인 실제 연동 확인은 완료했다. 실제 서울숲 그늘 예측을 재현하려면 전체 데이터 준비·검증·적재 및 모델 장면 준비가 남아 있다.

## 실행·확인 명령

```bash
cp -n .env.example .env
# .env에 필요한 키·자료 경로를 직접 설정한다. 키는 채팅·Git에 넣지 않는다.
docker compose up -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

local 프로필은 프로젝트 루트 `.env`를 자동으로 읽으며 OS 환경 변수가 우선한다. 따옴표·`export` 없이 `KEY=value`로 기록하고 서버를 재시작한다. `dev`/`prod`에는 배포 환경으로 별도 주입한다. 현재 8080 세션이 실행 중이면 동일 포트로 중복 실행하지 않는다.

```bash
curl -i http://localhost:8080/api/v1/health
curl -i http://localhost:8080/actuator/health/readiness
curl -i http://localhost:8080/api/v1/routes/search \
  -H 'Content-Type: application/json' \
  --data-binary @docs/examples/route-search.json
curl --get --include http://localhost:8080/api/v1/places/search \
  -H 'Accept: application/json' --data-urlencode 'query=서울숲'
```

API는 `http://localhost:8080`, DB는 `127.0.0.1:5432`, OpenAPI는 `/v3/api-docs`, Swagger는 `/swagger-ui/index.html`이다. 수정한 스크립트·문서는 이번 점검의 미커밋 변경사항으로 남긴다.
