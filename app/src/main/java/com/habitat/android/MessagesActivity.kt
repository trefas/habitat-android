package com.habitat.android

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Messages screen: incoming, sent and all messages with pagination, composing,
 * read receipts and share/revoke controls for the message owner.
 */
class MessagesActivity : AppCompatActivity(), SessionManager.Listener {

    private lateinit var boxToggle: MaterialButtonToggleGroup
    private lateinit var btnRefreshMessages: Button
    private lateinit var btnComposeMessage: Button
    private lateinit var btnNotifications: Button
    private lateinit var pbMessages: ProgressBar
    private lateinit var tvMessagesError: TextView
    private lateinit var tvMessagesEmpty: TextView
    private lateinit var messagesScroll: View
    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesPagination: View
    private lateinit var btnPrevPage: Button
    private lateinit var tvPage: TextView
    private lateinit var btnNextPage: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var currentBox = "inbox"
    private val limit = 20
    private var offset = 0
    private var total = 0
    private var messages: List<Message> = emptyList()
    private var isLoadingMessages = false

    private var composeDialog: AlertDialog? = null
    private var detailDialog: AlertDialog? = null
    private var detailView: View? = null
    private var detailMessage: Message? = null
    private var isDetailLoading = false

    private var forcedLogoutShown = false
    private var isEnding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)

        ApiClient.init(applicationContext)

        boxToggle = findViewById(R.id.boxToggle)
        btnRefreshMessages = findViewById(R.id.btnRefreshMessages)
        btnComposeMessage = findViewById(R.id.btnComposeMessage)
        btnNotifications = findViewById(R.id.btnNotifications)
        pbMessages = findViewById(R.id.pbMessages)
        tvMessagesError = findViewById(R.id.tvMessagesError)
        tvMessagesEmpty = findViewById(R.id.tvMessagesEmpty)
        messagesScroll = findViewById(R.id.messagesScroll)
        messagesContainer = findViewById(R.id.messagesContainer)
        messagesPagination = findViewById(R.id.messagesPagination)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPage = findViewById(R.id.tvPage)
        btnNextPage = findViewById(R.id.btnNextPage)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnRefreshMessages.setOnClickListener { loadMessages() }
        btnComposeMessage.setOnClickListener { showComposeDialog() }
        btnNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }

        btnPrevPage.setOnClickListener {
            if (offset == 0) return@setOnClickListener
            offset = (offset - limit).coerceAtLeast(0)
            loadMessages()
        }

        btnNextPage.setOnClickListener {
            if (offset + limit >= total) return@setOnClickListener
            offset += limit
            loadMessages()
        }

        boxToggle.check(R.id.btnInbox)
        boxToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val box = when (checkedId) {
                R.id.btnSent -> "sent"
                R.id.btnAll -> "all"
                else -> "inbox"
            }

            if (box != currentBox) {
                currentBox = box
                offset = 0
                loadMessages()
            }
        }

        loadMessages()
        consumeRequestedMessage(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRequestedMessage(intent)
    }

    override fun onStart() {
        super.onStart()
        SessionManager.registerListener(this)
    }

    override fun onStop() {
        SessionManager.unregisterListener(this)
        super.onStop()
    }

    private fun consumeRequestedMessage(intent: Intent?) {
        val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_MESSAGE_ID)
        openMessageById(messageId)
    }

    private fun loadMessages() {
        if (isLoadingMessages) return
        isLoadingMessages = true

        pbMessages.visibility = View.VISIBLE
        tvMessagesError.visibility = View.GONE
        btnRefreshMessages.isEnabled = false

        scope.launch {
            try {
                val page = ApiClient.listMessages(currentBox, limit, offset)
                messages = page.items
                total = page.total
                renderMessages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showMessagesError(e)
                }
            } catch (e: Throwable) {
                showMessagesError(e)
            } finally {
                isLoadingMessages = false
                if (!isDestroyed) {
                    pbMessages.visibility = View.GONE
                    btnRefreshMessages.isEnabled = true
                }
            }
        }
    }

    private fun showMessagesError(error: Throwable) {
        tvMessagesError.text = ErrorMapper.message(this, error)
        tvMessagesError.visibility = View.VISIBLE
        tvMessagesEmpty.visibility = View.GONE
        messagesPagination.visibility = View.GONE
    }

    private fun renderMessages() {
        messagesContainer.removeAllViews()

        val hasMessages = messages.isNotEmpty()
        tvMessagesEmpty.visibility = if (hasMessages) View.GONE else View.VISIBLE
        messagesScroll.visibility = if (hasMessages) View.VISIBLE else View.GONE
        messagesPagination.visibility = if (total > 0) View.VISIBLE else View.GONE
        if (hasMessages) {
            tvMessagesEmpty.visibility = View.GONE
        }

        val inflater = LayoutInflater.from(this)
        for (message in messages) {
            val card = inflater.inflate(R.layout.item_message, messagesContainer, false)
            bindMessageCard(card, message)
            card.setOnClickListener { showDetailDialog(message) }
            messagesContainer.addView(card)
        }

        val pageNumber = offset / limit + 1
        val totalPages = ((total + limit - 1) / limit).coerceAtLeast(1)
        tvPage.text = getString(R.string.page_info, pageNumber, totalPages)
        btnPrevPage.isEnabled = offset > 0
        btnNextPage.isEnabled = offset + limit < total
    }

    private fun bindMessageCard(card: View, message: Message) {
        val unread = !message.isOwner && !message.isRead

        val tvSender = card.findViewById<TextView>(R.id.tvMessageSender)
        val tvStatus = card.findViewById<TextView>(R.id.tvMessageStatus)
        val tvPreview = card.findViewById<TextView>(R.id.tvMessagePreview)
        val tvMeta = card.findViewById<TextView>(R.id.tvMessageMeta)

        tvSender.text = if (message.isOwner) {
            getString(R.string.message_from_you)
        } else {
            message.sender.displayName.ifBlank { message.sender.email }
        }
        tvSender.setTextColor(ContextCompat.getColor(this, if (unread) R.color.cat_blue else R.color.cat_text))

        tvStatus.text = when {
            message.isOwner -> getString(
                R.string.messages_owner_status,
                message.readCount,
                message.recipientsCount,
            )
            message.isRead -> getString(R.string.message_read)
            else -> getString(R.string.message_unread)
        }
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    message.isOwner || message.isRead -> R.color.cat_green
                    else -> R.color.cat_yellow
                },
            ),
        )

        tvPreview.text = message.text
        tvMeta.text = if (message.isOwner) {
            getString(
                R.string.messages_meta,
                formatDate(message.createdAt),
                getString(R.string.messages_recipients_count, message.recipientsCount),
            )
        } else {
            getString(R.string.messages_meta, message.sender.email, formatDate(message.createdAt))
        }

        (card as MaterialCardView).strokeColor = ContextCompat.getColor(
            this,
            if (unread) R.color.cat_blue else R.color.cat_surface1,
        )
    }

    private fun showComposeDialog() {
        if (composeDialog?.isShowing == true) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_compose_message, null)
        val tilRecipients = dialogView.findViewById<TextInputLayout>(R.id.tilRecipients)
        val tilMessageText = dialogView.findViewById<TextInputLayout>(R.id.tilMessageText)
        val etRecipients = dialogView.findViewById<TextInputEditText>(R.id.etRecipients)
        val etMessageText = dialogView.findViewById<TextInputEditText>(R.id.etMessageText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.messages_compose)
            .setView(dialogView)
            .setPositiveButton(R.string.messages_send, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        composeDialog = dialog

        dialog.setOnShowListener {
            val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            sendButton.setOnClickListener {
                tilRecipients.error = null
                tilMessageText.error = null

                val text = etMessageText.text?.toString().orEmpty().trim()
                val recipients = parseRecipients(etRecipients.text?.toString().orEmpty())

                when {
                    text.isEmpty() -> {
                        tilMessageText.error = getString(R.string.error_empty_message)
                    }

                    recipients.isEmpty() -> {
                        tilRecipients.error = getString(R.string.error_empty_recipients)
                    }

                    else -> {
                        sendButton.isEnabled = false
                        sendButton.text = getString(R.string.messages_sending)

                        scope.launch {
                            try {
                                ApiClient.sendMessage(text, recipients)
                                dialog.dismiss()
                                Toast.makeText(
                                    this@MessagesActivity,
                                    R.string.messages_sent,
                                    Toast.LENGTH_LONG,
                                ).show()
                                openSentBox()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ApiException) {
                                if (e.statusCode == 401) {
                                    onForcedLogout()
                                } else {
                                    Toast.makeText(
                                        this@MessagesActivity,
                                        ErrorMapper.message(this@MessagesActivity, e),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } catch (e: Throwable) {
                                Toast.makeText(
                                    this@MessagesActivity,
                                    ErrorMapper.message(this@MessagesActivity, e),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                if (dialog.isShowing) {
                                    sendButton.isEnabled = true
                                    sendButton.text = getString(R.string.messages_send)
                                }
                            }
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            if (composeDialog === dialog) {
                composeDialog = null
            }
        }

        dialog.show()
    }

    private fun openSentBox() {
        offset = 0
        if (boxToggle.checkedButtonId == R.id.btnSent) {
            currentBox = "sent"
            loadMessages()
        } else {
            boxToggle.check(R.id.btnSent)
        }
    }

    private fun openMessageById(id: String) {
        scope.launch {
            try {
                val message = ApiClient.getMessage(id)
                if (!isFinishing && !isDestroyed) {
                    showDetailDialog(message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    Toast.makeText(
                        this@MessagesActivity,
                        ErrorMapper.message(this@MessagesActivity, e),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(
                    this@MessagesActivity,
                    ErrorMapper.message(this@MessagesActivity, e),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showDetailDialog(message: Message) {
        if (detailDialog?.isShowing == true) {
            detailDialog?.dismiss()
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_message, null)
        detailView = dialogView
        detailMessage = message

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.messages_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_close, null)
            .create()

        detailDialog = dialog

        dialogView.findViewById<Button>(R.id.btnShareRecipient).setOnClickListener {
            shareFromDetail()
        }

        dialog.setOnDismissListener {
            if (detailDialog === dialog) {
                detailDialog = null
                detailView = null
                detailMessage = null
                isDetailLoading = false
                if (!isEnding && !isFinishing && !isDestroyed && !forcedLogoutShown) {
                    loadMessages()
                }
            }
        }

        dialog.show()

        if (message.isOwner) {
            isDetailLoading = true
            renderDetail()
            loadOwnerDetail(message.id)
        } else {
            renderDetail()
            if (!message.isRead) {
                markDetailRead(message.id)
            }
        }
    }

    private fun renderDetail() {
        val view = detailView ?: return
        val message = detailMessage ?: return

        val tvSender = view.findViewById<TextView>(R.id.tvDetailSender)
        val tvMeta = view.findViewById<TextView>(R.id.tvDetailMeta)
        val tvText = view.findViewById<TextView>(R.id.tvDetailText)
        val tvStatus = view.findViewById<TextView>(R.id.tvDetailStatus)
        val ownerSection = view.findViewById<View>(R.id.ownerSection)
        val tvDetailError = view.findViewById<TextView>(R.id.tvDetailError)

        tvSender.text = if (message.isOwner) {
            getString(R.string.message_from_you)
        } else {
            message.sender.displayName.ifBlank { message.sender.email }
        }
        tvMeta.text = getString(
            R.string.messages_meta,
            message.sender.email,
            formatDate(message.createdAt),
        )
        tvText.text = message.text

        tvStatus.text = when {
            message.isOwner -> getString(
                R.string.messages_owner_status,
                message.readCount,
                message.recipientsCount,
            )
            message.isRead -> getString(R.string.message_read)
            else -> getString(R.string.message_unread)
        }
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (message.isOwner || message.isRead) R.color.cat_green else R.color.cat_yellow,
            ),
        )

        tvDetailError.visibility = View.GONE
        ownerSection.visibility = if (message.isOwner) View.VISIBLE else View.GONE

        if (message.isOwner) {
            renderRecipients(message)
        }
    }

    private fun renderRecipients(message: Message) {
        val view = detailView ?: return
        val container = view.findViewById<LinearLayout>(R.id.recipientsContainer)
        val tvNoRecipients = view.findViewById<TextView>(R.id.tvNoRecipients)

        container.removeAllViews()

        tvNoRecipients.visibility = if (!isDetailLoading && message.recipients.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        for (recipient in message.recipients) {
            container.addView(createRecipientRow(recipient))
        }
    }

    private fun createRecipientRow(recipient: MessageRecipient): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val name = TextView(this).apply {
            text = recipient.displayName.ifBlank { recipient.email }
            setTextColor(ContextCompat.getColor(context, R.color.cat_text))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val email = TextView(this).apply {
            text = recipient.email
            setTextColor(ContextCompat.getColor(context, R.color.cat_subtext0))
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        info.addView(name)
        info.addView(email)

        val status = TextView(this).apply {
            text = if (recipient.isRead) {
                getString(R.string.message_read_at, formatDate(recipient.readAt))
            } else {
                getString(R.string.message_unread)
            }
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (recipient.isRead) R.color.cat_green else R.color.cat_yellow,
                ),
            )
            textSize = 11f
            setPadding(dp(8), 0, dp(8), 0)
        }

        val revoke = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = getString(R.string.btn_revoke)
            textSize = 12f
            minimumWidth = 0
            setTextColor(ContextCompat.getColor(context, R.color.cat_red))
            setOnClickListener { confirmRevoke(recipient) }
        }

        row.addView(info)
        row.addView(status)
        row.addView(revoke)
        return row
    }

    private fun loadOwnerDetail(messageId: String) {
        val view = detailView ?: return
        val pbDetail = view.findViewById<ProgressBar>(R.id.pbDetail)

        isDetailLoading = true
        pbDetail.visibility = View.VISIBLE

        scope.launch {
            try {
                val full = ApiClient.getMessage(messageId)
                if (detailDialog?.isShowing == true && detailMessage?.id == messageId) {
                    detailMessage = full
                    isDetailLoading = false
                    renderDetail()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showDetailError(e)
                }
            } catch (e: Throwable) {
                showDetailError(e)
            } finally {
                isDetailLoading = false
                if (detailDialog?.isShowing == true) {
                    pbDetail.visibility = View.GONE
                }
            }
        }
    }

    private fun markDetailRead(messageId: String) {
        scope.launch {
            try {
                ApiClient.markMessageRead(messageId)
                val message = detailMessage
                if (detailDialog?.isShowing == true && message?.id == messageId) {
                    detailMessage = message.copy(isRead = true)
                    renderDetail()
                }
                messages = messages.map {
                    if (it.id == messageId) it.copy(isRead = true) else it
                }
                renderMessages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showDetailError(e)
                }
            } catch (e: Throwable) {
                showDetailError(e)
            }
        }
    }

    private fun shareFromDetail() {
        val view = detailView ?: return
        val message = detailMessage ?: return

        val tilShareRecipients = view.findViewById<TextInputLayout>(R.id.tilShareRecipients)
        val etShareRecipients = view.findViewById<TextInputEditText>(R.id.etShareRecipients)
        val shareButton = view.findViewById<Button>(R.id.btnShareRecipient)
        val pbShare = view.findViewById<ProgressBar>(R.id.pbShare)

        tilShareRecipients.error = null
        clearShareMessages()

        val recipients = parseRecipients(etShareRecipients.text?.toString().orEmpty())
        if (recipients.isEmpty()) {
            tilShareRecipients.error = getString(R.string.error_empty_recipients)
            return
        }

        shareButton.isEnabled = false
        pbShare.visibility = View.VISIBLE

        scope.launch {
            try {
                val items = ApiClient.shareMessage(message.id, recipients)
                if (detailDialog?.isShowing == true && detailMessage?.id == message.id) {
                    detailMessage = message.copy(
                        recipients = items,
                        recipientsCount = items.size,
                        readCount = items.count { it.isRead },
                    )
                    etShareRecipients.text?.clear()
                    renderDetail()
                    showShareNotice(R.string.messages_share_done)
                }
                loadMessages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showShareError(e)
                }
            } catch (e: Throwable) {
                showShareError(e)
            } finally {
                if (detailDialog?.isShowing == true) {
                    shareButton.isEnabled = true
                    pbShare.visibility = View.GONE
                }
            }
        }
    }

    private fun confirmRevoke(recipient: MessageRecipient) {
        AlertDialog.Builder(this)
            .setMessage(
                getString(
                    R.string.messages_revoke_confirm,
                    recipient.displayName.ifBlank { recipient.email },
                ),
            )
            .setPositiveButton(R.string.btn_revoke) { _, _ -> revokeRecipient(recipient) }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun revokeRecipient(recipient: MessageRecipient) {
        val view = detailView ?: return
        val message = detailMessage ?: return

        val shareButton = view.findViewById<Button>(R.id.btnShareRecipient)
        val pbShare = view.findViewById<ProgressBar>(R.id.pbShare)

        clearShareMessages()
        shareButton.isEnabled = false
        pbShare.visibility = View.VISIBLE

        scope.launch {
            try {
                ApiClient.revokeMessageShare(message.id, recipient.userId)
                val full = ApiClient.getMessage(message.id)
                if (detailDialog?.isShowing == true && detailMessage?.id == message.id) {
                    detailMessage = full
                    isDetailLoading = false
                    renderDetail()
                    showShareNotice(R.string.messages_revoke_done)
                }
                loadMessages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showShareError(e)
                }
            } catch (e: Throwable) {
                showShareError(e)
            } finally {
                if (detailDialog?.isShowing == true) {
                    shareButton.isEnabled = true
                    pbShare.visibility = View.GONE
                }
            }
        }
    }

    private fun showDetailError(error: Throwable) {
        val view = detailView ?: return
        val tvDetailError = view.findViewById<TextView>(R.id.tvDetailError)
        tvDetailError.text = ErrorMapper.message(this, error)
        tvDetailError.visibility = View.VISIBLE
    }

    private fun showShareError(error: Throwable) {
        val view = detailView ?: return
        val tvShareError = view.findViewById<TextView>(R.id.tvShareError)
        tvShareError.text = ErrorMapper.message(this, error)
        tvShareError.visibility = View.VISIBLE
    }

    private fun showShareNotice(noticeRes: Int) {
        val view = detailView ?: return
        val tvShareNotice = view.findViewById<TextView>(R.id.tvShareNotice)
        tvShareNotice.setText(noticeRes)
        tvShareNotice.visibility = View.VISIBLE
    }

    private fun clearShareMessages() {
        val view = detailView ?: return
        view.findViewById<TextView>(R.id.tvShareError).visibility = View.GONE
        view.findViewById<TextView>(R.id.tvShareNotice).visibility = View.GONE
    }

    private fun parseRecipients(raw: String): List<String> =
        raw.split(Regex("[\\s,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun formatDate(raw: String?): String {
        if (raw.isNullOrBlank()) return getString(R.string.value_unknown)
        return raw
            .substringBefore('.')
            .replace('T', ' ')
            .removeSuffix("Z")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onForcedLogout() {
        SessionManager.clearPending()

        if (isFinishing || isDestroyed || forcedLogoutShown) return
        forcedLogoutShown = true

        composeDialog?.dismiss()
        detailDialog?.dismiss()

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
        isEnding = true
        composeDialog?.dismiss()
        detailDialog?.dismiss()
        composeDialog = null
        detailDialog = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "com.habitat.android.extra.MESSAGE_ID"
    }
}
