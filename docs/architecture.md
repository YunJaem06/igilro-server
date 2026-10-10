# 구조와 설계 근거

## Phase 1의 범위

Phase 1 완료 시점의 기능은 서비스 상태 확인이었습니다. 이를 실제 기능 단위의 예시로 사용하여 경로·환경 기능에 같은 계층 규칙을 적용합니다. 이후 Phase 2에서 실제 보행 경로 검색을 추가했습니다. Flutter가 Mapbox로 지도를 표시하고 서버가 데이터와 계산을 담당한다는 책임 분담을 유지합니다. 건물 데이터와 그림자 계산은 다음 단계입니다.

## Feature-first 구조

```text
com.igilro.server
├── IgilroServerApplication.kt
├── health
│   ├── api              # Controller, 외부 응답 DTO, Mapper
│   ├── application      # CheckHealthUseCase
│   ├── domain           # ServiceHealth, HealthStatus, HealthRepository 계약
│   └── infrastructure   # JDBC 구현, Spring 조립, Actuator 어댑터
└── shared
    ├── api              # 공통 오류 처리, 요청 식별자
    └── infrastructure   # Clock, OpenAPI 설정
```

요청은 `HealthController → CheckHealthUseCase → HealthRepository`로 전달됩니다. Spring 설정이 `JdbcHealthRepository`를 계약에 연결합니다. Domain의 `ServiceHealth`는 HTTP 응답 DTO와 분리하며, `HealthResponseMapper`가 경계에서 변환합니다. 상태 값은 Domain의 enum으로 판단합니다.

`HealthRepository`는 공간 DB의 사용 가능 여부를 읽는 계약입니다. JDBC SQL, 연결 예외와 Spring의 세부 사항은 구현에만 있습니다. 예상되는 연결·공간 함수 오류는 `false`로 변환되어 HTTP 503을 만들고, 예상하지 못한 프로그램 오류는 공통 500 응답으로 처리됩니다. UseCase는 상태를 확인하고 확인 시각을 정하는 하나의 동작을 수행합니다.

Domain과 Application은 Spring, JDBC, Jakarta Validation, Jackson과 Swagger에 의존하지 않습니다. Kotlin 및 JDK의 `Instant`/`Clock`만 사용합니다. `Clock`을 주입해 시간에 의존하는 테스트를 재현할 수 있게 했습니다. Spring Bean 조립은 infrastructure에 둡니다.

새 기능은 `route/api`, `route/application`, `route/domain`, `route/infrastructure`처럼 해당 기능 아래 추가합니다. 실제 영속화 요구가 생기면 그 기능의 domain에 Repository 계약을 만들고 infrastructure에 구현합니다. UseCase 전용 외부 조회·계산 계약은 application에 둘 수 있습니다. 기술만 공유하는 이유로 기능 모델을 shared로 이동하지 않습니다.

## 의존성 규칙

- Domain은 Kotlin/JDK와 Domain 타입만 참조합니다.
- Application은 Application과 Domain만 참조합니다.
- API는 Application을 호출하고 DTO/Mapper를 담당하며 infrastructure·JDBC를 참조하지 않습니다.
- Infrastructure는 내부 계약을 구현하고 Bean을 조립하며 API를 참조하지 않습니다.
- 기능 패키지 사이 순환 의존을 허용하지 않습니다.

ArchUnit 테스트가 이 규칙을 빌드에서 검사합니다. 현재 패키지 경계로 시작하고, 팀 규모나 빌드 격리 필요가 생기면 Gradle 모듈 경계로 강화할 수 있습니다. 요구가 없는 추상 BaseUseCase, BaseRepository, 공통 Entity, 빈 기능 패키지는 만들지 않았습니다.

## PostgreSQL/PostGIS와 마이그레이션

