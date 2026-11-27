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
import org.json.JSONArray
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

/** Author of a message. */
data class MessagePerson(
    val id: String,
    val email: String,
    val displayName: String,
)

/** A user who can read the message, with the read receipt. */
data class MessageRecipient(
    val userId: String,
    val email: String,
    val displayName: String,
    val readAt: String?,
    val isRead: Boolean,
)

/** A single message returned by `/api/v1/messages`. */
data class Message(
    val id: String,
    val text: String,
    val sender: MessagePerson,
    val isOwner: Boolean,
    val readAt: String?,
    val isRead: Boolean,
    val recipientsCount: Int,
    val readCount: Int,
    val recipients: List<MessageRecipient>,
    val createdAt: String,
    val updatedAt: String,
)

/** A page of messages. */
data class MessagePage(
    val items: List<Message>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** A server-side notification delivered to the current user. */
data class AppNotification(
    val id: String,
    val kind: String,
    val entityId: String?,
    val shareId: String?,
    val actorId: String?,
    val entityType: String?,
    val textPreview: String,
    val readAt: String?,
    val isRead: Boolean,
    val createdAt: String,
)

/** A page of notifications. */
data class NotificationPage(
    val items: List<AppNotification>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** A single item of a checklist. */
data class ChecklistItem(
    val id: String,
    val text: String,
    val isChecked: Boolean,
    val position: Long,
    val createdAt: String,
    val updatedAt: String,
)

/** A user who has access to a checklist. */
data class ListRecipient(
    val userId: String,
    val email: String,
    val displayName: String,
    val permission: String,
    val grantedAt: String,
)

/** A checklist returned by `/api/v1/lists`. */
data class HabitatList(
    val id: String,
    val title: String,
    val owner: MessagePerson,
    val isOwner: Boolean,
    val canEdit: Boolean,
    val permission: String,
    val itemsTotal: Int,
    val itemsChecked: Int,
    val recipientsCount: Int,
    val items: List<ChecklistItem>,
    val recipients: List<ListRecipient>,
    val createdAt: String,
    val updatedAt: String,
)

/** A page of checklists. */
data class ListPage(
    val items: List<HabitatList>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/** An item draft used when creating a checklist. */
data class ChecklistItemDraft(
    val text: String,
    val isChecked: Boolean = false,
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

    /**
     * Fetches messages for the current user.
     *
     * @param box one of `inbox`, `sent`, `all`.
     * @param limit page size, 1..100.
     * @param offset page offset, >= 0.
     */
    suspend fun listMessages(box: String, limit: Int = 20, offset: Int = 0): MessagePage {
        val response = authenticatedRequest(
            "GET",
            "/api/v1/messages?box=$box&limit=$limit&offset=$offset",
        )
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseMessagePage(JSONObject(response.body))
    }

    /** Fetches a single message available to the current user. */
    suspend fun getMessage(id: String): Message {
        val response = authenticatedRequest("GET", "/api/v1/messages/$id")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseMessage(JSONObject(response.body))
    }

    /** Sends a message to one or more recipients (email or UUID). */
    suspend fun sendMessage(text: String, recipients: List<String>): Message {
        val body = JSONObject()
            .put("text", text)
            .put("recipients", JSONArray(recipients))

        val response = authenticatedRequest("POST", "/api/v1/messages", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseMessage(JSONObject(response.body))
    }

    /** Marks the message as read by the current recipient. Idempotent. */
    suspend fun markMessageRead(id: String) {
        val response = authenticatedRequest("POST", "/api/v1/messages/$id/read")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
    }

    /** Grants access to the current user's message to additional recipients. */
    suspend fun shareMessage(id: String, recipients: List<String>): List<MessageRecipient> {
        val body = JSONObject().put("recipients", JSONArray(recipients))
        val response = authenticatedRequest("POST", "/api/v1/messages/$id/shares", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseRecipients(JSONObject(response.body))
    }

    /** Revokes a recipient's access to the current user's message. */
    suspend fun revokeMessageShare(id: String, granteeId: String) {
        val response = authenticatedRequest("DELETE", "/api/v1/messages/$id/shares/$granteeId")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
    }

    /** Fetches server-side notifications for the current user. */
    suspend fun listNotifications(
        unreadOnly: Boolean = false,
        limit: Int = 20,
        offset: Int = 0,
    ): NotificationPage {
        val response = authenticatedRequest(
            "GET",
            "/api/v1/notifications?unread=$unreadOnly&limit=$limit&offset=$offset",
        )
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseNotificationPage(JSONObject(response.body))
    }

    /** Marks a notification as read. Idempotent and only for the owner. */
    suspend fun markNotificationRead(id: String) {
        val response = authenticatedRequest("POST", "/api/v1/notifications/$id/read")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
    }

    /** Fetches checklists available to the current user. */
    suspend fun listLists(limit: Int = 20, offset: Int = 0): ListPage {
        val response = authenticatedRequest(
            "GET",
            "/api/v1/lists?limit=$limit&offset=$offset",
        )
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseListPage(JSONObject(response.body))
    }

    /** Fetches a single checklist with its items and, for the owner, recipients. */
    suspend fun getList(id: String): HabitatList {
        val response = authenticatedRequest("GET", "/api/v1/lists/$id")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseHabitatList(JSONObject(response.body))
    }

    /** Creates a checklist and optionally shares it with recipients. */
    suspend fun createList(
        title: String,
        items: List<ChecklistItemDraft>,
        recipients: List<String>,
    ): HabitatList {
        val itemsJson = JSONArray()
        for (item in items) {
            itemsJson.put(
                JSONObject()
                    .put("text", item.text)
                    .put("is_checked", item.isChecked),
            )
        }

        val body = JSONObject()
            .put("title", title)
            .put("items", itemsJson)
            .put("recipients", JSONArray(recipients))

        val response = authenticatedRequest("POST", "/api/v1/lists", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseHabitatList(JSONObject(response.body))
    }

    /** Renames a checklist. Requires the owner or write permission. */
    suspend fun updateList(id: String, title: String): HabitatList {
        val body = JSONObject().put("title", title)
        val response = authenticatedRequest("PATCH", "/api/v1/lists/$id", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseHabitatList(JSONObject(response.body))
    }

    /** Archives a checklist. Only the owner may do this. */
    suspend fun archiveList(id: String) {
        val response = authenticatedRequest("DELETE", "/api/v1/lists/$id")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
    }

    /** Appends an item to the checklist. */
    suspend fun addListItem(
        id: String,
        text: String,
        isChecked: Boolean = false,
    ): HabitatList {
        val body = JSONObject()
            .put("text", text)
            .put("is_checked", isChecked)
        val response = authenticatedRequest("POST", "/api/v1/lists/$id/items", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseHabitatList(JSONObject(response.body))
    }

    /** Updates the text and/or checked state of a checklist item. */
    suspend fun updateListItem(
        id: String,
        itemId: String,
        text: String? = null,
        isChecked: Boolean? = null,
    ): HabitatList {
        val body = JSONObject()
        if (text != null) body.put("text", text)
        if (isChecked != null) body.put("is_checked", isChecked)

        val response = authenticatedRequest("PATCH", "/api/v1/lists/$id/items/$itemId", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseHabitatList(JSONObject(response.body))
    }

    /** Deletes a checklist item. */
    suspend fun deleteListItem(id: String, itemId: String): HabitatList {
        val response = authenticatedRequest("DELETE", "/api/v1/lists/$id/items/$itemId")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseHabitatList(JSONObject(response.body))
    }

    /** Grants write access to the checklist to additional recipients. */
    suspend fun shareList(id: String, recipients: List<String>): List<ListRecipient> {
        val body = JSONObject().put("recipients", JSONArray(recipients))
        val response = authenticatedRequest("POST", "/api/v1/lists/$id/shares", body)
        if (!response.isSuccessful) {
            throw apiException(response)
        }
        return parseListRecipients(JSONObject(response.body).optJSONArray("items") ?: JSONArray())
    }

    /** Revokes a recipient's access to the checklist. */
    suspend fun revokeListShare(id: String, granteeId: String) {
        val response = authenticatedRequest("DELETE", "/api/v1/lists/$id/shares/$granteeId")
        if (!response.isSuccessful) {
            throw apiException(response)
        }
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

    private fun parseMessagePage(data: JSONObject): MessagePage {
        val items = data.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<Message>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { list.add(parseMessage(it)) }
        }
        return MessagePage(
            items = list,
            total = data.optInt("total", list.size),
            limit = data.optInt("limit", list.size),
            offset = data.optInt("offset", 0),
        )
    }

    private fun parseMessage(data: JSONObject): Message {
        val senderData = data.optJSONObject("sender") ?: JSONObject()
        return Message(
            id = data.optString("id"),
            text = data.optString("text"),
            sender = MessagePerson(
                id = senderData.optString("id"),
                email = senderData.optString("email"),
                displayName = senderData.optString("display_name"),
            ),
            isOwner = data.optBoolean("is_owner"),
            readAt = nullableString(data, "read_at"),
            isRead = data.optBoolean("is_read"),
            recipientsCount = data.optInt("recipients_count"),
            readCount = data.optInt("read_count"),
            recipients = parseRecipients(data),
            createdAt = data.optString("created_at"),
            updatedAt = data.optString("updated_at"),
        )
    }

    private fun parseRecipients(data: JSONObject): List<MessageRecipient> {
        val items = data.optJSONArray("items") ?: data.optJSONArray("recipients") ?: JSONArray()
        val list = ArrayList<MessageRecipient>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            list.add(
                MessageRecipient(
                    userId = item.optString("user_id"),
                    email = item.optString("email"),
                    displayName = item.optString("display_name"),
                    readAt = nullableString(item, "read_at"),
                    isRead = item.optBoolean("is_read") || !item.isNull("read_at"),
                ),
            )
        }
        return list
    }

    private fun parseNotificationPage(data: JSONObject): NotificationPage {
        val items = data.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<AppNotification>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val payload = item.optJSONObject("payload")
            list.add(
                AppNotification(
                    id = item.optString("id"),
                    kind = item.optString("kind"),
                    entityId = nullableString(item, "entity_id"),
                    shareId = nullableString(item, "share_id"),
                    actorId = nullableString(item, "actor_id"),
                    entityType = payload?.optString("entity_type")?.takeIf { it.isNotBlank() },
                    textPreview = payload
                        ?.optString("text_preview")
                        .orEmpty(),
                    readAt = nullableString(item, "read_at"),
                    isRead = item.optBoolean("is_read") || !item.isNull("read_at"),
                    createdAt = item.optString("created_at"),
                ),
            )
        }
        return NotificationPage(
            items = list,
            total = data.optInt("total", list.size),
            limit = data.optInt("limit", list.size),
            offset = data.optInt("offset", 0),
        )
    }

    private fun parseListPage(data: JSONObject): ListPage {
        val items = data.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<HabitatList>(items.length())
        for (i in 0 until items.length()) {
            items.optJSONObject(i)?.let { list.add(parseHabitatList(it)) }
        }
        return ListPage(
            items = list,
            total = data.optInt("total", list.size),
            limit = data.optInt("limit", list.size),
            offset = data.optInt("offset", 0),
        )
    }

    private fun parseHabitatList(data: JSONObject): HabitatList {
        val ownerData = data.optJSONObject("owner") ?: JSONObject()
        val itemsJson = data.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<ChecklistItem>(itemsJson.length())
        for (i in 0 until itemsJson.length()) {
            val item = itemsJson.optJSONObject(i) ?: continue
            items.add(
                ChecklistItem(
                    id = item.optString("id"),
                    text = item.optString("text"),
                    isChecked = item.optBoolean("is_checked"),
                    position = item.optLong("position"),
                    createdAt = item.optString("created_at"),
                    updatedAt = item.optString("updated_at"),
                ),
            )
        }

        return HabitatList(
            id = data.optString("id"),
            title = data.optString("title"),
            owner = MessagePerson(
                id = ownerData.optString("id"),
                email = ownerData.optString("email"),
                displayName = ownerData.optString("display_name"),
            ),
            isOwner = data.optBoolean("is_owner"),
            canEdit = data.optBoolean("can_edit"),
            permission = data.optString("permission"),
            itemsTotal = data.optInt("items_total", items.size),
            itemsChecked = data.optInt("items_checked"),
            recipientsCount = data.optInt("recipients_count"),
            items = items,
            recipients = parseListRecipients(data.optJSONArray("recipients") ?: JSONArray()),
            createdAt = data.optString("created_at"),
            updatedAt = data.optString("updated_at"),
        )
    }

    private fun parseListRecipients(items: JSONArray): List<ListRecipient> {
        val list = ArrayList<ListRecipient>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            list.add(
                ListRecipient(
                    userId = item.optString("user_id"),
                    email = item.optString("email"),
                    displayName = item.optString("display_name"),
                    permission = item.optString("permission"),
                    grantedAt = item.optString("granted_at"),
                ),
            )
        }
        return list
    }

    private fun nullableString(data: JSONObject, key: String): String? {
        if (!data.has(key) || data.isNull(key)) return null
        return data.optString(key).takeIf { it.isNotBlank() }
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
