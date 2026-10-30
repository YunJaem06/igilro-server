# 서울숲 공공 건물 수집·적재 검증 — 2026-10-26

이 문서는 첫 수집·적재 결과입니다. 이후 [PK 매칭·누락 높이 검증](building-matching-verification-2026-10-26.md)을 진행해 활성 버전을 교체했습니다. 현재 자료와 남은 보완 대상은 해당 문서를 따릅니다.

브이월드 GIS건물통합정보와 건축HUB를 실제 호출하여 건물 14,075개를 개발 PostGIS에 적재했다. 직접 높이가 있는 건물의 그림자 연산도 실제 경로로 실행했다. 전체 건물 목록의 완전성·누락 높이의 상한은 확인되지 않았으므로 앱용 전체 경로 그늘 비율은 제공하지 않는다.

## 구현과 재실행

경로 요청마다 외부 건물 API를 호출하지 않는다. `scripts/building_sources.py`가 공급자별 응답을 읽고, `prepare-public-buildings.py`가 수집·정규화·매칭 감사 자료를 만든다. `validate-public-buildings.py`의 PostGIS 검증을 통과한 파일을 기존 JVM 적재기로 원자적으로 활성화한다. 현재 수집기는 로컬 CLI이며 AWS 수집 태스크/스케줄 배포는 별도 작업이다.

Java 21, Python 3.12, 실행 중인 Compose PostgreSQL/PostGIS가 필요하다. `.env` 또는 프로세스 환경 변수의 `VWORLD_API_KEY`, `BUILDING_HUB_API_KEY`를 사용하고 `VWORLD_DOMAIN`은 필요한 키에서만 지정한다. 실제 값은 `.env.example`에 저장하지 않는다. 프로세스 환경 변수가 로컬 파일보다 우선한다. 로컬 파일은 셸 코드를 실행하지 않는 `KEY=value` 형식이다.

```powershell
docker compose up -d postgres
python -m venv .venv-data
.venv-data/Scripts/python.exe -m pip install -r scripts/requirements-public-buildings.txt
.venv-data/Scripts/python.exe scripts/prepare-public-buildings.py --output data/public-buildings/seoul-forest-20261026
.venv-data/Scripts/python.exe scripts/validate-public-buildings.py data/public-buildings/seoul-forest-20261026/buildings.incomplete.geojson --output data/public-buildings/seoul-forest-20261026/buildings.validated.geojson
./gradlew.bat bootJar
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --spring.main.web-application-type=none --spring.flyway.enabled=true --igilro.building-import.file=data/public-buildings/seoul-forest-20261026/buildings.validated.geojson
java -jar build/libs/igilro-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=local --server.port=8080
```

새 스냅샷은 다른 출력 디렉터리를 사용한다. 실패 후 같은 디렉터리로 다시 실행하면 체크섬을 검증한 캐시에서 이어 받는다. 완성된 출력은 덮어쓰지 않는다. 캐시에는 인증키·인증 URL을 쓰지 않으며 공급자가 되돌려준 키 문자열도 제거한다.

검증기는 로컬 DB만 허용하며 임시 테이블을 만들고 롤백한다. `DB_URL/DB_USERNAME/DB_PASSWORD`를 사용하고, 생략하면 로컬 `igilro` DB 및 `POSTGRES_USER/POSTGRES_PASSWORD` 설정을 사용한다. 다른 계정·포트는 DB 변수를 명시한다. 유효하지 않은 형상은 자동 수정하지 않고 격리한다. 검증 명령은 서비스 테이블이나 활성 버전을 바꾸지 않는다.

## 실제 공급자와 수집 경계

