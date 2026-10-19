# Flutter 서버 전달 내용 반영 현황

참고 문서: Flutter 프로젝트의 `docs/server_handoff.md`와 2026-10-19에 전달받은 최신 Flutter 상태. 서버 Phase 1 기반과 Phase 2의 실제 fastest 경로 검색, Phase 3의 기존 경로 그늘 평가가 구현되어 있습니다. 이 문서는 최신 전달 내용에 대한 구현 현황과 앱 연동 사항을 정리합니다. 장소 검색은 아직 제안입니다.

## Flutter 현재 상태와 바로 연결할 흐름

- Flutter/Dart, Riverpod, go_router, Dio, Freezed/json_serializable을 사용합니다.
- Mapbox 지도와 현재 위치, 출발지/도착지 지도 선택, 핀 표시·교환·삭제가 구현되어 있습니다. 아직 서버 API는 호출하지 않습니다.
- 선택값은 WGS84 좌표이며 장소명·주소·공급자 ID는 없습니다. 현재 경로 API는 좌표만 받으므로 이 값들을 추가하지 않아도 호출할 수 있습니다. 장소 검색은 경로 API 연결의 선행 조건이 아닙니다.
- `API_BASE_URL`은 `/api/v1`이 없는 서버 origin입니다. development/staging/production은 서버 local/dev/prod에 대응하며 개발 외에는 HTTPS origin을 사용합니다.
- 요청/응답은 `application/json`이며 연결·송신 10초, 수신 30초를 기준으로 합니다. 서버의 로컬 측정 기록은 아래 경로 계약과 [Phase 2 검증 기록](phase-2-verification.md)을 참고합니다.

다음 연결 순서는 **Java 21·Docker 준비 → 실제 그래프 적재 → health 확인 → Flutter의 지도 선택 좌표로 fastest 요청 → 경로·오류 표시 검증**입니다. 앱이 서버 응답의 `snapping`을 이용해 실제 경로 끝점과 입력 핀 사이의 차이를 표시하고 OSM attribution을 표시해야 합니다. Phase 3에서 그늘 평가를 추가했으며 실제 건물 자료 준비와 장소 검색 공급자 선정은 후속 작업입니다.

## Flutter 아키텍처에 맞춘 경로 API 연결

2026-10-19에 추가로 전달받은 Flutter 아키텍처에 따르면 좌표 선택 Domain/Notifier와 지도 핀은 구현되어 있고, 서버 검색용 UseCase·Repository·DataSource와 응답 DTO/Mapper는 후속 작업입니다. 좌표 입력 준비와 서버 검색 구현의 완료 여부를 구분합니다. 아래 내용은 Flutter의 다음 구현 범위이며, 이 서버 저장소에서 Flutter 코드를 변경한 것은 아닙니다.

- 검색은 View → AsyncNotifier → UseCase → Repository interface → Data 구현체 → DataSource → DioClient 순서로 연결합니다. JSON은 DTO → Mapper → 순수 Domain 값으로 변환하고 Domain에 Dio·Mapbox·DTO를 노출하지 않습니다.
- 기능이 정의한 Domain 타입의 의존성 계약에 `app/di`가 구현체를 주입합니다. 입력 선택 상태와 비동기 검색 결과 상태는 분리하고 AsyncValue의 로딩·오류를 별도 State 필드로 중복하지 않습니다.
- DioClient의 ApiException을 Repository에서 Failure로 변환할 때 서버 code/requestId를 보존합니다. 취소된 이전 요청은 화면 오류로 표시하지 않고, 입력 변경·연속 검색 후 이전 응답이 최신 결과를 덮어쓰지 않도록 요청 수명과 결과 적용 정책을 둡니다.
- 앱 조립 계층이 경로 Domain 좌표를 지도 렌더링 입력으로 변환합니다. 지도 feature가 경로의 Data/Notifier를 직접 참조하거나 서버 DTO를 그대로 렌더링하지 않도록 합니다.

