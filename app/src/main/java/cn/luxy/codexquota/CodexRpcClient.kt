package cn.luxy.codexquota

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class CodexRpcClient(private val context: Context) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val writeMutex = Mutex()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val closed = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    @Volatile private var ready = false
    @Volatile private var networkTunnel: AndroidNetworkTunnel? = null
    @Volatile var onNotification: (String, JSONObject) -> Unit = { _, _ -> }

    suspend fun start() = startMutex.withLock {
        check(!closed.get()) { "查询组件已关闭。" }
        if (ready && process?.isAlive == true) return@withLock
        val executable = File(context.applicationInfo.nativeLibraryDir, "libcodex.so")
        check(executable.isFile) { "未找到手机查询组件，请重新安装完整测试版。" }
        val privateHome = File(context.filesDir, "codex-core").apply { mkdirs() }
        var created: Process? = null
        var succeeded = false
        try {
        val launched = withContext(Dispatchers.IO) {
            check(!closed.get()) { "查询组件已关闭。" }
            process?.destroy()
            networkTunnel?.close()
            val tunnel = AndroidNetworkTunnel().also { networkTunnel = it }
            val caBundle = AndroidNetworkTunnel.writeTrustedCaBundle(File(privateHome, "android-trusted-ca.pem"))
            ProcessBuilder(executable.absolutePath, "app-server", "--listen", "stdio://", "-c", "cli_auth_credentials_store=\"file\"")
                .directory(privateHome)
                .apply {
                    // 组件使用独立的手机应用私有目录，绝不导入电脑的登录状态。
                    environment()["CODEX_HOME"] = privateHome.absolutePath
                    environment()["RUST_LOG"] = "error"
                    environment()["HTTPS_PROXY"] = tunnel.proxyUrl
                    environment()["HTTP_PROXY"] = tunnel.proxyUrl
                    environment()["NO_PROXY"] = "127.0.0.1,localhost"
                    environment()["CODEX_CA_CERTIFICATE"] = caBundle.absolutePath
                    environment()["SSL_CERT_FILE"] = caBundle.absolutePath
                }.start().also {
                    // 必须在 IO 阶段登记：协程返回前被取消时也能回收子进程。
                    created = it
                    process = it
                    check(!closed.get()) { "查询组件已关闭。" }
                }
        }
        process = launched
        writer = launched.outputStream.bufferedWriter()
        scope.launch {
            // 不输出组件日志或认证响应，避免登录信息进入系统日志。
            runCatching { launched.errorStream.bufferedReader().use { lines -> while (lines.readLine() != null) Unit } }
        }
        scope.launch {
            try {
                launched.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val message = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                        if (message.has("id") && !message.isNull("id")) {
                            val waiter = pending.remove(message.optInt("id")) ?: return@forEach
                            val error = message.optJSONObject("error")
                            if (error != null) {
                                waiter.completeExceptionally(CodexRpcException(error.optInt("code")))
                            } else waiter.complete(message.optJSONObject("result") ?: JSONObject())
                        } else {
                            onNotification(message.optString("method"), message.optJSONObject("params") ?: JSONObject())
                        }
                    }
                }
            } catch (_: Exception) {
                // 子进程关闭和读取失败统一由下面的待处理请求清理处理。
            } finally {
                if (process === launched) {
                    ready = false
                    val error = IllegalStateException("手机查询组件已停止，请重试。")
                    pending.values.forEach { it.completeExceptionally(error) }
                    pending.clear()
                }
            }
        }
            requestRaw("initialize", JSONObject().put("clientInfo", JSONObject()
                .put("name", "codex_quota_android").put("title", "Codex 额度").put("version", "0.1.0")))
            send(JSONObject().put("method", "initialized").put("params", JSONObject()))
            check(!closed.get()) { "查询组件已关闭。" }
            ready = true
            succeeded = true
        } finally {
            if (!succeeded) {
                created?.destroy()
                if (process === created) process = null
                writer = null
                ready = false
                networkTunnel?.close()
                networkTunnel = null
            }
        }
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        start()
        // 将可调用方法限制在查询和登录，禁止消费重置权益或发送消息。
        require(method in allowedMethods) { "不支持此操作。" }
        return requestRaw(method, params)
    }

    private suspend fun requestRaw(method: String, params: JSONObject): JSONObject {
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        return try {
            send(JSONObject().put("id", id).put("method", method).put("params", params))
            withTimeout(45_000) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun send(message: JSONObject) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val output = checkNotNull(writer) { "查询组件尚未启动。" }
            output.write(message.toString())
            output.newLine()
            output.flush()
        }
    }

    override fun close() {
        closed.set(true)
        ready = false
        process?.destroy()
        networkTunnel?.close()
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
    }

    private companion object {
        val allowedMethods = setOf("account/read", "account/login/start", "account/login/cancel", "account/logout", "account/rateLimits/read")
    }
}

class CodexRpcException(val code: Int) : IllegalStateException("查询组件返回错误（${code}），请检查登录或重试。")
