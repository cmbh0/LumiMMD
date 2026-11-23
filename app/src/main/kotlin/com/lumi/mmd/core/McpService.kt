package com.lumi.mmd.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.lumi.mmd.MainActivity
import com.lumi.mmd.R

/**
 * MCP 服务开着的时候挂在通知栏里的前台服务。它自己不干活（监听端口的是 [com.lumi.mmd.engine.mcp.McpServer]），
 * 作用只有一个：告诉系统「这个应用现在有事要做」。
 *
 * 没有它的话，只要切到别的应用（AI 客户端和本应用在同一台手机上时必然如此），系统很快就把本应用的进程
 * 冻住或者只给一点点时间片，客户端发来的握手、列工具请求要等进程下一次被放行才有人答——
 * 表现出来就是「配好地址以后要等一分钟才拿到工具列表」。有了前台服务（外加 CPU 和 Wi-Fi 的锁，
 * 让熄屏时也照常应答），请求随到随答。
 */
class McpService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知上的「停止」：让应用把 MCP 关掉（它随后会来停这个服务）
            try { onStopRequested?.invoke() } catch (_: Throwable) {}
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(intent?.getStringExtra(EXTRA_TEXT) ?: "")
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Throwable) {
            // 系统不让在这个时候开前台服务（应用已经在后台）：MCP 照常开着，只是切到后台以后可能变慢
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        acquire()
        try { onStateChanged?.invoke() } catch (_: Throwable) {}
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "MCP 服务", NotificationManager.IMPORTANCE_LOW)
            ch.description = "MCP 服务开着时显示，让应用在后台也能马上应答 AI 客户端"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or immutable)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_mcp)
            .setContentTitle("MCP 服务运行中")
            .setContentText(text.ifEmpty { "AI 客户端可以连接并操作 Lumi MMD" })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun acquire() {
        try {
            if (wake == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LumiMMD:mcp").also { it.setReferenceCounted(false); it.acquire() }
            }
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm?.createWifiLock(mode, "LumiMMD:mcp")?.also { it.setReferenceCounted(false); it.acquire() }
            }
        } catch (_: Throwable) {
        }
    }

    /** 从最近任务里划掉了应用：服务跟着停（MCP 也就停了）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try { onStopRequested?.invoke() } catch (_: Throwable) {}
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        try { wake?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wake = null; wifi = null
        try { onStateChanged?.invoke() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "mcp"
        private const val NOTIFICATION_ID = 8765
        private const val ACTION_STOP = "com.lumi.mmd.MCP_STOP"
        private const val EXTRA_TEXT = "text"

        /** 前台服务现在是不是真的在跑（界面上据此提示「后台可能变慢」）。 */
        @Volatile var running = false
            private set
        /** 服务真的跑起来了 / 停了（启动是异步的，界面要等这一下才知道保活有没有开成）。 */
        @Volatile var onStateChanged: (() -> Unit)? = null
        /** 用户在通知上点了「停止」。 */
        @Volatile var onStopRequested: (() -> Unit)? = null

        /** 开始（或者更新通知上的文字）。返回 false = 系统没让开。 */
        fun start(context: Context, text: String): Boolean = try {
            val i = Intent(context, McpService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            true
        } catch (_: Throwable) {
            false
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, McpService::class.java)) } catch (_: Throwable) {}
        }
    }
}
