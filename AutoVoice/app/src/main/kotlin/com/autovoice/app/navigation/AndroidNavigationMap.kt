package com.autovoice.app.navigation

import android.content.Context
import android.view.View
import com.autovoice.voicebusiness.navigation.NavigationMapPort

internal interface AndroidNavigationMap : NavigationMapPort {
    fun createView(context: Context): View?
    fun resumeView(view: View)
    fun pauseView(view: View)
    fun releaseView(view: View)
}
