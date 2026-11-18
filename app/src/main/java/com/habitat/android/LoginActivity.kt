package com.habitat.android

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Email/password authentication.
 *
 * Registration is intentionally not available on Android: accounts are created
 * from the desktop client only.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var btnSettings: Button
    private lateinit var pbLogin: ProgressBar
    private lateinit var tvServerUrl: TextView

    private lateinit var connectionConfig: ConnectionConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isLoggingIn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        ApiClient.init(applicationContext)
        connectionConfig = ConnectionConfig(this)

        tilEmail = findViewById(R.id.tilEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        btnSettings = findViewById(R.id.btnSettings)
        pbLogin = findViewById(R.id.pbLogin)
        tvServerUrl = findViewById(R.id.tvServerUrl)

        btnLogin.setOnClickListener { attemptLogin() }
        btnSettings.setOnClickListener {
            SettingsDialog.show(this) { updateServerLabel() }
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else {
                false
            }
        }

        updateServerLabel()
    }

    override fun onResume() {
        super.onResume()
        updateServerLabel()
    }

    private fun updateServerLabel() {
        tvServerUrl.text = getString(R.string.server_label, connectionConfig.getBaseUrl())
    }

    private fun attemptLogin() {
        if (isLoggingIn) return

        tilEmail.error = null
        tilPassword.error = null

        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.error = getString(R.string.error_email_invalid)
            return
        }

        hideKeyboard()
        setLoading(true)

        scope.launch {
            try {
                ApiClient.login(email, password)
                SessionManager.clearPending()
                goToWelcome()
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(
                        this@LoginActivity,
                        ErrorMapper.message(this@LoginActivity, error),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                if (!isFinishing && !isDestroyed) {
                    setLoading(false)
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        isLoggingIn = loading
        btnLogin.isEnabled = !loading
        btnLogin.text = getString(if (loading) R.string.btn_logging_in else R.string.btn_login)
        pbLogin.visibility = if (loading) View.VISIBLE else View.GONE
        etEmail.isEnabled = !loading
        etPassword.isEnabled = !loading
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etPassword.windowToken, 0)
    }

    private fun goToWelcome() {
        startActivity(
            Intent(this, WelcomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
