package com.pluscubed.velociraptor.api

import android.content.Context
import android.location.Location
import com.android.billingclient.api.Purchase
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import com.pluscubed.velociraptor.BuildConfig
import com.pluscubed.velociraptor.api.osm.OsmLimitProvider
import com.pluscubed.velociraptor.api.raptor.RaptorLimitProvider
import com.pluscubed.velociraptor.cache.LimitCache
import com.pluscubed.velociraptor.utils.Utils
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.util.concurrent.TimeUnit

class LimitFetcher(private val context: Context) {

    private val osmLimitProvider: OsmLimitProvider
    private val raptorLimitProvider: RaptorLimitProvider

    private var lastResponse: LimitResponse? = null
    private var lastNetworkResponse: LimitResponse? = null

    init {

        val client = buildOkHttpClient()

        val cache = LimitCache.getInstance(context)
        this.osmLimitProvider = OsmLimitProvider(context, client, cache)
        this.raptorLimitProvider = RaptorLimitProvider(context, client, cache)
    }

    private fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor()
            loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
            builder.addInterceptor(loggingInterceptor)
        }
        return builder.build()
    }

    suspend fun verifyRaptorService(purchase: Purchase) {
        raptorLimitProvider.verify(purchase)
    }

    suspend fun getSpeedLimit(location: Location): LimitResponse {
        val limitResponses = ArrayList<LimitResponse>()

        val cacheResponses = LimitCache.getInstance(context).getSpeedLimit(location, lastResponse)
        limitResponses.add(cacheResponses[0])

        val networkConnected = Utils.isNetworkConnected(context)

        if (limitResponses.last().speedLimit() == -1 && networkConnected) {
            // Delay network query if the last response was received less than 5 seconds ago
            if (lastNetworkResponse != null) {
                val delayMs = 5000 - (System.currentTimeMillis() - lastNetworkResponse!!.timestamp())
                delay(delayMs)
            }
        }

        // 1. Always try raptor service if cache didn't hit / didn't contain a limit
        if (limitResponses.last().speedLimit() == -1 && networkConnected) {
            val hereResponses = raptorLimitProvider.getSpeedLimit(location, lastResponse, LimitResponse.ORIGIN_HERE)
            if (hereResponses.isNotEmpty())
                limitResponses += hereResponses[0]
        }

        if (limitResponses.last().speedLimit() == -1 && networkConnected) {
            val tomtomResponses = raptorLimitProvider.getSpeedLimit(location, lastResponse, LimitResponse.ORIGIN_TOMTOM)
            if (tomtomResponses.isNotEmpty())
                limitResponses += tomtomResponses[0]
        }

        if (limitResponses.last().speedLimit() == -1 && networkConnected) {
            // 2. Try OSM if the cache hits didn't contain a limit BUT were not from OSM
            //    i.e. query OSM as it might have the limit
            var cachedOsmWithNoLimit = false
            for (cacheRes in cacheResponses) {
                if (cacheRes.origin() == LimitResponse.ORIGIN_OSM) {
                    cachedOsmWithNoLimit = true
                    break
                }
            }
            if (!cachedOsmWithNoLimit) {
                val osmResponse = osmLimitProvider.getSpeedLimit(location, lastResponse)
                limitResponses.add(osmResponse[0])
            }
        }

        //Accumulate debug infos, based on the last response (the one with the speed limit or the last option)
        var finalResponse = limitResponses.map { limitResponse ->
            limitResponse.toBuilder()
        }.reduce { acc, builder ->
            builder.setDebugInfo(acc.debugInfo() + "\n" + builder.debugInfo())
        }.build()


        //Record the last response's timestamp and network response
        if (finalResponse.timestamp() == 0L) {
            finalResponse = finalResponse.toBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .build()
        }
        lastResponse = finalResponse
        if (!finalResponse.fromCache()) {
            lastNetworkResponse = finalResponse
        }


        return finalResponse
    }


    companion object {
        fun buildRetrofit(client: OkHttpClient, baseUrl: String): Retrofit {
            return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addCallAdapterFactory(CoroutineCallAdapterFactory())
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .addConverterFactory(JacksonConverterFactory.create())
                    .build()
        }
    }

}