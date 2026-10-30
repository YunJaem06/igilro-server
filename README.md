# 이길로 서버

사용자의 환경과 상황에 맞는 보행 경로를 추천하는 서비스의 서버입니다. 실제 OSM fastest 경로, 건물 그늘 평가, 검증된 건물 자료에서 작동하는 shade 경로 최적화와 카카오 Local 기반 한국 장소명·주소 검색을 구현했습니다. **별도의 공공 3D 모델 기반 예측 API**는 서울숲 주요 산책로·출입 지점의 경로와 지도에 표시할 그늘/햇빛 구간을 제공합니다. 직접 높이 기반 그늘 수치는 별도로 검증합니다. [예측 API·범위·구간 계약](docs/model-based-shade-prediction.md), [공공 원본 대조](docs/public-spatial-pilot-2026-10-27.md)를 참고합니다.

일반/모델 경로에 [보행 길안내 단계](docs/walking-guidance.md)를 추가했습니다. 실제 보행 분기와 원본 횡단 태그를 사용하고, 기존 경로선·거리·시간·그늘 응답은 유지합니다.

## 기술 구성

| 항목 | 버전 / 구성 |
| --- | --- |
| Kotlin / Java | 2.3.21 / 21 |
| Spring Boot / Gradle Wrapper | 4.1.1 / 9.7.1 |
| 서버 | Spring Web MVC, JDBC, Validation, Actuator |
| DB | PostgreSQL 17.11, PostGIS (검증 시 3.6.4) |
| DB 변경 | Flyway + PostgreSQL 전용 모듈 |
| API 문서 | springdoc-openapi 3.1.1 |
| 테스트 | JUnit, MockMvc, ArchUnit, Testcontainers |

## 로컬 실행 (Apple Silicon 포함)

Java 21과 Docker Desktop이 필요합니다. Docker Desktop을 실행한 후 프로젝트 루트에서 실행합니다.

```bash
docker version
docker compose version
cp -n .env.example .env
docker compose up --build -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` 프로필과 Compose는 프로젝트 루트의 `.env`를 읽습니다. IntelliJ의 Working directory도 프로젝트 루트로 지정합니다. `.env`는 Git에서 제외되고 `.env.example`에는 빈 키와 개발용 기본값만 보관합니다. 파일이 없으면 기존 기본값으로 시작하며, OS 환경 변수가 있으면 파일보다 우선합니다. `dev`/`prod`는 `.env`를 자동으로 읽지 않습니다.

발급받은 키는 `.env`의 아래 항목에 따옴표 없이 넣고 서버를 다시 시작합니다. IntelliJ 환경 변수에 별도로 복사할 필요는 없습니다.

```dotenv
VWORLD_API_KEY=발급받은_브이월드_키
VWORLD_DOMAIN=키_발급시_등록한_URL
BUILDING_HUB_API_KEY=발급받은_건축HUB_키
```

건물 공급자 수집은 Python 도구의 명시적인 실행으로 진행하며 키를 저장하는 것만으로 시작되지 않습니다. 브이월드 도메인은 발급 시 등록한 값이 필요한 경우에 설정합니다. `SHADE_GROUND_VERIFIED_DATASETS`는 지상 보행 맥락을 검증한 그래프 버전 목록이며 현재는 비워 둡니다. [검증 조건과 그늘 경로 설정](docs/phase-4-shade-routing.md)을 참고합니다.

기본 DB 이름·계정·포트는 그대로 쓰면 됩니다. DB를 변경한 경우 `.env`의 Compose 설정과 함께 서버의 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`도 맞춥니다. 이 세 값도 `.env`에 보관할 수 있습니다. 또는 아래처럼 실행 환경으로 전달합니다.

```bash
DB_URL=jdbc:postgresql://localhost:55432/igilro \
DB_USERNAME=igilro DB_PASSWORD=local_custom_password \
./gradlew bootRun --args='--spring.profiles.active=local'
```

위 예시를 쓰려면 `.env`에도 `POSTGRES_PORT=55432`, `POSTGRES_PASSWORD=local_custom_password`를 설정합니다. Compose의 로컬 계정은 개발용 슈퍼유저이므로 운영에서 사용하지 않습니다. DB 포트는 `127.0.0.1`에만 공개됩니다.

| 확인 항목 | 주소 |
| --- | --- |
| 실제 도보 경로 검색 | `POST http://localhost:8080/api/v1/routes/search` |
| 실험적 3D 모델 기반 경로 예측 | `POST http://localhost:8080/api/v1/routes/predictions/search` |
| 예측 검색 범위·연결된 보행망 | `GET http://localhost:8080/api/v1/routes/predictions/coverage` |
| 한국 장소명·주소 검색 | `GET http://localhost:8080/api/v1/places/search?query=서울숲` |
| 서비스 및 공간 DB 상태 | `GET http://localhost:8080/api/v1/health` |
| 종합 상태 | `GET http://localhost:8080/actuator/health` |
| 프로세스 생존 | `GET http://localhost:8080/actuator/health/liveness` |
| 요청 수용 가능 여부 | `GET http://localhost:8080/actuator/health/readiness` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |

