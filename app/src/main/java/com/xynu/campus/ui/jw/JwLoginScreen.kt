package com.xynu.campus.ui.jw

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import com.xynu.campus.ui.common.AppTopBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xynu.campus.ui.theme.XynuRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JwLoginScreen(
    vm: JwViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val state by vm.state.collectAsState()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var saveCredentials by remember { mutableStateOf(true) }
    val alreadyLogged = state.loggedIn

    if (alreadyLogged) {
        LaunchedEffect(Unit) { onSuccess() }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = "教务登录", onBack = onBack)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Logo
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(XynuRed, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("教", color = androidx.compose.ui.graphics.Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text("教务系统", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("信阳师范大学 · 正方教务服务平台", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Gray)

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("学号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Person, null) }
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Filled.Lock, null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = "显示密码"
                        )
                    }
                }
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveCredentials, onCheckedChange = { saveCredentials = it })
                Text("记住账号密码（本地加密保存）", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.DarkGray)
            }

            if (state.loginMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val isError = !state.loginMessage.startsWith("登录成功")
                Text(
                    state.loginMessage,
                    color = if (isError) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    vm.login(username, password, saveCredentials)
                },
                enabled = !state.loggingIn,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                if (state.loggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("登录中…")
                } else {
                    Text("登 录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "账号为学号，密码与教务系统一致\n登录数据仅用于查询，账号信息加密保存在本机",
                fontSize = 11.sp,
                color = androidx.compose.ui.graphics.Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}