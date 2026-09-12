Looking to report an issue/bug or make a feature request? Please refer to the [README file](https://salmanbappi/AniZen#issues-feature-requests-and-contributing).

---

Thanks for your interest in contributing to AniZen!


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://salmanbappi/AniZen/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

## Getting help

- Join [the Discord server](https://discord.gg/J2wmZqEJnS) for online help and to ask questions while developing.

# Translations

Translations are done externally via [Weblate](https://hosted.weblate.org/projects/salmanbappi/anizen/). See [our GitHub](https://github.com/salmanbappi/AniZen) for more details.


# Forks

Forks are allowed so long as they abide by [the project's LICENSE](https://github.com/tachiyomiorg/tachiyomi/blob/master/LICENSE).

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the [app update checker](https://salmanbappi/AniZen/blob/master/app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt)
- To avoid installation conflicts:
    - Change the `applicationId` in [`build.gradle.kts`](https://salmanbappi/AniZen/blob/master/app/build.gradle.kts)
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you want to use ACRA crash reporting, replace the `ACRA_URI` endpoint in [`build.gradle.kts`](https://salmanbappi/AniZen/blob/master/app/build.gradle.kts) with your own

# Linting

To auto-fix formatting, run `./gradlew spotlessApply`. `./gradlew spotlessCheck` must pass; CI runs
`detekt` as well.

# Upstream merging & provenance markers

AniZen descends from the Anikku/Aniyomi lineage, but its git history was squashed: it shares **no
commits** with Anikku, so a plain `git merge` finds no merge base and conflicts on nearly every file.
Fork hygiene — not git ancestry — is what keeps upstream syncs possible. Full details live in
[`AGENTS.md`](AGENTS.md).

## Mark your changes

Wrap every line you add or edit for AniZen (imports excluded) in:

```kotlin
// ANZ -->
... your code ...
// ANZ <--
```

Do **not** remove inherited markers. `// ANK`, `// KMK`, `// SY` and `// AY` map to other upstreams
and must survive a merge.

## Strings

Add new strings to `i18n-ank/` (resource class `AMR`). Do not add strings to `i18n/`, `i18n-sy/` or
`i18n-kmk/` — those are frozen upstream modules. Never edit non-`base` locale files; Weblate owns
translations.

## Check before pushing

```bash
scripts/check-provenance.sh                        # fork hygiene
scripts/anz-upstream.sh report --mirror --fetch    # what upstream changed
```

