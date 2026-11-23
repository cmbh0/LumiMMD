package com.lumi.mmd.engine.script

import com.lumi.mmd.engine.control.InputState
import com.lumi.mmd.engine.fx.Effects
import com.lumi.mmd.engine.model.ModelData
import com.lumi.mmd.engine.render.RenderSettings
import com.lumi.mmd.engine.scene.ModelInstance
import com.lumi.mmd.engine.scene.Scene

/** 脚本引擎需要的东西：场景、特效、画面设置、输入，加上由应用 / 桌面工具实现的 [ScriptCallbacks]。都在渲染线程上用。 */
interface ScriptHost : ScriptCallbacks {
    val scene: Scene
    val effects: Effects
    val settings: RenderSettings
    val input: InputState
}

/** 要由宿主（应用 / 桌面工具）来做的事。所有方法都在渲染线程上调用。 */
interface ScriptCallbacks {
    /** print 的输出和报错。script 为 null 表示控制台。 */
    fun scriptLog(script: Script?, text: String, error: Boolean)
    /** 屏幕上的一行字（字幕、提示）。text 为 null = 去掉。x / y 是 0..1 的屏幕位置，color 是 ARGB。 */
    fun uiText(owner: Int, id: String, text: String?, x: Float, y: Float, size: Float, color: Int) {}
    /** 屏幕上的一个按钮，按下 / 松开时脚本的 on_button(id, down) 会被调用。label 为 null = 去掉。 */
    fun uiButton(owner: Int, id: String, label: String?, x: Float, y: Float) {}
    /** 去掉某个脚本放在屏幕上的全部文字和按钮。 */
    fun uiClear(owner: Int) {}
    fun toast(text: String) {}
    fun setPlaying(on: Boolean)
    fun seekSeconds(seconds: Float)
    /** 用户当前选中的模型。 */
    fun selectedModel(): ModelInstance? = null
    /** 把一个模型加进场景（脚本创建的几何体、分身）。 */
    fun addModel(data: ModelData, kind: Int): ModelInstance
    fun removeModel(model: ModelInstance)
    /** require("名字")：返回脚本库里同名脚本的源码，没有则为 null。 */
    fun scriptSource(name: String): String? = null
    /** 场景里的模型增减了、参数变了：刷新界面。 */
    fun sceneChanged() {}
    /** 特效贴图用的图片：把脚本写的文件名解析成资源库里的路径。 */
    fun resolveImage(name: String): String? = null
    /** 画布要用的图片：按文件名在资源库里找，返回文件内容（没有则为 null）。 */
    fun imageBytes(name: String): ByteArray? = null
}
