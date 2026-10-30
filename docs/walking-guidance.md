# 보행 길안내 단계 계약

2026-10-29. 기존 `POST /api/v1/routes/search`와 `POST /api/v1/routes/predictions/search`의 성공 `routes[]`에 **guidance** 객체를 추가한다. 요청 형식, geometry, 거리·시간, snapping, 그늘 비율·구간, 데이터·탐색 버전, 공통 오류 계약은 유지한다. 기존 클라이언트는 추가 필드를 무시하며 계속 사용할 수 있다.

## 응답

| guidance 필드 | 의미 |
| --- | --- |
| `status` | `AVAILABLE` / `UNAVAILABLE`. 없는 간선 통과 기록에서 임의 안내를 만들지 않는다 |
| `metadataStatus` | `OSM_TAGS` / `TOPOLOGY_ONLY`. 원본 태그가 아직 적재되지 않은 기존 DB는 보행망 연결 관계에 기반한 방향 안내만 제공 |
| `modelVersion` | `walking-guidance-v1-topology-look10m` |
| `language` | 현재 `ko` 고정. 요청 language 필드는 미지원 |
| `steps` | 출발→도착 순서의 안내 배열. AVAILABLE이면 최소 출발·도착 두 단계 |

| steps[] 필드 | 타입·단위 |
| --- | --- |
| `index` | 응답 안에서 0부터 증가하는 정수 |
| `type` | 아래 maneuver 식별자 |
| `coordinate` | `{longitude, latitude}`, WGS84 |
| `geometryIndex` | **전체 경로 `routes[].geometry.coordinates`** 안의 인덱스. shadeSegments 내부 인덱스가 아님 |
| `distanceFromStartMeters` | 스냅된 출발점부터 해당 안내 지점까지 누적 거리(m) |
| `distanceToNextStepMeters` | 해당 안내 지점부터 다음 안내 지점까지 거리(m) |
| `durationToNextStepSeconds` | 다음 안내까지 기존 보행 시간 모델의 예상 초. 올림하지 않은 double |
| `instruction` | 안내 지점에서 수행할 행동을 설명하는 한국어 일반 텍스트 |
| `streetName` | 이후 통과하는 원본 도로명. 없으면 null |
| `bearingBeforeDegrees` | 도착 방향의 방위각. 북=0, 동=90, 남=180, 서=270. DEPART는 null |
| `bearingAfterDegrees` | 출발 방향의 방위각. ARRIVE는 null |
| `crossing` | 원본에 명시된 횡단 정보 또는 null. 아래 규칙 참고 |

`type`은 `DEPART`, `CONTINUE`, `KEEP_LEFT`, `KEEP_RIGHT`, `TURN_LEFT`, `TURN_RIGHT`, `U_TURN`, `CROSSING`, `STAIRS`, `ARRIVE`이다. 좌표 순서대로 늘어나는 geometryIndex와 누적 거리를 사용한다. 마지막 ARRIVE의 다음 거리·시간은 0이다.

guidance의 AVAILABLE은 길안내 생성 상태이며, 건물·그늘의 실제 검증 상태와 별개다. 기존 shadeEvaluation 또는 모델 prediction의 품질 표시와 혼합하지 않는다.

각 단계의 다음 거리 합은 원시 경로 거리와 같다. 기존 `distanceMeters`는 소수 첫째 자리로 반올림하므로 0.05m 수준 차이가 가능하다. 다음 시간 합은 올림 전 원시 경로 시간이며 기존 정수 `durationSeconds`와는 1초 미만 차이가 가능하다. DB의 기존 거리 비용과 파일 기반 예측의 거리 비용을 각각 유지한다.

instruction은 **그 좌표에서의 행동**이다. 앱은 현재 진행 거리와 다음 단계의 `distanceFromStartMeters` 차이를 계산하여 “80m 후 오른쪽으로…”처럼 표시할 수 있다. `distanceToNextStepMeters`를 현재 위치 기준 거리로 그대로 쓰지 않는다. 도로명이 없으면 앱에서도 임의 장소명을 만들지 않는다.

