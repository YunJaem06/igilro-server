# 서버 컨테이너

루트 `Dockerfile`은 Java 21 JDK로 `bootJar`를 빌드한 뒤 JRE 이미지에 실행 JAR만 복사합니다. Docker 호스트에 Java나 Gradle을 설치하지 않아도 빌드할 수 있습니다.

```powershell
docker build -t igilro-server:local .
```

## 이미지 특성

- Temurin 21 JDK/JRE의 다중 아키텍처 manifest digest를 고정했습니다. 기본 이미지를 갱신할 때 새 digest와 Java 버전을 검증합니다.
- 실행 사용자는 UID/GID `10001:10001`입니다. 서버는 stdout/stderr로 로그를 출력합니다.
- `SPRING_PROFILES_ACTIVE=prod`, `TZ=UTC`가 기본값이며 `JAVA_TOOL_OPTIONS`로 JVM 옵션을 조정할 수 있습니다.
- 이미지 메타데이터에는 앱 포트 8080만 노출합니다. `EXPOSE`는 실제 포트 공개 명령이 아니므로 배포 시 별도로 매핑합니다.
- 운영 Actuator는 8081에서 동작합니다. ALB health check는 앱 포트의 `/api/v1/health`를 사용하고 8081은 외부에 공개하지 않습니다.
- `.dockerignore`는 wrapper, 빌드 정의, `src/main`만 허용합니다. 로컬 DB 자료, `.env`, Git/IDE 설정과 테스트 fixture는 빌드 컨텍스트에 포함하지 않습니다.
- 이미지 빌드는 테스트와 분리한 패키징 단계입니다. CI는 기존 전체 테스트를 통과한 뒤 이미지를 빌드합니다.

## 로컬 PostGIS에 연결

기본 `compose.yaml` 설정을 사용하는 PowerShell 예시입니다. `.env`에서 DB 이름·계정·비밀번호를 바꿨다면 서버에도 같은 값을 전달합니다. 아래 계정은 로컬 검증 전용입니다.

```powershell
docker compose up --build -d --wait
docker run --rm --name igilro-server-local `
  --network igilro_default `
  --publish 127.0.0.1:8080:8080 `
  --env SPRING_PROFILES_ACTIVE=prod `
  --env DB_URL=jdbc:postgresql://postgres:5432/igilro `
  --env DB_USERNAME=igilro `
  --env DB_PASSWORD=igilro_local `
  igilro-server:local
```

Compose의 프로젝트 이름은 `igilro`이므로 기본 네트워크는 `igilro_default`입니다. 프로젝트 이름을 명시적으로 바꾼 경우 네트워크 이름도 변경합니다. 같은 네트워크의 DB DNS 이름은 서비스 이름 `postgres`이며, 호스트의 `localhost`를 DB 주소로 사용하지 않습니다.

다른 터미널에서 확인합니다.

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/health
docker exec igilro-server-local id
```

DB와 공간 함수가 정상일 때 health는 HTTP 200과 `status: UP`입니다. Flyway가 최초 시작 시 마이그레이션을 적용합니다. 실제 OSM 그래프를 적재하기 전에는 경로 검색이 HTTP 503 `ROUTING_DATA_UNAVAILABLE`을 반환합니다.

이미지의 prod 기본값으로 OpenAPI와 Swagger는 비활성화됩니다. 필요할 때 로컬에서만 `SPRING_PROFILES_ACTIVE=local`로 바꿀 수 있습니다. DB 설정은 동일하게 전달합니다.

실행 중인 서버는 `docker stop igilro-server-local`로 종료합니다. Spring Boot는 정상 종료 신호를 직접 받으며 graceful shutdown을 수행합니다. 운영 ECS에서는 DB 비밀값, 마이그레이션 계정, 메모리, 종료 유예 시간과 RDS TLS 검증을 함께 설정해야 합니다.

## 인프라와의 연결

[igilro-infra](https://github.com/YunJaem06/igilro-infra)의 개발 ECR 저장소는 태그 덮어쓰기를 허용하지 않습니다. 후속 배포 workflow에서는 커밋 SHA 태그를 사용하고 ECS task에는 이미지 digest를 전달합니다. 현재 CI는 이미지의 로컬 빌드까지만 수행하며 ECR push나 ECS 배포를 실행하지 않습니다.
