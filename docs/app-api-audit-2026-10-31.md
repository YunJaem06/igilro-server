# Flutter–서버 API 대조 (2026-10-31)

Flutter `/Users/yunjae/AndroidStudioProjects/igilro`의 현재 소스와 서버 `a40f222`를 대조했다. Flutter HEAD `4aefc9da4d2a1262dc015f9b2c22f68ad94346b2`가 GitHub main과 같음을 읽기 전용으로 확인했다. 앱에 이미 있던 홈·위치 관련 미커밋 변경사항도 현재 소스에 포함해 확인했으며 앱 파일은 수정하지 않았다.

## 키 준비 상태

- 서버 `.env`의 `KAKAO_REST_API_KEY`, `VWORLD_API_KEY`, `BUILDING_HUB_API_KEY`가 모두 설정되어 있다. 각 공급자의 실제 제한 조회 성공은 [서버 검증 기록](server-review-2026-10-31.md)에 있다. 현재 구현한 공급자 연동에 추가 키는 필요하지 않다.
- Flutter `config/development.local.json`에는 별도의 `MAPBOX_ACCESS_TOKEN` 공개 토큰이 있으며 AppConfig가 요구하는 형식과 일치한다. APP_ENV는 development, API_BASE_URL은 `http://localhost:8080`이다. 토큰 원문은 출력하지 않았다. 이번에는 Mapbox SDK의 실제 지도 표시·실기기 동작까지 다시 검증하지 않았다.
- 서버의 `SHADE_PREDICTION_FILE`과 `SHADE_GROUND_VERIFIED_DATASETS`는 비어 있다. 이 둘은 키가 아니라 장면 파일 위치와 검증 완료 그래프 설정이다.

## 앱이 실제 호출하는 네 API

앱 DI는 실제 RemoteDataSource·Repository를 사용한다. 네 URL·HTTP 메서드는 실행 중인 서버 OpenAPI에도 모두 존재하며, 이번 확인에서 미구현 URL의 404는 없었다.

| Flutter 기능 / 요청 | 현재 8080 응답 | 판정 |
| --- | --- | --- |
| 장소 검색 `GET /api/v1/places/search?query=서울숲` | 200, 실제 결과 10개 | 구현·실제 카카오 연동 정상 |
| 일반 도보 `POST /api/v1/routes/search`, fastest | 200, 800.8m / 573초, guidance 19단계 | 구현·실제 보행 그래프 정상 |
| 모델 범위 `GET /api/v1/routes/predictions/coverage` | 503 PREDICTION_DATA_UNAVAILABLE | 구현됨, 실제 장면 파일 미설정 |
| 모델 경로 `POST /api/v1/routes/predictions/search`, fastest/shade | 두 모드 모두 503 PREDICTION_DATA_UNAVAILABLE | 구현됨, 실제 장면 파일 미설정 |

모든 JSON 응답의 `X-Request-Id`를 확인했다. 직접 HTTP 호출과 별도로 앱의 실제 Dio → RemoteDataSource → DTO → Mapper → Repository 코드를 임시 Dart 스크립트에서 실행했다. 장소와 일반 경로는 정상 Domain 결과로 변환됐고, 두 모델 API는 위 code와 requestId가 보존된 Failure로 변환됐다. 앱 저장소에 테스트용 코드를 추가하지 않았으며 실제 장소 응답을 파일로 보관하지 않았다.

앱 `lib/features/route_search/presentation/providers/route_search_kind_notifier.dart:10`의 기본값은 prediction이다. 따라서 현재 Mac의 기본 ‘그늘길’은 위 두 모델 API의 503으로 검색 범위·출입 지점·경로 비교를 사용할 수 없다. 일반 ‘도보’ 검색과 장소 검색의 정상 동작과 구분한다.

## 별도로 남은 기능