[공식 PostGIS Docker 이미지](https://github.com/postgis/docker-postgis)는 확인 시 AMD64만 지원했습니다. Apple Silicon에서 CPU 에뮬레이션을 강제하지 않도록 [공식 PostgreSQL 이미지](https://github.com/docker-library/official-images/blob/master/library/postgres)의 멀티 아키텍처 인덱스를 고정하고, [PGDG APT](https://wiki.postgresql.org/wiki/Apt)의 PostGIS 패키지를 설치합니다. 같은 Dockerfile을 로컬 ARM64와 CI AMD64에서 각각 네이티브로 빌드합니다. `platform: linux/amd64`는 설정하지 않습니다.

베이스 이미지는 PostgreSQL 17.11의 digest로 고정했습니다. PostGIS와 그 의존 패키지는 빌드 시 APT가 제공하는 버전이며, 검증 시 PostGIS는 3.6.4였습니다. APT 저장소 스냅샷을 고정하지 않았으므로 이후 재빌드에서 패키지 패치 버전은 바뀔 수 있습니다. 운영 배포 전에는 빌드 결과를 레지스트리에 저장하고 결과 이미지 digest를 고정하는 절차가 필요합니다.

Docker init SQL과 Flyway가 같은 변경을 중복 관리하지 않도록 DB 확장 생성은 Flyway에만 둡니다. V1은 PostGIS를 활성화하고 SRID 공간 함수를 호출해 실제 확장이 동작하는지 확인합니다. `IF NOT EXISTS`는 관리형 DB에서 미리 활성화한 확장도 허용합니다. 보행 노드·간선과 건물 스키마는 데이터 모델이 결정되는 다음 단계에서 추가합니다.

## 운영 기반

상태 API와 Actuator의 `postgis` 지표는 같은 Repository 계약을 사용합니다. readiness는 `readinessState`, `db`, `postgis`를 검사하고 liveness는 프로세스 상태를 검사합니다. 상태 상세, DB 버전·접속 정보는 외부 응답에 포함하지 않습니다.

공통 오류는 Flutter 전달 계약에 맞춘 `ApiErrorResponse`와 `application/json`입니다. 최상위 `code`, `message`, `requestId`와 진단용 `timestamp`, `errors`를 제공합니다. MVC의 입력 검증, JSON 파싱, 404, 405, 406, 415, HTTP 429/503과 예상하지 못한 예외를 처리합니다. 앱용 health 장애도 공통 오류를 사용하고 Actuator는 표준 응답을 유지합니다. 요청마다 UUID `X-Request-Id`를 응답하고 MDC 로그와 오류 본문에 연결합니다. 클라이언트가 보낸 유효한 UUID는 유지하고 다른 값은 새 UUID로 교체합니다. 잘못된 입력값·쿼리 문자열·스택 트레이스를 오류 본문에 포함하지 않습니다.

OpenAPI의 오류 스키마는 API DTO의 annotation에서 생성하여 실제 응답과 별도 수동 스키마 사이의 불일치를 줄입니다. Infrastructure의 OpenAPI 설정은 API DTO를 직접 참조하지 않고 문서 정보만 설정합니다. 경로 업무 오류는 해당 Domain에 실제 역할이 생기는 다음 단계에 정의하고 API 계층에서 HTTP로 변환합니다.

OpenAPI는 [Spring Boot 4 지원을 명시한 springdoc 3.x](https://springdoc.org/)를 사용합니다. local/dev에서 문서를 제공하고 prod에서는 비활성화합니다. prod의 Actuator는 별도 관리 포트에서 health만 공개합니다. 인증·인가, TLS, 배포 네트워크와 비밀 저장소는 아직 구성하지 않았으며 운영 배포 단계에서 결정합니다.


## Phase 2 경로 기능

route feature에 검색·적재 UseCase, 순수 Kotlin의 좌표·그래프·Dijkstra·후보 snapping, API DTO/Mapper/업무 오류 변환, OSM/JDBC adapter를 추가했습니다. Domain은 WKT나 HTTP, XML과 DB 형식을 알지 않습니다. 활성 snapshot metadata를 읽고 불변 그래프를 버전별로 캐시하며, 새 적재는 트랜잭션 안에서 활성 포인터를 교체합니다.

공통 오류 responder를 공유하여 feature별 오류도 code/requestId/JSON/로그 계약을 유지합니다. 상세 설계는 [Phase 2 문서](phase-2-routing.md)를 참고합니다.
