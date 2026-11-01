# 경로 검색 요청 제한·동시성 제어

일반 `POST /api/v1/routes/search`와 모델 `POST /api/v1/routes/predictions/search`가 하나의 프로세스별 실행 예산을 공유한다. fastest/shade 모두 적용하며 API 요청·성공 응답 DTO와 경로 계산 모델은 유지한다.

## 설정

| 환경 변수 | 기본값 | 허용 범위 | 의미 |
| --- | ---: | --- | --- |
| `ROUTE_SEARCH_REQUESTS_PER_SECOND` | 4 | 1~1000 정수 | 초당 연속 보충하는 token 수 |
| `ROUTE_SEARCH_BURST_CAPACITY` | 4 | 1~1000 정수 | 저장할 최대 token 및 즉시 수용 가능한 요청 예산 |
| `ROUTE_SEARCH_MAX_CONCURRENT` | 2 | 1~64 정수 | 두 API를 합한 동시 계산 수 |

유효하지 않은 값은 기동 시 실패한다. local은 `.env` 또는 OS 환경 변수로, dev/prod는 배포 환경으로 주입한다. 변경 후 서버 재시작이 필요하다. 기본 burst는 Flutter의 즉시 fastest/shade 비교를 허용한다. 동시 한도와 기본값은 부하 성능 보장이 아니며 실제 CPU·메모리·요청 지연 측정에 맞춰 조정한다.

`GET /api/v1/routes/predictions/coverage`, health, readiness, OpenAPI, 그래프/건물 import는 이 예산을 사용하지 않는다. 장소 검색은 기존의 독립된 한도를 유지한다. IP·사용자 식별자나 X-Forwarded-For로 분기하지 않는다. 여러 앱 사용자가 한 프로세스의 한도를 공유하며 복제 수가 늘면 전체 허용량도 늘어난다. 서비스 전체·사용자별 제한이 필요하면 별도의 공통 제어 계층이 필요하다.

## 수용·거절 규칙

1. MVC의 JSON/필드 검증과 UseCase의 기본 mode·travelMode·동일 좌표 검증을 먼저 수행한다.
2. token이 1개 미만이면 `429 TOO_MANY_REQUESTS`로 거절한다.
3. token이 있어도 실행 슬롯이 모두 사용 중이면 `503 ROUTE_SEARCH_BUSY`로 거절한다. 두 조건이 동시에 부족하면 429가 우선한다.
4. 수용 시 token 1개와 슬롯 1개를 함께 확보한다. DB 그래프 조회, 탐색, 그늘 측정·최적화·구간 추적과 길안내 계산을 같은 슬롯 안에서 실행한다.
5. 성공·업무 실패·예상 밖 예외·Error에 모두 `finally`로 슬롯을 반환한다. 수용한 요청의 token은 실패해도 환불하지 않는다. 거절한 요청은 token과 슬롯을 소비하지 않는다.

Application의 순수 Kotlin/JDK `RouteSearchAdmission`을 두 UseCase에 같은 Spring singleton으로 주입한다. 단조 증가 시간을 사용하고, token 계산은 고정 소수점 정수로 처리해 시간 경계의 부동소수점 누적 오차를 피한다. 잠금은 짧은 수용 판단·반환에만 사용하며 실제 검색 계산은 잠금 밖에서 병렬 실행한다.

대기열·별도 계산 스레드 풀·결과 캐시·DB 테이블은 추가하지 않았다. 계산 중 클라이언트가 연결을 끊어도 실제 계산이 끝나기 전에 슬롯을 반환하지 않는다. 기존 SQL timeout과 모델/탐색의 시간·샘플·label 한도를 유지한다. 이번 변경은 전체 HTTP 요청에 새로운 강제 종료 deadline을 추가하지 않는다.

## HTTP·Flutter 계약

두 거절 응답 모두 `Retry-After: 1`, `Cache-Control: no-store`, `Content-Type: application/json`과 `X-Request-Id`를 제공한다. 현재 설정 범위에서 token 한 개가 보충되는 데 필요한 대기는 초 단위 올림 시 1초다. BUSY의 1초는 재시도 간격 안내이며 슬롯 확보나 성공을 예약하지 않는다.

공통 본문은 `code`, `message`, `requestId`, `timestamp`, `errors`이며 errors는 빈 배열이다. 새 코드는 `ROUTE_SEARCH_BUSY`이고 기존 `ROUTE_SEARCH_LIMIT_EXCEEDED`는 계산 내부의 자원 한도 실패라는 의미를 유지한다. 두 상태를 섞거나 빈 성공 응답으로 대체하지 않는다.