- 형상: [국토교통부 GIS건물통합정보](https://www.data.go.kr/data/15123970/openapi.do), [브이월드 전용 WFS 안내](https://www.vworld.kr/dtna/dtna_apiSvcFc_s001.do?apiNum=76).
- 형상 endpoint는 `https://api.vworld.kr/ned/wfs/getBldgisSpceWFS`, `typename=dt_d010`이다. 일반 WFS 2.0의 도로명주소 건물 레이어와 구분한다.
- 높이: [건축HUB 건축물대장정보](https://www.data.go.kr/data/15134735/openapi.do)의 표제부 `https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo`.
- GIS 높이 필드는 `hg`, HUB 높이 필드는 `heit`다. 0·누락·음수·비정상 수치는 UNKNOWN이며 층수에서 높이를 생성하지 않는다.

두 공공데이터포털 카탈로그는 확인 시 이용허락범위 제한 없음으로 표시했다. 출처를 보존하며 운영 공개 시 브이월드 서비스·키 이용 조건도 확인한다. 수집 시각을 측량·원본 갱신 시각으로 해석하지 않는다.

WFS는 EPSG:3857 bbox에 CRS 접미사를 붙이고 `srsName=EPSG:3857`로 요청한다. 응답 CRS를 검사하여 WGS84 `[longitude,latitude]`로 변환한다. 적재 입력·앱 좌표는 4326, 도형 저장·미터 단위 연산은 5179다. 4326 bbox 축 순서에 의존하지 않는다.

GIS가 1,000개를 넘으면 bbox를 네 부분으로 나눈다. 경계 중복을 제거한 원본 행 수가 최상위 `totalFeatures`와 같아야 한다. HUB는 1,000개를 요청해도 실제 최대 100개가 반환되어 총건수와 고유 PK를 대조하며 모든 페이지를 읽었다. 건수 변화, 빈 중간 페이지, 동일 PK의 내용 변화, 미진행·한도 초과는 실패 처리한다. 무제한 재시도는 하지 않는다.

제한은 bbox 각 축 0.1도, GIS 25,000개/80요청, HUB 50,000건/법정동당 100페이지, 최소 요청 간격 0.5초다. 응답당 10 MB/25초, JVM 입력은 20 MB/250,000좌표다. 공급자의 원자적 스냅샷은 보장되지 않으므로 원본별 관측 시각·해시를 보존한다.

## 수집·매칭 결과

형상 bbox는 서울숲 주변 `[127.022,37.528,127.060,37.563]`이다. HUB는 등장한 22개 법정동의 표제부를 조회하여 다동 건물 후보가 유일한지 확인했다. 전국 형상은 받지 않았다. 일부 원본 지번은 현재 형상 위치와 추가 확인이 필요하므로 주소 속성만으로 위치 정확성을 입증했다고 보지 않는다.

2026-10-26 15:46:54~16:00:55 KST에 GIS 45개 요청, HUB 439페이지를 수집했다. 원본은 GIS 14,075행, HUB 42,827건이며 인증정보를 제거한 응답 484개를 보관했다.

| 항목 | 결과 |
| --- | ---: |
| 적재 건물 | 14,075 |
| 직접 높이 ACTUAL | 5,585 (39.68%) |
| 높이 UNKNOWN | 8,490 (60.32%) |
| 선택 높이 출처: HUB / GIS | 4,320 / 1,265 |
| LEVEL_ESTIMATED / OSM | 0 / 0 |
| 유효하지 않거나 빈 도형 / 투영 후 유효성 실패 | 0 / 0 |
| 원본 형상·분류된 높이 출처 누락 | 0 |
| 입력 좌표 수 (coverage 포함) | 98,074 |
| 동일 도형 그룹 / 추가 원본 행 | 36 / 38 |

같은 `(src_signgu_code,src_objectid,gis_idntfc_no)`가 서로 다른 필지·내용으로 재사용된 그룹도 1개 있었다. 원본 식별자와 전체 내용 해시로 행을 구분하며 요청별 GeoServer feature ID를 영구 식별자로 사용하지 않는다. 동일 도형 그룹에서 서로 다른 직접 높이가 붙은 사례는 0개였지만 중복 원본 검토는 필요하다.

PNU, 정규화한 동명, 건축면적(0.01㎡ 정밀도)이 양쪽에서 유일하면 HUB를 연결한다. 동명이 없으면 양쪽 모두 해당 필지에 단일 건물이며 면적도 같아야 한다. 4,637개를 연결했고 이 중 직접 높이가 있는 HUB 4,320개를 선택했다. 나머지는 GIS 직접 높이를 사용하거나 UNKNOWN으로 남겼다. 공식 안내상 변경된 이전/현재 PK를 숫자가 비슷하다는 이유로 동일시하지 않았다.

HUB가 GIS의 높이 누락을 새로 보완한 건물은 1개였다. 두 값이 모두 있으면서 0.01m를 넘게 다른 경우도 1개였다. GIS가 이미 대장 속성을 결합한 자료이므로 HUB를 추가해도 누락 높이가 대량으로 채워지지는 않았다. 매칭 감사 기록과 양쪽 원본을 보존했다. ACTUAL은 공공 원본의 직접 수치 분류이며 현장 실측 검증 완료라는 뜻은 아니다.

직접 높이 5,585개 중 양수 건축면적은 5,547개다. 도형 면적/속성 건축면적이 0.5~2 범위 밖인 행은 247개다. 이는 점검용 범위이며 매칭 승인 임계값이 아니다. 지붕 도형과 법정 면적 차이, 부분/전체, 갱신·연계 문제를 구분해야 한다. OSM 형상에 근접 거리만으로 높이를 복사하지 않았다. 4326→5179→4326 경계의 최대 Hausdorff 오차는 약 `2.13e-14`도다.

## JVM 적재와 실제 경로 API

새 활성 버전은 `buildings-v1-5333b751c8f21ed4149d135171f19723b16ced2539e78520b4749afb6d056413`이다. 첫 적재 `reused=false`, 반복 `reused=true`, Flyway V1~V5 성공을 확인했다. 기존 건물 버전과 OSM 보행 그래프는 보존했다.

두 키가 있는 로컬 환경으로 서버를 재시작했다. `/api/v1/health`, `/actuator/health` 모두 UP이다. `http://localhost:8080/v3/api-docs`, `/swagger-ui/index.html`에 접근할 수 있다.

출발 `(127.0374,37.5445)`, 도착 `(127.0430,37.5480)`, `walking/fastest`의 실제 요청 네 경우 모두 HTTP 200, **800.8m / 573초**다. 적재 전과 geometry·거리·시간·보행 버전이 같다. `X-Request-Id`와 `evaluatedAt=departureAt`의 동일 순간을 확인했다.

| departureAt (KST) | shadeEvaluation.status | coverage | shadeRatio | 로컬 응답 시간 |
| --- | --- | --- | --- | ---: |
| 2026-10-26 12:00 | INCOMPLETE_COVERAGE | 0 | null | 488ms |
| 2026-10-26 00:00 | NIGHT | null | null | 54ms |
| 2026-03-21 06:45 | LOW_SUN | null | null | 54ms |
| 2200-01-01 12:00 | UNSUPPORTED_TIME | null | null | 43ms |

단발 로컬 측정이며 부하 환경의 지연 보장은 아니다. 불완전한 목록에서는 전체 그림자 집계를 실행하지 않으므로 위 낮 지연을 전체 그림자 계산 성능으로 해석하지 않는다.

## 실제 그림자 연산의 부분 진단

별도 읽기 전용 진단에서 서버의 `SolarPositionCalculator`와 DB `igilro_building_shadow`를 그대로 사용했다. 2026-10-26 12:00 KST 경로 중심의 태양 고도는 40.267196847도, 방위각은 174.942950691도다. 주변 직접 높이 건물 214행 중 3행의 모델 그림자가 경로와 교차했고 중복 그림자를 합친 교차 길이는 **172.5249m**다.

이는 알려진 높이와 현재 도형만의 모델 연산 확인이다. 전체 경로 그늘 비율이나 현장 관측 정확도가 아니며 앱 `shadeRatio`에 넣지 않는다. 5179 투영 경로 길이 800.5375m와 API의 지리 좌표 거리 합계 800.8m는 산출 기준이 다르다. 알려진 최대 높이 159.8m를 누락 높이의 상한으로 사용하지 않았다.

현재 `inventoryComplete=false`, `maximumHeightMeters=null`이므로 coverage=0을 유지한다. coverage는 높이 보유율 39.68%와 다른 값이다. 중복·형상/대장 연결을 검토하고 누락 건물·높이의 영향 범위를 확인한 뒤 평가 가능 영역을 선언해야 한다. 층수 추정은 정책과 버전을 합의한 뒤 LEVEL_ESTIMATED로 도입한다. 이번에는 추정값을 만들지 않았다.

## 로컬 증거와 테스트

- `data/public-buildings/seoul-forest-20261026/`: 원본 캐시, 정규화·검증 GeoJSON, 매칭·유효성·품질 감사 기록.
- `data/public-buildings/verification-20261026/`: 네 가지 실제 요청·응답과 `summary.json`.
- 같은 검증 폴더의 `known-height-diagnostic.json`, `known-height-shadow-intersection.geojson`: 부분 모델 수치와 경로 교차 형상.

`data/`와 `.env`는 Git에서 제외된다. 키 원문이 추적/추가 대상 파일에 없음을 검사했다.

Python 준비 테스트 21개가 통과했다. 키 제거, 캐시 훼손, WFS 경계 중복·잘림, 실제 페이지 크기 차이, HUB 누락·내용 변화, 모호한 매칭, 출처 보존과 층수 추정 방지를 포함한다. 기존 CI의 오프라인 테스트 탐색에 포함되며 실제 키가 필요 없다. Windows OSM C++ 라이브러리의 한글 임시 경로 제한으로 영문 임시 경로를 사용했다.

실제 PostGIS의 별도 임시 샘플에서 잘못된 교차 도형 1개 격리·정상 1개 유지도 확인했다. 합성 샘플은 서비스에 적재하지 않았다. 이번 추가 변경은 Python 수집/검증과 문서이며 JVM 소스를 추가 변경하지 않았다. 앞선 JVM 검증은 단위/API 92개 + PostGIS 통합 29개, 총 121개 통과였다. 이번에는 실제 JVM 신규/반복 적재, 활성 도형·출처 검사, 재시작 후 경로 API 네 경우를 검증했다.
