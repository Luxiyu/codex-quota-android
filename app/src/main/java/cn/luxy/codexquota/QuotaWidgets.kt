package cn.luxy.codexquota

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.RemoteViews
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

class SmallQuotaWidgetProvider : QuotaWidgetProvider()
class LargeQuotaWidgetProvider : QuotaWidgetProvider()

open class QuotaWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        QuotaWidgets.updateAll(context)
        QuotaWidgets.schedule(context)
        QuotaWidgets.refresh(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == QuotaWidgets.REFRESH) QuotaWidgets.refresh(context)
    }
    override fun onDeleted(context: Context, ids: IntArray) { QuotaWidgets.schedule(context) }
    override fun onDisabled(context: Context) { QuotaWidgets.schedule(context) }
}

object QuotaWidgets {
    const val REFRESH = "cn.luxy.codexquota.WIDGET_REFRESH"
    private const val PERIODIC = "quota-widget-periodic"
    private val providers = listOf(SmallQuotaWidgetProvider::class.java, LargeQuotaWidgetProvider::class.java)

    fun schedule(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val work = WorkManager.getInstance(context)
        if (providers.any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }) {
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<QuotaWidgetWorker>(30, TimeUnit.MINUTES)
                    .build())
        } else work.cancelUniqueWork(PERIODIC)
    }

    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("quota-widget-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<QuotaWidgetWorker>()
                .build())
    }

    @Synchronized fun updateAll(context: Context) {
        val cache = context.getSharedPreferences("usage_snapshot", Context.MODE_PRIVATE)
        val snapshot = cache.getString("payload", null)?.let {
            runCatching { UsageSnapshot.parse(JSONObject(it), cache.getLong("fetchedAt", 0)) }.getOrNull()
        }
        val stale = cache.getBoolean("failed", false) || snapshot == null || Instant.now().epochSecond - snapshot.fetchedAt > 3600
        val manager = AppWidgetManager.getInstance(context)
        providers.forEach { provider ->
            val large = provider == LargeQuotaWidgetProvider::class.java
            val component = ComponentName(context, provider)
            manager.getAppWidgetIds(component).forEach { id ->
                val layout = if (large) R.layout.widget_quota_large else R.layout.widget_quota_small
                val rootId = if (!large && Build.VERSION.SDK_INT >= 31) R.id.widget_root_compact else R.id.widget_root
                // 改变根视图标识，使启动器重新载入紧凑布局，避免只重用升级前的尺寸。
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(context.packageName, layout, rootId)
                    else RemoteViews(context.packageName, layout)
                views.setTextViewText(R.id.widget_primary_value, widgetPercent(snapshot?.fiveHour))
                views.setTextViewText(R.id.widget_primary_reset, widgetReset(snapshot?.fiveHour))
                views.setTextViewText(R.id.widget_secondary_value, if (large) widgetPercent(snapshot?.weekly) else "周剩余 ${widgetPercent(snapshot?.weekly)}")
                views.setTextViewText(R.id.widget_secondary_reset, widgetReset(snapshot?.weekly))
                views.setImageViewBitmap(R.id.widget_primary_bar, progressBitmap(snapshot?.fiveHour?.remainingPercent))
                if (large) views.setImageViewBitmap(R.id.widget_secondary_bar, progressBitmap(snapshot?.weekly?.remainingPercent))
                val numberColor = Color.parseColor(if (stale) "#A8B5AC" else "#46EC70")
                views.setTextColor(R.id.widget_primary_value, numberColor)
                views.setTextColor(R.id.widget_secondary_value, numberColor)
                views.setContentDescription(rootId, if (stale) "缓存额度，点击刷新或打开APP确认" else "Codex剩余额度，点击打开APP")
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val refresh = PendingIntent.getBroadcast(context, if (large) 2 else 1,
                    Intent(context, provider).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(rootId, open)
                views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
                manager.updateAppWidget(id, views)
            }
        }
    }

    private fun progressBitmap(percent: Double?): Bitmap {
        val bitmap = Bitmap.createBitmap(600, 52, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(1f, 1f, 599f, 51f)
        paint.color = Color.parseColor("#314337")
        canvas.drawRoundRect(rect, 26f, 26f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.parseColor("#485A4C")
        canvas.drawRoundRect(rect, 26f, 26f, paint)
        paint.style = Paint.Style.FILL
        val width = ((percent ?: 0.0).coerceIn(0.0, 100.0) / 100 * 580).toFloat()
        if (width > 0) {
            // 圆角随填充宽度收缩，低额度也保留真实比例。
            paint.shader = LinearGradient(10f, 0f, 590f, 0f, Color.parseColor("#18CD47"), Color.parseColor("#63FF82"), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(10f, 10f, 10f + width, 42f), minOf(16f, width / 2), 16f, paint)
        }
        return bitmap
    }
}

fun widgetPercent(window: QuotaWindow?): String = window?.remainingPercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "—"
fun widgetReset(window: QuotaWindow?): String = window?.resetsAt?.let { "${formatTimestamp(it)} 恢复" } ?: "恢复时间 —"

class QuotaWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cache = applicationContext.getSharedPreferences("usage_snapshot", Context.MODE_PRIVATE)
        val client = CodexRuntime.acquire(applicationContext)
        try {
            CodexRuntime.accountMutex.withLock {
                try {
                    val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                    if (network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
                        cache.edit().putBoolean("failed", true).commit()
                        return@withLock Result.success()
                    }
                    withTimeout(55_000) {
                        val account = client.request("account/read", JSONObject().put("refreshToken", true)).optJSONObject("account")
                        val owner = account?.optString("email")
                        if (account == null || account.optString("type") != "chatgpt" || owner.isNullOrBlank()) {
                            cache.edit().clear().commit()
                            return@withTimeout Result.success()
                        }
                        // 查询前清除其它账户缓存；失败也不得展示旧账户额度。
                        if (cache.getString("owner", null) != owner) cache.edit().clear().commit()
                        val usage = readUsageWithRecovery(client::request)
                        val snapshot = UsageSnapshot.parse(usage)
                        cache.edit().putString("owner", owner).putString("payload", usage.toString())
                            .putLong("fetchedAt", snapshot.fetchedAt).putBoolean("failed", false).commit()
                        Result.success()
                    }
                } catch (_: TimeoutCancellationException) {
                    cache.edit().putBoolean("failed", true).commit()
                    Result.failure()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    cache.edit().putBoolean("failed", true).commit()
                    Result.failure()
                } finally {
                    // 缓存写入与渲染共同持锁，退出登录后不能被旧快照覆盖。
                    runCatching { QuotaWidgets.updateAll(applicationContext) }
                }
            }
        } finally {
            CodexRuntime.release(client)
        }
    }
}
