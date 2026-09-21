-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.Source { public protected *; }
# RK -->
# Novel APKs implement these; neither extends Source, so the line above leaves their members strippable.
-keep class eu.kanade.tachiyomi.source.SourceTracker { public protected *; }
-keep class eu.kanade.tachiyomi.source.RateLimited { public protected *; }
# RK <--

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }
