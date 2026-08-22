# Comprehensive System, Architectural, and UX Audit Report: Schedule Module & Settings Architecture

**Target Repository**: AniZen (Android / Kotlin / Jetpack Compose / Voyager / SQLDelight / WorkManager / Injekt DI)  
**Author**: Worker 1 (Senior Systems & UX Architect)  
**Date**: 2026-08-22  
**Document Status**: Final Deliverable  
**Target Path**: `SCHEDULE_AUDIT_REPORT.md`  

---

## 1. Executive Summary & Scope

### 1.1 Overview & System Purpose
AniZen is a modern Android application engineered for anime and manga cataloging, tracking, streaming, and offline consumption. At the core of the application's automation is the **Schedule & Update Subsystem**, an orchestration engine responsible for:
1. Tracking real-time broadcast schedules and episode releases across global television networks (via the AniList GraphQL API).
2. Continuously learning and predicting source-specific upload delays through empirical observation and Exponential Moving Average (EMA) mathematical modeling.
3. Automatically checking library anime for newly released episodes using adaptive, dynamic fetch interval algorithms.
4. Coordinating background downloads, segmented multi-threaded video stream assembly, tracking updates, and cloud sync pipelines under strict operating system battery and network constraints.

This audit provides a comprehensive, rigorous, and exhaustive evaluation of the scheduling architecture, data persistence layers, background execution engines, automated decision-making policies, user journeys, visual hierarchies, and cognitive friction points across the AniZen codebase.

---

### 1.2 Architectural Taxonomy & Technology Matrix

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    ANIZEN ARCHITECTURE TIERS                                    │
├───────────────────────┬─────────────────────────────────────────────────────────────────────────┤
│ UI & Presentation     │ Jetpack Compose, Voyager Navigation, Material 3, Haze, Coil 3           │
├───────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Dependency Injection  │ Injekt (Singletons, Factory Scopes, Module Registries)                 │
├───────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Domain / Interactors  │ Kotlin Coroutines, Flow, Arrow (Either/Option), Clean Architecture      │
├───────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Data & Persistence    │ SQLDelight (SQLite/SQLCipher), AndroidPreferenceStore, JSON Caches      │
├───────────────────────┼─────────────────────────────────────────────────────────────────────────┤
│ Background Processing │ Jetpack WorkManager (Periodic/One-Time), AlarmManager (RTC_WAKEUP),      │
│                       │ Android Foreground Services (DATA_SYNC, SHORT_SERVICE)                  │
└───────────────────────┴─────────────────────────────────────────────────────────────────────────┘
```

---

### 1.3 Key Audit Findings & Critical Anomalies

| Category | Severity | Finding Summary | Primary Location |
|---|---|---|---|
| **Domain Logic** | 🚨 **P0 Critical** | **Off-By-One Calculation in Interval Picker**: Selecting "1 Day" in `SetIntervalDialog` saves `-2`, resulting in a 2-day update cycle instead of 1 day. | `AnimeDialogs.kt:284` |
| **Concurrency** | 🚨 **P0 Critical** | **Indefinitely Blocking Coroutine Scope in DownloadJob**: `coroutineScope` wrapper encloses hot flows, preventing the loop below it from ever executing. | `DownloadJob.kt:70-85` |
| **Data Integrity** | 🚨 **P1 High** | **SyncPreferences Key Clobbering**: `setSyncSettings()` sets `anime_history` with `history` and drops `animeHistory`, corrupting sync state. | `SyncPreferences.kt:78` |
| **Schema** | 🚨 **P1 High** | **SQL Syntax Error in Database Definition**: Parameter identifier `:sourceIds` is malformed as `:sourceIdsAND AND`. | `animes.sq:167` |
| **UX & Navigation** | 🚨 **P1 High** | **Screen Trapping in AiringScheduleTab**: Launching Schedule from the More tab hides the bottom bar while the top bar lacks a back navigation icon. | `AiringScheduleTab.kt:113` |
| **Lifecycle** | ⚠️ **P1 High** | **Preference Restorer Omits Schedule Background Jobs**: Restoring backups re-arms library updates but completely forgets schedule workers. | `PreferenceRestorer.kt:28-30` |
| **Settings UX** | ⚠️ **P2 Medium** | **Broken Settings Hierarchy in Schedule Screen**: Sub-preferences for custom delays and intervals remain interactable when the master toggle is disabled. | `SettingsScheduleScreen.kt:171` |
| **Information Arch**| ⚠️ **P2 Medium** | **Dual Schedule Cognitive Collision**: Users encounter two disparate upcoming episode screens (`UpcomingScreen` vs `AiringScheduleTab`) with fragmented settings. | `UpdatesTab.kt:99` |
| **Affordance** | ⚠️ **P2 Medium** | **Disguised Per-Anime Schedulers**: Interval configuration trigger in `AnimeInfoHeader` resembles a static status text without clickable affordances. | `AnimeInfoHeader.kt:274` |
| **Concurrency** | ⚠️ **P2 Medium** | **Non-Atomic StringSet Preference Mutation**: Alarm registrations stored in SharedPreferences `Set<String>` lack inter-process atomicity. | `ScheduleNotifications.kt:103` |

---

## 2. Settings & Configuration Audit (Settings Matrix)

### 2.1 Storage Mechanism Architecture
AniZen decouples application configuration and operational state across several specialized storage layers:

```
                                  ┌───────────────────────────────┐
                                  │      Application State        │
                                  └───────────────┬───────────────┘
                                                  │
         ┌─────────────────────────┬──────────────┴──────────────┬─────────────────────────┐
         │                         │                             │                         │
         v                         v                             v                         v
