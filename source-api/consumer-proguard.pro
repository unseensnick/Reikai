# Types extensions implement, extend or construct
-keep class eu.kanade.tachiyomi.source.Source { public protected *; }
-keep class eu.kanade.tachiyomi.source.Source$DefaultImpls { public protected *; }
-keep class eu.kanade.tachiyomi.source.CatalogueSource { public protected *; }
-keep class eu.kanade.tachiyomi.source.CatalogueSource$DefaultImpls { public protected *; }
-keep class eu.kanade.tachiyomi.source.ConfigurableSource { public protected *; }
-keep class eu.kanade.tachiyomi.source.SourceFactory { public protected *; }
-keep class eu.kanade.tachiyomi.source.UnmeteredSource { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.Filter { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.Filter$* { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.FilterList { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.MangasPage { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.Page { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.SChapter { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.SChapter$Companion { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.SManga { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.SManga$Companion { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.SMangaUpdate { public protected *; }
-keep class eu.kanade.tachiyomi.source.model.UpdateStrategy { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.HttpSource { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.ParsedHttpSource { public protected *; }
# RK -->
# Reikai's extension-facing types beyond upstream's: novel APKs implement SourceTracker, RateLimited and
# ResolvableSource, and Komikku-built extensions the other online interfaces and PagePreviewSource. Nothing
# in the app implements ResolvableSource, so unkept R8 drops getUriType's parameter.
-keep class eu.kanade.tachiyomi.source.SourceTracker { public protected *; }
-keep class eu.kanade.tachiyomi.source.SourceTracker$DefaultImpls { public protected *; }
-keep class eu.kanade.tachiyomi.source.RateLimited { public protected *; }
-keep class eu.kanade.tachiyomi.source.RateLimited$DefaultImpls { public protected *; }
-keep class eu.kanade.tachiyomi.source.PagePreviewSource { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
# Extensions on older libs call preferenceKey() and sourcePreferences() here, and nothing in the app
# does, so R8 deletes both classes without this. Upstream's list omits them too.
-keep,allowoptimization class eu.kanade.tachiyomi.source.ConfigurableSourceKt { public *; }
-keep,allowoptimization class eu.kanade.tachiyomi.source.ConfigurableSource$DefaultImpls { public *; }
# RK <--

# Final classes and top-level functions extensions only call into
-keep,allowoptimization class eu.kanade.tachiyomi.AppInfo { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.HttpException { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.JavaScriptEngine { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.NetworkHelper { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.OkHttpExtensionsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.RequestsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }
