# sdk-kotlin-time

사용자가 기기 시계를 바꿔도 흔들리지 않는 **정확한 현재 시각**을 얻는 Android 라이브러리다.
NTP → HTTP `Date` → (허락하면) 기기 시계 순서로 시각을 얻는다. 한 번 맞춘 값은 **단조 시계**(`elapsedRealtime`) 기준으로
보관하므로 그 뒤에는 네트워크 없이 계산만 한다. 결과에는 출처·오차 추정·기기 시계로 물러났는지가 함께 담긴다.

| 좌표 | 형태 | 상태 |
|---|---|---|
| `net.plumt.time:time` | aar | 0.1.0 |

## 설치

```kotlin
dependencies {
    implementation("net.plumt.time:time:0.1.0")
}
```

- 최소 요구: Android minSdk 23, JVM 11 바이트코드. 런타임 의존은 `kotlin-stdlib`·`kotlinx-coroutines-android`·`androidx.annotation` 뿐이다.
- 매니페스트 병합으로 `INTERNET`(NTP·HTTPS 조회)·`ACCESS_NETWORK_STATE`(조회 전 연결 판단) 권한을 넣는다. 그 밖의 권한·컴포넌트는 넣지 않는다.
- ProGuard·R8 규칙을 따로 적지 않는다(리플렉션·직렬화를 쓰지 않는다).
- `epochMillis` 는 모든 API 레벨에서 쓴다. `java.time` 변환은 아래 [API 레벨별 동작](#api-레벨별-동작) 을 본다.

## 사용 예

### 한국 기본값

```kotlin
// 앱 전체에서 하나만 만든다(Application 이나 DI 싱글턴)
val clock: TrustedClock = TrustedClock.create(context)   // 한국 서버, Asia/Seoul

// 앱 시작 때 한 번 맞춰 두면 이후 now() 는 네트워크를 타지 않는다
scope.launch { clock.sync() }

when (val result = clock.now()) {         // suspend. 메인 스레드에서 불러도 된다
    is TimeResult.Success -> {
        val reading = result.reading
        reading.epochMillis               // UTC epoch 밀리초
        reading.toLocalDateTime()         // 2026-11-03T21:00 (서울)
        reading.origin                    // NTP · HTTP_DATE · CUSTOM · DEVICE_CLOCK
        reading.uncertaintyMillis         // 오차 추정(±ms). 기기 시계면 null
        reading.deviceClockOffsetMillis   // 이 시각 − 기기 시계. 사용자가 시계를 바꿨는지 볼 때
    }
    is TimeResult.Failure -> showRetry(result.failure.reason)
}
```

### 콜백(코루틴 없이·Java)

```kotlin
val handle = clock.now { result ->        // 메인 스레드에서 한 번 온다
    if (result is TimeResult.Success) render(result.reading)
}
handle.cancel()                           // 화면이 사라지면 취소 — 콜백을 부르지 않는다
```

```java
TimeCallHandle handle = clock.now(result -> { /* 메인 스레드 */ });
```

### 해외 서비스·다른 시간대

```kotlin
val config = TimeConfig.builder()
    .ntpServers(TimeServers.GLOBAL_NTP)          // time.cloudflare.com, time.google.com, pool.ntp.org
    .httpDateUrls(TimeServers.GLOBAL_HTTP)
    .zone("America/New_York")                    // 또는 zone(ZoneId.of(...)) — API 26 미만은 desugaring
    .build()
val clock = TrustedClock.create(context, config)
```

### 실패 처리

```kotlin
// 1) 기본 정책(DeviceClock): 실패해도 값은 나온다. 믿을 수 있는지 확인한다
val r = (clock.now() as TimeResult.Success).reading
if (r.isDeviceFallback) {
    // 사용자가 바꿀 수 있는 기기 시계다. 출석·쿠폰 만료 같은 판정에 쓰지 않는다
    log("기기 시계 사용: ${r.failure?.reason}")
} else if (r.isStale) {
    // 갱신은 실패했지만 지난 동기화를 단조 시계로 이어 셌다 — 오차는 r.uncertaintyMillis
}

// 2) 기기 시계를 절대 쓰지 않는 판정용 시계
val strict = TrustedClock.create(context, TimeConfig.builder().fallbackPolicy(FallbackPolicy.None).build())
when (val result = strict.now()) {
    is TimeResult.Success -> judge(result.reading)
    is TimeResult.Failure -> {
        val reason = result.failure.reason
        when (reason) {
            FailureReason.NO_NETWORK -> showOffline()
            FailureReason.TIMEOUT -> showRetry()
            else -> showServerProblem()            // FailureReason 은 값이 늘 수 있다 — else 를 둔다
        }
        result.failure.attempts.forEach { log("${it.source}: ${it.reason}") }   // 출처별 원인
    }
}
```

`TimeResult`·`FallbackPolicy` 는 닫힌 집합(`sealed`)이라 `else` 없이 다룬다. `FailureReason`·`TimeOrigin` 은 값이 늘 수 있는
상수 클래스라 `else` 를 둔다. 오류 전체는 [ERRORS.md](ERRORS.md).

### 단위 테스트

```kotlin
val clock = FakeTrustedClock(1_793_707_200_000L)   // 기기·네트워크 없이
clock.advanceBy(60_000)
clock.failWith(FailureReason.NO_NETWORK)          // 이후 now()·sync() 가 Failure
```

`FakeTrustedClock` 은 같은 아티팩트에 들어 있다. 콜백 형태는 부른 스레드에서 바로 결과를 준다.

## NTP 서버 지역과 시간대는 별개다

NTP 서버는 어느 나라에 있든 **UTC** 를 준다. 서버를 한국에 가까운 곳으로 고르는 이유는 왕복 지연이 짧아 오차가 작기
때문이지, 한국 시각을 받기 위해서가 아니다. 결과를 몇 시로 보여 줄지는 `TimeConfig.Builder.zone(...)` 이 정하고, 서버 목록과 무관하다.

| 하고 싶은 것 | 바꿀 것 |
|---|---|
| 해외 사용자가 많아 응답이 늦다 | `ntpServers(TimeServers.GLOBAL_NTP)`·`httpDateUrls(TimeServers.GLOBAL_HTTP)` |
| 화면에 다른 나라 시각을 보인다 | `zone(...)` 또는 `reading.toZonedDateTime(zone)` |
| 사내 시각 서버를 쓴다 | `ntpServers(listOf("ntp.example.com"))` 또는 `sources(...)` |

기본 목록:

| 상수 | 값 |
|---|---|
| `TimeServers.KOREA_NTP`(기본) | `time.kriss.re.kr`(한국표준과학연구원), `time.google.com`, `kr.pool.ntp.org` |
| `TimeServers.GLOBAL_NTP` | `time.cloudflare.com`(애니캐스트), `time.google.com`, `pool.ntp.org` |
| `TimeServers.KOREA_HTTP`(기본) | `https://www.google.com`, `https://www.naver.com` |
| `TimeServers.GLOBAL_HTTP` | `https://www.google.com`, `https://www.cloudflare.com` |

- `time.google.com` 은 윤초를 하루에 걸쳐 나눠 반영한다(leap smear). 윤초 전후에는 다른 서버와 최대 0.5초 다를 수 있다.
- NTP 풀(`pool.ntp.org`)은 많은 기기에 배포하는 소프트웨어에 자기 [vendor zone](https://www.ntppool.org/vendors.html) 을 쓰라고 권한다.
  사용자가 많은 앱은 vendor zone 을 받아 `ntpServers` 로 바꾸는 것을 권한다.
- UDP 123 은 일부 통신사·사내망이 막는다. 그때는 HTTPS `Date`(초 단위, 오차 0.5초 이상)로 맞춘다.

## 동작

- **오프셋 보관**: 서버 시각과 그 순간의 단조 시계 값을 한 쌍으로 보관한다. 지금 = 서버 시각 + (지금 단조 시계 − 기준점).
  기기 시계를 바꿔도 단조 시계는 튀지 않으므로 값이 그대로다. NTP 조회 자체도 기기 시계를 쓰지 않는다(요청에 난수를 넣고
  서버 시각을 `T3 + 지연/2` 로 구한다).
- **갱신 주기**: `refreshIntervalMillis`(기본 6시간)가 지나면 `now()` 가 다시 맞춘다. 실패하면 `maxAgeMillis`(기본 24시간)까지는
  지난 동기화를 `isStale = true` 로 쓴다. 오차 추정은 경과 시간 × 100ppm 만큼 는다.
- **재부팅**: 부팅 ID 가 다르거나 단조 시계가 기준점보다 작으면 보관한 값을 버린다. Android 는 `Settings.Global.BOOT_COUNT`(API 24+)로
  부팅을 가린다. API 23 은 부팅을 식별할 수 없어 앱 재시작 뒤에는 되살리지 않는다(같은 프로세스 안에서는 그대로 쓴다).
- **동시 호출**: 여러 곳에서 동시에 `now()`·`sync()` 를 불러도 조회는 한 번 나가고 결과를 나눠 받는다.
- **실패 대기**: `now()` 의 자동 동기화가 실패하면 30초부터 두 배씩(상한 30분) 다시 묻지 않고 바로 물러난다. `sync()` 는 대기를 무시한다.
  연결 없음(`NetworkMonitor` 판단)은 네트워크를 건드리지 않았으므로 대기를 걸지 않는다.
- **응답 검사**: 원본 시각 불일치(다른 요청의 응답·위조), 모드·버전, stratum 0(KoD)·16 이상, LI 3, 빈 시각, 거꾸로 된 시각,
  루트 거리 1.5초 초과, 왕복 상한(기본 1.5초) 초과, 2025 ~ 2200 년 밖의 시각을 버린다. KoD 를 보낸 서버는 1시간 쉰다.
- **비차단 조회**: `peek()` 은 보관한 값만으로 계산하고 없으면 `null`. `asJavaClock()` 은 `java.time.Clock` 으로 감싼다(네트워크를 기다리지 않는다).

| `FallbackPolicy` | 유효한 동기화 없음 + 새 동기화 실패 시 |
|---|---|
| `DeviceClock`(기본) | 지난 동기화 → 기기 시계(`isDeviceFallback = true`) |
| `LastSyncOnly` | 지난 동기화까지만. 없으면 `TimeResult.Failure` |
| `None` | 바로 `TimeResult.Failure` |

오류 전체는 [ERRORS.md](ERRORS.md).

## kotlinx-datetime

의존을 늘리지 않으려고 대응 모듈을 두지 않았다. `epochMillis`·`zoneId` 로 한 줄이면 바꾼다.

```kotlin
val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(reading.epochMillis)
val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.of(reading.zoneId))
```

## API 레벨별 동작

| API | 동작 |
|---|---|
| 23 | 부팅을 식별할 수 없다(`Settings.Global.BOOT_COUNT` 없음). 같은 프로세스 안에서는 보관한 값을 쓰지만 앱 재시작 뒤에는 되살리지 않고 다시 동기화한다 |
| 24 | `BOOT_COUNT` 로 부팅을 가려 앱 재시작 뒤에도 같은 부팅이면 동기화 없이 정확한 시각을 낸다 |
| 26 | `java.time`(`toInstant`·`toZonedDateTime`·`toLocalDateTime`·`asJavaClock`·`zone(ZoneId)`)을 desugaring 없이 쓴다. 25 이하는 앱이 [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) 을 켜야 한다. `epochMillis`·`zoneId` 와 내부 계산(NTP·HTTP 날짜 해석)은 `java.time` 을 쓰지 않는다 |
| 28 | 기본 정책이 평문 HTTP 를 막는다. 라이브러리는 기본으로 `https` 만 쓰므로 영향이 없다(`httpDateAllowingCleartext` 를 쓰면 앱의 네트워크 보안 설정이 필요하다) |
| 36 | `compileSdk`·검증 기준. 동작 차이 없음 |

새 Android 버전이 나오면 이 표부터 고친다.

## 알려진 한계

- SNTP 는 인증이 없다(NTS 미지원). 네트워크를 장악한 공격자(DNS·라우팅)는 NTP 응답을 위조할 수 있다 — 중요한 판정은 서버가 서버 시각으로 한다.
- 루팅·후킹한 기기에서는 앱 프로세스 자체를 바꿀 수 있어 막지 못한다.
- API 23 은 앱 재시작마다 다시 동기화한다(위 표).
- HTTP `Date` 출처는 초 단위라 오차가 0.5초 이상이다. UDP 123 이 막힌 망에서만 쓰인다.
- 단조 시계의 주파수 편차를 100ppm 으로 가정해 오차 추정에 더한다. 실제 편차가 더 큰 기기에서는 오차 추정이 작게 나온다.
- 리플렉션·비공개 API 는 쓰지 않는다. `BOOT_COUNT` 를 못 읽으면 부팅 ID 를 `null` 로 두고 재시작 뒤 되살리지 않는다(폴백).
- 콜백 형태의 진행 중 호출은 프로세스와 함께 사라진다. 결과를 저장하지 않으므로 화면 회전 뒤에는 다시 부른다(조회는 한 번만 나가고 결과를 나눠 받는다).

## 확장

| 지점 | 무엇을 바꾸나 | 기본 |
|---|---|---|
| `TimeSource` + `TimeConfig.Builder.sources(...)` | 시각 출처(사내 서버·다른 프로토콜). `TimeSources.ntp(host, port)`·`TimeSources.httpDate(url)` 를 섞어도 된다 | NTP 3개 + HTTPS 2개 |
| `SyncStateStore` | 오프셋 저장소(`SyncStateStore.inMemory()`·`sharedPreferences(context, name)`) | SharedPreferences `net.plumt.time.sync` |
| `MonotonicClock` | 단조 시계·부팅 ID(시험용) | `elapsedRealtime` + `BOOT_COUNT` |
| `WallClock` | 기기 시계(폴백·차이 계산) | `System.currentTimeMillis` |
| `NetworkMonitor` | 조회 전 연결 판단(`NetworkMonitor.assumeAvailable()` 은 판단을 조회에 맡긴다) | `ConnectivityManager` |
| `dispatcher(...)` | 블로킹 조회를 돌릴 디스패처 | `Dispatchers.IO` |

`TimeSource.query` 는 블로킹이다. 예상되는 실패는 `TimeSourceResult.Failure(reason, message)` 로 돌려주고, 던진 예외(`IOException` 등)는 라이브러리가 원인별로 나눈다.

## 위협 모델

| 막는 것 | 방법 |
|---|---|
| 사용자가 기기 시계·시간대를 바꿈 | 단조 시계 기준 오프셋. 기기 시계는 폴백일 때만 쓰고 결과에 표시한다 |
| 다른 요청의 응답·단순 위조 NTP 패킷 | 요청마다 난수를 넣고 응답의 원본 시각과 맞춘다. 소켓을 서버 주소에 connect 한다 |
| 공유기·캡티브 포털의 HTTP 응답 바꿔치기 | 기본은 `https` 만 받는다 |
| 손상·엉뚱한 응답 | 형식·stratum·LI·루트 거리·왕복·시각 범위 검사 |

| 막지 못하는 것 | 이유 |
|---|---|
| 네트워크를 장악한 공격자(DNS·라우팅)의 NTP 위조 | SNTP 는 인증이 없다(NTS 미지원). 중요한 판정은 서버가 서버 시각으로 한다 |
| 루팅·후킹한 기기 | 앱 프로세스 자체를 바꿀 수 있다 |

로그를 남기지 않는다. 예외 메시지에는 출처 이름(호스트·URL)과 응답 일부(`Date` 헤더 앞 64자)만 담는다. URL 에 사용자 정보(`user@`)를 넣으면 거부한다.

## 스레드

모든 공개 함수는 스레드 안전하다. `now()`·`sync()` 는 suspend 이고 소켓 조회는 지정한 디스패처에서 돈다. 콜백 형태는 결과를
메인 스레드에서 준다. `peek()`·`asJavaClock()` 은 블로킹하지 않는다. 조회 도중 코루틴이 취소되면 진행 중인 소켓 대기(최대 `timeoutMillis`)가 끝난 뒤 취소되고 그 결과는 버린다.

## 버전

SemVer. `time/gradle.properties` 의 `VERSION_NAME`, 태그는 `time-v<버전>`(예: `time-v0.1.0`). 1.0 전에는 마이너 상승이 호환을 깨뜨릴 수 있고
바뀐 점은 [CHANGELOG.md](CHANGELOG.md) 의 "깨짐" 에 적는다.
공개 API 는 `.api` 파일로 고정하고 `./gradlew build` 가 `apiCheck` 로 검사한다.

## 라이선스

[Apache License 2.0](LICENSE)
