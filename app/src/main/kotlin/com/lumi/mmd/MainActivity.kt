package com.lumi.mmd

import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lumi.mmd.core.MmdSurfaceView
import com.lumi.mmd.core.SceneController
import com.lumi.mmd.ui.LumiTheme
import com.lumi.mmd.ui.MainScreen

/**
 * 唯一的 Activity：一个全屏的 OpenGL ES 视图，上面叠一层 Compose 界面。
 *
 * 屏幕旋转 / 分屏等配置变化在清单里声明为自行处理，不重建 Activity——
 * 否则 GL 上下文会跟着销毁，所有模型和贴图都得重新上传。
 */
class MainActivity : ComponentActivity() {
    private lateinit var controller: SceneController
    private lateinit var glView: MmdSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        controller = SceneController(applicationContext)
        glView = MmdSurfaceView(this, controller)
        controller.restoreProject()
        if (savedInstanceState == null) handleIntent(intent)

        setContent { LumiTheme { MainScreen(controller, glView) } }
        hideSystemBars()
    }

    /** 沉浸式全屏：画面占满整个屏幕，状态栏和导航栏收起来；从屏幕边缘滑一下可以临时呼出。 */
    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 从文件管理器「用 Lumi MMD 打开」或「分享到 Lumi MMD」进来的文件。 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
            Intent.ACTION_SEND -> streamUri(intent)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> streamUris(intent)?.let { uris.addAll(it) }
        }
        if (uris.isEmpty()) return
        // 和在应用里点「添加」一样：模型加进场景（贴图会回原来的文件夹找），动作、镜头、音乐各就各位
        controller.addFiles(uris)
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamUris(intent: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    override fun onResume() {
        super.onResume()
        controller.resumed = true
        glView.onResume()
        controller.refreshMcp()
        // 刚从系统设置里给了"访问所有文件"的权限回来：接着把贴图找齐
        controller.retryTextures()
    }

    override fun onPause() {
        // 进后台：停止播放（音乐不要在后台继续响），把当前场景存盘，再暂停渲染线程
        controller.pause()
        controller.silenceNow()
        controller.saveProjectNow()
        controller.resumed = false
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) controller.trimMemory()
    }
}
