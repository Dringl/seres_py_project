package com.seres.evtoldemo.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val form by viewModel.form.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "eVTOL 空中出行",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (form.isRegister) "创建账号" else "登录后即可使用",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 28.dp)
            )

            OutlinedTextField(
                value = form.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("用户名（≥3 位，可用手机号）") },
                singleLine = true,
                enabled = !form.loading,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("密码（≥6 位）") },
                singleLine = true,
                enabled = !form.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )

            if (form.error != null) {
                Text(
                    text = form.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }

            Button(
                onClick = viewModel::submit,
                enabled = !form.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            ) {
                if (form.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (form.isRegister) "注册并登录" else "登录")
                }
            }

            TextButton(
                onClick = viewModel::toggleMode,
                enabled = !form.loading,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(if (form.isRegister) "已有账号？去登录" else "没有账号？去注册")
            }
        }
    }
}
