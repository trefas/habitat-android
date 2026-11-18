package com.habitat.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Data returned by the Habitat API for a single user. */
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val role: String,
    val status: String? = null,
    val createdAt: String? = null,
)

/** Result of a health check against the `/healthz` endpoint. */
sealed class HealthResult {
    object Ok : HealthResult()
    data class HttpError(val code: Int) : HealthResult()
    data class Failure(val error: IOException) : HealthResult()
    object InvalidResponse : HealthResult()
}

/** Error returned by the API (non-2xx response). */
class ApiException(
    val statusCode: Int,
    val errorCode: String?,
    message: String,
) : IOException(message)

/**
 * Single entry point for all Habitat API calls.
 *
 * Tokens are handled here exclusively:
 *  - the access token lives in memory;
 *  - the refresh token is stored by [ConnectionConfig];
 *  - on HTTP 401 the client refreshes the token once and retries the request;
 *  - if refreshing is rejected, the local session is cleared and a forced
 *    logout event is delivered to the UI through [SessionManager].
 */
object ApiClient {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val EMPTY_JSON = "{}"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val refreshMutex = Mutex()

    @Volatile
    private var config: ConnectionConfig? = null

    @Volatile
    private var accessToken: String? = null

    fun init(context: Context) {
        if (config == null) {
            synchronized(this) {
                if (config == null) {
                    config = ConnectionConfig(context.applicationContext)
                }
            }
        }
    }

    private fun requireConfig(): ConnectionConfig =
        config ?: throw IllegalStateException("ApiClient.init() was not called")

    fun hasSession(): Boolean {
        val cfg = requireConfig()
        return !accessToken.isNullOrBlank() || !cfg.refreshToken.isNullOrBlank()
    }

    /** Clears tokens both in memory and in persistent storage. */
    fun clearSession() {
        accessToken = null
        requireConfig().clearAuth()
    }

    suspend fun checkHealth(): HealthResult {
        return try {
            val response = execute("GET", "/healthz", token = null)
            when {
                !response.isSuccessful -> HealthResult.HttpError(response.code)
                response.body.trim().equals("ok", ignoreCase = true) -> HealthResult.Ok
                else -> HealthResult.InvalidResponse
            }
        } catch (e: IOException) {
            HealthResult.Failure(e)
        }
    }

    suspend fun login(email: String, password: String): User {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)

        val response = execute("POST", "/api/v1/auth/login", token = null, jsonBody = body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }

        val data = JSONObject(response.body)
        val newAccessToken = data.optString("access_token")
        val newRefreshToken = data.optString("refresh_token")

        if (newAccessToken.isBlank() || newRefreshToken.isBlank()) {
            throw ApiException(
                statusCode = 500,
                errorCode = "invalid_response",
                message = "Server did not return a token pair",
            )
        }

        accessToken = newAccessToken
        requireConfig().refreshToken = newRefreshToken

