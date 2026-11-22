-keep class eu.kanade.tachiyomi.animesource.** { *; }
-keep class eu.kanade.tachiyomi.animesource.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.** extends eu.kanade.tachiyomi.animesource.AnimeSource { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.AnimeSourceFactory { *; }
-keep class eu.kanade.tachiyomi.animesource.** extends eu.kanade.tachiyomi.torrentutils.** { public protected *; }

-keep class **$DefaultImpls { *; }

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }

-keep class exh.metadata.** { public protected *; }

# ANZ -->
-keep class fi.iki.elonen.** { public protected *; }
# ANZ <--
