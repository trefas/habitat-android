package com.habitat.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the server connection settings and the refresh token.
 *
 * The access token is intentionally kept in memory only (see [ApiClient]); the
 * refresh token is persisted so the session can be restored after a restart.
 */
class ConnectionConfig(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "habitat_prefs"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_IP_ADDRESS = "ip_address"
        private const val KEY_PORT = "port"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val LEGACY_KEY_AUTH_TOKEN = "auth_token"
        private const val DEFAULT_PROTOCOL = "http"
        private const val DEFAULT_IP = "10.0.2.2"  // localhost для Android-эмулятора
        private const val DEFAULT_PORT = 8080
    }

    var protocol: String
        get() = prefs.getString(KEY_PROTOCOL, DEFAULT_PROTOCOL) ?: DEFAULT_PROTOCOL
        set(value) = prefs.edit().putString(KEY_PROTOCOL, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var ipAddress: String
        get() = prefs.getString(KEY_IP_ADDRESS, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit().putString(KEY_IP_ADDRESS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /**
     * Normalizes the host and returns the base URL without a trailing slash.
     */
    fun getBaseUrl(): String {
        val host = ipAddress
            .trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trim()
            .trimEnd('/')

        val formattedHost = if (host.count { it == ':' } >= 2 && !host.startsWith("[")) {
            "[$host]"
        } else {
            host
        }

        return "$protocol://$formattedHost:$port"
    }

    /**
     * Saves connection settings. If the resulting base URL changed, the stored
     * session is invalidated so tokens are never sent to another server.
     */
    fun setConnection(newProtocol: String, newIpAddress: String, newPort: Int) {
        val oldBaseUrl = getBaseUrl()

        prefs.edit()
            .putString(KEY_PROTOCOL, newProtocol)
            .putString(KEY_IP_ADDRESS, newIpAddress)
            .putInt(KEY_PORT, newPort)
            .apply()

        if (oldBaseUrl != getBaseUrl()) {
            clearAuth()
        }
    }

    fun clearAuth() {
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(LEGACY_KEY_AUTH_TOKEN)
            .apply()
    }
}