연동 완료 기준은 실제 서버의 200 경로를 GeoJSON `[경도, 위도]` 순서로 표시하고 거리·시간·스냅 지점·출처와 null 환경 지표를 처리하는 것입니다. 400/404/422/503 및 연결·timeout 오류를 구분하고, 취소·이전 응답 무시와 실제 기기 통신도 확인합니다. 서버는 실제 공간 계산과 오류 응답을 검증하고 Flutter는 DTO/Mapper·Repository·UseCase·Notifier·화면과 기기 연동을 검증합니다.

## 서버 Phase 1 반영 결과

기존 Java 21/Kotlin/Spring Boot, Feature-first 계층, JDBC Repository 계약, ARM64 PostgreSQL/PostGIS, Flyway, 상태 검사, 테스트와 CI 구성을 유지합니다. Flutter와의 차이였던 오류 미디어 타입과 JSON 형식을 조정했습니다.

| 전달 요구사항 | 현재 구현 |
| --- | --- |
| Java 21, Kotlin/Spring Boot, feature-first 경계 | Gradle Java 21 toolchain, 기능별 api/application/domain/infrastructure, ArchUnit 검사 |
| PostgreSQL/PostGIS와 환경 변수, Flyway | Compose와 자체 PostGIS 이미지, `DB_*` 설정, V1 공간 확장 및 V2 보행 그래프 스키마 |
| 로컬 실행·포트·환경 변수 | README에 8080 기본 포트, 실행 명령, Compose와 앱 환경 변수의 차이 문서화 |
| health와 DB 상태 | Actuator health/readiness/liveness 및 `GET /api/v1/health` |
| Validation, 공통 오류, requestId, OpenAPI | MVC 검증과 예외 처리, UUID 요청 추적, local/dev OpenAPI/Swagger |
| 단위/API/DB 테스트와 CI | Gradle test/integrationTest, Testcontainers, Python 데이터 준비 테스트, GitHub Actions workflow |

- 일반 API 오류: `application/json`, 최상위 `code`, `message`, `requestId`, 부가 `timestamp`, `errors`.
- `X-Request-Id` 헤더, 오류 본문과 로그 식별자 연결.
- 앱용 health 장애도 HTTP 503 `SERVICE_UNAVAILABLE` 공통 오류로 통일.
- Actuator의 표준 상태 형식 유지, OpenAPI 오류 스키마를 실제 DTO에서 생성.
- Flutter의 origin 기반 API 주소, emulator·simulator·기기 연결 주소와 환경 매핑 문서화.

실행·환경 변수·포트는 [README](../README.md), 최종 오류 예시와 HTTP/code 표는 [API 계약](api-contract.md)을 참고합니다. 이전 RFC 9457 오류 응답은 Flutter 전달 계약을 반영하면서 교체했습니다.

## 장소 검색 제안

`GET /api/v1/places/search?query=서울숲`과 `places[]` 응답 형식을 수용합니다. 공급자 이름을 포함한 opaque `id`, 장소명·주소와 `{longitude, latitude}`를 API DTO로 제공하고 공급자 응답 변환은 infrastructure adapter에서 담당합니다.

빈 검색 결과는 HTTP 200 `places: []`, 빈 문자열·형식 오류는 HTTP 400 `INVALID_REQUEST`로 처리하는 방향입니다. 페이지네이션, 검색 길이 제한, 후보 수, 공급자 장애 코드, 캐시와 위치·표시 약관은 공급자 선정 후 확정합니다. 지도 렌더링 공급자와 검색 공급자를 같은 것으로 가정하지 않습니다.

## 경로 검색 구현 계약

`POST /api/v1/routes/search`, 좌표 객체, `travelMode: walking`, 초기 `mode: fastest`, `routes[]`와 GeoJSON `LineString` 형식을 수용해 구현했습니다. 서버의 이전 문서에 있던 단일 `route`/Feature 형식은 Flutter 제안의 `routes[].geometry`로 정리했습니다. 현재 성공 응답은 경로 한 개이며, 유효한 자료와 지역 안에서 경로가 없으면 404 `ROUTE_NOT_FOUND`입니다.

