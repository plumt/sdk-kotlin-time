# 오류와 실패 신호

라이브러리가 던지는 예외와, 예외 대신 값으로 알리는 실패 신호를 적는다.

예상되는 실패(연결 없음·시간 초과·서버 응답 이상)는 던지지 않고 `TimeResult.Failure` 나 `TimeReading.failure` 로 알린다.
던지는 예외는 프로그래머 오류뿐이다 — 틀린 인자(`IllegalArgumentException`), 동기화 전에 엄격한 정책으로 `asJavaClock()` 을
읽음(`java.time.DateTimeException`). 앱이 끼운 `SyncStateStore` 가 던진 예외만은 감싸지 않고 그대로 전파한다.
코루틴 취소는 `CancellationException` 으로 전파한다.

## 인자 오류 — `IllegalArgumentException`

| 어디 | 언제 |
|---|---|
| `TimeSources.ntp` · `TimeConfig.Builder.ntpServers` | 호스트가 비었거나 공백·`/` 를 담았거나 253자 초과, 포트가 1..65535 밖 |
| `TimeSources.httpDate` · `TimeConfig.Builder.httpDateUrls` | URL 형식 오류, 스킴이 `https` 가 아님, 호스트 없음, 사용자 정보(`user@`) 포함 |
| `TimeSources.httpDateAllowingCleartext` | 위와 같고 스킴은 `https`·`http` 만 받는다 |
| `TimeConfig.Builder.sources` | 빈 목록, `TimeOrigin.DEVICE_CLOCK` 출처 |
| `TimeConfig.Builder.zone(String)` | `java.util.TimeZone` 이 모르는 ID(대소문자 구분) |
| `TimeConfig.Builder.timeoutMillis` | 100..60000 밖 |
| `TimeConfig.Builder.maxRoundTripMillis` | 1..60000 밖 |
| `TimeConfig.Builder.refreshIntervalMillis` · `maxAgeMillis` | 1초..30일 밖 |
| `TimeConfig.Builder.build` | 출처가 하나도 없음(NTP·HTTP 목록이 모두 빔), `maxAgeMillis < refreshIntervalMillis` |
| `TimeSample` · `SyncSnapshot` 생성자 | 음수 왕복·오차, `SyncSnapshot` 의 `DEVICE_CLOCK` 출처 |
| `SyncStateStore.sharedPreferences(context, name)` | 이름이 비었거나 `/` 를 담음 |

## 사전 조건 — `java.time.DateTimeException`

| 어디 | 언제 | 할 일 |
|---|---|---|
| `asJavaClock().millis()` · `instant()` | 쓸 동기화가 없고 정책이 `FallbackPolicy.LastSyncOnly`·`FallbackPolicy.None`(지난 동기화는 `None` 만 막는다) | 먼저 `sync()` 가 `Success` 인지 확인한다 |
| `FakeTrustedClock.asJavaClock().millis()` | `failWith` 로 실패 상태 | 시험이 정한 상태다 |

`java.time.Clock` 계약에는 결과 타입이 없어 이 경로만 예외로 알린다.

## 시각 실패 — `TimeFailure.reason`

출처 하나의 실패는 `source` 에 이름을 담는다. 모든 출처를 시도한 끝의 실패는 `attempts` 에 시도별 실패를 순서대로 담는다
(`cause` 는 비어 있다). 묶음의 `reason` 은 모든 시도의 원인이 같으면 그 원인, 섞이면 `ALL_SOURCES_FAILED` 다.
`FailureReason` 은 값이 늘 수 있는 상수 클래스다 — 앱의 `when` 에 `else` 를 둔다.

| `FailureReason` | 언제 | 호출부가 할 일 |
|---|---|---|
| `NO_NETWORK` | `NetworkMonitor` 가 연결 없음(이때 `attempts` 는 비고 대기도 걸지 않는다), 또는 모든 시도가 `NoRouteToHostException`·"Network is unreachable" | 연결이 돌아오면 `sync()` |
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
| `ALL_SOURCES_FAILED` | 원인이 섞인 묶음 실패 | `attempts` 를 본다 |

끼운 `TimeSource` 가 `TimeSourceResult.Failure` 를 돌려주면 그 `reason`·`cause` 를 그대로 두고 메시지 앞에 출처 이름을 붙인다.

## 어떤 함수가 무엇을 돌려주나

| 함수 | 결과 |
|---|---|
| `now()` | 정책이 `FallbackPolicy.DeviceClock`(기본)이면 늘 `Success`(물러났으면 `reading.failure` 에 이유). `FallbackPolicy.LastSyncOnly`·`FallbackPolicy.None` 은 쓸 값이 없을 때 `Failure` |
| `sync()` | 실패하면 정책과 무관하게 `Failure`. 동시에 부른 호출은 같은 `TimeFailure` 객체를 받는다 |
| `now(callback)` · `sync(callback)` | 위와 같은 결과를 메인 스레드에서 한 번. 취소하면 부르지 않는다 |
| `peek()` · `invalidate()` | 던지지 않는다(저장소 예외 제외) |
| `asJavaClock().millis()` · `instant()` | 위 "사전 조건" 표 |

## 값으로 알리는 신호

| 신호 | 뜻 |
|---|---|
| `TimeReading.isDeviceFallback` | 기기 시계(`origin` 이 `DEVICE_CLOCK`)로 물러났다. `uncertaintyMillis`·`sourceName` 은 `null`, `failure` 에 이유 |
| `TimeReading.isStale` | 갱신 주기를 넘긴 지난 동기화로 계산했다. `failure` 에 마지막 실패 |
| `TimeReading.origin` | `NTP`·`HTTP_DATE`·`CUSTOM`(앱이 끼운 출처)·`DEVICE_CLOCK`. 값이 늘 수 있는 상수 클래스다 |
| `peek() == null` | 보관한 값이 없음·최대 보관 기간 초과·재부팅으로 무효 |
| 저장소에서 되살리지 않음 | 부팅 ID 가 다르거나 `null`, 단조 시계가 기준점보다 작음, 보관 기간 초과 — 저장소를 지운다 |

## Android 결합

| 어디 | 무엇 |
|---|---|
| 저장된 값이 깨짐(모르는 형식 버전·타입·빠진 키·모르는 출처·음수 오차) | 예외 없이 저장소를 지우고 `load()` 가 `null` — 다음 `now()` 가 다시 동기화한다 |
| 저장 형식 버전 1(0.0.1) | 읽어서 쓰고 곧바로 버전 2 로 다시 쓴다 |
| 연결 판단이 권한(`ACCESS_NETWORK_STATE`) 없이 불림 | `SecurityException` 을 잡아 `true` 를 돌린다 — 연결 판단을 실제 조회에 넘겨 원인별 `TimeFailure` 로 드러난다 |
| `INTERNET` 권한이 지워짐 | 소켓이 거부돼 `CONNECTION_FAILED`(Android 버전에 따라 `SOURCE_ERROR`) |
| API 23 | 부팅 ID 가 `null` 이라 앱 재시작 뒤 오프셋을 되살리지 않는다(오류 아님) |
| 콜백 형태 | 결과는 `Dispatchers.Main` 에서 온다. 앱이 끼운 저장소가 던진 예외는 콜백 대신 메인 스레드의 처리되지 않은 예외가 된다 |
