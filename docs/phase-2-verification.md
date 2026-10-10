# Phase 2 검증 기록

검증일: 2026-10-10 (Asia/Seoul). 로컬 Mac Apple Silicon, Java 21, PostgreSQL 17.11/PostGIS 3.6.4 ARM64에서 검증했습니다.

## 실제 데이터

| 항목 | 결과 |
| --- | --- |
| source | Geofabrik 한국 OSM PBF |
| source timestamp | 2026-10-09T20:21:04Z |
| PBF SHA-256 | 70007aa979f8e362ca609fdc2d6302164c2f1379f11c8fc5fd39a7a2e79d3c17 |
| 지역 XML SHA-256 | ec2fb740c3292c93425a0175a65a78d5256933e2b4330ed1082cf9eb47bb1124 |
| dataVersion | osm-v1-49f53ea1a6d5cb06c4c4e5443d0a9a147abb3d4e53db384f2135be4c644220c4 |
| 원본 XML | 9,398개 노드, 1,633개 way |
| 적재 보행 그래프 | 5,733개 노드, 6,268개 구간 |
| 구간 종류 | WALKWAY 6,111 / STEPS 157 |
| 연결 성분 | 34개, 최대 5,468개 노드(약 95.4%) |
| 명시적 보행 방향 제한 | 이 스냅샷은 0개; 합성 테스트에서 방향 제한 검증 |

Flyway V2 적재와 같은 스냅샷 재적재 `reused=true`를 확인했습니다. PBF·XML·metadata·계산 결과는 로컬 `data/osm/`에 유지하며 Git에는 포함하지 않습니다.

공개 Overpass 서버의 조회 실패(504/500)를 확인한 후 실제 정적 PBF를 받아 추출했습니다. 설명용 도착 좌표의 최근접 구간이 4개 노드의 단절 성분에 속해 단일 최근접 snapping은 404였습니다. 최대 8개 후보로 범위 안의 연결 가능한 구간을 선택하여 그래프에 없는 연결을 추가하지 않고 경로를 계산했습니다.

## 실제 HTTP와 성능

`docs/examples/route-search.json` 요청은 200, 800.8m, 예상 573초, 좌표 40개로 응답했습니다. 출발 접근 거리 2.4m, 도착 접근 거리 39.3m이며 접근선을 네트워크 경로에 포함하지 않습니다. 환경 점수 네 필드는 null, fastest 기준 추가 시간은 0, 평가 시각은 요청 출발 시각을 UTC로 정규화했습니다.

| 요청 | 거리(m) | 예상 시간(s) | 좌표 수 | warm p50(ms) | warm p95(ms) | warm max(ms) |
| --- | --- | --- | --- | --- | --- | --- |
| 문서 좌표 | 800.8 | 573 | 40 | 22.63 | 31.39 | 31.52 |
| 실제 도로 노드 쌍 1 | 2354.8 | 1682 | 127 | 6.71 | 8.04 | 8.55 |
| 실제 도로 노드 쌍 2 | 2222.4 | 1588 | 102 | 7.63 | 9.69 | 12.86 |
| 실제 도로 노드 쌍 3 | 3153.0 | 2259 | 99 | 6.96 | 16.70 | 28.82 |

각 요청을 25회, 총 100회 순차 실행했습니다. 최초 캐시 로드·서블릿 초기화가 포함된 문서 좌표 요청은 약 350ms였습니다. Flutter 수신 timeout 30초 안의 동기 처리를 이 로컬 범위에서 확인했으며, 동시 부하나 배포 환경의 SLO를 검증한 수치는 아닙니다. 자세한 입력과 원시 결과는 `data/osm/route-benchmark.json`에 있습니다.

health 200/UP과 `/v3/api-docs`의 route endpoint, 400/404/422/503 오류, 필수 입력·응답 스키마를 확인했습니다. 예상 경로 시간은 모델 계산 값이며 실제 걸어서 측정한 시간은 아닙니다.

## 자동 검증

`./gradlew clean build --no-daemon --warning-mode all`과 최종 경계 조건 보완 후 전체 build가 성공했습니다. JVM 테스트 63개(단위·아키텍처 50 / API·DB 통합 13), Python 데이터 준비 테스트 4개로 총 **67개 성공 / 실패 0 / 건너뜀 0**입니다.

JVM 단위·아키텍처·API/DB 테스트와 Python 데이터 준비 테스트를 검증합니다. 범위는:

- Dijkstra의 시간 비용, 계단 비용, 구간 중간점, 방향 제한, 단절 그래프, 후보 snapping, 동일 스냅 위치와 범위 제한.
- 날짜 오프셋/기본 Clock, 400/404/422/503 업무 코드, requestId, GeoJSON 경도/위도와 PostGIS 미터 거리.
- XML 참조·조건부 통행·barrier·foot 접근 정책·DTD 거부·재현 가능한 버전.
- idempotent import, SQL 오류 rollback, 활성 snapshot 변경과 캐시 갱신.
- 합성 PBF의 경계 밖 참조 완성, 태그·timestamp 보존, 파일 보존과 입력 제한.

CI는 Java 21 및 Python 3.12에서 같은 검증을 실행합니다. 경로 탐색과 CI는 외부 OSM API 가용성에 의존하지 않습니다.

실제 DB 중지 시 캐시가 존재해도 경로 API는 503 ROUTING_DATA_UNAVAILABLE과 JSON code/requestId를 반환했습니다. DB 복구 후 서버 재시작 없이 경로 검색 200으로 돌아왔습니다. 검증용 서버는 종료하고 개발 DB와 적재 데이터를 유지했습니다.