현재 구현 계약과 후속 확장 사항:

| 항목 | 현재 계약 |
| --- | --- |
| departureAt / evaluatedAt | departureAt은 선택 사항이며 제공 시 오프셋 필수. 생략하면 주입된 Clock의 현재 시각을 사용하고, 적용한 순간을 evaluatedAt에 UTC로 응답 |
| additionalDurationSeconds | 동일 조건의 fastest 기준 대비 차이. 현재 fastest 한 개이므로 0이며 실제 계산한 baselineDurationSeconds를 함께 제공 |
| 예상 시간 | 보행 1.4m/s, 계단 0.9m/s, 전체 시간 올림 정수(초). 신호 대기는 미반영. timeModelVersion 제공 |
| metrics / coverage | Phase 3에서 전체 경로를 평가할 수 있을 때 shadeRatio 제공. routes[].shadeEvaluation에 상태·coverage·건물 버전·모델·출처 제공. 나머지 세 지표는 null. 비율·점수는 0..1, trafficSignalCount는 개수 |
| dataVersion | 현재 그래프 스냅샷 식별자. 향후 환경 자료 버전도 재현할 수 있도록 확장 필요 |
| route id | 응답 안에서만 식별하며 저장·재조회·상세 API와 유효기간 계약은 미지원 |
| 검색 제한 | 서울숲 pilot 직사각형 내부의 두 좌표, 직선거리 최대 5km. 각 위치의 50m 안에서 서로 다른 스냅 위치 최대 8개를 확인 |
| snapping | 스냅 좌표와 접근 거리 제공. geometry·distanceMeters·durationSeconds는 스냅 지점 사이의 그래프 경로만 포함하며 입력 핀까지의 접근선은 미포함 |
| 추가 응답 필드 | baselineDurationSeconds, timeModelVersion, routingModelVersion, snapping, attribution. Flutter DTO의 파싱 정책과 화면 표시를 연동 시 확인 |
| 타임아웃 | 2026-10-10 Mac 검증에서 최초 요청 약 350ms, 재점검 순차 요청 warm p95 최대 44.23ms. 수신 30초 이내였으나 배포 환경·실기기 통신과 운영 부하는 별도 검증 필요 |

요청에는 origin/destination 좌표, travelMode, mode, departureAt만 전달합니다. 장소명·주소·공급자 ID 등 정의되지 않은 요청 필드는 400 `INVALID_REQUEST`입니다. 정확한 응답과 오류 표는 [API 계약](api-contract.md), 실행·데이터 준비 방법은 [Phase 2 문서](phase-2-routing.md)를 따릅니다.

업무 오류의 400 `INVALID_ROUTE_QUERY`, 422 `OUTSIDE_SUPPORTED_AREA`, 404 `ROUTE_NOT_FOUND`, 503 `ROUTING_DATA_UNAVAILABLE` 제안은 수용합니다. 문법·타입 오류는 공통 `INVALID_REQUEST`, 미구현 URL은 `NOT_FOUND`로 구분합니다. graph가 준비되지 않은 상태를 경로 없음으로 숨기지 않습니다. 인증·요청 제한 기능 자체는 아직 도입하지 않았습니다.

## 첫 지역·데이터 현황과 환경 계산 계획

첫 지역은 서울숲 주변 pilot 직사각형 `[서쪽 127.027, 남쪽 37.533, 동쪽 127.055, 북쪽 37.558]`입니다. 2026-10-10 Mac 검증에서는 실제 OSM 그래프 5,733개 노드·6,268개 구간으로 경로를 계산했습니다. 범위 내부라도 연결 가능한 보행 구간이 없으면 404이며 모든 장소의 출입구·현장 통행을 검증한 영역은 아닙니다.

