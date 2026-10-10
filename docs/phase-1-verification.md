# Phase 1 분석과 검증 결과

검증일: 2026-10-10 (Asia/Seoul). 이 문서는 Phase 1 로컬 실행 결과입니다. 원격 CI 결과는 [GitHub Actions](https://github.com/YunJaem06/igilro-server/actions)에서 확인합니다.

Flutter 프로젝트 `docs/server_handoff.md`를 반영한 최종 검증입니다. 공통 오류를 `application/json`으로 변경했고, 장소·경로의 다음 단계 제안은 [전달 문서 검토](flutter-handoff-review.md)에 정리했습니다. 원격 push는 앞선 작업에서 HTTPS/SSH 인증이 없어 실패했으며, GitHub 호스팅 CI는 아직 실행 확인하지 못했습니다.

## 기존 프로젝트 분석

- Spring Initializr 기본 애플리케이션, `application.properties`, `contextLoads()` 테스트만 있었습니다.
- Kotlin 2.3.21, Spring Boot 4.1.1, Java 21, Gradle Kotlin DSL 및 Wrapper 9.7.1을 확인하고 유지했습니다.
- 기존 Web MVC/JDBC/Validation/Actuator/Flyway/PostgreSQL 의존성은 유지했습니다. Jackson은 Boot 4의 `tools.jackson` 모듈입니다.
- DB 설정과 마이그레이션이 없어서 변경 전 `./gradlew test`는 DataSource 생성 오류로 실패했습니다.
- Phase 1 분석을 시작할 때 작업 디렉터리에 Git 저장소와 원격 이력이 없었습니다. 이후 사용자가 GitHub 저장소를 만든 뒤 로컬 `main` 브랜치를 초기화하고 `YunJaem06/igilro-server`를 origin으로 연결했습니다.
- Mac은 `arm64`, 설치된 Java는 Temurin 21.0.12.1입니다. Docker CLI는 설치되어 있었지만 Docker Desktop 데몬이 중지된 상태였습니다. Desktop을 시작한 후 검증했습니다.

## 변경 파일

기존 파일 수정:

- `build.gradle.kts`: springdoc, ArchUnit, Testcontainers 의존성, 통합 테스트 작업 및 check 연결
- `gradle/wrapper/gradle-wrapper.properties`: 공식 Gradle 배포본 SHA-256 고정
- `.gitignore`: 로컬 환경 변수 파일 및 로그 제외

기존 파일 교체에 따른 삭제:

- `src/main/resources/application.properties`: YAML 공통·프로필 설정으로 교체
- `src/test/kotlin/com/igilro/server/IgilroServerApplicationTests.kt`: DB 설정이 없는 기본 contextLoads 대신 의미 있는 단위·MVC·통합 테스트로 교체

추가 파일:

```text
.editorconfig
.env.example
compose.yaml
docker/postgis/Dockerfile
.github/workflows/ci.yml
README.md
docs/architecture.md
docs/api-contract.md
docs/phase-1-verification.md
docs/flutter-handoff-review.md
src/main/resources/application.yml
src/main/resources/application-local.yml
src/main/resources/application-dev.yml
src/main/resources/application-prod.yml
src/main/resources/db/migration/V1__enable_postgis.sql
src/main/kotlin/com/igilro/server/health/api/HealthController.kt
src/main/kotlin/com/igilro/server/health/api/HealthResponse.kt
src/main/kotlin/com/igilro/server/health/api/HealthResponseMapper.kt
src/main/kotlin/com/igilro/server/health/application/CheckHealthUseCase.kt
src/main/kotlin/com/igilro/server/health/domain/HealthRepository.kt
src/main/kotlin/com/igilro/server/health/domain/ServiceHealth.kt
src/main/kotlin/com/igilro/server/health/infrastructure/HealthConfiguration.kt
src/main/kotlin/com/igilro/server/health/infrastructure/JdbcHealthRepository.kt
src/main/kotlin/com/igilro/server/health/infrastructure/PostgisHealthIndicator.kt
src/main/kotlin/com/igilro/server/shared/api/RequestIdFilter.kt
src/main/kotlin/com/igilro/server/shared/api/error/FieldViolation.kt
src/main/kotlin/com/igilro/server/shared/api/error/ApiErrorResponse.kt
src/main/kotlin/com/igilro/server/shared/api/error/GlobalExceptionHandler.kt
src/main/kotlin/com/igilro/server/shared/infrastructure/OpenApiConfiguration.kt
src/main/kotlin/com/igilro/server/shared/infrastructure/TimeConfiguration.kt
src/test/resources/application-test.yml
src/test/kotlin/com/igilro/server/architecture/ArchitectureTest.kt
src/test/kotlin/com/igilro/server/health/api/HealthControllerTest.kt
src/test/kotlin/com/igilro/server/health/application/CheckHealthUseCaseTest.kt
src/test/kotlin/com/igilro/server/shared/api/error/GlobalExceptionHandlerTest.kt
src/test/kotlin/com/igilro/server/integration/ServerIntegrationTest.kt
```

`IgilroServerApplication.kt`, `settings.gradle.kts`, Kotlin/Boot 버전과 Java toolchain은 변경하지 않았습니다. 생성된 빌드 파일, IDE 설정, 캐시는 배포 소스에 포함하지 않습니다.

## 자동 검증

`./gradlew clean build --no-daemon --warning-mode all` 및 최종 스키마·로그 보완 후 `./gradlew build --no-daemon --warning-mode all`이 성공했습니다. 일반 테스트 23개, 통합 테스트 6개, 총 **29개 성공 / 실패 0 / 건너뜀 0**입니다.

| 테스트 | 개수 | 검증 내용 |
| --- | --- | --- |
| CheckHealthUseCaseTest | 2 | 정상·장애 상태와 주입된 시각 |
| HealthControllerTest | 2 | 200 및 503 공통 JSON 오류, UTC 응답, 요청 ID |
| GlobalExceptionHandlerTest | 14 | 본문·파라미터 검증, JSON 파싱, 404/405/406/415/429, 안전한 500, UUID 처리, JSON Accept와 로그/MDC requestId 추적 |
| ArchitectureTest | 5 | 순수 Domain, Application 의존성, API/인프라 경계, 기능 순환 의존 |
| ServerIntegrationTest | 6 | Flyway 생성·검증·재실행, 좌표·미터 단위 공간 함수, Actuator health, 공간 확장 장애, OpenAPI 필수 오류 필드/Swagger, 실제 404 계약 |

통합 테스트는 `igilro-postgis:local`로 별도의 임시 컨테이너를 만들었으며 종료 후 제거되었습니다. H2를 사용하지 않았고 Docker가 없을 때 통합 테스트를 자동 생략하는 설정도 없습니다. Docker 이미지 변경이 Gradle 입력에 추적되지 않으므로 통합 테스트의 up-to-date 판정과 결과 캐시를 비활성화했습니다. 최종 빌드에서 Gradle 스크립트의 deprecated property delegate 사용을 제거했습니다. 테스트 JVM의 CDS 경고는 테스트 실패가 아닙니다.

## Docker·DB 검증

| 항목 | 관찰 결과 |
| --- | --- |
| Docker 데몬 | 시작 후 `linux/aarch64` |
| 로컬 이미지 | `linux/arm64`, CPU 에뮬레이션 강제 설정 없음 |
| 공식 베이스 이미지 매니페스트 | `linux/arm64/v8`, `linux/amd64` 모두 확인 |
| 실제 DB | PostgreSQL 17.11, `aarch64-unknown-linux-gnu` |
| PostGIS | 3.6.4 |
| Compose | 설정 검증 성공, 컨테이너 healthy |
| Flyway | V1 `enable postgis`, success=true |
| 공간 출력 | `ST_AsGeoJSON`이 `[126.978, 37.5665]` 순서 반환 |

컨테이너 최초 실행 시에는 PostGIS 패키지만 존재하고 확장은 미설치였습니다. 서버 JAR를 실행한 뒤 V1이 확장을 활성화했습니다. 서버를 운영 프로필로 다시 시작했을 때 기존 마이그레이션을 검증하고 중복 적용하지 않는 것도 확인했습니다.

## 실제 HTTP 검증

패키징한 JAR를 로컬 프로필의 기본 8080 포트에서 실행했습니다. 리스너가 `*:8080`으로 열리는 것도 확인했습니다. Flutter emulator/simulator/실제 기기에서의 통신은 앱 연결 단계에 검증합니다.

- `/api/v1/health`: 200, UP, `checkedAt` UTC 문자열
- `/actuator/health`: 200, `status: UP`, liveness/readiness 그룹 포함
- `/actuator/health/readiness`, `/actuator/health/liveness`: 200
- `/v3/api-docs`: 200, OpenAPI 3.1.0, 상태 API와 503/500 `ApiErrorResponse` JSON 스키마 포함. `code`, `requestId`를 required로 확인
- `/swagger-ui/index.html`: 200
- `/api/v1/missing`: 404, `application/json`, `NOT_FOUND`, 최상위 requestId와 X-Request-Id 응답 헤더 일치

운영 프로필에서는 앱 18080, 관리 18081 포트로 실행하여 다음을 확인했습니다.

- 앱 포트의 health API: 200
- OpenAPI/Swagger URL: 404
- 앱 포트의 Actuator health: 404, 관리 포트의 readiness: 200
- 관리 포트의 Actuator info: 404
- 지원하지 않는 POST: 405, 응답 형식 `text/plain` 요청: 406

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 제거한 `dev`/`prod` 프로세스는 모두 종료 코드 1로 시작을 거부했습니다. 이때 DB URL 바인딩 단계에서 `'url' must start with "jdbc"` 오류가 발생합니다. 해당 오류가 보이면 활성 프로필과 DB 환경 변수 전달 여부를 확인합니다.

Compose DB를 잠시 중지했을 때 앱용 health는 HTTP 503 `SERVICE_UNAVAILABLE`과 최상위 code/requestId를 반환했고, Actuator 종합 health는 HTTP 503 `status: DOWN`, liveness는 HTTP 200 UP을 유지했습니다. DB 재시작 뒤 서버 재시작 없이 정상으로 복구됐습니다. readiness는 DB와 PostGIS를 각각 검사하므로 연결 획득 제한 시간이 합산될 수 있습니다.

설정·CI YAML 파싱과 Compose 설정 검증도 성공했습니다. 검증용 서버 프로세스는 종료했으며 개발 DB 컨테이너와 볼륨은 유지했습니다.

## 남은 작업

- 운영 이미지 게시와 digest 고정, DB 권한 분리, 비밀 저장소·네트워크·인증·TLS·배포 구성.
- 보행 그래프 데이터 수집과 스키마, 경로 탐색, 환경 점수, 길 모드·복수 조건·가중치.
- 건물 Polygon/높이 데이터와 태양 위치·평가 시각을 이용한 그림자 계산 및 길이 기반 그늘 비율.
- Flutter의 장소·경로 endpoint 및 응답 제안은 수용했습니다. 평가 시각 기본값, fastest 기준 시간, metric별 coverage, 검색 제한과 ID 유효기간은 다음 단계에서 합의합니다. [API 계약](api-contract.md)과 [검토 결과·데이터 확보 계획](flutter-handoff-review.md)을 참고합니다.