앱의 기존 HTTP/code/requestId 처리는 유지된다. 재시도를 추가할 때는 Retry-After 이후에 수행하고 사용자가 이미 입력을 바꾼 요청을 다시 보내지 않는다. 자동 무한 재시도는 하지 않는다. 데이터 미준비·내부 탐색 한도 초과는 이 수용 거절의 재시도 대상이 아니다. 새 필수 요청 필드나 별도 재탐색 endpoint는 없다.

저장소의 `scripts/verify-model-navigation.py`는 오직 429 TOO_MANY_REQUESTS / 503 ROUTE_SEARCH_BUSY에 대해서만 숫자 1~10초 Retry-After를 따라 최대 2번 재시도한다. 원래 검증 사례가 기대하는 마지막 HTTP·code를 계속 검사한다. elapsedSeconds에는 재시도 대기까지 포함하므로 계산 자체의 성능 수치와 구분한다.

## 자동 검증

- 단조 시간 주입으로 초기 burst, 정확한 보충 경계, 긴 유휴 시간의 상한과 시간 값 wraparound를 검사한다.
- 동시에 실행한 20개 호출로 rate burst 및 슬롯 초과를 검사하고, 거절 요청이 실행 중 작업의 종료를 기다리지 않음을 확인한다.
- 성공·RuntimeException·Error 후 슬롯 반환, 실패의 token 소비와 BUSY의 token 미소비를 검사한다.
- 두 실제 Controller/UseCase를 함께 사용해 공유 예산, 429/503, Retry-After, 공통 오류·requestId, 입력 검증과 coverage 독립성을 검사한다.
- 별도 PostGIS 컨테이너의 Spring 통합 테스트에서 설정 바인딩, 공유 singleton, 실제 경로 검색의 복구, health/readiness/OpenAPI 독립성을 검사한다.
- 기존 경로·건물·OpenAPI 회귀 테스트는 이 정책과 분리하기 위해 test 프로필에서 큰 한도를 사용하며, 전용 제한 테스트는 작은 한도를 명시한다.
- Python 검증 도구는 정상 재시도, 비재시도 업무 오류, 잘못된 Retry-After와 최대 시도 수를 오프라인으로 검사한다.

```bash
./gradlew test integrationTest --no-daemon --console=plain
python -m unittest discover -s scripts/tests
```

## 2026-10-31 실행 결과

- `./gradlew build --no-daemon --console=plain` 성공. 단위/MVC/아키텍처 162개 + 실제 PostGIS 통합 38개 = JVM 200개, 실패·오류·건너뜀 0이다.
- Python 오프라인 테스트 67개 통과. 새 재시도 도구 테스트 4개를 포함한다. 이번 검증은 외부 장소·건물 공급자를 호출하지 않았다.
- 새 JAR를 기존 local 프로필·모델 장면으로 8080에서 재기동했다. 일반 fastest 800.8m/573초, 모델 fastest 800.7m/572초, 모델 shade 865.6m/619초를 확인했다. 변경 직전에 저장한 응답과 매번 새로 생성되는 route ID를 제외한 전체 필드(형상·그늘 구간·안내·버전·평가 시각 포함)가 같았다.
- 기본 설정으로 모델 shade 20개 동시 요청과 이어지는 일반 검색 10개를 보냈다. 성공 4건, 503 ROUTE_SEARCH_BUSY 18건, 429 TOO_MANY_REQUESTS 8건이었다. 모든 거절의 Retry-After·no-store·공통 본문·요청 ID를 검사했고, health/readiness/coverage는 정상이며 1초 대기 후 모델 검색은 200으로 복구됐다. 이 수치는 한 번의 로컬 실행 결과이며 처리량 보장이 아니다.
- `verify-model-navigation.py --bidirectional`의 실제 서울숲 성공 40건과 예상 오류 8건이 모두 통과했다. 경로 선분·그늘 구간·비율·안내·동일 스냅·추가 시간 한도를 확인했다. 새 bounded 재시도가 기본 제한 아래에서 작동했다.
- OpenAPI에서 두 POST 검색의 200/429/503 및 Retry-After 설명을 확인했다. 서버와 기존 개발 DB를 실행 상태로 유지한다.

관측 결과·전후 응답·이전 JAR 백업은 Git 밖의 임시 `igilro-route-limits-20261031-*` 작업 폴더에 있다. API 키 원문은 공유 파일에 추가하지 않았고 기존 미커밋 작업도 보존했다. AWS 배포와 다중 인스턴스의 전체 한도 검증은 수행하지 않았다.
