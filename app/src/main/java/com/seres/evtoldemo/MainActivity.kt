package com.seres.evtoldemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seres.evtoldemo.data.auth.AuthState
import com.seres.evtoldemo.ui.MainRoute
import com.seres.evtoldemo.ui.auth.AuthViewModel
import com.seres.evtoldemo.ui.auth.LoginScreen
import com.seres.evtoldemo.ui.theme.EvtolLanDemoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EvtolLanDemoTheme {
                val authViewModel: AuthViewModel = hiltViewModel()
                val authState by authViewModel.authState.collectAsStateWithLifecycle()
                when (val s = authState) {
                    is AuthState.LoggedOut -> LoginScreen(authViewModel)
                    // key 绑定用户名：切换账号时重建 MainViewModel，加载该用户数据并断点续单
                    is AuthState.LoggedIn -> MainRoute(viewModel = hiltViewModel(key = "main_${s.username}"))
                }
            }
        }
    }
}
