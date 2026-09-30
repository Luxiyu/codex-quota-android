package cn.luxy.codexquota

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: QuotaViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 页面固定浅色：显式使用深色系统栏图标，避免随手机深色模式变成白色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF286754), background = Color(0xFFF5F7F4), surface = Color.White)) {
                val state by model.state.collectAsStateWithLifecycle()
                QuotaScreen(state, model)
            }
        }
    }
}

@Composable private fun QuotaScreen(state: QuotaUiState, model: QuotaViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now().epochSecond; delay(30_000) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.onForeground() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
        .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Codex 额度", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(if (state.signedIn) "${state.plan?.uppercase(Locale.ROOT) ?: "ChatGPT"} · 手机独立查询" else "随时查看你的使用额度", color = Color(0xFF63716A))
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        state.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBE7))) {
                Text(it, Modifier.padding(16.dp), color = Color(0xFF9C3025))
            }
        }
        if (!state.signedIn) {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("登录 ChatGPT", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("使用你在 Codex 中使用的同一账户。登录在官方页面完成。")
                    if (state.userCode != null && state.verificationUrl != null) {
                        Text(state.userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                    .setPrimaryClip(ClipData.newPlainText("登录设备码", state.userCode))
                            }) { Text("复制设备码") }
                            Button(onClick = {
                                if (isOfficialLoginUrl(state.verificationUrl)) context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(state.verificationUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
                            }) { Text("打开官方登录页") }
                        }
                        Text("授权完成后返回本 APP，额度会自动更新。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { model.cancelLogin() }, enabled = !state.busy) { Text("取消登录") }
                    } else Button(onClick = { model.login() }, enabled = !state.busy && state.engineReady) { Text("开始登录") }
                }
            }
        }
        val snapshot = state.snapshot
        WindowCard("5 小时额度", snapshot?.fiveHour, now)
        WindowCard("周额度", snapshot?.weekly, now)
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("免费重置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(snapshot?.availableResets?.let { "${it} 次可用" } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                val details = snapshot?.resetDetails
                when {
                    snapshot == null -> Text("登录后查看重置次数与有效期。")
                    details == null -> Text("服务端暂未提供到期明细。")
                    details.isEmpty() -> Text(if (snapshot.availableResets == 0) "暂无可用的免费重置。" else "服务端暂未提供到期明细。")
                    else -> {
                        details.forEachIndexed { index, credit -> Text("第 ${index + 1} 次：${formatTimestamp(credit.expiresAt)} 到期") }
                        if ((snapshot.availableResets ?: 0) > details.size) Text("以上为服务端提供的部分明细。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (snapshot != null) Text("最后更新 ${formatTimestamp(snapshot.fetchedAt)}${if (state.stale) " · 数据可能已过期" else ""}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF63716A))
        Button(onClick = { model.refresh() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("刷新") }
        if (state.signedIn) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { model.refresh(true) }, enabled = !state.busy) { Text("验证登录续期") }
                TextButton(onClick = { model.logout() }, enabled = !state.busy) { Text("退出登录") }
            }
        }
        Text("仅查询额度 · 时间按手机时区显示", style = MaterialTheme.typography.bodySmall, color = Color(0xFF63716A))
    }
}

@Composable private fun WindowCard(title: String, window: QuotaWindow?, now: Long) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(window?.remainingPercent?.let { "剩余 ${String.format(Locale.CHINA, "%.0f", it)}%" } ?: "—",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            window?.remainingPercent?.let { LinearProgressIndicator(progress = { (it / 100).toFloat() }, modifier = Modifier.fillMaxWidth()) }
            if (window != null) {
                Text(formatCountdown(window.resetsAt, now))
                Text("下次恢复 ${formatTimestamp(window.resetsAt)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF63716A))
            } else Text("登录后查询；缺失数据不会显示成零。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
