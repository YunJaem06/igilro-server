# 이길로 서버

사용자의 환경과 상황에 맞는 보행 경로를 추천하는 서비스의 서버입니다. Phase 1은 서버 실행 기반, PostgreSQL/PostGIS, 오류 응답, 상태 확인, API 문서와 테스트를 구축합니다. 경로 탐색과 그림자 계산은 다음 단계에서 구현합니다.

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
cp .env.example .env
docker compose up --build -d --wait
./gradlew bootRun --args='--spring.profiles.active=local'
```

`.env`는 Compose 설정입니다. Spring Boot는 이 파일을 자동으로 읽지 않습니다. 기본 DB 이름·계정·포트를 그대로 쓰면 위 명령으로 실행됩니다. 변경한 경우 아래처럼 서버에도 동일한 값을 전달합니다.

```bash
DB_URL=jdbc:postgresql://localhost:55432/igilro \
DB_USERNAME=igilro DB_PASSWORD=local_custom_password \
./gradlew bootRun --args='--spring.profiles.active=local'
```

위 예시를 쓰려면 `.env`에도 `POSTGRES_PORT=55432`, `POSTGRES_PASSWORD=local_custom_password`를 설정합니다. Compose의 로컬 계정은 개발용 슈퍼유저이므로 운영에서 사용하지 않습니다. DB 포트는 `127.0.0.1`에만 공개됩니다.

| 확인 항목 | 주소 |
| --- | --- |
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

정상 상태는 HTTP 200과 `{"status":"UP","checkedAt":"...Z"}`입니다. DB 연결 또는 공간 함수 호출에 실패하면 상태 API와 readiness가 HTTP 503을 반환합니다. liveness는 DB 장애로 프로세스를 반복 재시작하지 않도록 DB를 검사하지 않습니다.

## DB와 Flyway 확인

이미지에는 PostGIS 패키지만 설치합니다. **확장 생성은 서버 시작 시 Flyway의 `V1__enable_postgis.sql`이 수행합니다.** DB 컨테이너만 처음 실행한 시점에는 확장이 활성화되지 않은 것이 정상입니다.

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

배포 환경에서는 `SPRING_PROFILES_ACTIVE=prod`를 명시합니다. 설정이 누락된 `dev`/`prod`는 시작에 실패합니다. `SERVER_PORT`, `MANAGEMENT_SERVER_PORT`로 포트를 변경할 수 있습니다. 운영 관리 포트는 내부 네트워크에서만 접근하도록 배포 환경에서 제한합니다. 서버 인증과 운영 배포 자동화는 다음 단계의 별도 작업입니다.

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_URL=jdbc:postgresql://db-host:5432/igilro \
DB_USERNAME=igilro_app DB_PASSWORD=replace_with_secret \
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar
```

마이그레이션 계정에는 확장 생성 권한이 필요합니다. 운영 DB 관리자가 PostGIS를 미리 활성화하거나 `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`로 별도 마이그레이션 계정을 전달할 수 있습니다. 실제 운영 비밀은 저장소에 기록하지 않습니다. 기존 마이그레이션을 수정하지 않고 `V2__...sql`부터 추가하며, baseline과 Flyway clean은 자동 실행하지 않습니다.

## 설계와 후속 작업

- [구조와 설계 근거](docs/architecture.md)
- [현재 API 및 향후 Flutter 계약](docs/api-contract.md)
- [초기 분석·변경 내역·검증 결과](docs/phase-1-verification.md)

다음 단계는 보행 데이터 수집 및 그래프 저장, 경로 탐색, 환경 점수와 복수 조건 가중치, 건물·태양 위치 기반 그림자 계산입니다. 현재는 관련 엔티티·테이블·빈 API를 미리 생성하지 않습니다.
