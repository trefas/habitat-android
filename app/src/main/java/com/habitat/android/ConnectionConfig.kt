package com.habitat.android

import android.content.Context
import android.content.SharedPreferences

class ConnectionConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "habitat_prefs"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PORT = "port"
        private const val DEFAULT_IP = "10.0.2.2"  // localhost для Android-эмулятора
        private const val DEFAULT_PORT = 8080
    }

    var ipAddress: String
        get() = prefs.getString(KEY_IP_ADDRESS, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit().putString(KEY_IP_ADDRESS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    fun getBaseUrl(): String {
        return "http://$ipAddress:$port"
    }
}