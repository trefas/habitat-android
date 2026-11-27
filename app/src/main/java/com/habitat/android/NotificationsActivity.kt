package com.habitat.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Incoming notifications screen. Supports filtering by unread state, pagination,
 * marking as read and opening the linked message.
 */
class NotificationsActivity : AppCompatActivity(), SessionManager.Listener {

    private lateinit var cbUnreadOnly: CheckBox
    private lateinit var btnRefreshNotifications: Button
    private lateinit var pbNotifications: ProgressBar
    private lateinit var tvNotificationsError: TextView
    private lateinit var tvNotificationsEmpty: TextView
    private lateinit var notificationsScroll: View
    private lateinit var notificationsContainer: LinearLayout
    private lateinit var notificationsPagination: View
    private lateinit var btnPrevNotifications: Button
    private lateinit var tvNotificationsPage: TextView
    private lateinit var btnNextNotifications: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val limit = 20
    private var offset = 0
    private var total = 0
    private var notifications: List<AppNotification> = emptyList()
    private var isLoadingNotifications = false

    private var forcedLogoutShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notifications)

        ApiClient.init(applicationContext)

        cbUnreadOnly = findViewById(R.id.cbUnreadOnly)
        btnRefreshNotifications = findViewById(R.id.btnRefreshNotifications)
        pbNotifications = findViewById(R.id.pbNotifications)
        tvNotificationsError = findViewById(R.id.tvNotificationsError)
        tvNotificationsEmpty = findViewById(R.id.tvNotificationsEmpty)
        notificationsScroll = findViewById(R.id.notificationsScroll)
        notificationsContainer = findViewById(R.id.notificationsContainer)
        notificationsPagination = findViewById(R.id.notificationsPagination)
        btnPrevNotifications = findViewById(R.id.btnPrevNotifications)
        tvNotificationsPage = findViewById(R.id.tvNotificationsPage)
        btnNextNotifications = findViewById(R.id.btnNextNotifications)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnRefreshNotifications.setOnClickListener { loadNotifications() }

        cbUnreadOnly.setOnCheckedChangeListener { _, _ ->
            offset = 0
            loadNotifications()
        }

        btnPrevNotifications.setOnClickListener {
            if (offset == 0) return@setOnClickListener
            offset = (offset - limit).coerceAtLeast(0)
            loadNotifications()
        }

        btnNextNotifications.setOnClickListener {
            if (offset + limit >= total) return@setOnClickListener
            offset += limit
            loadNotifications()
        }

        loadNotifications()
    }

    override fun onStart() {
        super.onStart()
        SessionManager.registerListener(this)
    }

    override fun onStop() {
        SessionManager.unregisterListener(this)
        super.onStop()
    }

    private fun loadNotifications() {
        if (isLoadingNotifications) return
        isLoadingNotifications = true

        pbNotifications.visibility = View.VISIBLE
        tvNotificationsError.visibility = View.GONE
        btnRefreshNotifications.isEnabled = false

        scope.launch {
            try {
                val page = ApiClient.listNotifications(
                    unreadOnly = cbUnreadOnly.isChecked,
                    limit = limit,
                    offset = offset,
                )
                notifications = page.items
                total = page.total
                renderNotifications()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showNotificationsError(e)
                }
            } catch (e: Throwable) {
                showNotificationsError(e)
            } finally {
                isLoadingNotifications = false
                if (!isDestroyed) {
                    pbNotifications.visibility = View.GONE
                    btnRefreshNotifications.isEnabled = true
                }
            }
        }
    }

    private fun showNotificationsError(error: Throwable) {
        tvNotificationsError.text = ErrorMapper.message(this, error)
        tvNotificationsError.visibility = View.VISIBLE
        tvNotificationsEmpty.visibility = View.GONE
        notificationsPagination.visibility = View.GONE
    }

    private fun renderNotifications() {
        notificationsContainer.removeAllViews()

        val hasItems = notifications.isNotEmpty()
        tvNotificationsEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
        notificationsScroll.visibility = if (hasItems) View.VISIBLE else View.GONE
        notificationsPagination.visibility = if (total > 0) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (notification in notifications) {
            val card = inflater.inflate(R.layout.item_notification, notificationsContainer, false)
            bindNotificationCard(card, notification)
            notificationsContainer.addView(card)
        }

        val pageNumber = offset / limit + 1
        val totalPages = ((total + limit - 1) / limit).coerceAtLeast(1)
        tvNotificationsPage.text = getString(R.string.page_info, pageNumber, totalPages)
        btnPrevNotifications.isEnabled = offset > 0
        btnNextNotifications.isEnabled = offset + limit < total
    }

    private fun bindNotificationCard(card: View, notification: AppNotification) {
        val tvKind = card.findViewById<TextView>(R.id.tvNotificationKind)
        val tvStatus = card.findViewById<TextView>(R.id.tvNotificationStatus)
        val tvText = card.findViewById<TextView>(R.id.tvNotificationText)
        val tvMeta = card.findViewById<TextView>(R.id.tvNotificationMeta)
        val btnOpen = card.findViewById<Button>(R.id.btnNotificationOpen)
        val btnRead = card.findViewById<Button>(R.id.btnNotificationRead)

        tvKind.text = kindLabel(notification)
        tvText.text = notification.textPreview.ifBlank { kindLabel(notification) }
        tvMeta.text = formatDate(notification.createdAt)

        tvStatus.text = if (notification.isRead) {
            getString(R.string.message_read)
        } else {
            getString(R.string.message_unread)
        }
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (notification.isRead) R.color.cat_green else R.color.cat_yellow,
            ),
        )

        (card as MaterialCardView).strokeColor = ContextCompat.getColor(
            this,
            if (notification.isRead) R.color.cat_surface1 else R.color.cat_blue,
        )

        btnOpen.visibility = if (notification.entityId.isNullOrBlank()) View.GONE else View.VISIBLE
        btnOpen.setOnClickListener { openNotification(notification) }

        btnRead.visibility = if (notification.isRead) View.GONE else View.VISIBLE
        btnRead.setOnClickListener { markRead(notification) }
    }

    private fun kindLabel(notification: AppNotification): String {
        val isList = notification.entityType == "list"
        return when (notification.kind) {
            "share_received" -> getString(
                if (isList) {
                    R.string.notification_list_share_received
                } else {
                    R.string.notification_share_received
                },
            )
            "share_revoked" -> getString(
                if (isList) {
                    R.string.notification_list_share_revoked
                } else {
                    R.string.notification_share_revoked
                },
            )
            "share_expiring" -> getString(R.string.notification_share_expiring)
            "entity_copied" -> getString(R.string.notification_entity_copied)
            "entity_updated" -> getString(R.string.notification_entity_updated)
            else -> notification.kind
        }
    }

    private fun markRead(notification: AppNotification) {
        scope.launch {
            try {
                ApiClient.markNotificationRead(notification.id)
                applyReadState(notification.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showActionError(e)
                }
            } catch (e: Throwable) {
                showActionError(e)
            }
        }
    }

    private fun openNotification(notification: AppNotification) {
        val entityId = notification.entityId?.takeIf { it.isNotBlank() } ?: return

        scope.launch {
            try {
                if (!notification.isRead) {
                    ApiClient.markNotificationRead(notification.id)
                    applyReadState(notification.id)
                }

                if (notification.entityType == "list") {
                    if (notification.kind != "share_revoked" && !isFinishing && !isDestroyed) {
                        startActivity(
                            Intent(this@NotificationsActivity, ListsActivity::class.java).apply {
                                putExtra(ListsActivity.EXTRA_LIST_ID, entityId)
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            },
                        )
                    }
                    return@launch
                }

                if (!isFinishing && !isDestroyed) {
                    startActivity(
                        Intent(this@NotificationsActivity, MessagesActivity::class.java).apply {
                            putExtra(MessagesActivity.EXTRA_MESSAGE_ID, entityId)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showActionError(e)
                }
            } catch (e: Throwable) {
                showActionError(e)
            }
        }
    }

    private fun applyReadState(notificationId: String) {
        if (cbUnreadOnly.isChecked) {
            notifications = notifications.filterNot { it.id == notificationId }
            total = (total - 1).coerceAtLeast(0)
        } else {
            notifications = notifications.map {
                if (it.id == notificationId) it.copy(isRead = true) else it
            }
        }
        renderNotifications()
    }

    private fun showActionError(error: Throwable) {
        Toast.makeText(
            this,
            ErrorMapper.message(this, error),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun formatDate(raw: String?): String {
        if (raw.isNullOrBlank()) return getString(R.string.value_unknown)
        return raw
            .substringBefore('.')
            .replace('T', ' ')
            .removeSuffix("Z")
    }

    override fun onForcedLogout() {
        SessionManager.clearPending()

        if (isFinishing || isDestroyed || forcedLogoutShown) return
        forcedLogoutShown = true

        AlertDialog.Builder(this)
            .setTitle(R.string.session_expired_title)
            .setMessage(R.string.session_expired_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ -> goToLogin() }
            .show()
    }

    private fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
