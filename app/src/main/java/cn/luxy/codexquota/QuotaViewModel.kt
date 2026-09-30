package cn.luxy.codexquota

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

data class QuotaUiState(
    val busy: Boolean = true,
    val engineReady: Boolean = false,
    val signedIn: Boolean = false,
    val plan: String? = null,
    val snapshot: UsageSnapshot? = null,
    val stale: Boolean = true,
    val message: String = "正在准备手机查询组件…",
    val error: String? = null,
    val loginId: String? = null,
    val verificationUrl: String? = null,
    val userCode: String? = null,
)

class QuotaViewModel(application: Application) : AndroidViewModel(application) {
    private val client = CodexRuntime.acquire(application)
    private val operationMutex = CodexRuntime.accountMutex
    // 缓存只包含额度和账户标记，登录令牌由组件保存在应用私有目录。
    private val cache = application.getSharedPreferences("usage_snapshot", 0)
    private val mutableState = MutableStateFlow(QuotaUiState())
    private var currentOwner: String? = null
    val state = mutableState.asStateFlow()

    private val notificationSubscription: AutoCloseable

    init {
        notificationSubscription = CodexRuntime.subscribe(client) { method, params ->
            if (method == "account/login/completed") {
                viewModelScope.launch {
                    if (!isCurrentLoginCompletion(state.value.loginId, params.optString("loginId"))) return@launch
                    if (params.optBoolean("success")) {
                        mutableState.update { it.copy(loginId = null, verificationUrl = null, userCode = null) }
                        refresh()
                    } else mutableState.update {
                        it.copy(loginId = null, userCode = null, verificationUrl = null,
                            error = "登录未完成，请重新登录。", message = "请登录你的 ChatGPT 账户。")
                    }
                }
            }
        }
        runOperation("正在准备手机查询组件…") { readAccountAndUsage(false) }
    }

    fun refresh(verifyRenewal: Boolean = false) = runOperation(
        if (verifyRenewal) "正在验证登录续期…" else "正在刷新额度…"
    ) { readAccountAndUsage(verifyRenewal) }

    fun onForeground() {
        val current = state.value
        val age = Instant.now().epochSecond - (current.snapshot?.fetchedAt ?: 0)
        if (current.engineReady && current.signedIn && !current.busy && age >= 60) refresh()
    }

    fun login() = runOperation("正在获取登录码…") {
        val result = client.request("account/login/start", JSONObject().put("type", "chatgptDeviceCode"))
        val url = result.optString("verificationUrl").takeIf { isOfficialLoginUrl(it) }
        val code = result.optString("userCode").takeIf { it.isNotBlank() }
        check(url != null && code != null) { "暂时无法获取设备登录码，请稍后重试。" }
        mutableState.update {
            it.copy(loginId = result.optString("loginId"), verificationUrl = url, userCode = code,
                message = "在官方登录页面输入下面的设备码。", error = null)
        }
    }

    fun cancelLogin() {
        val cancelledId = state.value.loginId
        // 先使旧设备码失效，迟到的完成通知不得影响下一次登录。
        mutableState.update { it.copy(loginId = null, verificationUrl = null, userCode = null) }
        runOperation("正在取消登录…") {
            cancelledId?.let { client.request("account/login/cancel", JSONObject().put("loginId", it)) }
            mutableState.update { it.copy(message = "请登录你的 ChatGPT 账户。") }
        }
    }

    fun logout() = runOperation("正在退出登录…") {
        client.request("account/logout")
        withContext(Dispatchers.IO) { cache.edit().clear().commit() }
        currentOwner = null
        mutableState.update { QuotaUiState(busy = true, engineReady = true, message = "已退出，请登录你的 ChatGPT 账户。") }
    }

    private fun runOperation(message: String, action: suspend () -> Unit) = viewModelScope.launch {
        operationMutex.withLock {
            mutableState.update { it.copy(busy = true, message = message, error = null) }
            try {
                action()
            } catch (timeout: TimeoutCancellationException) {
                mutableState.update { it.copy(stale = true, error = "查询超时，请检查网络后重试。", message = "暂时无法更新，请重试。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(stale = true, error = readableError(failure), message = "暂时无法更新，请重试。") }
            } finally {
                if (state.value.stale) cache.edit().putBoolean("failed", true).apply()
                runCatching { QuotaWidgets.updateAll(getApplication()) }
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun readAccountAndUsage(forceRenewal: Boolean) {
        val result = client.request("account/read", JSONObject().put("refreshToken", forceRenewal || !state.value.engineReady))
        val account = result.optJSONObject("account")
        mutableState.update { it.copy(engineReady = true) }
        if (account == null) {
            withContext(Dispatchers.IO) { cache.edit().clear().commit() }
            mutableState.update { it.copy(signedIn = false, snapshot = null, plan = null, message = "请登录你的 ChatGPT 账户。") }
            currentOwner = null
            return
        }
        check(account.optString("type") == "chatgpt") { "请使用 ChatGPT 账户登录以查询订阅额度。" }
        val owner = account.optString("email")
        val plan = account.optString("planType").takeIf { it.isNotBlank() }
        mutableState.update { reconcileAccountState(it, currentOwner, owner, plan) }
        currentOwner = owner
        withContext(Dispatchers.IO) {
            if (cache.getString("owner", null) != owner || owner.isBlank()) cache.edit().clear().commit()
            if (state.value.snapshot == null) {
                val previous = cache.getString("payload", null)?.let { runCatching { UsageSnapshot.parse(JSONObject(it), cache.getLong("fetchedAt", 0)) }.getOrNull() }
                mutableState.update { it.copy(snapshot = previous, stale = true) }
            }
        }
        mutableState.update { it.copy(signedIn = true, plan = plan) }
        val usage = readUsageWithRecovery(client::request)
        val snapshot = UsageSnapshot.parse(usage)
        withContext(Dispatchers.IO) {
            cache.edit().putString("owner", owner).putString("payload", usage.toString()).putLong("fetchedAt", snapshot.fetchedAt).putBoolean("failed", false).commit()
        }
        mutableState.update { it.copy(snapshot = snapshot, stale = false,
            message = if (forceRenewal) "登录续期与额度查询成功。" else "已更新手机账户的额度。") }
    }

    private fun readableError(failure: Exception): String = when {
        failure is java.io.IOException -> "无法启动查询组件或网络连接失败，请重试。"
        failure is kotlinx.coroutines.TimeoutCancellationException -> "查询超时，请检查网络后重试。"
        failure is IllegalStateException -> failure.message ?: "查询失败，请重试。"
        else -> "查询失败，请检查网络与登录状态。"
    }

    override fun onCleared() { notificationSubscription.close(); CodexRuntime.release(client) }
}

fun reconcileAccountState(state: QuotaUiState, previousOwner: String?, owner: String, plan: String?): QuotaUiState {
    val changed = previousOwner != owner || owner.isBlank()
    return state.copy(signedIn = true, plan = plan, snapshot = if (changed) null else state.snapshot,
        stale = if (changed) true else state.stale)
}

fun isCurrentLoginCompletion(currentLoginId: String?, receivedLoginId: String): Boolean =
    !currentLoginId.isNullOrBlank() && currentLoginId == receivedLoginId

fun isOfficialLoginUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme == "https" && uri.host in setOf("auth.openai.com", "chatgpt.com") && (uri.port == -1 || uri.port == 443)
}.getOrDefault(false)