1. [Geofabrik 한국 extract](https://download.geofabrik.de/asia/south-korea.html)의 PBF에서 지역 XML을 추출하고 실제 보행 그래프를 적재하는 도구가 구현되어 있습니다. 데이터 파일은 Git에서 제외되므로 새 환경에서는 [준비·적재 절차](phase-2-routing.md)를 실행해야 합니다. 출처·체크섬·시각·버전을 기록하고 [OSM 공식 라이선스 안내](https://www.openstreetmap.org/copyright)에 따른 attribution을 제공합니다.
2. snapping과 fastest 경로 탐색은 구현되어 있습니다. Flutter Phase 3에서 지도 선택 좌표를 연결하고 정상 경로, 지원 지역 밖, 연결 불가, 그래프 미적재 오류를 확인합니다. 장소 공급자 구현은 이 검증의 선행 조건이 아닙니다.
3. 건물 Polygon은 [국토교통부 GIS건물통합정보](https://www.data.go.kr/data/15083092/fileData.do)를 수급 후보로 검토합니다. 공간 도형과 건축물 속성 자료를 제공하는 데이터이며, 실제 높이(m) 필드의 충족률·정확도·갱신 시점은 수급 후 확인해야 합니다. 층수를 검증된 높이로 간주하지 않습니다.
4. 이후 환경 계산 단계에서 검증된 높이·건물 도형·태양 위치·평가 시각으로 그림자를 계산하고 shade/sunlight 모드를 추가합니다. 조용함·밝기·비 회피 자료와 복수 조건·가중치는 별도 모델로 확장합니다.

Phase 1 이후 Phase 2의 구현과 실제 데이터 검증은 [검증 기록](phase-2-verification.md)에 정리했습니다. 건물·높이 데이터의 품질을 확인한 뒤 기존 fastest 경로에 그늘 비율을 계산하고, 이를 검증한 다음 shade/sunlight 경로 탐색으로 확장합니다.

## 2026-10-19 현재 작업 환경 재확인

아래는 그늘 기능 구현에 착수하기 전 문서 검토 당시의 환경 기록입니다. 이후 Java 21을 프로젝트 임시 폴더에 준비하고 Docker를 실행해 Phase 3 검증을 진행했습니다. 위의 HTTP 성공·성능·75개 테스트 통과는 2026-10-10 Mac 검증 기록이며, Phase 3 결과는 [그늘 평가 문서](phase-3-shade.md)를 참고합니다.

Phase 3 최종 `build`에서 JVM 단위·아키텍처 62개와 PostgreSQL/PostGIS 통합 26개가 모두 통과했습니다. 실행 가능한 JAR를 생성했으며 실제 서울숲 건물 자료의 품질 검증은 아직 별도 작업입니다.

- 현재 Windows 환경의 JAVA_HOME은 Java 17이며 확인한 설치 경로에서 Java 21은 찾지 못했습니다. 프로젝트의 Java 21 toolchain은 유지합니다.
- `docker version`은 클라이언트를 확인했으나 Docker Desktop Linux 엔진에 연결하지 못했습니다. DB 통합 테스트·health·OpenAPI의 실제 HTTP 재검증은 수행하지 못했습니다.
- `./gradlew.bat test --offline --no-daemon --console=plain`은 사용자 Gradle 캐시의 wrapper 잠금 파일 접근 거부로 테스트 시작 전에 실패했습니다. 이번 테스트 통과 결과는 없습니다.
- 현재 체크아웃에는 `data/`가 없습니다. 기존 Mac의 적재 데이터가 이 환경에 있다고 가정하지 않습니다.
- [공식 PostgreSQL 이미지 안내](https://hub.docker.com/_/postgres)와 [공식 이미지 정의](https://github.com/docker-library/official-images/blob/master/library/postgres)에서 17.11-bookworm과 ARM64 지원을 확인했습니다. 프로젝트는 PostgreSQL 다중 아키텍처 이미지에 PostGIS를 설치하는 구성을 유지합니다. 고정 digest의 registry manifest 재조회는 현재 환경의 Docker Hub DNS 조회 실패로 확인하지 못했으며, ARM64 실제 실행 근거는 기존 Mac 검증 기록입니다.