```bash
curl -i http://localhost:8080/api/v1/health
curl -i http://localhost:8080/actuator/health/readiness
```

정상 상태는 HTTP 200과 `{"status":"UP","checkedAt":"...Z"}`입니다. DB 연결 또는 공간 함수 호출에 실패하면 앱용 상태 API는 HTTP 503과 공통 JSON 오류 `SERVICE_UNAVAILABLE`을 반환합니다. Actuator health/readiness는 HTTP 503과 표준 `{"status":"DOWN"}`을 유지합니다. liveness는 DB 장애로 프로세스를 반복 재시작하지 않도록 DB를 검사하지 않습니다.

## 장소명·주소 검색

`GET /api/v1/places/search`는 `query` 하나를 받습니다. 카카오 Local 키워드 검색을 먼저 호출하고 정상 빈 결과일 때 주소 검색으로 확인합니다. 최대 10개를 반환하며 모두 빈 결과면 `200 {"places":[]}`입니다. 서울숲 경로 지원 범위와 장소 검색 범위는 별개입니다.

[카카오디벨로퍼스](https://developers.kakao.com/) 앱에서 카카오맵 API를 활성화하고 서버 `.env`에 `KAKAO_REST_API_KEY`를 설정한 뒤 local 프로필로 서버를 재시작합니다. 키가 없으면 장소 요청만 `503 PLACE_SEARCH_NOT_CONFIGURED`를 반환합니다. 기존 `GEOAPIFY_API_KEY`는 더 이상 사용하지 않습니다.

```dotenv
KAKAO_REST_API_KEY=발급받은_실제_REST_API_키
```

검색·선택 결과는 현재 실행 중 메모리 상태로만 사용하며 DB·파일·캐시에 저장하지 않습니다. 결과의 출처는 카카오맵 링크로 표시합니다. Mapbox 지도·자체 경로 계산은 유지하며 경로 요청에는 기존 좌표만 전달합니다.

기본 `PLACES_CONNECT_TIMEOUT=2s`, `PLACES_RESPONSE_TIMEOUT=3s`, `PLACES_REQUESTS_PER_SECOND=4`를 유지합니다. 키워드·주소 호출은 하나의 응답 제한 시간을 공유하며, 한 사용자 검색당 공급자 호출은 최대 두 번입니다.

[카카오 전환 계약·Mapbox 병용 근거·키 설정·검증 범위](docs/kakao-place-search.md)를 참고합니다. 2026-10-29 실제 키를 등록해 장소명·주소 검색 HTTP 200과 휴대폰 LAN 검색 통신을 확인했습니다. 다양한 검색어의 품질·핀 위치 평가는 별도입니다. [이전 Geoapify 검증 기록](docs/place-search-verification.md)은 당시 기록입니다.

## 서버 컨테이너와 AWS 인프라

프로젝트 루트의 `Dockerfile`로 Java 21 서버 이미지를 빌드합니다. 빌드 단계의 JDK와 실행 단계의 JRE를 분리하고 non-root 사용자로 실행합니다. 기본 프로필은 `prod`이며 DB 설정을 외부에서 전달해야 합니다.

```bash
docker build -t igilro-server:local .
```

[컨테이너 실행과 검증](docs/container.md)에 로컬 PostGIS 연결 방법을 정리했습니다. AWS 네트워크·ECR·RDS·ECS Fargate·ALB는 별도 [igilro-infra](https://github.com/YunJaem06/igilro-infra)에서 관리합니다. prod 앱의 Flyway는 기본적으로 꺼져 있으며 동일 배포 아티팩트의 일회성 migration을 먼저 실행합니다. [배포 워크플로](.github/workflows/deploy.yml)는 OIDC 인증으로 이미지 발행 → 선택적 DB 초기화 → Flyway → 서비스 갱신 → health 검증을 수행합니다. [최초 적용과 GitHub 변수 설정](https://github.com/YunJaem06/igilro-infra/blob/main/docs/ecs-deployment.md)을 먼저 완료한 뒤 main에서 수동 실행합니다.

## Flutter 연결 주소

`API_BASE_URL`에는 `/api/v1` 없이 서버 origin만 지정합니다.

| 실행 환경 | API_BASE_URL |
| --- | --- |
| Mac / iOS Simulator | `http://localhost:8080` |
| Android Emulator | `http://10.0.2.2:8080` |
| 실제 기기 | 같은 네트워크의 `http://<Mac LAN IP>:8080` |

로컬 서버는 기본적으로 모든 인터페이스에서 API 포트를 수신합니다. 실제 기기 연결 시 Mac의 LAN IP는 `ipconfig getifaddr en0` 등으로 확인합니다. 기기 통신과 앱의 로컬 HTTP 설정 검증은 Flutter API 연결 단계에서 수행합니다. native 앱 연결에 웹 CORS 설정은 필요하지 않으며 웹 지원은 별도 작업입니다.

Flutter `APP_ENV=development`는 서버 `local`, staging은 `dev`, production은 `prod`로 대응합니다. staging/production 앱에는 HTTPS origin이 필요하므로 해당 배포의 TLS reverse proxy 주소를 제공합니다. 서버 프로필만 바꾸는 것으로 HTTPS가 자동 활성화되지는 않습니다.

오류도 `application/json`이며 최상위 `code`/`requestId`를 사용합니다. [최종 오류 형식과 코드 표](docs/api-contract.md), [장소·경로 계약 검토 및 데이터 확보 계획](docs/flutter-handoff-review.md)을 참고합니다.

## 실제 경로 검색 (Phase 2)

2026-10-10 Mac 검증 환경의 개발 DB에는 서울숲 주변 실제 보행 그래프를 적재했습니다. 데이터 파일과 DB 볼륨은 Git에 포함되지 않으므로 새 환경·새 DB는 [데이터 준비·적재 절차](docs/phase-2-routing.md)를 먼저 실행합니다. 데이터가 없으면 경로 API는 HTTP 503 `ROUTING_DATA_UNAVAILABLE`입니다.

Flutter가 지도 선택·현재 위치로 준비한 출발지/도착지 좌표를 바로 전달할 수 있습니다. 장소 검색 구현은 fastest 경로 API 연결의 선행 조건이 아닙니다. [최신 Flutter 전달 내용과 연동 사항](docs/flutter-handoff-review.md)을 참고합니다.

```bash
curl -i http://localhost:8080/api/v1/routes/search \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  --data-binary @docs/examples/route-search.json
```

검증 스냅샷의 위 요청은 약 800.8m·예상 573초의 실제 그래프 경로를 반환했습니다. 입력 위치와 경로 끝점 사이의 차이는 `snapping`으로 제공합니다. 건물 자료와 출발 시각으로 전체 경로를 평가할 수 있을 때 shadeRatio를 계산하며 미평가 지표는 null입니다. 범위·시간 모델·후보 snapping·데이터 한계는 [Phase 2 실행/설계](docs/phase-2-routing.md), 측정 결과는 [검증 기록](docs/phase-2-verification.md), 건물 적재와 평가 상태는 [Phase 3 그늘 평가](docs/phase-3-shade.md)를 참고합니다.

## DB와 Flyway 확인

이미지에는 PostGIS 패키지만 설치합니다. local/dev는 서버 시작 시 Flyway V1이 확장을 만듭니다. prod RDS는 관리자가 PostGIS를 사전 설치하고 migrator 계정의 일회성 Flyway가 V1~V3를 적용합니다. DB 컨테이너만 처음 실행한 시점에는 확장이 활성화되지 않은 것이 정상입니다.

서버 실행 후 아래 명령으로 확인합니다. `.env`에서 DB 이름·사용자를 바꾼 경우 SQL 명령의 인자도 변경합니다.

```bash
docker compose exec -T postgres psql -U igilro -d igilro \
  -c "SELECT PostGIS_Full_Version();" \
  -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;" \
  -c "SELECT ST_AsGeoJSON(ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326));"
```

DB 중지·재개와 종료:

```bash
docker compose stop
docker compose up -d --wait
docker compose down
```

`down`은 이름 있는 볼륨을 유지합니다. 로컬 데이터를 모두 버릴 때만 `docker compose down -v`를 사용합니다. 초기화 후에는 서버를 다시 실행해 Flyway를 적용합니다. 이미 초기화된 볼륨에는 `POSTGRES_PASSWORD` 변경이 자동 반영되지 않습니다.

## 테스트와 빌드

```bash
# Docker 없이: UseCase, MVC 오류 응답, 계층 의존성 규칙
./gradlew test

# Docker 필요: 테스트 전용 DB 컨테이너가 자동 생성·정리됨
docker compose build postgres
./gradlew integrationTest

# CI와 동일한 전체 검증 및 실행 가능한 JAR 생성
docker compose build postgres
./gradlew clean build
```

`check`와 `build`는 통합 테스트를 반드시 포함합니다. Docker가 없으면 실패하며, 자동으로 건너뛰지 않습니다. 통합 테스트는 매번 실행하며 Gradle 캐시를 재사용하지 않습니다. Compose의 개발 DB를 사용하지 않고 임시 DB와 임의 포트를 사용합니다. `POSTGIS_TEST_IMAGE` 환경 변수로 호환되는 테스트 이미지를 지정할 수 있습니다.

테스트 보고서는 `build/reports/tests/test/index.html`, `build/reports/tests/integrationTest/index.html`에 생성됩니다. 실행 파일은 `build/libs/igilro-server-0.0.1-SNAPSHOT.jar`입니다.

```bash
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

GitHub Actions는 Java 21 환경에서 같은 Dockerfile을 빌드하고 `clean build`를 실행하며 테스트 보고서를 보관합니다. 저장소는 [YunJaem06/igilro-server](https://github.com/YunJaem06/igilro-server)이며 기본 브랜치는 `main`입니다. Push와 Pull Request에서 CI가 실행되고, 결과는 [GitHub Actions](https://github.com/YunJaem06/igilro-server/actions)에서 확인합니다.

## 커밋 규칙

커밋 메시지는 `type(scope): 변경 내용` 형식으로 작성하며 scope는 필요한 경우에만 사용합니다. 하나의 커밋에는 한 가지 목적의 변경을 담습니다.

| type | 사용 기준 |
| --- | --- |
| `feat` | 새로운 기능 추가 |
| `fix` | 기존 기능의 오류 수정 |
| `build` | 빌드 도구, 의존성, 개발 환경 구성 |
| `test` | 테스트 추가·수정 |
| `ci` | CI 워크플로 변경 |
| `docs` | 문서 변경 |
| `refactor` | 동작을 유지하는 내부 구조 개선 |

## 환경별 설정

| 프로필 | DB 설정 | OpenAPI / Swagger | Actuator |
| --- | --- | --- | --- |
| `local` (기본) | 로컬 기본값, `DB_*`로 변경 가능 | 활성화 | 앱 포트, health/info |
| `dev` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 필수 | 활성화 | 앱 포트, health/info |
| `prod` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 필수 | 비활성화 | 관리 포트 8081, health만 |
| `test` | Testcontainers가 동적 주입 | 활성화 | 테스트 컨텍스트 |

배포 환경에서는 `SPRING_PROFILES_ACTIVE=prod`를 명시합니다. DB 설정이 누락된 `dev`/`prod`는 시작에 실패합니다. `SERVER_PORT`, `MANAGEMENT_SERVER_PORT`로 포트를 변경할 수 있습니다. 운영 관리 포트는 내부 네트워크에서만 접근하도록 배포 환경에서 제한합니다. 서버 인증·인가는 후속 작업이며 AWS 수동 배포 워크플로는 인프라 설정 후 사용합니다.

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_URL=jdbc:postgresql://db-host:5432/igilro \
DB_USERNAME=igilro_app DB_PASSWORD=replace_with_secret \
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar
```

prod의 자동 Flyway는 비활성화입니다. 운영 DB 관리자가 PostGIS를 사전 설치한 뒤 migration 작업에 `SPRING_FLYWAY_ENABLED=true`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`과 별도 migrator DB 계정을 전달합니다. 실제 운영 비밀은 Secrets Manager에서 주입합니다. [인프라 초기화 절차](https://github.com/YunJaem06/igilro-infra/blob/main/docs/database-initialization.md)를 참고합니다. 기존 migration은 수정하지 않고 V4부터 추가하며 baseline/clean을 자동 실행하지 않습니다.

## 설계와 후속 작업

- [구조와 설계 근거](docs/architecture.md)
- [현재 API 및 향후 Flutter 계약](docs/api-contract.md)
- [Flutter 전달 문서 반영·계약 검토](docs/flutter-handoff-review.md)
- [실제 보행 경로 실행·설계](docs/phase-2-routing.md)
- [Phase 2 데이터·성능 검증](docs/phase-2-verification.md)
- [Phase 3 건물 자료·그늘 평가](docs/phase-3-shade.md)
- [서울숲 공공 건물 수집·적재 및 실제 그림자 진단](docs/public-building-verification-2026-10-26.md)
- [누락 높이·PK 매칭 및 OSM 도형 교차 검증](docs/building-matching-verification-2026-10-26.md)
- [실제 건물 검수 지도 생성·실행](docs/building-review-map.md)
- [작은 평가 구역 후보·50m 실제 경로 검증](docs/shade-pilot-area-2026-10-26.md)
- [Phase 4 그늘 경로 최적화·검증](docs/phase-4-shade-routing.md)
- [서울숲 50m 현장 확인 지정·공공 원본 재검증](docs/pilot-field-review-2026-10-27.md)
- [초기 분석·변경 내역·검증 결과](docs/phase-1-verification.md)

다음 단계는 실제 건물 목록·직접 높이·주변 상한과 지상 보행 맥락을 검증해 shade 평가 구역을 확보하는 작업입니다. 장소 공급자 키/국내 품질과 Flutter 출처 표시, 보행 데이터 품질, 기타 환경 점수·복수 조건 가중치 검증도 남아 있습니다.