┌─────────────────┐       ┌─────────────────┐           ┌─────────────────┐       ┌─────────────────┐
│ PreferenceStore │       │ JSON File Cache │           │ Rolling Journal │       │ SQLite Database │
│ (SharedPreferences)     │ (schedule_cache)│           │(source_feed_sync│       │  (SQLDelight)   │
└────────┬────────┘       └────────┬────────┘           └────────┬────────┘       └────────┬────────┘
         │                         │                             │                         │
         v                         v                             v                         v
User Configuration,       Serialized AniList            Two-day rolling EMA       Entities, History,
Booleans, Enums,          Global Broadcast              Source Upload Delay       Episodes, Hierarchy,
Alarm StringSets          Offline Fallback              Observations              Sync Versioning
```

1. **`AndroidPreferenceStore`** (`tachiyomi.core.common.preference.AndroidPreferenceStore`):
   - Backed by Android `SharedPreferences` (`getDefaultSharedPreferences`).
   - Thread-safe, provides reactive Kotlin `Flow` observation via `OnSharedPreferenceChangeListener`.
   - Used for all user-configurable settings and preference models.
2. **`DelayedTrackingStore`** (`eu.kanade.domain.track.store.DelayedTrackingStore`):
   - Backed by an isolated XML file (`tracking_queue.xml`) to prevent write contention with standard preferences during offline media playback.
3. **`Schedule Cache`** (`filesDir/schedule_cache.json`):
   - Ephemeral structured JSON cache containing serialized AniList weekly airing episodes, enabling instant UI hydration upon app launch without awaiting network I/O.
4. **`Source Feed Sync Store`** (`filesDir/source_feed_sync/{YYYY-MM-DD}.json`):
   - Rolling two-day journal of observed release delays per extension source. Employs atomic file renames during flush operations.
5. **`SQLDelight Database`** (`anizen.db` / SQLCipher):
   - Fully relational, schema-migrated ACID database handling core domain entities (`animes`, `episodes`, `history`, `categories`, etc.).

---

### 2.2 Exhaustive Settings Matrix

#### 2.2.1 Schedule & Airing Settings (`SchedulePreferences`)

| Configuration Key | Kotlin Type | Default Value | Validation / Range Constraints | Storage Mechanism | Consumer / Access Locations | Gaps & Inconsistencies Identified |
|---|---|---|---|---|---|---|
| `schedule_favorite_source_ids` | `Set<String>` | `emptySet()` | Installed extension source IDs | `PreferenceStore` | `SettingsScheduleScreen`, `AiringScheduleScreenModel`, `ScheduleRefreshWorker` | None. Reactively updates UI source list. |
| `schedule_show_only_favorite_sources` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsScheduleScreen`, `AiringScheduleScreenModel` | Missing filter chips in `AiringScheduleTab` top sheet. |
| `schedule_title_language` | `Enum<TitleLanguage>` | `USER_PREFERRED` | `USER_PREFERRED`, `ENGLISH`, `ROMAJI`, `NATIVE` | `PreferenceStore` (`getEnum`) | `SettingsScheduleScreen`, `AiringScheduleScreenModel`, `ScheduleAnimeCard` | In sync with tracking title language preferences. |
| `schedule_show_adult_content` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsScheduleScreen`, `AiringScheduleScreenModel`, `ScheduleDataRefreshWorker` | Hardcodes 18+ filter in GraphQL query variables. |
| `schedule_upload_delay_enabled` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsScheduleScreen`, `AiringScheduleScreenModel`, `ScheduleRefreshWorker` | Sub-preferences in settings do not disable when this is false. |
| `schedule_upload_delay_interval` | `Enum<UploadDelayInterval>` | `ONE_HOUR` | `THIRTY_MIN` (30m), `ONE_HOUR` (60m), `TWO_HOURS` (120m), `SIX_HOURS` (360m), `TWELVE_HOURS` (720m), `CUSTOM` (0m), `NEVER` (0m) | `PreferenceStore` (`getEnum`) | `SettingsScheduleScreen`, `AiringScheduleScreenModel`, `ScheduleRefreshWorker` | `CUSTOM` interval does not schedule a periodic worker. |
| `schedule_custom_upload_delay_minutes` | `String` | `"60"` | Clamped: `-1440` to `1440` minutes (`-24h` to `+24h`) | `PreferenceStore` | `SettingsScheduleScreen`, `SchedulePreferences.parseCustomDelayMinutes` | Stored as `String` rather than `Int`, requiring runtime parsing. |
| `schedule_source_upload_delays` | `String` (JSON) | `"{}"` | Serialized `Map<Long, Long>` (SourceId -> DelayMinutes) | `PreferenceStore` | `UploadDelayTracker`, `AiringScheduleScreenModel`, `SettingsScheduleScreen` | JSON serialized in SharedPreferences rather than relational DB. |
| `schedule_last_delay_check_time` | `Long` | `0L` | Epoch timestamp in seconds | `PreferenceStore` | `ScheduleRefreshWorker` | Epoch seconds used here, while other keys use epoch milliseconds. |
| `schedule_last_source_feed_sync_time` | `Long` | `0L` | Epoch timestamp in milliseconds | `PreferenceStore` | `ScheduleRefreshWorker`, `SettingsScheduleScreen` | Displayed in UI; no auto-reset on source uninstallation. |
| `schedule_source_feed_sync_status` | `String` | `""` | Status descriptor string | `PreferenceStore` | `ScheduleRefreshWorker`, `SettingsScheduleScreen` | Ephemeral worker state persisted in persistent SharedPreferences. |
| `schedule_auto_refresh_enabled` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsScheduleScreen`, `ScheduleDataRefreshWorker` | Worker lifecycle tied to UI `LaunchedEffect`. |
| `schedule_auto_refresh_frequency` | `Enum<AutoRefreshFrequency>` | `EVERY_7_DAYS` | `EVERY_1_DAY`, `EVERY_2_DAYS`, `EVERY_3_DAYS`, `EVERY_4_DAYS`, `EVERY_5_DAYS`, `EVERY_6_DAYS`, `EVERY_7_DAYS` | `PreferenceStore` (`getEnum`) | `SettingsScheduleScreen`, `ScheduleDataRefreshWorker` | Lacks hourly or 12-hour granularity for airing-heavy seasons. |
| `schedule_last_auto_refresh` | `Long` | `0L` | Epoch timestamp in milliseconds | `PreferenceStore` | `ScheduleDataRefreshWorker` | Unused in UI; internal timestamp only. |
| `schedule_notify_once_media_ids` | `Set<String>` | `emptySet()` | AniList `mediaId` strings | `PreferenceStore` | `AiringScheduleScreenModel`, `AiringScheduleTab` | Non-atomic write operations during alarm trigger. |
| `schedule_notify_series_media_ids` | `Set<String>` | `emptySet()` | AniList `mediaId` strings | `PreferenceStore` | `AiringScheduleScreenModel`, `AiringScheduleTab`, `ScheduleDataRefreshWorker` | Non-atomic write operations. |
| `schedule_scheduled_alarm_keys` | `Set<String>` | `emptySet()` | Composite `"mediaId:episode"` strings | `PreferenceStore` | `ScheduleNotifications`, `ScheduleAlarmReceiver` | Risk of orphan alarms on unexpected app termination. |

---

#### 2.2.2 Library & Update Settings (`LibraryPreferences`)

| Configuration Key | Kotlin Type | Default Value | Constraints / Options | Storage Mechanism | Consumer / Access Locations | Gaps & Inconsistencies Identified |
|---|---|---|---|---|---|---|
| `pref_library_update_interval_key` | `Int` | `0` | `0` (Disabled), `12`, `24`, `48`, `72`, `168` (Hours) | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | Lacks custom cron or specific time-of-day execution windows. |
| `library_update_restriction` | `Set<String>` | `["wifi"]` | `"wifi"`, `"network_not_metered"`, `"ac"` | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | Wi-Fi check evaluated imperatively in worker rather than WorkManager constraint. |
| `library_update_manga_restriction` | `Set<String>` | `["anime_fully_seen", "anime_ongoing", "anime_started", "anime_outside_release_period"]` | Multi-select Smart Restrictions | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob`, `GetUpcomingAnime` | `anime_outside_release_period` has inverted logic between Job and GetUpcoming. |
| `auto_update_metadata` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | Significantly increases network payload during auto-updates. |
| `animelib_update_categories` | `Set<String>` | `emptySet()` | Category ID strings to include | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | If both include and exclude contain an ID, exclude wins. |
| `animelib_update_categories_exclude` | `Set<String>` | `emptySet()` | Category ID strings to exclude | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | Clean exclusion filtering. |
| `group_anime_library_update_type` | `Enum<GroupLibraryMode>` | `GLOBAL` | `GLOBAL`, `ALL_BUT_UNGROUPED`, `ALL` | `PreferenceStore` (`getEnum`) | `SettingsLibraryScreen`, `LibraryUpdateJob` | Determines season grouping during update passes. |
| `library_update_last_timestamp` | `Long` | `0L` | Epoch timestamp in milliseconds | `PreferenceStore` | `LibraryUpdateJob`, `LibraryPreferences` | AppState preference key. |
| `library_unseen_updates_count` | `Int` | `0` | Count of unread/unseen new episodes | `PreferenceStore` | `LibraryUpdateJob`, `UpdatesTab` | Resets immediately upon opening Updates tab. |
| `pref_show_updating_progress_banner_key` | `Boolean` | `true` | `true`, `false` | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | Disabled by default in some configurations. |
| `use_hierarchical_seasons` | `Boolean` | `true` | `true`, `false` | `PreferenceStore` | `SettingsLibraryScreen`, `LibraryUpdateJob` | Alters query pipeline to flatten parent series. |

---

#### 2.2.3 Download Settings (`DownloadPreferences`)

| Configuration Key | Kotlin Type | Default Value | Constraints / Options | Storage Mechanism | Consumer / Access Locations | Gaps & Inconsistencies Identified |
|---|---|---|---|---|---|---|
| `pref_download_only_over_wifi_key` | `Boolean` | `true` | `true`, `false` | `PreferenceStore` | `SettingsDownloadScreen`, `DownloadJob`, `Downloader` | Enforced dynamically inside Downloader and via NetworkStateFlow. |
| `download_speed_limit` | `Int` | `0` | `0` (Unlimited) or positive KiB/s | `PreferenceStore` | `SettingsDownloadScreen`, `Downloader` | Implemented via token-bucket byte throttling in downloader stream. |
| `download_slots` | `Int` | `1` | `1` to `5` | `PreferenceStore` | `SettingsDownloadScreen`, `Downloader` | Controls parallel episode downloads. |
| `concurrent_downloads` | `Int` | `1` | `1` to `100` | `PreferenceStore` | `SettingsDownloadScreen`, `Downloader` | Total network workers across all downloading episodes. |
| `download_threads` | `Int` | `8` | `1` to `64` (clamped on low RAM) | `PreferenceStore` | `SettingsDownloadScreen`, `Downloader` | Multi-threaded segmented video chunk downloading. |
| `download_new_episode` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsDownloadScreen`, `FilterEpisodesForDownload` | Auto-queues downloads upon detecting new episodes during update. |
| `download_new_unread_episodes_only`| `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SettingsDownloadScreen`, `FilterEpisodesForDownload` | Only queues if prior episodes have been watched. |
| `auto_download_while_watching` | `Int` | `0` | `0` (Off), `2`, `3`, `5`, `10` ahead | `PreferenceStore` | `SettingsDownloadScreen`, `PlayerViewModel` | Automatically queues upcoming episodes during active playback. |
| `remove_after_read_slots` | `Int` | `-1` | `-1` (Disabled), `0` (Last), `1`..`4` | `PreferenceStore` | `SettingsDownloadScreen`, `DownloadManager` | Auto-deletes downloaded episodes once marked seen. |

---

#### 2.2.4 Cloud Sync & Backup Settings (`SyncPreferences` & `BackupPreferences`)

| Configuration Key | Kotlin Type | Default Value | Constraints / Options | Storage Mechanism | Consumer / Access Locations | Gaps & Inconsistencies Identified |
|---|---|---|---|---|---|---|
| `sync_service` | `Int` | `0` | `0` (Disabled), `1` (Self-host), `2` (GDrive)| `PreferenceStore` | `SyncPreferences`, `SyncDataJob` | Controls sync provider backend. |
| `sync_interval` | `Int` | `0` | `0` (Manual), `30`, `60`, `180`, `720`, `1440` (m)| `PreferenceStore` | `SyncPreferences`, `SyncDataJob` | WorkManager periodic sync interval. |
| `sync_on_library_update` | `Boolean` | `true` | `true`, `false` | `PreferenceStore` | `SyncPreferences`, `LibraryUpdateJob` | Chains `SyncDataJob` before `LibraryUpdateJob`. |
| `sync_on_app_start` / `_resume` | `Boolean` | `false` | `true`, `false` | `PreferenceStore` | `SyncPreferences`, `MainActivity` | Triggers immediate one-time sync on app lifecycle. |
| `anime_history` (in sync settings) | `Boolean` | `true` | `true`, `false` | `PreferenceStore` | `SyncPreferences.kt:78` | **Bug**: `setSyncSettings()` overwrites `anime_history` with `history`. |
| `backup_interval` | `Int` | `12` | `0` (Off), `6`, `12`, `24`, `48`, `168` (Hours) | `PreferenceStore` | `SettingsDataScreen`, `BackupCreateJob` | Configures periodic backup creation worker. |
| `backup_slots` | `Int` | `4` | `1` to `10` retained backup files | `PreferenceStore` | `SettingsDataScreen`, `BackupCreator` | Automatically purges older backup archives. |

---

### 2.3 Database Schema & Migrations Audit

AniZen uses **SQLDelight** for database generation, query type safety, and migrations. The schema comprises **19 `.sq` files** and **15 `.sqm` migration files** (`129.sqm` to `143.sqm`).

#### 2.3.1 Critical Schema Anomaly in `animes.sq`
- **File**: `data/src/main/sqldelight/tachiyomi/data/animes.sq:167`
- **Query**: `deleteAnimesNotInLibraryAndNotSeenBySourceIds`
- **Defective Code**:
  ```sql
  deleteAnimesNotInLibraryAndNotSeenBySourceIds:
  DELETE FROM animes
  WHERE favorite = 0 AND source IN :sourceIdsAND AND _id NOT IN (
      SELECT anime_id FROM merged WHERE anime_id != merge_id
  ) AND _id NOT IN (
      SELECT anime_id FROM episodes WHERE seen = 1 OR last_second_seen != 0
  );
  ```
- **Technical Analysis**: The SQLDelight parameter token `:sourceIds` was inadvertently concatenated with `AND`, creating `:sourceIdsAND AND`. This produces a malformed token. If this query is invoked or processed by automated schema validation, it will fail.
- **Resolution**:
  ```sql
  WHERE favorite = 0 AND source IN :sourceIds AND _id NOT IN (
  ```

---

## 3. Structural & Architectural Review

### 3.1 Background Processing & Orchestration Topology

AniZen orchestrates background computation across three distinct Android system mechanisms:

```
                               ┌─────────────────────────────────────────┐
                               │       Background Orchestration          │
                               └────────────────────┬────────────────────┘
                                                    │
             ┌──────────────────────────────────────┼──────────────────────────────────────┐
             │                                      │                                      │
             v                                      v                                      v
┌─────────────────────────┐            ┌─────────────────────────┐            ┌─────────────────────────┐
│     Jetpack WorkManager │            │   Android AlarmManager  │            │   Foreground Services   │
├─────────────────────────┤            ├─────────────────────────┤            ├─────────────────────────┤
│ • LibraryUpdateJob      │            │ • ScheduleAlarmReceiver │            │ • Downloader (DATA_SYNC)│
│ • ScheduleDataRefresh   │            │   - Exact RTC_WAKEUP    │            │ • TorrentServerService  │
│ • ScheduleRefreshWorker │            │   - setExactAndAllow    │            │ • LocalHttpServerService│
│ • SyncDataJob           │            │     WhileIdle           │            │ • ExtensionInstall      │
│ • BackupCreateJob       │            │   - goAsync() (6s cap)  │            │ • DiscordRPCService     │
│ • DelayedTrackingUpdate │            │   - Heads-Up Broadcast  │            │                         │
│ • AppUpdateJob          │            │     Notification        │            │                         │
└─────────────────────────┘            └─────────────────────────┘            └─────────────────────────┘
```

---

### 3.2 State Machines & Concurrency Pipelines

#### 3.2.1 Anime Fetch Interval State Machine (`FetchInterval.kt`)
The update interval engine governs when library anime are refreshed against source extensions:

```
                          ┌─────────────────────────────────────┐
                          │          Anime In Library           │
                          └──────────────────┬──────────────────┘
                                             │
                       ┌─────────────────────┴─────────────────────┐
                       │                                           │
                       v                                           v
           ┌───────────────────────┐                   ┌───────────────────────┐
           │ fetchInterval == -1   │                   │  fetchInterval >= 0   │
           │   (MANUAL_DISABLE)    │                   │   (AUTO / DYNAMIC)    │
           └───────────┬───────────┘                   └───────────┬───────────┘
                       │                                           │
                       v                                           v
               nextUpdate = 0L                             Calculate Delta
             (Excluded from Auto)                          (Distinct Uploads)
                                                                   │
                                                                   v
                                                        Base Interval [1..28]
                                                           (Default: 7 days)
                                                                   │
                                                                   v
                                                        Missed Cycles Check:
                                                        timeSinceLatest / int > 9
                                                        -> Double up to 28 days
                                                                   │
                                                                   v
                                                        nextUpdate Epoch =
                                                        latestDate + (cycle+1)*interval
```

#### 3.2.2 The Off-By-One Interval Calculation Bug
In `AnimeDialogs.kt`, the interval selection dialog constructs a list:
```kotlin
val items = remember {
    buildList {
        add("Disabled")                                              // Index 0 -> -1
        add(context.stringResource(MR.strings.label_default))       // Index 1 -> 0
        addAll((1..FetchInterval.MAX_INTERVAL).map { it.toString() })// Index 2 -> "1", Index 3 -> "2"...
    }
}
```
When persisting the user's selection:
```kotlin
val newValue = when (selectedIntervalIndex) {
    0 -> FetchInterval.MANUAL_DISABLE // -1
    1 -> 0                            // Auto
    else -> -selectedIntervalIndex    // BUG: Index 2 ("1 Day") saves -2!
}
```
When `FetchInterval.calculateNextUpdate` calculates the cycle:
```kotlin
val cycle = timeSinceLatest.floorDiv(
    interval.absoluteValue.takeIf { interval < 0 } ?: calculatedInterval
)
return latestDate.plusDays((cycle + 1) * interval.absoluteValue.toLong())
```
**Impact**: When a user selects **1 Day**, the system saves `-2` and calculates a **2-day interval**. Selecting **28 Days** saves `-29` and calculates **29 days** (exceeding `MAX_INTERVAL = 28`).

---

#### 3.2.3 Downloader 8-Stage Pipeline & Concurrency Safeguards

```
┌────────────────────┐
│   NOT_DOWNLOADED   │ <═══════════════════════════════════════════════════════════════╗
└─────────┬──────────┘                                                                 ║
          │ (Queue Action)                                                             ║
          v                                                                            ║
┌────────────────────┐         (User Pause / Network Drop)                             ║
│       QUEUE        │ ────────────────────────────────────────┐                       ║
└─────────┬──────────┘                                         │                       ║
          │ (Downloader Dispatcher)                            │                       ║
          v                                                    │                       ║
┌────────────────────┐                                         │                       ║
│    DOWNLOADING     │ ────────────────────────────────────────┤                       ║
└─────────┬──────────┘                                         │                       ║
          │ (Stream Fragments Acquired)                        │                       ║
          v                                                    v                       ║
┌────────────────────┐                              ┌────────────────────┐             ║
│      MERGING       │ <───┐ (Local Phase:          │       PAUSED       │             ║
└─────────┬──────────┘     │  Network drop does     │ (Preserves partial │             ║
          │ (FFmpeg Mux)   │  NOT cancel worker)    │  stream artifacts) │             ║
          v                │                        └──────────┬─────────┘             ║
┌────────────────────┐     │                                   │                       ║
│     DECRYPTING     │ ────┤                                   │ (Resume)              ║
└─────────┬──────────┘     │                                   v                       ║
          │ (Decryption)   │                        ┌────────────────────┐             ║
          v                │                        │       QUEUE        │             ║
┌────────────────────┐     │                        └────────────────────┘             ║
│     FINALIZING     │ ────┘                                                           ║
└─────────┬──────────┘                                                                 ║
          │ (Atomic Move: Sandbox -> External App Storage)                             ║
          ├────────────────────────────────────────────────────┐                       ║
          │                                                    │ (HTTP 4xx / Error)    ║
          v                                                    v                       ║
┌────────────────────┐                              ┌────────────────────┐             ║
│     DOWNLOADED     │                              │       ERROR        │ ════════════╝
└────────────────────┘                              └────────────────────┘ (Retry / Clear)
```

**Local Phase Isolation (`isLocalPhase`)**:
When an episode transitions into `MERGING`, `DECRYPTING`, or `FINALIZING`, `downloadManager.isLocalPhase` returns `true`. If the device switches networks or goes offline during this local disk processing phase, the job does not abort, preventing file corruption.

---

#### 3.2.4 Dead Code & Coroutine Scope Lockup in `DownloadJob.kt`
In `DownloadJob.kt:70-85`:
```kotlin
coroutineScope {
    combineTransform(
        applicationContext.networkStateFlow(),
        downloadPreferences.downloadOnlyOverWifi().changes(),
        transform = { a, b -> emit(checkNetworkState(a, b)) },
    )
        .onEach { networkCheck = it }
        .launchIn(this)
}

// Keep the worker running when needed
while (active) {
    delay(1000)
    active = !isStopped && downloadManager.isRunning && (networkCheck || downloadManager.isLocalPhase)
}
```
**Defect Analysis**: `coroutineScope` suspends until all launched child jobs finish. Because `networkStateFlow()` and `changes()` are infinite flows, `launchIn(this)` never terminates. As a result, `coroutineScope` **blocks forever**, and the `while (active)` loop below it is **completely unreachable dead code**.

---

## 4. End-to-End User Flow & UX Evaluation

### 4.1 Information Architecture Map

```
========================================================================================
                              MAIN NAVIGATION GRAPH (VOYAGER)
========================================================================================

Bottom Navigation Bar / Navigation Rail
 │
 ├── [1] LibraryTab (Default Landing Screen)
 │    ├── Filter / Category Sheet (Display Mode, Badges, Sort, Filters)
 │    ├── Category Tabs (Horizontal Pager)
 │    └── Anime Screen (Details, Episodes, Seasons, Cast)
 │         └── Action Row ──► SetIntervalDialog (Interval / Scheduled Wheel Pickers)
 │
 ├── [2] UpdatesTab
 │    ├── Top Bar Calendar Icon ──► UpcomingScreen (Predictive FetchInterval Calendar)
 │    ├── Chronological Update Feed (Today, Yesterday, Last 7 days...)
 │    └── Tab Double-Tap ──► DownloadQueueScreen
 │
 ├── [3] HistoryTab
 │    └── Chronological Watch History
 │
 ├── [4] BrowseTab
 │    ├── Sources Tab (Pinned & Installed Extensions)
 │    ├── Extensions Tab (Updates, Repositories)
 │    └── Migration Tab (Source-to-Source Batch Migration)
 │
 ├── [5] AiringScheduleTab (Default: Hidden in More Tab)
 │    ├── Weekday Tabs (Mon - Sun + Date + Airing Count)
 │    ├── Top Bar Filter Sheet (Favorites Only, Hide Aired, 18+ Adult)
 │    └── Schedule Anime Cards (Air Time, Upload Delay Badge, Episode Bell)
 │
 └── [6] MoreTab
      ├── System Toggles (Downloaded Only, Incognito)
      ├── Library Sub-Screens (Download Queue, Categories, Stats, Library Update Errors)
      ├── Hidden Navigation Items (Airing Schedule, Feed)
      └── Settings Navigation (Appearance, Library, Schedule, Downloads, Sync, Advanced)
```

---

### 4.2 Detailed User Journey Walkthroughs

#### Journey 1: Airing Schedule Discovery & Navigation Screen Trapping
- **User Intent**: User wants to browse the upcoming weekly anime airing calendar.
- **Current Flow**:
  1. Under default settings (`NavPresets.DEFAULT`), `AiringScheduleTab` is hidden from the bottom navigation bar.
  2. The user navigates to `MoreTab` -> scrolls down -> taps `Airing Schedule`.
  3. `HomeScreen.kt:317` switches the Voyager tab navigator to `AiringScheduleTab`.
  4. `HomeScreen.kt:209` determines that `AiringScheduleTab` is not in the visible bottom bar items, causing the bottom navigation bar to **animate away and disappear**.
  5. `AiringScheduleTab.kt:113` renders a `TopAppBar` with title and action buttons, but **lacks a navigation back arrow**.
  6. **UX Failure**: The user is **trapped on this screen**. The only exit is the Android system back gesture, which abruptly resets the tab navigator to `LibraryTab` (`HomeScreen.kt:299-302`), losing the user's place in `MoreTab`.

---

#### Journey 2: Dual Schedule / Upcoming Cognitive Collision
- **User Intent**: User wants to see what anime episodes are releasing soon.
- **Current Flow**:
  - Tapping the **Calendar icon** in `UpdatesTab` opens `UpcomingScreen` (internal mathematical predictor based on `FetchInterval`).
  - Tapping `Airing Schedule` in `MoreTab` opens `AiringScheduleTab` (AniList global broadcast calendar with learned source upload delays).
- **UX Confusion**: Users are presented with two distinct schedule interfaces with different data sources, different UIs, and different settings locations (`Settings -> Library -> Library update` vs `Settings -> Schedule`).

---

#### Journey 3: Per-Anime Custom Fetch Schedule Configuration
- **User Intent**: User wants a specific anime to update every Friday at 11:00 PM.
- **Current Flow**:
  1. User opens `AnimeScreen.kt` and views `AnimeInfoHeader.kt:255-320`.
  2. The second button displays cryptic text like `"3 days"`, `"Soon"`, or `"N/A"` next to an hourglass icon.
  3. **Affordance Failure**: There is no visual cue (e.g. edit pencil, chevron, or `"Schedule"` label) indicating this button is interactive.
  4. If the anime is not in the library, tapping the button fails silently with zero feedback.
  5. When opened, `SetIntervalDialog` allows configuring day and time wheel pickers, but does not display a human-readable confirmation summary (e.g. *"Checks every Friday at 11:00 PM"*).

---

#### Journey 4: Notification Routing & Quick Action Absence
- **User Intent**: User receives a notification that a new episode has aired and wants to watch it immediately.
- **Current Flow**:
  1. `ScheduleAlarmReceiver.kt` posts a heads-up notification to `CHANNEL_AIRING_SCHEDULE`.
  2. Tapping the notification launches `MainActivity` with `INTENT_SEARCH_QUERY` set to the series title.
  3. `MainActivity` pushes `GlobalSearchScreen`, forcing the user to wait for a multi-source global search.
  4. **UX Failure**: If the anime is already in the library, it fails to deep-link to `AnimeScreen(animeId)`. Furthermore, the notification contains **zero quick actions** (`[Watch Now]`, `[Mark Seen]`, `[Download]`).

---

### 4.3 Visual Hierarchy & Friction Evaluation Matrix

| UI Component | File & Line Location | Observed Friction / Defect | Severity | Recommended Fix |
|---|---|---|---|---|
| **AiringScheduleTab Top Bar** | `AiringScheduleTab.kt:113` | TopAppBar lacks navigation back icon when opened from More Tab. | **Critical** | Add `navigationIcon = { BackButton { tabNavigator.current = MoreTab } }` when tab is not in bottom bar. |
| **SettingsScheduleScreen** | `SettingsScheduleScreen.kt:171` | `customUploadDelayMinutes` and `uploadDelayRefreshInterval` remain active when `uploadDelayEnabled` is OFF. | **High** | Bind `enabled = uploadDelayEnabled` to all dependent delay preferences. |
| **UpdatesTab Calendar Icon** | `UpdatesTab.kt:99` | Opens internal `UpcomingScreen` instead of unified airing schedule. | **High** | Provide tab switcher or route directly to `AiringScheduleTab` with library filter. |
| **AnimeInfoHeader Action Row** | `AnimeInfoHeader.kt:274` | Interval button has no descriptive label; silent failure on non-library anime. | **High** | Label as `"Schedule"`; show snackbar prompt if anime is not in library. |
| **UpdatesTab Unread Badge** | `UpdatesTab.kt:150` | Badge count resets instantly on tab entrance (`DisposableEffect`). | **Medium** | Reset badge only after user interacts with update feed items or scrolls. |
| **MoreTab Error Logs** | `MoreScreen.kt:201` | `"Library update errors"` has no badge, counter, or warning tint. | **Medium** | Display error count badge on More tab row when update failures exist. |
| **Schedule Notifications** | `ScheduleAlarmReceiver.kt:81` | Notification opens Global Search instead of Anime Details; lacks quick actions. | **Medium** | Deep-link to `AnimeScreen` if matched; add `[Watch]` and `[Download]` action buttons. |
| **EpisodeBell Icon** | `EpisodeBell.kt:57-65` | Alpha tints (0.4 / 0.85 / 1.0) are too subtle to distinguish Once vs Series alerts. | **Low** | Use distinct iconography (e.g. outline bell vs filled bell with badge). |

---

## 5. Decision-Maker & Policy Analysis

### 5.1 Automated Scheduling & Prediction Algorithms

#### 5.1.1 Empirical Upload Delay Learning (EMA)
AniZen features an intelligent upload delay tracker (`UploadDelayTracker.kt` and `ScheduleRefreshWorker.kt`). For anime in the user's library, the worker compares the official AniList airing timestamp against actual episode release timestamps published by source extensions.

1. **Observation Filtering**: Observations are recorded in the 2-day rolling journal (`source_feed_sync/`) and filtered to valid windows $[-60\text{ min}, +1440\text{ min}]$.
2. **Exponential Moving Average (EMA)**:
   $$\text{Delay}_{\text{new}} = (1 - \alpha) \cdot \text{Delay}_{\text{prev}} + \alpha \cdot \text{Delay}_{\text{observed}} \quad (\alpha = 0.4)$$
3. **Six-Tier Priority Resolution Hierarchy**:
   When computing the expected upload countdown on `ScheduleAnimeCard.kt`, delays are resolved in strict priority order:
   ```
   1. User-Configured Custom Delay (if enabled in settings)
        └── 2. Specific Library-Matched Pinned Source (1st pinned -> 2nd pinned -> ...)
             └── 3. Specific Library-Matched Favorite Sources
                  └── 4. Global Pinned Extension Sources
                       └── 5. Global Favorite Sources
                            └── 6. Maximum Learned Delay across all sources
   ```

---

### 5.2 Conflict Resolution Policies

| Conflict Scenario | System Resolution Policy | Code Location |
|---|---|---|
| **Manual vs Auto Library Update** | Periodic worker yields immediately (`Result.retry()`) if manual work is running. Manual requests are rejected if any update job is already active. | `LibraryUpdateJob.kt:127, 680` |
| **Cloud Sync vs Library Update** | When `syncOnLibraryUpdate` is true, WorkManager explicitly chains tasks: `SyncDataJob` -> `LibraryUpdateJob`. | `LibraryUpdateJob.kt:716` |
| **Backup Create vs Restore** | `BackupCreateJob` checks `BackupRestoreJob.isRunning()` and yields (`Result.retry()`) to prevent corrupting database dumps. | `BackupCreateJob.kt:42` |
| **Host Concurrency Contention** | `Downloader` throttles thread concurrency to 1 for delicate hosts (`animepahe`, `sibnet`) to prevent rate-limiting and HTTP 429 errors. | `Downloader.kt:143` |
| **Cross-Week Alarm Re-arming** | Periodic `ScheduleDataRefreshWorker` automatically re-evaluates all `notifySeriesMediaIds` subscriptions against new weekly schedules. | `ScheduleDataRefreshWorker.kt:70` |

---

### 5.3 System Resource Constraints & OS Compliance

1. **Doze Mode & Standby Buckets**:
   - Time-critical episode airing notifications utilize `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, ...)` when `SCHEDULE_EXACT_ALARM` permission is granted, guaranteeing execution even in deep Doze.
   - Non-critical schedule and library refreshes use flexible WorkManager intervals with 10-minute flex windows.
2. **Network Metering & Battery Saver**:
   - `requiresBatteryNotLow = true` is enforced across all periodic workers.
   - WorkManager `NetworkType.UNMETERED` is configured when `DEVICE_ONLY_ON_WIFI` or `DEVICE_NETWORK_NOT_METERED` is active.
3. **Foreground Service Compliance (Android 14+ / API 34)**:
   - Workers configure `ForegroundInfo` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
   - Foreground service launches are wrapped with safety checks to prevent fatal `ForegroundServiceStartNotAllowedException` crashes.

---

## 6. Prioritized Recommendations & Action Plan

### 6.1 Prioritized Engineering Matrix

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                               PRIORITIZED REMEDIATION MATRIX                                    │
├──────┬───────────────────────────────────────────┬──────────┬───────────┬────────────┬──────────┤
│ Rank │ Issue Description                         │ Impact   │ Risk      │ Complexity │ Target   │
├──────┼───────────────────────────────────────────┼──────────┼───────────┼────────────┼──────────┤
│ P0   │ Fix Off-by-One Interval Bug in Picker     │ Critical │ Very Low  │ Low        │ Domain   │
│ P0   │ Fix CoroutineScope Lockup in DownloadJob  │ Critical │ Very Low  │ Low        │ Worker   │
│ P1   │ Fix SyncPreferences Key Clobbering        │ High     │ Very Low  │ Low        │ Settings │
│ P1   │ Fix SQL Syntax Error in animes.sq         │ High     │ Very Low  │ Low        │ Database │
│ P1   │ Fix Screen Trapping in AiringScheduleTab  │ High     │ Very Low  │ Low        │ UI/Nav   │
│ P1   │ Add Schedule Workers to PreferenceRestorer│ High     │ Very Low  │ Low        │ Backup   │
│ P2   │ Decouple Worker Lifecycle from LaunchedEff│ High     │ Low       │ Medium     │ Arch     │
│ P2   │ Fix Settings Dependency Hierarchy         │ Medium   │ Very Low  │ Low        │ Settings │
│ P2   │ Unify Updates Calendar & Schedule Routes  │ High     │ Low       │ Medium     │ UI/UX    │
│ P2   │ Migrate Scheduled Alarm Keys to SQLite    │ High     │ Low       │ Medium     │ Database │
│ P3   │ Add Notification Quick Actions & DeepLink │ Medium   │ Low       │ Medium     │ System   │
│ P3   │ Add Error Counter Badges in More Tab      │ Medium   │ Very Low  │ Low        │ UI/UX    │
└──────┴───────────────────────────────────────────┴──────────┴───────────┴────────────┴──────────┘
```

---

### 6.2 Concrete Code-Level Engineering Solutions

#### Solution 1: Fix Off-by-One Interval Bug (`AnimeDialogs.kt`)
- **Target**: `app/src/main/java/eu/kanade/presentation/anime/components/AnimeDialogs.kt:284`
- **Diff**:
```kotlin
// BEFORE
val newValue = if (!isScheduledMode) {
    when (selectedIntervalIndex) {
        0 -> FetchInterval.MANUAL_DISABLE
        1 -> 0
        else -> -selectedIntervalIndex
    }
}

// AFTER (CORRECTED)
val newValue = if (!isScheduledMode) {
    when (selectedIntervalIndex) {
        0 -> FetchInterval.MANUAL_DISABLE
        1 -> 0
        else -> -(selectedIntervalIndex - 1)
    }
}
```

---

#### Solution 2: Fix Coroutine Scope Lockup (`DownloadJob.kt`)
- **Target**: `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadJob.kt:70-85`
- **Diff**:
```kotlin
// BEFORE
setForegroundSafely()

coroutineScope {
    combineTransform(
        applicationContext.networkStateFlow(),
        downloadPreferences.downloadOnlyOverWifi().changes(),
        transform = { a, b -> emit(checkNetworkState(a, b)) },
    )
        .onEach { networkCheck = it }
        .launchIn(this)
}

while (active) {
    delay(1000)
    active = !isStopped && downloadManager.isRunning && (networkCheck || downloadManager.isLocalPhase)
}

// AFTER (CORRECTED)
setForegroundSafely()

val networkJob = combineTransform(
    applicationContext.networkStateFlow(),
    downloadPreferences.downloadOnlyOverWifi().changes(),
    transform = { a, b -> emit(checkNetworkState(a, b)) },
)
    .onEach { networkCheck = it }
    .launchIn(this)

try {
    while (active) {
        delay(1000)
        active = !isStopped && downloadManager.isRunning && (networkCheck || downloadManager.isLocalPhase)
    }
} finally {
    networkJob.cancel()
}
```

---

#### Solution 3: Fix `SyncPreferences.kt` Key Mapping
- **Target**: `app/src/main/java/eu/kanade/domain/sync/SyncPreferences.kt:78`
- **Diff**:
```kotlin
// BEFORE
preferenceStore.getBoolean("anime_tracking", true).set(syncSettings.animeTracking)
preferenceStore.getBoolean("anime_history", true).set(syncSettings.history)
preferenceStore.getBoolean("appSettings", true).set(syncSettings.appSettings)

// AFTER (CORRECTED)
preferenceStore.getBoolean("anime_tracking", true).set(syncSettings.animeTracking)
preferenceStore.getBoolean("history", true).set(syncSettings.history)
preferenceStore.getBoolean("anime_history", true).set(syncSettings.animeHistory)
preferenceStore.getBoolean("appSettings", true).set(syncSettings.appSettings)
```

---

#### Solution 4: Fix SQL Syntax Error (`animes.sq`)
- **Target**: `data/src/main/sqldelight/tachiyomi/data/animes.sq:167`
- **Diff**:
```sql
-- BEFORE
deleteAnimesNotInLibraryAndNotSeenBySourceIds:
DELETE FROM animes
WHERE favorite = 0 AND source IN :sourceIdsAND AND _id NOT IN (

-- AFTER (CORRECTED)
deleteAnimesNotInLibraryAndNotSeenBySourceIds:
DELETE FROM animes
WHERE favorite = 0 AND source IN :sourceIds AND _id NOT IN (
```

---

#### Solution 5: Fix Preference Restorer Worker Scheduling (`PreferenceRestorer.kt`)
- **Target**: `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt:28-30`
- **Diff**:
```kotlin
// BEFORE
LibraryUpdateJob.setupTask(context)
BackupCreateJob.setupTask(context)

// AFTER (CORRECTED)
LibraryUpdateJob.setupTask(context)
BackupCreateJob.setupTask(context)

val schedulePreferences = Injekt.get<SchedulePreferences>()
if (schedulePreferences.autoRefreshEnabled().get()) {
    ScheduleDataRefreshWorker.schedule(context, schedulePreferences.autoRefreshFrequency().get())
}
if (schedulePreferences.uploadDelayEnabled().get()) {
    ScheduleRefreshWorker.schedule(context, schedulePreferences.uploadDelayRefreshInterval().get())
}
```

---

#### Solution 6: Fix Screen Trapping in `AiringScheduleTab.kt`
- **Target**: `app/src/main/java/mihon/feature/airingschedule/AiringScheduleTab.kt:113`
- **Diff**:
```kotlin
// In AiringScheduleTab.kt topBar definition
val tabNavigator = LocalTabNavigator.current
val uiPreferences = remember { Injekt.get<UiPreferences>() }
val visibleTabs by uiPreferences.bottomNavTabs().changes().collectAsState(initial = uiPreferences.bottomNavTabs().get())
val isTabInBottomBar = remember(visibleTabs) { visibleTabs.contains(AiringScheduleTab.options.title) }

TopAppBar(
    navigationIcon = {
        if (!isTabInBottomBar) {
            IconButton(onClick = { tabNavigator.current = MoreTab }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(MR.strings.action_back),
                )
            }
        }
    },
    title = { ... },
    actions = { ... }
)
```

---

#### Solution 7: Fix Broken Preference Hierarchy (`SettingsScheduleScreen.kt`)
- **Target**: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsScheduleScreen.kt:161-173`
- **Diff**:
```kotlin
// In SettingsScheduleScreen.kt PreferenceGroup
Preference.PreferenceItem.ListPreference(
    pref = schedulePreferences.uploadDelayRefreshInterval(),
    title = "Refresh interval",
    subtitle = "How often to re-check and continuously refine the learned upload delay per source using a running average",
    entries = intervalOptions,
    enabled = uploadDelayEnabled,
),
Preference.PreferenceItem.EditTextPreference(
    pref = schedulePreferences.customUploadDelayMinutes(),
    title = "Custom delay (minutes)",
    subtitle = "Only used when Refresh interval is set to Custom...",
    enabled = uploadDelayEnabled && uploadDelayInterval == SchedulePreferences.UploadDelayInterval.CUSTOM,
    onValueChanged = { it.trim().toLongOrNull()?.let { minutes -> minutes in -24 * 60..24 * 60 } ?: false },
),
```

---

## 7. Conclusion & Strategic Roadmap

The AniZen schedule module is built on advanced technical ideas: live AniList GraphQL synchronization, empirical upload delay tracking using exponential moving averages, multi-stage segmented video assembly with local-phase fault isolation, and exact alarm broadcast dispatching.

However, resolving the **P0 calculation and concurrency defects** (`AnimeDialogs.kt` off-by-one error and `DownloadJob.kt` coroutine scope blocking), fixing the **P1 schema and preference corruption bugs** (`animes.sq` and `SyncPreferences.kt`), and correcting the **navigation screen trapping and broken settings hierarchies** will elevate the AniZen schedule subsystem into a robust, intuitive, and cohesive experience.