ARRIVE는 실제 선택 핀이 아니라 **스냅된 경로 끝점**이다. 입력 핀까지의 접근 거리·접근선은 안내 거리·시간에 포함되지 않고 기존 `snapping`으로 구분한다. route ID는 계속 응답 내 식별자이며 저장/재조회 API를 추가하지 않는다.

## 실제 분기와 원본 태그

안내는 최종 선택 경로가 통과한 **방향과 부분 간선 범위**를 기록해 생성한다. 같은 좌표의 다른 원본 노드를 연결하지 않고, 좌표에서 가장 가까운 간선을 다시 추측하지 않는다. fastest와 shade 모두 자신이 실제 선택한 간선 기록을 사용한다.

- 현재 노드에서 들어온 길의 역방향을 제외하고 합법적으로 나갈 수 있는 목적 노드가 두 개 이상일 때 분기 안내를 만든다. 같은 목적 노드로 가는 중복 간선은 하나의 선택지로 취급한다.
- 선택 가능한 분기가 없는 곡선·직각 보행로에서는 꺾임만 보고 회전 안내를 만들지 않는다.
- 실제 분기점의 경로 앞뒤 약 10m 방향을 사용한다. 약 20~45도는 KEEP, 45~160도는 TURN, 160도 이상은 U_TURN으로 구분한다. 얕은 Y자 분기는 다른 전방 선택지와의 방향 차이로 KEEP_LEFT/RIGHT를 구분한다.
- 명시된 횡단 지점이나 계단 진입은 분기가 없어도 안내할 수 있다. 한 횡단 way의 여러 보조 좌표마다 같은 안내를 반복하지 않는다. 계단의 상승/하강은 추정하지 않는다.
- 회전과 횡단이 같은 지점이면 type은 회전 종류를 유지하고 crossing 객체와 횡단 문구를 함께 제공할 수 있다.

원본 도로명은 `name:ko` 우선, 다음 `name`을 사용한다. 보행 권한·방향 검증은 기존 OsmWalkingPolicy를 유지한다.

crossing 객체는 다음 구조다.

```json
{"trafficSignals": null}
```

객체가 있으면 원본에서 횡단 지점으로 명시된 것이고, `trafficSignals`는 신호 **설치 여부**이다. true/false/null을 구분하며 null을 false로 바꾸지 않는다. 현재 신호의 빨강·초록이나 실시간 통행 허용을 제공하는 값이 아니다.

