package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.sportslive.scores.EspnScoreProvider
import com.nuvio.tv.core.sportslive.scores.GameSummarySource
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/** Live scores (Sports & Live): the ESPN provider on its own small HTTP cache. */
@Module
@InstallIn(SingletonComponent::class)
object ScoresModule {
    private const val CACHE_BYTES = 10L * 1024 * 1024

    /**
     * ESPN scoreboards get their own ~10 MB cache, apart from the app's, honouring ESPN's max-age;
     * the provider asks for no-cache for today and yesterday and accepts up to 7-day-old copies of
     * older days. Game summaries bypass it (see [EspnScoreProvider.summary]).
     */
    @Provides
    @Singleton
    fun provideEspnScoreProvider(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        moshi: Moshi
    ): EspnScoreProvider = EspnScoreProvider(
        okHttpClient.newBuilder().cache(Cache(File(context.cacheDir, "espn_scores"), CACHE_BYTES)).build(),
        moshi
    )

    @Provides
    fun provideScoreProvider(espn: EspnScoreProvider): ScoreProvider = espn

    @Provides
    fun provideGameSummarySource(espn: EspnScoreProvider): GameSummarySource = espn
}
