package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/** Loads team logos into the app image loader's disk cache ahead of drawing them (#66). */
internal object TeamLogoPrefetch {
    fun enqueue(context: Context, urls: List<String>) {
        val loader = SingletonImageLoader.get(context)
        for (url in urls) {
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build()
            )
        }
    }
}
