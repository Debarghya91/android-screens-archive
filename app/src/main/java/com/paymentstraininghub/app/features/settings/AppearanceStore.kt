// AppearanceStore.kt
// Device-scoped appearance preferences, mirroring the iOS
// AppStorage keys pf.appearance.darkMode and
// pf.appearance.largerText. Device-scoped, not account-scoped:
// display preferences follow the device, like iOS. PfTheme reads
// the flows, so toggling a setting takes effect immediately.

package com.paymentstraininghub.app.features.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppearanceStore {
    private const val PREFS_NAME = "pf_appearance"
    const val KEY_DARK_MODE = "pf.appearance.darkMode"
    const val KEY_LARGER_TEXT = "pf.appearance.largerText"

    private val _darkMode = MutableStateFlow(false)
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    private val _largerText = MutableStateFlow(false)
    val largerText: StateFlow<Boolean> = _largerText.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _darkMode.value = prefs.getBoolean(KEY_DARK_MODE, false)
        _largerText.value = prefs.getBoolean(KEY_LARGER_TEXT, false)
        initialized = true
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_MODE, enabled)
            .apply()
        _darkMode.value = enabled
    }

    fun setLargerText(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LARGER_TEXT, enabled)
            .apply()
        _largerText.value = enabled
    }
}
