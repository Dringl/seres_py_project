package com.seres.evtoldemo.data.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthState {
    data object LoggedOut : AuthState
    data class LoggedIn(val username: String) : AuthState
}

/**
 * 登录态本地存储（SharedPreferences，同步读取以供 OkHttp 拦截器使用）。
 * token 持久化，App 重启后仍保持登录（支持断点续单）。
 */
@Singleton
class AuthStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("evtol_auth", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** 供拦截器同步读取的当前 token（未登录为 null）。 */
    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    val username: String?
        get() = prefs.getString(KEY_USER, null)

    fun save(token: String, username: String) {
        prefs.edit().putString(KEY_TOKEN, token).putString(KEY_USER, username).apply()
        _state.value = AuthState.LoggedIn(username)
    }

    fun clear() {
        prefs.edit().clear().apply()
        _state.value = AuthState.LoggedOut
    }

    private fun readState(): AuthState {
        val token = prefs.getString(KEY_TOKEN, null)
        val user = prefs.getString(KEY_USER, null)
        return if (token != null && user != null) AuthState.LoggedIn(user) else AuthState.LoggedOut
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_USER = "username"
    }
}
