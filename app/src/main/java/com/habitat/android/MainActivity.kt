package com.habitat.android

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnSettings: Button
    private lateinit var connectionConfig: ConnectionConfig

    private val client = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionConfig = ConnectionConfig(this)

        tvStatus = findViewById(R.id.tvStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSettings = findViewById(R.id.btnSettings)

        btnRefresh.setOnClickListener { attemptConnection() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        // Первая попытка подключения при запуске
        attemptConnection()
    }

    private fun attemptConnection() {
        tvStatus.text = getString(R.string.status_connecting)
        btnRefresh.isEnabled = false

        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) { connectToServer() }

            if (success) {
                tvStatus.text = getString(R.string.status_connected)
                Toast.makeText(
                    this@MainActivity,
                    R.string.toast_connection_success,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                tvStatus.text = getString(R.string.status_disconnected)
                Toast.makeText(
                    this@MainActivity,
                    R.string.toast_connection_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }

            btnRefresh.isEnabled = true
        }
    }

    private fun connectToServer(): Boolean {
        return try {
            val url = "${connectionConfig.getBaseUrl()}/health"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etIpAddress = dialogView.findViewById<TextInputEditText>(R.id.etIpAddress)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.etPort)

        etIpAddress.setText(connectionConfig.ipAddress)
        etPort.setText(connectionConfig.port.toString())

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_btn_save) { _, _ ->
                val newIp = etIpAddress.text.toString().trim()
                val newPort = etPort.text.toString().toIntOrNull()

                if (newIp.isNotEmpty() && newPort != null) {
                    connectionConfig.ipAddress = newIp
                    connectionConfig.port = newPort
                    Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}