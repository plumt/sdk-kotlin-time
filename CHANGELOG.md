# Changelog

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.
1.0 전에는 마이너 상승이 호환을 깨뜨릴 수 있다.

## [Unreleased]

## [0.1.0] - 2026-11-10

0.0.1(태그만 있고 Maven Central 에는 올리지 않았다)을 공개 SDK 규칙에 맞게 다시 짰다.

### 깨짐
- 아티팩트를 `net.plumt.time:time`(aar) 하나로 합쳤다. `net.plumt.time:time-android` 는 없어졌고, 그 기능(단조 시계·부팅 ID·연결 판단·SharedPreferences 저장소)은 `time` 에 들어 있다. 순수 JVM jar 는 더 내지 않는다
- 진입점: `TrustedClock.builder()...build()`·`AndroidTime.clockBuilder(context)` → `TrustedClock.create(context)`·`TrustedClock.create(context, TimeConfig)`. 설정은 불변 `TimeConfig`(빌더·`toBuilder()`)
- `now()`·`sync()` 가 `TimeReading` 대신 `sealed interface TimeResult`(`Success(reading)`·`Failure(failure)`)를 돌려준다. 예상되는 실패를 던지지 않는다
- `TimeSyncException` 제거 → 값 `TimeFailure`(`reason`·`message`·`source`·`cause`·`attempts`). `TimeReading.failure` 의 타입도 `TimeFailure?`
- `FailureReason`·`TimeOrigin` 열거형 → 상수 클래스(`values` 목록, 앱의 `when` 에 `else` 필요). `FailureReason.NOT_SYNCED` 제거(`asJavaClock()` 은 `DateTimeException`)
- `FallbackPolicy` 열거형 → `sealed interface`(`DeviceClock`·`LastSyncOnly`·`None`)
- `TimeSource.query` 가 `TimeSample` 대신 `TimeSourceResult`(`Success(sample)`·`Failure(reason, message[, cause])`)를 돌려준다
- `TimeSources.httpDate(url, allowCleartext)` → `httpDate(url)`·`httpDateAllowingCleartext(url)`. `TimeSources.NTP_PORT`·`TrustedClock.Builder.DEFAULT_*` 상수와 `MonotonicClock.system()` 을 공개 API 에서 뺐다
- `AndroidTime` 제거 → `SyncStateStore.sharedPreferences(context[, name])`, 단조 시계·연결 판단은 `create` 가 끼운다
- `TimeQuery` 생성자를 내부로 돌렸다

### 추가
- 콜백 형태 `now(callback)`·`sync(callback)` — 결과를 메인 스레드에서 한 번 주고 취소 핸들(`TimeCallHandle`)을 돌려준다. suspend 와 같은 내부 로직을 쓴다
- 시험용 `FakeTrustedClock`(시각 지정·흘리기·실패 지정·호출 횟수)
- README 의 API 레벨별 동작 표·알려진 한계, 문서와 코드가 어긋나면 실패하는 `DocsConsistencyTest`
- R8 release 를 만드는 확인용 `sample` 앱(발행하지 않는다)

### 바뀜
- SharedPreferences 저장 형식 버전 2 — 출처를 고정 저장 키로 적는다. 버전 1 값을 읽고 버전 2 로 다시 쓴다
- `TimeServers.*` 목록과 `TimeConfig` 의 목록은 바꿀 수 없는 사본이다(Java 에서 `set` 하면 `UnsupportedOperationException`)
- 공개 인터페이스의 기본 구현을 Java 기본 메서드로만 낸다(`jvmDefault = NO_COMPATIBILITY`)
- 묶음 실패의 `cause` 는 비운다(시도별 원인은 `attempts`)

### 고침
- `BOOT_COUNT` 읽기의 API 분기를 `@RequiresApi` 함수로 격리했다

## [0.0.1] - 2026-11-07

태그(`time-v0.0.1`·`time-android-v0.0.1`)만 있고 Maven Central 에는 올리지 않았다.

### 추가
- `time`(jar)·`time-android`(aar) 초기 구현 — NTP → HTTP `Date` → 기기 시계 폴백, 단조 시계 기준 오프셋, 출처·오차 추정, `FallbackPolicy`, `TimeSyncException` + `FailureReason` 15종, SharedPreferences 오프셋 저장소
