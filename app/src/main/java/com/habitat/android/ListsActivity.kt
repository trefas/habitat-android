package com.habitat.android

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
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
 * Checklists screen: overview with pagination, creation, item editing and
 * sharing. Recipients with write access can edit the list; only the owner can
 * grant/revoke access and delete the list.
 */
class ListsActivity : AppCompatActivity(), SessionManager.Listener {

    private lateinit var btnRefreshLists: Button
    private lateinit var btnCreateList: Button
    private lateinit var pbLists: ProgressBar
    private lateinit var tvListsError: TextView
    private lateinit var tvListsEmpty: TextView
    private lateinit var listsScroll: View
    private lateinit var listsContainer: LinearLayout
    private lateinit var listsPagination: View
    private lateinit var btnPrevLists: Button
    private lateinit var tvListsPage: TextView
    private lateinit var btnNextLists: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val limit = 20
    private var offset = 0
    private var total = 0
    private var lists: List<HabitatList> = emptyList()
    private var isLoadingLists = false

    private var createDialog: AlertDialog? = null
    private var detailDialog: AlertDialog? = null
    private var detailView: View? = null
    private var detailList: HabitatList? = null
    private var isDetailBusy = false

    private var forcedLogoutShown = false
    private var isEnding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lists)

        ApiClient.init(applicationContext)

        btnRefreshLists = findViewById(R.id.btnRefreshLists)
        btnCreateList = findViewById(R.id.btnCreateList)
        pbLists = findViewById(R.id.pbLists)
        tvListsError = findViewById(R.id.tvListsError)
        tvListsEmpty = findViewById(R.id.tvListsEmpty)
        listsScroll = findViewById(R.id.listsScroll)
        listsContainer = findViewById(R.id.listsContainer)
        listsPagination = findViewById(R.id.listsPagination)
        btnPrevLists = findViewById(R.id.btnPrevLists)
        tvListsPage = findViewById(R.id.tvListsPage)
        btnNextLists = findViewById(R.id.btnNextLists)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        btnRefreshLists.setOnClickListener { loadLists() }
        btnCreateList.setOnClickListener { showCreateDialog() }

        btnPrevLists.setOnClickListener {
            if (offset == 0) return@setOnClickListener
            offset = (offset - limit).coerceAtLeast(0)
            loadLists()
        }

        btnNextLists.setOnClickListener {
            if (offset + limit >= total) return@setOnClickListener
            offset += limit
            loadLists()
        }

        loadLists()
        consumeRequestedList(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRequestedList(intent)
    }

    override fun onStart() {
        super.onStart()
        SessionManager.registerListener(this)
    }

    override fun onStop() {
        SessionManager.unregisterListener(this)
        super.onStop()
    }

    private fun consumeRequestedList(intent: Intent?) {
        val listId = intent?.getStringExtra(EXTRA_LIST_ID)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_LIST_ID)
        openListById(listId)
    }

    private fun loadLists() {
        if (isLoadingLists) return
        isLoadingLists = true

        pbLists.visibility = View.VISIBLE
        tvListsError.visibility = View.GONE
        btnRefreshLists.isEnabled = false

        var needsReload = false

        scope.launch {
            try {
                val page = ApiClient.listLists(limit, offset)
                if (page.items.isEmpty() && offset > 0) {
                    offset = (offset - limit).coerceAtLeast(0)
                    needsReload = true
                } else {
                    lists = page.items
                    total = page.total
                    renderLists()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    showListsError(e)
                }
            } catch (e: Throwable) {
                showListsError(e)
            } finally {
                isLoadingLists = false
                if (!isDestroyed) {
                    pbLists.visibility = View.GONE
                    btnRefreshLists.isEnabled = true
                }
                if (needsReload && !isFinishing && !isDestroyed) {
                    loadLists()
                }
            }
        }
    }

    private fun showListsError(error: Throwable) {
        tvListsError.text = ErrorMapper.message(this, error)
        tvListsError.visibility = View.VISIBLE
        tvListsEmpty.visibility = View.GONE
        listsPagination.visibility = View.GONE
    }

    private fun renderLists() {
        listsContainer.removeAllViews()

        val hasItems = lists.isNotEmpty()
        tvListsEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
        listsScroll.visibility = if (hasItems) View.VISIBLE else View.GONE
        listsPagination.visibility = if (total > 0) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(this)
        for (list in lists) {
            val card = inflater.inflate(R.layout.item_list, listsContainer, false)
            bindListCard(card, list)
            card.setOnClickListener { openListById(list.id) }
            listsContainer.addView(card)
        }

        val pageNumber = offset / limit + 1
        val totalPages = ((total + limit - 1) / limit).coerceAtLeast(1)
        tvListsPage.text = getString(R.string.page_info, pageNumber, totalPages)
        btnPrevLists.isEnabled = offset > 0
        btnNextLists.isEnabled = offset + limit < total
    }

    private fun bindListCard(card: View, list: HabitatList) {
        val tvTitle = card.findViewById<TextView>(R.id.tvListTitle)
        val tvPermission = card.findViewById<TextView>(R.id.tvListPermission)
        val pbProgress = card.findViewById<ProgressBar>(R.id.pbListProgress)
        val tvProgress = card.findViewById<TextView>(R.id.tvListProgress)
        val tvOwner = card.findViewById<TextView>(R.id.tvListOwner)
        val tvMeta = card.findViewById<TextView>(R.id.tvListMeta)

        tvTitle.text = list.title

        tvPermission.text = permissionLabel(list)
        tvPermission.setTextColor(permissionColor(list))

        pbProgress.max = list.itemsTotal.coerceAtLeast(1)
        pbProgress.progress = list.itemsChecked

        tvProgress.text = getString(
            R.string.lists_progress,
            list.itemsChecked,
            list.itemsTotal,
        )

        val ownerName = if (list.isOwner) {
            getString(R.string.lists_owner_you)
        } else {
            list.owner.displayName.ifBlank { list.owner.email }
        }
        tvOwner.text = getString(R.string.lists_detail_owner, ownerName, list.owner.email)
        tvMeta.text = formatDate(list.updatedAt)

        (card as MaterialCardView).strokeColor = ContextCompat.getColor(
            this,
            if (list.canEdit) R.color.cat_blue else R.color.cat_surface1,
        )
    }

    private fun openListById(id: String) {
        pbLists.visibility = View.VISIBLE
        btnRefreshLists.isEnabled = false

        scope.launch {
            try {
                val list = ApiClient.getList(id)
                if (!isFinishing && !isDestroyed) {
                    showDetailDialog(list)
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
            } finally {
                if (!isDestroyed) {
                    pbLists.visibility = View.GONE
                    btnRefreshLists.isEnabled = true
                }
            }
        }
    }

    private fun showCreateDialog() {
        if (createDialog?.isShowing == true) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_list_create, null)
        val tilTitle = dialogView.findViewById<TextInputLayout>(R.id.tilListTitle)
        val etTitle = dialogView.findViewById<TextInputEditText>(R.id.etListTitle)
        val draftContainer = dialogView.findViewById<LinearLayout>(R.id.draftItemsContainer)
        val btnAddDraft = dialogView.findViewById<Button>(R.id.btnAddDraftItem)
        val tilRecipients = dialogView.findViewById<TextInputLayout>(R.id.tilCreateListRecipients)
        val etRecipients = dialogView.findViewById<TextInputEditText>(R.id.etCreateListRecipients)
        val tvError = dialogView.findViewById<TextView>(R.id.tvCreateListError)

        fun addDraftRow() {
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_draft_checklist, draftContainer, false)
            row.findViewById<Button>(R.id.btnRemoveDraftItem).setOnClickListener {
                draftContainer.removeView(row)
            }
            draftContainer.addView(row)
        }

        addDraftRow()
        btnAddDraft.setOnClickListener { addDraftRow() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.lists_create)
            .setView(dialogView)
            .setPositiveButton(R.string.lists_create, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        createDialog = dialog

        dialog.setOnShowListener {
            val createButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            createButton.setOnClickListener {
                tilTitle.error = null
                tilRecipients.error = null
                tvError.visibility = View.GONE

                val title = etTitle.text?.toString().orEmpty().trim()
                if (title.isEmpty()) {
                    tilTitle.error = getString(R.string.lists_title_required)
                    return@setOnClickListener
                }
                if (title.length > MAX_TITLE_LENGTH) {
                    tilTitle.error = getString(R.string.lists_title_too_long)
                    return@setOnClickListener
                }

                val items = ArrayList<ChecklistItemDraft>()
                var itemError: String? = null
                for (i in 0 until draftContainer.childCount) {
                    val row = draftContainer.getChildAt(i)
                    val text = row.findViewById<TextInputEditText>(R.id.etDraftItemText)
                        .text?.toString().orEmpty().trim()
                    if (text.isEmpty()) continue
                    if (text.length > MAX_ITEM_LENGTH) {
                        itemError = getString(R.string.lists_item_too_long)
                        break
                    }
                    val checked = row.findViewById<CheckBox>(R.id.cbDraftItemChecked).isChecked
                    items.add(ChecklistItemDraft(text = text, isChecked = checked))
                }

                if (itemError != null) {
                    tvError.text = itemError
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }

                val recipients = parseRecipients(etRecipients.text?.toString().orEmpty())

                createButton.isEnabled = false
                createButton.text = getString(R.string.lists_creating)

                scope.launch {
                    try {
                        val created = ApiClient.createList(title, items, recipients)
                        dialog.dismiss()
                        offset = 0
                        loadLists()
                        Toast.makeText(
                            this@ListsActivity,
                            R.string.lists_created,
                            Toast.LENGTH_LONG,
                        ).show()
                        openListById(created.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.statusCode == 401) {
                            onForcedLogout()
                        } else {
                            showCreateError(tvError, e)
                        }
                    } catch (e: Throwable) {
                        showCreateError(tvError, e)
                    } finally {
                        if (dialog.isShowing) {
                            createButton.isEnabled = true
                            createButton.text = getString(R.string.lists_create)
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            if (createDialog === dialog) {
                createDialog = null
            }
        }

        dialog.show()
    }

    private fun showCreateError(tvError: TextView, error: Throwable) {
        tvError.text = ErrorMapper.message(this, error)
        tvError.visibility = View.VISIBLE
    }

    private fun showDetailDialog(list: HabitatList) {
        if (detailDialog?.isShowing == true) {
            detailDialog?.dismiss()
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_list_detail, null)
        detailView = dialogView
        detailList = list
        isDetailBusy = false

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.lists_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_close, null)
            .create()

        detailDialog = dialog

        dialogView.findViewById<Button>(R.id.btnSaveListTitle).setOnClickListener {
            saveListTitle()
        }
        dialogView.findViewById<Button>(R.id.btnAddListItem).setOnClickListener {
            addListItem()
        }
        dialogView.findViewById<Button>(R.id.btnShareList).setOnClickListener {
            shareList()
        }
        dialogView.findViewById<Button>(R.id.btnArchiveList).setOnClickListener {
            confirmArchive()
        }

        dialog.setOnDismissListener {
            if (detailDialog === dialog) {
                detailDialog = null
                detailView = null
                detailList = null
                isDetailBusy = false
                if (!isEnding && !isFinishing && !isDestroyed && !forcedLogoutShown) {
                    loadLists()
                }
            }
        }

        dialog.show()
        renderDetail()
    }

    private fun renderDetail() {
        val view = detailView ?: return
        val list = detailList ?: return

        val editSection = view.findViewById<View>(R.id.editListTitleSection)
        val tilTitle = view.findViewById<TextInputLayout>(R.id.tilDetailListTitle)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etDetailListTitle)
        val tvTitle = view.findViewById<TextView>(R.id.tvDetailListTitle)
        val tvOwner = view.findViewById<TextView>(R.id.tvDetailListOwner)
        val tvPermission = view.findViewById<TextView>(R.id.tvDetailListPermission)
        val pbProgress = view.findViewById<ProgressBar>(R.id.pbDetailListProgress)
        val tvProgress = view.findViewById<TextView>(R.id.tvDetailListProgress)
        val addItemSection = view.findViewById<View>(R.id.addListItemSection)
        val ownerSection = view.findViewById<View>(R.id.listOwnerSection)
        val tvError = view.findViewById<TextView>(R.id.tvDetailListError)
        val tvShareError = view.findViewById<TextView>(R.id.tvListShareError)
        val tvShareNotice = view.findViewById<TextView>(R.id.tvListShareNotice)

        if (list.canEdit) {
            editSection.visibility = View.VISIBLE
            tvTitle.visibility = View.GONE
            tilTitle.error = null
            if (etTitle.text?.toString() != list.title) {
                etTitle.setText(list.title)
                etTitle.setSelection(etTitle.text?.length ?: 0)
            }
        } else {
            editSection.visibility = View.GONE
            tvTitle.visibility = View.VISIBLE
            tvTitle.text = list.title
        }

        val ownerName = if (list.isOwner) {
            getString(R.string.lists_owner_you)
        } else {
            list.owner.displayName.ifBlank { list.owner.email }
        }
        tvOwner.text = getString(R.string.lists_detail_owner, ownerName, list.owner.email)
        tvPermission.text = permissionLabel(list)
        tvPermission.setTextColor(permissionColor(list))

        pbProgress.max = list.itemsTotal.coerceAtLeast(1)
        pbProgress.progress = list.itemsChecked
        tvProgress.text = getString(R.string.lists_progress, list.itemsChecked, list.itemsTotal)

        tvError.visibility = View.GONE
        addItemSection.visibility = if (list.canEdit) View.VISIBLE else View.GONE
        ownerSection.visibility = if (list.isOwner) View.VISIBLE else View.GONE

        if (!list.isOwner) {
            tvShareError.visibility = View.GONE
            tvShareNotice.visibility = View.GONE
        }

        renderDetailItems(list)
        if (list.isOwner) {
            renderListRecipients(list)
        }
    }

    private fun renderDetailItems(list: HabitatList) {
        val view = detailView ?: return
        val container = view.findViewById<LinearLayout>(R.id.detailItemsContainer)
        val tvNoItems = view.findViewById<TextView>(R.id.tvNoListItems)

        container.removeAllViews()
        tvNoItems.visibility = if (list.items.isEmpty()) View.VISIBLE else View.GONE

        for (item in list.items) {
            container.addView(createItemRow(list, item))
        }
    }

    private fun createItemRow(list: HabitatList, item: ChecklistItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val check = CheckBox(this).apply {
            isChecked = item.isChecked
            isEnabled = list.canEdit
            contentDescription = item.text
        }

        val label = TextView(this).apply {
            text = item.text
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (item.isChecked) R.color.cat_subtext0 else R.color.cat_text,
                ),
            )
            textSize = 14f
            if (item.isChecked) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }

        check.setOnClickListener {
            if (!list.canEdit || isDetailBusy) {
                check.isChecked = item.isChecked
                return@setOnClickListener
            }
            toggleItem(item)
        }

        row.addView(check)
        row.addView(label)

        if (list.canEdit) {
            val edit = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = getString(R.string.lists_item_edit)
                textSize = 11f
                minimumWidth = 0
                setTextColor(ContextCompat.getColor(context, R.color.cat_blue))
                setOnClickListener { promptRenameItem(item) }
            }

            val delete = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = getString(R.string.lists_item_delete)
                textSize = 11f
                minimumWidth = 0
                setTextColor(ContextCompat.getColor(context, R.color.cat_red))
                setOnClickListener { confirmDeleteItem(item) }
            }

            row.addView(edit)
            row.addView(delete)
        }

        return row
    }

    private fun renderListRecipients(list: HabitatList) {
        val view = detailView ?: return
        val container = view.findViewById<LinearLayout>(R.id.listRecipientsContainer)
        val tvNoRecipients = view.findViewById<TextView>(R.id.tvNoListRecipients)

        container.removeAllViews()
        tvNoRecipients.visibility = if (list.recipients.isEmpty()) View.VISIBLE else View.GONE

        for (recipient in list.recipients) {
            container.addView(createRecipientRow(recipient))
        }
    }

    private fun createRecipientRow(recipient: ListRecipient): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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

        val permission = TextView(this).apply {
            text = if (recipient.permission == "write") {
                getString(R.string.lists_permission_write)
            } else {
                getString(R.string.lists_permission_read)
            }
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (recipient.permission == "write") R.color.cat_green else R.color.cat_yellow,
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
        row.addView(permission)
        row.addView(revoke)
        return row
    }

    private fun toggleItem(item: ChecklistItem) {
        val list = detailList ?: return
        if (!list.canEdit || isDetailBusy) return

        setDetailBusy(true)
        scope.launch {
            try {
                val updated = ApiClient.updateListItem(
                    list.id,
                    item.id,
                    isChecked = !item.isChecked,
                )
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    renderDetail()
                }
                loadLists()
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
                setDetailBusy(false)
            }
        }
    }

    private fun promptRenameItem(item: ChecklistItem) {
        if (isDetailBusy) return

        val input = TextInputEditText(this).apply {
            setText(item.text)
            setSelection(item.text.length)
            maxLines = 3
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(
            input,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.lists_item_rename_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_btn_save, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString().orEmpty().trim()
                when {
                    text.isEmpty() -> input.error = getString(R.string.lists_item_required)
                    text.length > MAX_ITEM_LENGTH ->
                        input.error = getString(R.string.lists_item_too_long)
                    else -> {
                        dialog.dismiss()
                        updateItemText(item, text)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun updateItemText(item: ChecklistItem, text: String) {
        val list = detailList ?: return
        if (!list.canEdit) return

        setDetailBusy(true)
        scope.launch {
            try {
                val updated = ApiClient.updateListItem(list.id, item.id, text = text)
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    renderDetail()
                }
                loadLists()
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
                setDetailBusy(false)
            }
        }
    }

    private fun confirmDeleteItem(item: ChecklistItem) {
        if (isDetailBusy) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.lists_item_delete_confirm, item.text))
            .setPositiveButton(R.string.lists_item_delete) { _, _ -> deleteItem(item) }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun deleteItem(item: ChecklistItem) {
        val list = detailList ?: return
        if (!list.canEdit) return

        setDetailBusy(true)
        scope.launch {
            try {
                val updated = ApiClient.deleteListItem(list.id, item.id)
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    renderDetail()
                }
                loadLists()
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
                setDetailBusy(false)
            }
        }
    }

    private fun addListItem() {
        val view = detailView ?: return
        val list = detailList ?: return
        if (!list.canEdit || isDetailBusy) return

        val tilNewItem = view.findViewById<TextInputLayout>(R.id.tilNewListItem)
        val etNewItem = view.findViewById<TextInputEditText>(R.id.etNewListItem)
        tilNewItem.error = null

        val text = etNewItem.text?.toString().orEmpty().trim()
        when {
            text.isEmpty() -> {
                tilNewItem.error = getString(R.string.lists_item_required)
                return
            }
            text.length > MAX_ITEM_LENGTH -> {
                tilNewItem.error = getString(R.string.lists_item_too_long)
                return
            }
        }

        setDetailBusy(true)
        scope.launch {
            try {
                val updated = ApiClient.addListItem(list.id, text)
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    etNewItem.text?.clear()
                    renderDetail()
                }
                loadLists()
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
                setDetailBusy(false)
            }
        }
    }

    private fun saveListTitle() {
        val view = detailView ?: return
        val list = detailList ?: return
        if (!list.canEdit || isDetailBusy) return

        val tilTitle = view.findViewById<TextInputLayout>(R.id.tilDetailListTitle)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etDetailListTitle)
        tilTitle.error = null

        val title = etTitle.text?.toString().orEmpty().trim()
        when {
            title.isEmpty() -> {
                tilTitle.error = getString(R.string.lists_title_required)
                return
            }
            title.length > MAX_TITLE_LENGTH -> {
                tilTitle.error = getString(R.string.lists_title_too_long)
                return
            }
        }

        setDetailBusy(true)
        scope.launch {
            try {
                val updated = ApiClient.updateList(list.id, title)
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    renderDetail()
                    Toast.makeText(
                        this@ListsActivity,
                        R.string.lists_title_updated,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                loadLists()
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
                setDetailBusy(false)
            }
        }
    }

    private fun shareList() {
        val view = detailView ?: return
        val list = detailList ?: return
        if (!list.isOwner || isDetailBusy) return

        val tilRecipients = view.findViewById<TextInputLayout>(R.id.tilShareListRecipients)
        val etRecipients = view.findViewById<TextInputEditText>(R.id.etShareListRecipients)
        val tvShareError = view.findViewById<TextView>(R.id.tvListShareError)
        val tvShareNotice = view.findViewById<TextView>(R.id.tvListShareNotice)

        tilRecipients.error = null
        tvShareError.visibility = View.GONE
        tvShareNotice.visibility = View.GONE

        val recipients = parseRecipients(etRecipients.text?.toString().orEmpty())
        if (recipients.isEmpty()) {
            tilRecipients.error = getString(R.string.error_empty_recipients)
            return
        }

        setDetailBusy(true)
        scope.launch {
            try {
                ApiClient.shareList(list.id, recipients)
                val updated = ApiClient.getList(list.id)
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    etRecipients.text?.clear()
                    renderDetail()
                    tvShareNotice.setText(R.string.lists_share_done)
                    tvShareNotice.visibility = View.VISIBLE
                }
                loadLists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    tvShareError.text = ErrorMapper.message(this@ListsActivity, e)
                    tvShareError.visibility = View.VISIBLE
                }
            } catch (e: Throwable) {
                tvShareError.text = ErrorMapper.message(this@ListsActivity, e)
                tvShareError.visibility = View.VISIBLE
            } finally {
                setDetailBusy(false)
            }
        }
    }

    private fun confirmRevoke(recipient: ListRecipient) {
        if (isDetailBusy) return
        AlertDialog.Builder(this)
            .setMessage(
                getString(
                    R.string.lists_revoke_confirm,
                    recipient.displayName.ifBlank { recipient.email },
                ),
            )
            .setPositiveButton(R.string.btn_revoke) { _, _ -> revokeRecipient(recipient) }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun revokeRecipient(recipient: ListRecipient) {
        val view = detailView ?: return
        val list = detailList ?: return
        if (!list.isOwner) return

        val tvShareError = view.findViewById<TextView>(R.id.tvListShareError)
        val tvShareNotice = view.findViewById<TextView>(R.id.tvListShareNotice)
        tvShareError.visibility = View.GONE
        tvShareNotice.visibility = View.GONE

        setDetailBusy(true)
        scope.launch {
            try {
                ApiClient.revokeListShare(list.id, recipient.userId)
                val updated = ApiClient.getList(list.id)
                if (detailDialog?.isShowing == true && detailList?.id == list.id) {
                    detailList = updated
                    renderDetail()
                    tvShareNotice.setText(R.string.lists_revoke_done)
                    tvShareNotice.visibility = View.VISIBLE
                }
                loadLists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    onForcedLogout()
                } else {
                    tvShareError.text = ErrorMapper.message(this@ListsActivity, e)
                    tvShareError.visibility = View.VISIBLE
                }
            } catch (e: Throwable) {
                tvShareError.text = ErrorMapper.message(this@ListsActivity, e)
                tvShareError.visibility = View.VISIBLE
            } finally {
                setDetailBusy(false)
            }
        }
    }

    private fun confirmArchive() {
        val list = detailList ?: return
        if (!list.isOwner || isDetailBusy) return

        AlertDialog.Builder(this)
            .setMessage(getString(R.string.lists_archive_confirm, list.title))
            .setPositiveButton(R.string.lists_archive) { _, _ -> archiveList() }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun archiveList() {
        val list = detailList ?: return
        if (!list.isOwner) return

        setDetailBusy(true)
        scope.launch {
            try {
                ApiClient.archiveList(list.id)
                detailDialog?.dismiss()
                offset = 0
                loadLists()
                Toast.makeText(
                    this@ListsActivity,
                    R.string.lists_archived,
                    Toast.LENGTH_LONG,
                ).show()
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
                setDetailBusy(false)
            }
        }
    }

    private fun setDetailBusy(busy: Boolean) {
        isDetailBusy = busy
        val view = detailView ?: return

        view.findViewById<ProgressBar>(R.id.pbDetailListAction).visibility =
            if (busy) View.VISIBLE else View.GONE

        view.findViewById<Button>(R.id.btnSaveListTitle).isEnabled = !busy
        view.findViewById<Button>(R.id.btnAddListItem).isEnabled = !busy
        view.findViewById<Button>(R.id.btnShareList).isEnabled = !busy
        view.findViewById<Button>(R.id.btnArchiveList).isEnabled = !busy
    }

    private fun showDetailError(error: Throwable) {
        val view = detailView ?: return
        val tvError = view.findViewById<TextView>(R.id.tvDetailListError)
        tvError.text = ErrorMapper.message(this, error)
        tvError.visibility = View.VISIBLE
    }

    private fun permissionLabel(list: HabitatList): String = when {
        list.isOwner || list.permission == "owner" ->
            getString(R.string.lists_permission_owner)
        list.permission == "write" -> getString(R.string.lists_permission_write)
        else -> getString(R.string.lists_permission_read)
    }

    private fun permissionColor(list: HabitatList): Int = ContextCompat.getColor(
        this,
        if (list.canEdit) R.color.cat_green else R.color.cat_yellow,
    )

    private fun parseRecipients(raw: String): List<String> =
        raw.split(Regex("[\\s,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onForcedLogout() {
        SessionManager.clearPending()

        if (isFinishing || isDestroyed || forcedLogoutShown) return
        forcedLogoutShown = true

        createDialog?.dismiss()
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
        createDialog?.dismiss()
        detailDialog?.dismiss()
        createDialog = null
        detailDialog = null
        detailView = null
        detailList = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LIST_ID = "com.habitat.android.extra.LIST_ID"

        private const val MAX_TITLE_LENGTH = 256
        private const val MAX_ITEM_LENGTH = 500
    }
}
