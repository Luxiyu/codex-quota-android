package cn.luxy.codexquota

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 静态 Linux 组件无法直接使用 Android 的 DNS 和证书目录。
 * 回环 CONNECT 隧道只转发加密字节，由 Android 解析域名；TLS 仍由官方组件校验。
 */
class AndroidNetworkTunnel : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val proxyUrl: String = "http://127.0.0.1:${server.localPort}"

    init {
        scope.launch {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets.add(socket)
                if (!slots.tryAcquire()) {
                    socket.close()
                    sockets.remove(socket)
                    continue
                }
                scope.launch {
                    try { runCatching { relay(socket) } } finally { sockets.remove(socket); socket.close(); slots.release() }
                }
            }
        }
    }

    private fun relay(client: Socket) {
        client.use {
            client.soTimeout = 5_000
            val input = client.getInputStream()
            val header = StringBuilder()
            while (header.length < 8_192 && !header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte == -1) return
                header.append(byte.toChar())
            }
            val line = header.lineSequence().firstOrNull().orEmpty()
            val destination = Regex("^CONNECT ([A-Za-z0-9.-]+):443 HTTP/1\\.[01]$").matchEntire(line)?.groupValues?.get(1)
            val output = client.getOutputStream()
            if (!header.endsWith("\r\n\r\n") || destination == null || !isAllowedTunnelHost(destination)) {
                output.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                return
            }
            Socket().use { upstream ->
                sockets.add(upstream)
                try {
                    // 使用 Android 自带的 Socket 路径，遵循手机当前网络和 VPN 的 DNS。
                    upstream.connect(InetSocketAddress(destination, 443), 15_000)
                    upstream.soTimeout = 90_000
                    client.soTimeout = 90_000
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    val upload = scope.launch {
                        runCatching { input.copyTo(upstream.getOutputStream()) }
                        runCatching { upstream.shutdownOutput() }
                    }
                    try { upstream.getInputStream().copyTo(output) } finally {
                        upload.cancel()
                        runCatching { client.shutdownInput() }
                    }
                } catch (_: Exception) {
                    // 不记录目标请求、TLS 内容或登录数据。
                } finally { sockets.remove(upstream) }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        scope.cancel()
    }

    companion object {
        fun writeTrustedCaBundle(target: File): File {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            target.bufferedWriter(Charsets.US_ASCII).use { output ->
                store.aliases().asSequence().forEach { alias ->
                    val certificate = store.getCertificate(alias) ?: return@forEach
                    output.appendLine("-----BEGIN CERTIFICATE-----")
                    output.appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded))
                    output.appendLine("-----END CERTIFICATE-----")
                }
            }
            return target
        }
    }
}

fun isAllowedTunnelHost(host: String): Boolean {
    val normalized = host.lowercase(java.util.Locale.ROOT)
    return normalized == "chatgpt.com" || normalized == "openai.com" || normalized.endsWith(".openai.com")
}
