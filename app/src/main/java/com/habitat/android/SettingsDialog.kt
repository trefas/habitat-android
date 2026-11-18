package com.habitat.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/**
 * Dialog with server connection settings (protocol, host, port).
 * Changing the server invalidates the current session.
 */
object SettingsDialog {
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        ApiClient.init(context)
        val config = ConnectionConfig(context)

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_settings, null)
        val etIpAddress = dialogView.findViewById<TextInputEditText>(R.id.etIpAddress)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.etPort)
        val swHttps = dialogView.findViewById<SwitchMaterial>(R.id.swHttps)

        etIpAddress.setText(config.ipAddress)
        etPort.setText(config.port.toString())
        swHttps.isChecked = config.protocol == "https"

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_btn_save, null)
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ipAddress = etIpAddress.text?.toString()?.trim().orEmpty()
                val port = etPort.text?.toString()?.trim()?.toIntOrNull()

                if (ipAddress.isEmpty() || port == null || port !in 1..65535) {
                    Toast.makeText(context, R.string.error_invalid_connection, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val oldBaseUrl = config.getBaseUrl()
                val protocol = if (swHttps.isChecked) "https" else "http"

                config.setConnection(protocol, ipAddress, port)

                if (oldBaseUrl != config.getBaseUrl()) {
                    // Never reuse tokens from another server.
                    ApiClient.clearSession()
                    SessionManager.clearPending()
                }

                Toast.makeText(context, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onSaved?.invoke()
            }
        }

        dialog.show()
    }
}
