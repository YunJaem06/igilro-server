# ECS 경로 데이터와 장소 검색 배포

서버는 준비된 데이터만 읽습니다. 수집기와 VWorld·건축물대장 키는 ECS에 넣지 않습니다. 모델 결과는 기존 `EXPERIMENTAL_PARTIAL_MODEL` 계약을 유지하며 실측 그늘로 표시하지 않습니다.

## 데이터 전달

로컬에서는 기존 `SHADE_PREDICTION_FILE`을 사용합니다. ECS에서는 다음 네 변수를 모두 지정합니다. 모두 비어 있으면 AWS SDK 클라이언트와 자격 증명을 조회하지 않으므로 기존 로컬 실행에 영향이 없습니다. 일부만 지정하거나 로컬 파일과 함께 지정하면 기동을 중단합니다.

| 변수 | 내용 |
| --- | --- |
| `ROUTE_DATA_BUCKET` | 비공개 S3 버킷 |
| `ROUTE_DATA_MANIFEST_KEY` | `datasets/<64자리 해시>/manifest.json` |
| `ROUTE_DATA_MANIFEST_VERSION_ID` | manifest의 S3 VersionId, `null` 금지 |
| `ROUTE_DATA_MANIFEST_SHA256` | manifest 바이트의 SHA-256 |
| `ROUTE_DATA_DIRECTORY` | 선택, 기본 `/tmp/igilro-route-data` |

AWS 리전은 `AWS_REGION`, 권한은 ECS task role로 전달합니다. 장기 access key는 설정하지 않습니다. Java AWS SDK v2 S3 동기 클라이언트의 연결 timeout은 3초, socket 10초, API 호출 60초, 시도당 20초, 최대 2회 시도입니다. 전체 준비 단계는 읽기 사이에 120초 예산을 확인하며 blocking I/O는 SDK timeout으로 제한합니다. ECS ALB 시작 유예는 240초입니다.

manifest schema v1은 정확히 `scene.json`(32 MB), `roads.osm`(20 MB), `walking.osm`(20 MB)를 참조하며 manifest는 64 KiB 이하입니다. 각 파일에 동일 bundle prefix의 key, VersionId, SHA-256, 실제 바이트 수를 기록합니다. 그래프의 areaId/name/bounds/source/expectedDataVersion 및 배포 검증용 좌표·offset 있는 departureAt도 기록합니다. 업로드 도구가 content hash prefix를 계산하므로 수동으로 manifest를 작성하지 않습니다.

서버는 정확한 객체 버전을 내려받고 크기·해시를 확인합니다. 중간 파일은 비공개 임시 디렉터리에 쓰며 전체 검증 후 디렉터리를 원자적으로 전환합니다. 오류 시 부분 자료를 사용하거나 예전 로컬 파일로 대체하지 않습니다. 일반 앱은 DB 읽기 역할만 사용하고 파일 snapshot을 메모리에 로드합니다.

## 초기화와 릴리스

동일 image digest로 Flyway → `data-import` → 앱 배포를 순차 실행합니다. data-import는 `IGILRO_ROUTE_DATA_IMPORT_GRAPH=true`, `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `SPRING_FLYWAY_ENABLED=false`와 migrator DB 역할을 사용합니다. 검증된 walking.osm을 기존 OSM reader로 읽고 expectedDataVersion과 일치할 때만 저장합니다. 실행 완료 후 Spring context를 닫습니다.

자동 초기화는 비어 있는 그래프 집합을 생성하거나, 이미 활성화된 단일 area/version을 재사용합니다. DB transaction의 advisory lock 안에서 검사하므로 두 배포가 서로 다른 버전을 동시에 활성화할 수 없습니다. 다른 버전·추가 지역이 있으면 기존 자료를 보존하고 중단합니다. 통상 배포에서 활성 그래프를 교체하지 않아 이전 app revision으로 복구해도 같은 그래프를 사용합니다. 그래프 교체는 별도 검토·초기화/복구 계획이 필요한 후속 작업입니다.

배포 도구는 health 외에 일반 경로의 dataVersion, 모델 coverage/scene version, fastest/shade의 좌표·거리·시간·안내·평가시각, Kakao 장소 검색을 확인합니다. 실패하면 이전 ECS task revision/실행 수로 복구합니다. DB schema와 외부 secret 값은 자동 롤백하지 않습니다. 스키마 변경은 이전 앱과 호환되게 작성하고 이전 S3 객체 버전도 보존합니다.

## 비밀값과 적용 절차

`KAKAO_REST_API_KEY`는 Secrets Manager의 원문 문자열 secret으로 앱에만 주입합니다. 값은 Terraform state/SSM/소스에 넣지 않습니다. 회전 후 새 ECS task를 시작해야 반영됩니다. VWorld·건축물대장 키는 로컬 수집 도구에만 필요합니다.

인프라 저장소의 [배포 자료 준비·적용 절차](https://github.com/YunJaem06/igilro-infra/blob/main/docs/runtime-route-data.md)에 SSO, secret 설정, bundle 준비/업로드, Terraform 입력과 GitHub 변수 설정 명령을 정리했습니다. 실제 계정의 SSO 세션·리전·DNS/인증서가 있어야 AWS 적용과 공개 HTTPS 검증이 가능합니다.

## 구현 검증 (2026-11-01)

- Kotlin 단위 172개, 격리 PostGIS 통합 40개 통과 (`./gradlew build`). 기존 일반/모델 경로와 요청 제한 회귀 포함.
- 실제 SDK를 로컬 HTTP 서버에 연결해 S3 VersionId 인코딩을 확인했습니다. 실패한 크기·해시·참조·시간 예산·부분 다운로드는 snapshot으로 노출되지 않습니다.
- 실제 서울숲 파일 3개를 로컬 S3 모의 서버에서 받아 non-web 초기화 task를 두 번 실행했고 모두 exit 0, 같은 그래프 재사용을 확인했습니다.
- 새 JAR의 localhost:8080 health UP, 일반 경로/coverage/fastest/shade 및 실제 Kakao 장소 검색 검증 통과. AWS S3·ECS 실서비스 호출 검증은 아닙니다.
- 인프라 Python 40개, TLS DB/역할/Flyway/prod 기동 7개, Terraform 모의 25개 통과. 실제 AWS plan/apply·업로드·secret 쓰기·공개 HTTPS 배포는 실행하지 않았습니다.

Apple Silicon에서 새 Docker 이미지 빌드도 통과했습니다. AWS workflow는 별도로 linux/amd64를 빌드합니다. 배포 job은 migration/import/rollout과 실패 복구 시간까지 포함해 80분, OIDC 세션은 90분으로 설정했습니다.
