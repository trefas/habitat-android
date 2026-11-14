package com.habitat.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var connectionConfig: ConnectionConfig
    
    private val client = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        connectionConfig = ConnectionConfig(this)

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)

        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            attemptLogin(username, password)
        }
    }

    private fun attemptLogin(username: String, password: String) {
        btnLogin.isEnabled = false
        btnLogin.text = "Вход..."

        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                performLoginRequest(username, password)
            }

            if (result.isSuccess) {
                val token = result.getOrNull()
                if (!token.isNullOrEmpty()) {
                    connectionConfig.authToken = token
                }
                Toast.makeText(this@LoginActivity, "Успешный вход!", Toast.LENGTH_SHORT).show()
                // Здесь позже будет переход на главный экран приложения
                // Пока просто покажем сообщение и закроем активность
                finish() 
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: getString(R.string.error_auth_failed)
                Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                btnLogin.isEnabled = true
                btnLogin.text = getString(R.string.btn_login)
            }
        }
    }

    private fun performLoginRequest(username: String, password: String): Result<String?> {
        return try {
            // ВНИМАНИЕ: Замените "/api/login" на реальный эндпоинт вашего Go-сервера
            val url = "${connectionConfig.getBaseUrl()}/api/login"
            Log.d("HabitatLogin", "Sending login request to: $url")

            // Формируем JSON-тело запроса
            val json = JSONObject().apply {
                put("username", username)
                put("password", password)
            }.toString()

            val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("HabitatLogin", "Response code: ${response.code}")

                if (response.code == 200) {
                    val responseBody = response.body?.string()
                    Log.d("HabitatLogin", "Response body: $responseBody")

                    // Пытаемся распарсить JSON и извлечь токен
                    try {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        val token = jsonResponse.optString("token", null)

                        if (token != null) {
                            // Сервер вернул токен — успех
                            Result.success(token)
                        } else {
                            // Сервер вернул 200, но без токена
                            // Пока сервер не реализовал /api/login правильно, считаем это ошибкой
                            Log.w("HabitatLogin", "Server returned 200 but no token in response")
                            Result.failure(IOException("Сервер пока не поддерживает аутентификацию"))
                        }
                    } catch (e: Exception) {
                        // Ответ не является JSON
                        Log.w("HabitatLogin", "Response is not valid JSON", e)
                        Result.failure(IOException("Сервер пока не поддерживает аутентификацию"))
                    }
                } else if (response.code == 401 || response.code == 403) {
                    Result.failure(IOException(getString(R.string.error_auth_failed)))
                } else {
                    Result.failure(IOException("Ошибка сервера: ${response.code}"))
                }
            }
        } catch (e: IOException) {
            Log.e("HabitatLogin", "Network error", e)
            Result.failure(e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}