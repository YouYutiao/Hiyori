package eu.kanade.tachiyomi.data.prewarm

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.domain.source.service.SourceManager

@BindingContainer
object PrewarmBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providePrewarmConstraints(context: Context): PrewarmConstraints = AndroidPrewarmConstraints(context)

    @Provides
    @SingleIn(AppScope::class)
    fun providePrewarmPageSource(
        context: Context,
        downloadProvider: DownloadProvider,
        sourceManager: SourceManager,
    ): PrewarmPageSource = DownloadPrewarmPageSource(context, downloadProvider, sourceManager)

    @Provides
    @SingleIn(AppScope::class)
    fun providePrewarmCacheBridge(context: Context, preferences: ReaderPreferences): PrewarmCacheBridge =
        ImageEnhancementCacheBridge(context, preferences)

    @Provides
    @SingleIn(AppScope::class)
    fun providePrewarmEnhancer(context: Context): PrewarmEnhancer = ImageEnhancerAdapter(context)

    @Provides
    @SingleIn(AppScope::class)
    fun providePrewarmConfigSource(preferences: ReaderPreferences): PrewarmConfigSource =
        ReaderPrewarmConfigSource(preferences)
}
