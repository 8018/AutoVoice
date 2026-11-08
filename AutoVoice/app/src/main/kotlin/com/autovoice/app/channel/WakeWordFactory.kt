package com.autovoice.app.channel

import android.content.Context
import com.autovoice.adapteriflytek.IflytekWakeWordObserver
import com.autovoice.app.BuildConfig
import com.autovoice.app.WakeWordPort

internal data class WakeWordCredentials(val appId: String, val apiKey: String, val apiSecret: String)

/** Both flavors share this independent IVW observer and the existing process-wide AIKit runtime. */
internal object WakeWordFactory {
    fun create(
        context: Context,
        onWake: (String) -> Unit,
        onError: (Throwable) -> Unit,
        credentials: WakeWordCredentials = WakeWordCredentials(
            BuildConfig.XFYUN_APPID, BuildConfig.XFYUN_API_KEY, BuildConfig.XFYUN_API_SECRET,
        ),
    ): WakeWordPort {
        val observer = IflytekWakeWordObserver(
            appId = credentials.appId, apiKey = credentials.apiKey, apiSecret = credentials.apiSecret,
            onWake = onWake, onError = onError,
        )
        return object : WakeWordPort {
            override val keyword get() = observer.keyword
            override fun initialize() = observer.init(context)
            override fun arm() = observer.arm()
            override fun accept(pcm: ByteArray) = observer.accept(pcm)
            override fun pause() = observer.pause()
            override fun disarm() = observer.disarm()
            override fun close() = observer.close()
        }
    }
}
