package com.seres.evtoldemo.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seres.evtoldemo.data.auth.AuthState
import com.seres.evtoldemo.data.auth.AuthStore
import com.seres.evtoldemo.data.local.OrderDao
import com.seres.evtoldemo.data.remote.AuthApi
import com.seres.evtoldemo.data.remote.AuthRequestDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class AuthFormState(
    val username: String = "",
    val password: String = "",
    val isRegister: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val authStore: AuthStore,
    private val orderDao: OrderDao
) : ViewModel() {

    val authState: StateFlow<AuthState> = authStore.state

    private val _form = MutableStateFlow(AuthFormState())
    val form: StateFlow<AuthFormState> = _form.asStateFlow()

    fun onUsernameChange(value: String) {
        _form.value = _form.value.copy(username = value, error = null)
    }

    fun onPasswordChange(value: String) {
        _form.value = _form.value.copy(password = value, error = null)
    }

    fun toggleMode() {
        _form.value = _form.value.copy(isRegister = !_form.value.isRegister, error = null)
    }

    fun submit() {
        val current = _form.value
        if (current.loading) return
        val username = current.username.trim()
        val password = current.password
        if (username.length < 3 || password.length < 6) {
            _form.value = current.copy(error = "用户名至少 3 位，密码至少 6 位")
            return
        }
        viewModelScope.launch {
            _form.value = _form.value.copy(loading = true, error = null)
            val result = runCatching {
                val body = AuthRequestDto(username, password)
                if (current.isRegister) authApi.register(body) else authApi.login(body)
            }
            result.onSuccess { resp ->
                // 切换账号前清掉本地订单缓存，避免串号
                runCatching { orderDao.deleteAll() }
                authStore.save(resp.token, resp.username)
                _form.value = AuthFormState()
            }.onFailure { e ->
                _form.value = _form.value.copy(loading = false, error = mapError(current.isRegister, e))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { orderDao.deleteAll() }
            authStore.clear()
            _form.value = AuthFormState()
        }
    }

    private fun mapError(isRegister: Boolean, e: Throwable): String {
        if (e is HttpException) {
            return when (e.code()) {
                409 -> "用户名已被占用"
                401 -> "用户名或密码错误"
                422 -> "用户名至少 3 位，密码至少 6 位"
                else -> "服务器错误（${e.code()}）"
            }
        }
        return if (isRegister) "注册失败，请检查网络" else "登录失败，请检查网络"
    }
}
