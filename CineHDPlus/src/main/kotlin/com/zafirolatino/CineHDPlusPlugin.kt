package com.zafirolatino

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineHDPlusPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineHDPlus())
    }
}