1. **실제 모델 자료 준비.** `.env`, `data/`와 DB 볼륨은 Git으로 전달되지 않았다. 이 Mac에는 보행 데이터셋 1개(5,733노드·6,268간선)만 있고 건물 데이터셋·활성 건물 지역은 모두 0개다. 모델 예측은 별도의 `scene.json`과 같은 폴더의 체크섬 일치 `roads.osm`이 필요하다. 기존 준비 PC의 두 파일을 복사하거나 [모델 장면 준비](model-based-shade-prediction.md#데이터-준비와-실행)를 실행하고 SHADE_PREDICTION_FILE을 설정한 후 서버를 재시작해야 한다. 일반 직접 높이 건물 DB가 없어도 이 별도 모델 API는 준비한 장면으로 동작할 수 있다.
2. **직접 높이 기반 shade.** 일반 `/api/v1/routes/search`의 shade도 수동 호출해 503 SHADE_DATA_UNAVAILABLE를 확인했다. 서버 최적화 기능은 구현됐으나 완전한 건물·높이·주변 상한·평가 범위와 지상 보행 맥락 검증이 필요하다. 현재 Flutter의 일반 도보 요청은 fastest만 사용한다. 키 설정만으로 이 검증을 통과한 것으로 취급하지 않는다.
3. **서버 길안내의 앱 연결.** 서버는 두 경로 API의 `routes[].guidance`에 회전·횡단·계단 안내를 제공한다. 현재 앱의 `RouteCandidateDto`, `PredictedRouteDto`와 관련 Domain에는 guidance가 없어서 추가 응답 필드로 무시한다. 기존 앱 안내는 geometry·GPS 기반 남은 거리·진행률·방향과 수동 재검색을 사용한다. 서버 단계별 안내를 보이려면 nullable guidance DTO·Domain·Mapper 및 안내 UI 연결이 필요하다. 앱 README 97행의 ‘현재 API에는 회전 지시 데이터가 없습니다’는 최신 서버와 달라 문서 갱신도 필요하다.
4. **향후 서비스 모드.** 현재 서버는 walking과 fastest/shade만 받는다. sunlight 요청을 실제 호출해 400 INVALID_ROUTE_QUERY를 확인했다. 햇빛·러닝·조용함·밝기·비 회피, 복수 조건·가중치는 후속 구현이다. quietScore·lightingScore·trafficSignalCount는 현재 응답에서 null이다. 이는 누락 키로 활성화되는 기능이 아니다.
5. **초기 기획의 상세·저장 API.** 장소 상세, 경로 ID 재조회·저장용 endpoint는 현재 서버에 없으며 현재 앱도 호출하지 않는다. 이후 기능 요구와 공급자 저장 조건에 맞춰 별도 계약이 필요하다.

## 확인 범위와 다음 순서

이번에는 API·데이터 준비 상태를 확인했다. 대규모 공공 데이터 수집, 장면 생성, 건물 DB 적재, 앱 수정·기기 실행은 진행하지 않았다. 기존 서버의 health는 200 UP이었다.

현재 앱 기본 기능을 복구하는 첫 작업은 실제 서울숲 모델 장면 확보·설정이다. 이후 coverage와 fastest/shade를 앱의 실제 클라이언트로 재검증하고, 서버 guidance를 Flutter DTO·안내 화면에 연결한다. 다른 환경 모드와 직접 높이 shade의 검증 자료 확보는 별도 작업으로 진행한다.

## 후속 Flutter 소스·테스트 점검

같은 Flutter 프로젝트의 미커밋 위치 버튼 변경과 테스트까지 포함해 `flutter analyze --no-pub` 및 `flutter test --no-pub --reporter expanded`를 실행했다. 정적 분석 문제는 없었고 단위·위젯 테스트 322개가 모두 통과했다. 새 홈 위치 테스트의 지연 성공·실패 처리도 포함된다. 앱 소스와 기존 미커밋 변경사항은 수정하지 않았다. Mapbox·GPS의 네이티브 integration_test는 이번에 기기에서 실행하지 않았다.

추가로 실제 홈의 `PlacePickerSheet` 검색 키 처리가 빠져 있음을 확인했다. `RouteInputBar`는 이 시트를 열지만 해당 TextField(78~88행)에는 `textInputAction: TextInputAction.search`와 `onSubmitted`가 없다. 별도 `PlaceSearchPage`에는 두 처리가 있어 해당 페이지 테스트는 통과해도 홈의 시트 동작까지 보장하지 않는다.

임시 외부 위젯 테스트로 홈 시트에 검색어를 입력하고 키보드 검색 액션을 보냈다. 즉시 공급자 호출은 0회, 349ms까지 0회, 350ms 후 1회였다. 따라서 현재 시트는 검색 키를 눌러도 debounce를 건너뛰지 않는다. 시트에도 조합 완료 입력을 갱신한 후 `PlaceSearchNotifier.search()`를 호출하도록 연결하고 실제 홈 시트를 대상으로 즉시 제출 테스트를 추가해야 한다. 진단 테스트는 합성 Repository를 사용했고 외부 API를 호출하지 않았다.
