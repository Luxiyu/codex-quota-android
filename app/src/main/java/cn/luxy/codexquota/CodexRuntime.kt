package cn.luxy.codexquota

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** 前台与小组件共享客户端和账户锁，避免并发续期改写认证文件。 */
object CodexRuntime {
    val accountMutex = Mutex()
    private var client: CodexRpcClient? = null
    private var references = 0
    private var listeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
    @Synchronized fun acquire(context: Context): CodexRpcClient {
        references++
        return client ?: CodexRpcClient(context.applicationContext).also { created ->
            val callbacks = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
            listeners = callbacks
            created.onNotification = { method, params -> callbacks.forEach { it(method, params) } }
            client = created
        }
    }
    @Synchronized fun subscribe(value: CodexRpcClient, listener: (String, JSONObject) -> Unit): AutoCloseable {
        check(client === value)
        val callbacks = listeners
        callbacks.add(listener)
        return AutoCloseable { callbacks.remove(listener) }
    }
    @Synchronized fun release(value: CodexRpcClient) {
        if (client !== value) return
        references--
        if (references == 0) { listeners.clear(); value.close(); client = null }
    }
}
