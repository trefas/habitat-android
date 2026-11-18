package com.habitat.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Entry point of the app. Checks that the Habitat server is reachable and then
 * routes either to the login screen or, when a refresh token is stored, to the
 * authenticated welcome screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvServerUrl: TextView
    private lateinit var tvError: TextView
    private lateinit var pbChecking: ProgressBar
    private lateinit var btnRefresh: Button
    private lateinit var btnSettings: Button

    private lateinit var connectionConfig: ConnectionConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isChecking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ApiClient.init(applicationContext)
        connectionConfig = ConnectionConfig(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvServerUrl = findViewById(R.id.tvServerUrl)
        tvError = findViewById(R.id.tvError)
        pbChecking = findViewById(R.id.pbChecking)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSettings = findViewById(R.id.btnSettings)

        btnRefresh.setOnClickListener { attemptConnection() }
        btnSettings.setOnClickListener {
            SettingsDialog.show(this) {
                updateServerLabel()
                attemptConnection()
            }
        }

        updateServerLabel()
        attemptConnection()
    }

    override fun onResume() {
        super.onResume()
        updateServerLabel()
    }

    private fun updateServerLabel() {
        tvServerUrl.text = getString(R.string.server_label, connectionConfig.getBaseUrl())
    }

    private fun attemptConnection() {
        if (isChecking) return
        isChecking = true

        tvStatus.text = getString(R.string.status_connecting)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.cat_subtext0))
        tvError.visibility = View.GONE
        pbChecking.visibility = View.VISIBLE
        btnRefresh.isEnabled = false

        scope.launch {
            val health = ApiClient.checkHealth()

            if (isFinishing || isDestroyed) return@launch

            isChecking = false
            pbChecking.visibility = View.GONE
            btnRefresh.isEnabled = true

            when (health) {
                HealthResult.Ok -> {
                    tvStatus.text = getString(R.string.status_connected)
                    tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cat_green))

                    if (ApiClient.hasSession()) {
                        navigateTo(WelcomeActivity::class.java)
                    } else {
                        navigateTo(LoginActivity::class.java)
                    }
                }

                is HealthResult.HttpError -> showConnectionError(
                    getString(R.string.error_http, health.code),
                )

                is HealthResult.Failure -> showConnectionError(
                    ErrorMapper.message(this@MainActivity, health.error),
                )

                HealthResult.InvalidResponse -> showConnectionError(
                    getString(R.string.error_unexpected_response),
                )
            }
        }
    }

    private fun showConnectionError(message: String) {
        tvStatus.text = getString(R.string.status_disconnected)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.cat_red))
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    /**
     * Connection status is the root screen, so once the server is reachable the
     * task is cleared. Back from the login/welcome screen then exits the app.
     */
    private fun navigateTo(target: Class<out Activity>) {
        startActivity(
            Intent(this, target).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
