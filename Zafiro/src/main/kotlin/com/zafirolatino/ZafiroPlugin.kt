package com.zafirolatino

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ZafiroPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Zafiro())
    }
}
