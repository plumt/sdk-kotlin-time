# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 모듈별로 적는다.

## time

예외는 두 종류다. 인자가 틀리면 `IllegalArgumentException`(받은 값과 허용 범위를 메시지에 담는다), 시각을 얻지 못하면
`TimeSyncException`(`RuntimeException`, 원래 예외는 `cause`). 끼운 `SyncStateStore` 가 던진 예외만은 감싸지 않고 그대로 전파한다.
코루틴 취소는 `CancellationException` 으로 전파한다.

### 인자 오류 — `IllegalArgumentException`

| 어디 | 언제 |
|---|---|
| `TimeSources.ntp` · `Builder.ntpServers` | 호스트가 비었거나 공백·`/` 를 담았거나 253자 초과, 포트가 1..65535 밖 |
| `TimeSources.httpDate` · `Builder.httpDateUrls` | URL 형식 오류, 스킴이 `https`(평문 허용 시 `http` 포함)가 아님, 호스트 없음, 사용자 정보(`user@`) 포함 |
| `Builder.sources` | 빈 목록, `TimeOrigin.DEVICE_CLOCK` 출처 |
| `Builder.zone(String)` | `java.util.TimeZone` 이 모르는 ID(대소문자 구분) |
| `Builder.timeoutMillis` | 100..60000 밖 |
| `Builder.maxRoundTripMillis` | 1..60000 밖 |
| `Builder.refreshIntervalMillis` · `maxAgeMillis` | 1초..30일 밖 |
| `Builder.build` | 출처가 하나도 없음(NTP·HTTP 목록이 모두 빔), `maxAgeMillis < refreshIntervalMillis` |
| `TimeSample` · `SyncSnapshot` 생성자 | 음수 왕복·오차, `SyncSnapshot` 의 `DEVICE_CLOCK` 출처 |

### 시각 실패 — `TimeSyncException.reason`

출처 하나의 실패는 `source` 에 이름을 담는다. 모든 출처를 시도한 끝의 실패는 `failures` 에 시도별 실패를 순서대로 담고
`cause` 는 첫 실패다. 묶음의 `reason` 은 모든 시도의 원인이 같으면 그 원인, 섞이면 `ALL_SOURCES_FAILED` 다.

| `FailureReason` | 언제 | 호출부가 할 일 |
|---|---|---|
| `NO_NETWORK` | `NetworkMonitor` 가 연결 없음(이때 `failures` 는 비고 대기도 걸지 않는다), 또는 모든 시도가 `NoRouteToHostException`·"Network is unreachable" | 연결이 돌아오면 `sync()` |
| `TIMEOUT` | 응답이 `timeoutMillis` 안에 오지 않음(`SocketTimeoutException`) | 다시 시도, 망 확인 |
| `HOST_NOT_FOUND` | DNS 실패(`UnknownHostException`) | 호스트 이름 확인 |
| `CONNECTION_FAILED` | 연결 거부·포트 도달 불가·그 밖의 `IOException` | 서버·방화벽 확인 |
| `KISS_OF_DEATH` | NTP stratum 0 + 대문자·숫자 4자 코드(`RATE`·`DENY`·`RSTR` 등). 그 서버는 1시간 묻지 않는다(쉬는 동안의 시도도 이 원인) | 다른 서버를 목록에 둔다 |
| `INVALID_STRATUM` | stratum 0 인데 코드가 아님, stratum 16 이상 | 다른 서버 |
| `SERVER_UNSYNCHRONIZED` | NTP LI = 3 | 다른 서버 |
| `INVALID_RESPONSE` | 48바이트 미만, 모드 ≠ 4, 버전 1..4 밖, 원본 시각이 요청 난수와 다름, 수신·전송 시각 0, 전송 < 수신, 루트 거리 1.5초 초과, 끼운 출처의 기준점이 미래 | 다른 서버 |
| `ROUND_TRIP_TOO_LONG` | 왕복 > `maxRoundTripMillis`(같으면 받는다) | 가까운 서버, 상한 조정 |
| `IMPLAUSIBLE_TIME` | 서버 시각이 2025-01-01T00:00Z ~ 2200-01-01T00:00Z 밖(경계는 받는다) | 서버 확인 |
| `HTTP_DATE_MISSING` | 응답에 `Date` 헤더 없음 | 다른 URL |
| `HTTP_DATE_INVALID` | `Date` 를 IMF-fixdate·RFC 850·asctime 으로 읽지 못함(요일 불일치·없는 날짜·64자 초과 포함). 메시지에 헤더 앞 64자 | 다른 URL |
| `SOURCE_ERROR` | 끼운 `TimeSource` 가 `IOException` 이 아닌 예외를 던짐(`cause` 가 원래 예외) | 구현을 고친다 |
| `ALL_SOURCES_FAILED` | 원인이 섞인 묶음 실패 | `failures` 를 본다 |
| `NOT_SYNCED` | `asJavaClock()` 에서 쓸 동기화가 없고 정책이 기기 시계를 막음 | 먼저 `sync()` |

끼운 `TimeSource` 가 `source` 없이 던진 `TimeSyncException` 은 같은 `reason` 으로 출처 이름을 붙여 다시 싸고 원래 예외를 `cause` 로 둔다.

### 어떤 함수가 무엇을 던지나

| 함수 | 던지는 것 |
|---|---|
| `now()` | 정책이 `LAST_SYNC_ONLY`·`NONE` 이고 쓸 값이 없을 때 `TimeSyncException`. `DEVICE_CLOCK` 정책에서는 던지지 않는다 |
| `sync()` | 실패하면 정책과 무관하게 `TimeSyncException`. 동시에 부른 호출은 같은 예외 객체를 받는다 |
| `peek()` · `invalidate()` | 던지지 않는다(저장소 예외 제외) |
| `asJavaClock().millis()` · `instant()` | `NOT_SYNCED` |

### 값으로 알리는 신호

| 신호 | 뜻 |
|---|---|
| `TimeReading.isDeviceFallback` | 기기 시계로 물러났다. `uncertaintyMillis`·`sourceName` 은 `null`, `failure` 에 이유 |
| `TimeReading.isStale` | 갱신 주기를 넘긴 지난 동기화로 계산했다. `failure` 에 마지막 실패 |
| `peek() == null` | 보관한 값이 없음·최대 보관 기간 초과·재부팅으로 무효 |
| 저장소에서 되살리지 않음 | 부팅 ID 가 다르거나 `null`, 단조 시계가 기준점보다 작음, 보관 기간 초과 — 저장소를 지운다 |

## time-android

| 어디 | 무엇 |
|---|---|
| `AndroidTime.preferencesStore` | 이름이 비었거나 `/` 를 담으면 `IllegalArgumentException` |
| 저장된 값이 깨짐(형식 버전·타입·빠진 키·모르는 출처·음수 오차) | 예외 없이 저장소를 지우고 `load()` 가 `null` — 다음 `now()` 가 다시 동기화한다 |
| `networkMonitor` 가 권한(`ACCESS_NETWORK_STATE`) 없이 불림 | `SecurityException` 을 삼키지 않고 판단만 미룬다 — `true` 를 돌려 실제 조회 결과(원인별 `TimeSyncException`)로 드러난다 |
| `INTERNET` 권한이 지워짐 | 소켓이 거부돼 `CONNECTION_FAILED`(Android 버전에 따라 `SOURCE_ERROR`) |
| API 23 | 부팅 ID 가 `null` 이라 앱 재시작 뒤 오프셋을 되살리지 않는다(오류 아님) |