        return parseUser(data.optJSONObject("user") ?: JSONObject())
    }

    /** Fetches the current user profile from the protected `/api/v1/me`. */
    suspend fun getMe(): User {
        val response = authenticatedRequest("GET", "/api/v1/me")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseUser(JSONObject(response.body))
    }

    /**
     * Changes the current user's password. The server revokes all sessions on
     * success, so the local session is cleared as well.
     *
     * A wrong current password also produces HTTP 401, which must not be
     * mistaken for an expired access token. The access token is therefore
     * validated first, and the PATCH request itself is never auto-retried.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String) {
        getMe()

        val body = JSONObject()
            .put("current_password", currentPassword)
            .put("new_password", newPassword)

        val response = execute("PATCH", "/api/v1/me/password", accessToken, body)
        if (response.code == 401) {
            throw ApiException(
                statusCode = 401,
                errorCode = "invalid_credentials",
                message = "Current password is incorrect",
            )
        }
        if (!response.isSuccessful) {
            throw apiException(response)
        }

        clearSession()
    }

    /** Revokes the refresh token on the server and clears the local session. */
    suspend fun logout() {
        val refreshToken = requireConfig().refreshToken
        try {
            if (!refreshToken.isNullOrBlank()) {
                val body = JSONObject().put("refresh_token", refreshToken)
                execute("POST", "/api/v1/auth/logout", token = null, jsonBody = body)
            }
        } catch (_: IOException) {
            // Logout is best-effort: local tokens are cleared regardless.
        } finally {
            clearSession()
        }
    }

    private suspend fun authenticatedRequest(
        method: String,
        path: String,
        jsonBody: JSONObject? = null,
    ): HttpResult {
        if (!hasSession()) {
            throw ApiException(401, "no_session", "No active session")
        }

        val usedToken = accessToken
        var response = execute(method, path, usedToken, jsonBody)

        if (response.code == 401) {
            val newToken = refreshAccessToken(usedToken)
            if (newToken == null) {
                failSession()
            }

            response = execute(method, path, newToken, jsonBody)
            if (response.code == 401) {
                failSession()
            }
        }

        return response
    }

    /**
     * Rotates the token pair. Returns the new access token, or null when the
     * refresh token was rejected (expired, revoked, or reused).
     */
    private suspend fun refreshAccessToken(staleToken: String?): String? =
        refreshMutex.withLock {
            // Another coroutine may have refreshed the token while we waited.
            val currentToken = accessToken
            if (!currentToken.isNullOrBlank() && currentToken != staleToken) {
                return@withLock currentToken
            }

            val refreshToken = requireConfig().refreshToken
            if (refreshToken.isNullOrBlank()) {
                return@withLock null
            }

            val body = JSONObject().put("refresh_token", refreshToken)
            val response = execute("POST", "/api/v1/auth/refresh", token = null, jsonBody = body)

            when {
                response.code == 401 || response.code == 403 -> null
                !response.isSuccessful -> throw apiException(response)
                else -> {
                    val data = JSONObject(response.body)
                    val newAccessToken = data.optString("access_token")
                    val newRefreshToken = data.optString("refresh_token")

                    if (newAccessToken.isBlank()) {
                        throw ApiException(
                            statusCode = 500,
                            errorCode = "invalid_response",
                            message = "Server did not return an access token",
                        )
                    }

                    accessToken = newAccessToken
                    if (newRefreshToken.isNotBlank()) {
                        requireConfig().refreshToken = newRefreshToken
                    }
                    newAccessToken
                }
            }
        }

    private fun failSession(): Nothing {
        clearSession()
        SessionManager.notifyForcedLogout()
        throw ApiException(401, "invalid_token", "Session expired or revoked")
    }

    private suspend fun execute(
        method: String,
        path: String,
        token: String?,
        jsonBody: JSONObject? = null,
    ): HttpResult = withContext(Dispatchers.IO) {
        val requestBody = jsonBody?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        val url = requireConfig().getBaseUrl() + path

        try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "HabitatAndroid")

            when (method.uppercase()) {
                "GET" -> builder.get()
                "POST" -> builder.post(requestBody ?: EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE))
                "PATCH" -> builder.patch(requestBody ?: EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE))
                "DELETE" -> builder.delete()
                else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
            }

            if (!token.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $token")
            }

            httpClient.newCall(builder.build()).execute().use { response ->
                HttpResult(
                    code = response.code,
                    body = response.body?.string().orEmpty(),
                    isSuccessful = response.isSuccessful,
                )
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid server address", e)
        }
    }

    private fun apiException(response: HttpResult): ApiException {
        var errorCode: String? = null
        var message = ""

        try {
            val data = JSONObject(response.body)
            errorCode = data.optString("error").takeIf { it.isNotBlank() }
            message = data.optString("message")
        } catch (_: Exception) {
            // Response is not JSON.
        }

        if (message.isBlank()) {
            message = "HTTP ${response.code}"
        }

        return ApiException(response.code, errorCode, message)
    }

    private fun parseUser(data: JSONObject): User {
        val email = data.optString("email")
        return User(
            id = data.optString("id"),
            email = email,
            displayName = data.optString("display_name").ifBlank { email },
            role = data.optString("role"),
            status = data.optString("status").takeIf { it.isNotBlank() },
            createdAt = data.optString("created_at").takeIf { it.isNotBlank() },
        )
    }

    private data class HttpResult(
        val code: Int,
        val body: String,
        val isSuccessful: Boolean,
    )
}
