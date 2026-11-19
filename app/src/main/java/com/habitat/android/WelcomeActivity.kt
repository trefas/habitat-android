package com.habitat.android

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Authenticated screen: shows the profile from `/api/v1/me`, allows refreshing
 * it, changing the password and logging out.
 */
class WelcomeActivity : AppCompatActivity(), SessionManager.Listener {

    private lateinit var tvWelcomeTitle: TextView
    private lateinit var tvDisplayName: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvRole: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCreatedAt: TextView
    private lateinit var tvProfileError: TextView
    private lateinit var profileCard: LinearLayout
    private lateinit var pbProfile: ProgressBar
    private lateinit var btnRetry: Button
    private lateinit var btnMessages: Button
    private lateinit var btnChangePassword: Button
    private lateinit var btnLogout: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var isLoadingProfile = false
    private var isLoggingOut = false
    private var forcedLogoutShown = false
    private var passwordDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        ApiClient.init(applicationContext)

        tvWelcomeTitle = findViewById(R.id.tvWelcomeTitle)
        tvDisplayName = findViewById(R.id.tvDisplayName)
        tvEmail = findViewById(R.id.tvEmail)
        tvRole = findViewById(R.id.tvRole)
        tvStatus = findViewById(R.id.tvStatus)
        tvCreatedAt = findViewById(R.id.tvCreatedAt)
        tvProfileError = findViewById(R.id.tvProfileError)
        profileCard = findViewById(R.id.profileCard)
        pbProfile = findViewById(R.id.pbProfile)
        btnRetry = findViewById(R.id.btnRetry)
        btnMessages = findViewById(R.id.btnMessages)
        btnChangePassword = findViewById(R.id.btnChangePassword)
        btnLogout = findViewById(R.id.btnLogout)

        btnRetry.setOnClickListener { loadProfile() }
        btnMessages.setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
        }
        btnChangePassword.setOnClickListener { showChangePasswordDialog() }
        btnLogout.setOnClickListener { performLogout() }

        loadProfile()
    }

    override fun onStart() {
        super.onStart()
        SessionManager.registerListener(this)
    }

    override fun onStop() {
        SessionManager.unregisterListener(this)
        super.onStop()
    }

    private fun loadProfile() {
        if (isLoadingProfile) return
        isLoadingProfile = true

        pbProfile.visibility = View.VISIBLE
        tvProfileError.visibility = View.GONE
        btnRetry.visibility = View.GONE

        scope.launch {
            try {
                val user = ApiClient.getMe()
                renderProfile(user)
                profileCard.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.statusCode == 401) {
                    // Token was rejected and the session already cleared.
                    onForcedLogout()
                } else {
                    showProfileError(e)
                }
            } catch (e: IOException) {
                showProfileError(e)
            } finally {
                isLoadingProfile = false
                if (!isDestroyed) {
                    pbProfile.visibility = View.GONE
                }
            }
        }
    }

    private fun renderProfile(user: User) {
        val displayName = user.displayName.ifBlank { user.email }
        tvWelcomeTitle.text = getString(R.string.welcome_message, displayName)
        tvDisplayName.text = displayName
        tvEmail.text = user.email
        tvRole.text = row(R.string.label_role, user.role.ifBlank { unknown() })
        tvStatus.text = row(R.string.label_status, user.status ?: unknown())
        tvCreatedAt.text = row(R.string.label_created_at, formatDate(user.createdAt))
    }

    private fun row(labelRes: Int, value: String): String =
        getString(R.string.profile_row, getString(labelRes), value)

    private fun unknown(): String = getString(R.string.value_unknown)

    private fun formatDate(raw: String?): String {
        if (raw.isNullOrBlank()) return unknown()
        return raw
            .substringBefore('.')
            .replace('T', ' ')
            .removeSuffix("Z")
    }

    private fun showProfileError(error: Throwable) {
        profileCard.visibility = View.GONE
        tvProfileError.text = ErrorMapper.message(this, error)
        tvProfileError.visibility = View.VISIBLE
        btnRetry.visibility = View.VISIBLE
    }

    private fun performLogout() {
        if (isLoggingOut) return
        isLoggingOut = true

        btnLogout.isEnabled = false
        btnLogout.text = getString(R.string.btn_logging_out)

        scope.launch {
            try {
                ApiClient.logout()
            } finally {
                SessionManager.clearPending()
                if (!isFinishing && !isDestroyed) {
                    goToLogin()
                }
            }
        }
    }

    override fun onForcedLogout() {
        SessionManager.clearPending()

        if (isFinishing || isDestroyed || forcedLogoutShown) return
        forcedLogoutShown = true

        passwordDialog?.dismiss()

        AlertDialog.Builder(this)
            .setTitle(R.string.session_expired_title)
            .setMessage(R.string.session_expired_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_ok) { _, _ -> goToLogin() }
            .show()
    }

    private fun showChangePasswordDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null)
        val tilCurrentPassword = dialogView.findViewById<TextInputLayout>(R.id.tilCurrentPassword)
        val tilNewPassword = dialogView.findViewById<TextInputLayout>(R.id.tilNewPassword)
        val tilConfirmPassword = dialogView.findViewById<TextInputLayout>(R.id.tilConfirmPassword)
        val etCurrentPassword = dialogView.findViewById<TextInputEditText>(R.id.etCurrentPassword)
        val etNewPassword = dialogView.findViewById<TextInputEditText>(R.id.etNewPassword)
        val etConfirmPassword = dialogView.findViewById<TextInputEditText>(R.id.etConfirmPassword)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.change_password_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_btn_save, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        passwordDialog = dialog

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            positiveButton.setOnClickListener {
                tilCurrentPassword.error = null
                tilNewPassword.error = null
                tilConfirmPassword.error = null

                val currentPassword = etCurrentPassword.text?.toString().orEmpty()
                val newPassword = etNewPassword.text?.toString().orEmpty()
                val confirmation = etConfirmPassword.text?.toString().orEmpty()

                when {
                    currentPassword.isEmpty() || newPassword.isEmpty() || confirmation.isEmpty() -> {
                        Toast.makeText(
                            this,
                            R.string.error_empty_fields,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    newPassword.length < MIN_PASSWORD_LENGTH -> {
                        tilNewPassword.error = getString(R.string.error_password_short)
                    }

                    newPassword != confirmation -> {
                        tilConfirmPassword.error = getString(R.string.error_password_mismatch)
                    }

                    else -> {
                        positiveButton.isEnabled = false

                        scope.launch {
                            try {
                                ApiClient.changePassword(currentPassword, newPassword)
                                dialog.dismiss()
                                SessionManager.clearPending()
                                Toast.makeText(
                                    this@WelcomeActivity,
                                    R.string.password_changed,
                                    Toast.LENGTH_LONG,
                                ).show()
                                goToLogin()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (error: Throwable) {
                                positiveButton.isEnabled = true
                                val message = if (error is ApiException && error.statusCode == 401) {
                                    getString(R.string.error_current_password)
                                } else {
                                    ErrorMapper.message(this@WelcomeActivity, error)
                                }
                                Toast.makeText(
                                    this@WelcomeActivity,
                                    message,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            if (passwordDialog === dialog) {
                passwordDialog = null
            }
        }

        dialog.show()
    }

    private fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
    }

    override fun onDestroy() {
        passwordDialog?.dismiss()
        passwordDialog = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
    }
}