원본의 [`highway=crossing`](https://wiki.openstreetmap.org/wiki/Tag:highway%3Dcrossing) 노드와 [`footway=crossing`](https://wiki.openstreetmap.org/wiki/Tag:footway%3Dcrossing) 구간을 사용한다. 신호 설치 여부는 [`crossing:signals=yes/no`](https://wiki.openstreetmap.org/wiki/Key:crossing:signals) 또는 기존 `crossing=traffic_signals`에서 읽는다. 서로 모순되는 신호 태그는 null로 두며, 차량용 `highway=traffic_signals`만으로 보행 횡단을 만들지 않는다. 현장·실시간 검증을 의미하지 않는다.

## 기존 DB와 파일 장면

Flyway V6는 `route_guidance_dataset`, `route_node_guidance`, `route_edge_guidance` 보조 테이블을 추가한다. 기존 route_dataset/route_node/route_edge의 좌표·비용·버전을 바꾸지 않는다.

기존과 동일한 OSM 파일·체크섬·메타데이터로 원래 적재 명령을 다시 실행하면 기존 데이터셋을 재사용하고 안내 태그만 한 번 보완한다. 보완 전에는 TOPOLOGY_ONLY, 보완 후에는 OSM_TAGS이다. 기존 데이터셋의 내용 검증, 트랜잭션·잠금을 유지하고 이미 보완된 태그가 다르면 거부한다. 실패한 보완은 보조 레코드까지 롤백한다. 캐시는 데이터 버전과 안내 태그 버전을 함께 확인한다.

공공 모델 파일 장면은 checksum을 확인한 동일한 roads.osm에서 안내 태그를 읽는다. scene.json 재수집이나 공공 API 키 추가는 필요하지 않다. 모델 높이·그늘 계산 방식도 바꾸지 않는다.

## Flutter 반영

1. 기존 prediction/general 경로 DTO와 Domain에 nullable guidance 및 안내 단계 타입을 추가한다. 과거 응답·fixture에 필드가 없으면 기존 경로 표시를 유지한다.
2. AVAILABLE일 때 전체 경로의 진행 위치를 단계의 누적 거리·geometryIndex와 연결한다. 서버 원본 geometry를 임의로 단순화해 인덱스를 바꾸지 않는다.
3. instruction은 일반 텍스트로 표시한다. 미래의 알 수 없는 type/status를 임의 방향으로 해석하지 않는다.
4. GPS 품질·도착 판단·음성/진동·재탐색 실행 시점은 기존 앱 기능에서 처리한다. 새 응답은 위치 추적 세션이나 실시간 신호 API가 아니다.
5. SHADE/SUNLIGHT 색상은 기존 shadeSegments로 계속 표시한다. 길안내 단계와 그늘 경계는 서로 다른 목적의 분할이다.

실제 전체 응답은 [fastest](examples/model-route-prediction-fastest-response.json), [shade](examples/model-route-prediction-shade-response.json)를 참고한다. 로컬 검증에서는 기존 필드를 대조하고, 단계 좌표·순서·거리/시간 합, 스냅·횡단 태그, 양방향 경로를 검사한다.

실제 [일반 도보 응답](examples/walking-route-guidance-response.json), [모델 경로의 안내 단계 한 개](examples/walking-guidance-step.json)도 저장했다. 단계 예시에서 index는 5, type은 TURN_LEFT, 전체 경로 geometryIndex는 23이다. 출발점부터 273.4000576m 지점에서 왼쪽으로 돌고 다음 단계까지 61.5511205m를 이동한다. 이는 선택된 실제 경로의 계산 결과다.

## 2026-10-29 검증 기록

- JVM 단위/API/아키텍처 **144개**, PostGIS 통합 **37개**, 합계 **181개 통과**. 실패·오류·건너뜀 0. Python **63개 통과**.
- 갈림길 없는 곡선, 얕은 Y자 분기, 역방향·부분 간선, 일방통행, 같은 좌표의 다른 노드, 중복 간선, 횡단 구간의 보조 좌표, 미확인/모순 신호 태그, 계단 진입을 검사했다. 아주 짧은 보조 좌표를 회전으로 오해하지 않도록 10m 방향을 사용하며, 방향 탐색은 누적 거리의 이진 검색으로 처리한다.
- 기존 DB의 최초 메타데이터 보완·재적재·변경 거부·캐시 갱신·중간 SQL 오류 롤백을 검증했다. 로컬 동일 OSM 스냅샷을 reused=true로 보완했으며 원래 5,733개 노드·6,268개 간선을 유지했다.
- 서울숲 10개 좌표 쌍을 양방향 fastest/shade로 호출한 **40건 모두 200 + guidance AVAILABLE**. 범위 밖·보행로 50m 밖·야간 **8건의 오류 상태와 code 유지**.
- 매 요청 새로 발급되는 route id와 추가 guidance를 제외한 **기존 40개 성공 응답의 모든 필드가 변경 전과 동일**했다. coverage 응답도 동일하다.
- 기본 예제의 fastest 800.7m/572초/예측 그늘 31.14%에는 19단계, shade 865.6m/619초/54.60%에는 23단계가 있다. 일반 도보 API는 기존 800.8m/573초, shadeRatio null, 직접 높이 shade는 503 SHADE_DATA_UNAVAILABLE를 유지한다.
- 최종 JAR를 별도 임시 포트에서도 확인하고 검증 프로세스를 종료했다. IntelliJ의 기존 8080 세션에서 새 안내 응답과 health UP을 확인했다. OpenAPI, 공통 오류 JSON·requestId, 공유 파일의 키 원문 검사도 통과했다.

원본 검증 자료는 Git에서 제외하는 `data/public-buildings/guidance-20261029/`의 before/, after/, final/, audit.json에 있다. 이전/이후 전체 응답과 새 안내를 함께 보관한다.
