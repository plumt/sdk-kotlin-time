# Changelog

모듈별로 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/), 버전은 [SemVer](https://semver.org/lang/ko/) 이다.

## time

### [Unreleased]

### [0.0.1] - 2026-11-07

#### 추가
- `TrustedClock` — `now()`(필요할 때만 동기화)·`sync()`(즉시 동기화)·`peek()`(비차단)·`invalidate()`·`asJavaClock()`, 빌더 설정
- NTP → HTTP `Date` → 기기 시계 폴백과 `FallbackPolicy`(`DEVICE_CLOCK`·`LAST_SYNC_ONLY`·`NONE`)
- 단조 시계 기준 오프셋 보관, 갱신 주기(`refreshIntervalMillis`)·최대 보관 기간(`maxAgeMillis`), 재부팅 판정(부팅 ID·단조 시계 역행)
- `TimeReading` — epoch 밀리초·`Instant`·`ZonedDateTime`·`LocalDateTime`, 출처(`TimeOrigin`)·오차 추정·나이·stale·기기 시계와의 차이·실패 이유
- 기본 시간대 `Asia/Seoul`, `zone(ZoneId)`·`zone(String)` 으로 변경
- 서버 목록 `TimeServers.KOREA_*`(기본)·`TimeServers.GLOBAL_*`
- `TimeSyncException` + `FailureReason` 15종 — 연결 없음·시간 초과·이름 조회·연결 실패·KoD·stratum·LI 3·응답 이상·왕복 과다·시각 범위·`Date` 없음·`Date` 파싱 실패·끼운 출처 오류·전 출처 실패·미동기화
- SNTP: 난수 원본 시각 검사, 서버 주소 connect, 2036 era 전환, 루트 거리 상한, KoD 서버 1시간 휴식
- HTTP `Date`: IMF-fixdate·RFC 850·asctime 해석, 기본 `https` 만, 리다이렉트 따라가지 않음
- 동시 호출 한 번 조회, 실패 대기(30초 ~ 30분)
- 확장 지점 `TimeSource`·`SyncStateStore`·`MonotonicClock`·`WallClock`·`NetworkMonitor`, 디스패처 교체

## time-android

### [Unreleased]

### [0.0.1] - 2026-11-07

#### 추가
- `AndroidTime.clockBuilder(context)` — 아래 셋을 끼운 `TrustedClock.Builder`
- `AndroidTime.monotonicClock` — `elapsedRealtime` + `Settings.Global.BOOT_COUNT` 부팅 ID(API 24+)
- `AndroidTime.networkMonitor` — `ConnectivityManager` 인터넷 능력 판단
- `AndroidTime.preferencesStore` — SharedPreferences 오프셋 저장소(깨진 값은 지우고 다시 동기화)
