package com.autovoice.app.navigation

import android.content.Context
import com.amap.api.maps.MapsInitializer
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.NaviSetting
import com.amap.api.services.core.ServiceSettings
import com.autovoice.voicebusiness.navigation.NavigationMapEvent

/** Called only after the user has seen and accepted the vendor privacy disclosure. */
internal fun createAmapNavigation(
    context: Context,
    key: String,
    onEvent: (NavigationMapEvent) -> Unit,
): AndroidNavigationMap? {
    if (key.isBlank()) return null
    val app = context.applicationContext
    return try {
        MapsInitializer.updatePrivacyShow(app, true, true)
        MapsInitializer.updatePrivacyAgree(app, true)
        NaviSetting.updatePrivacyShow(app, true, true)
        NaviSetting.updatePrivacyAgree(app, true)
        ServiceSettings.updatePrivacyShow(app, true, true)
        ServiceSettings.updatePrivacyAgree(app, true)
        AMapNavi.setApiKey(app, key)
        MapsInitializer.setApiKey(key)
        ServiceSettings.getInstance().setApiKey(key)
        AmapNavigationMap(app, AMapNavi.getInstance(app) ?: return null, onEvent)
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}
