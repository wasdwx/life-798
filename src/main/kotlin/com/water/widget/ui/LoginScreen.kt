package com.water.widget.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoginScreen(
    state: LoginUiState,
    phone: String,
    graphCode: String,
    smsCode: String,
    captchaBitmap: Bitmap?,
    loading: Boolean,
    onPlatformChange: (LoginPlatform) -> Unit,
    onPhoneChange: (String) -> Unit,
    onGraphCodeChange: (String) -> Unit,
    onSmsCodeChange: (String) -> Unit,
    onLoadCaptcha: () -> Unit,
    onSendSms: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val graphCodeFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var previousCaptcha by remember { mutableStateOf(captchaBitmap) }
    LaunchedEffect(captchaBitmap, loading) {
        if (!loading && captchaBitmap != null && captchaBitmap !== previousCaptcha) {
            previousCaptcha = captchaBitmap
            graphCodeFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LoginHero(state = state, loading = loading, onPlatformChange = onPlatformChange, onBack = onBack)

        SectionCard(state.platformTitle, state.nextHint) {
            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("手机号") },
                placeholder = { Text("请输入手机号") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(76.dp)
                        .clip(WaterShapeTokens.compact)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (captchaBitmap != null) {
                        Image(
                            bitmap = captchaBitmap.asImageBitmap(),
                            contentDescription = "图形验证码",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("图形验证码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = WaterShapeTokens.compact,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    IconButton(
                        onClick = onLoadCaptcha,
                        enabled = !loading && state.canLoadCaptcha,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新图形验证码")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = graphCode,
                onValueChange = onGraphCodeChange,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().focusRequester(graphCodeFocusRequester),
                label = { Text("图形验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onSendSms,
                enabled = !loading && state.canSendSms,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("发送短信验证码")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = smsCode,
                onValueChange = onSmsCodeChange,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("短信验证码") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onLogin,
                enabled = !loading && state.canLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (loading) "处理中…" else state.actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LoginHero(
    state: LoginUiState,
    loading: Boolean,
    onPlatformChange: (LoginPlatform) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("登录账户", modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            "先完成用水登录，即可接水和使用钱包；积分登录可稍后添加。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            LoginPlatform.entries.forEach { item ->
                if (item == state.platform) {
                    Button(
                        onClick = { onPlatformChange(item) },
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                        shape = WaterShapeTokens.compact
                    ) { Text(item.title) }
                } else {
                    FilledTonalButton(
                        onClick = { onPlatformChange(item) },
                        enabled = !loading,
                        modifier = Modifier.weight(1f),
                        shape = WaterShapeTokens.compact
                    ) { Text(item.title) }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = WaterShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}
