# ---- LuaJ（场景脚本的 Lua 解释器）----
# 发布版会被 R8 压缩、改名。LuaJ 自己不靠反射工作，但这里整包保留、不让 R8 动它：
# 解释器的行为和调试版完全一样，换来的只是安装包大一点。
-keep class org.luaj.vm2.** { *; }
# LuaJ 里用不到的两块（编译成 Java 字节码的 luajc、javax.script 接口）引用了安卓上没有的类
-dontwarn javax.script.**
-dontwarn org.apache.bcel.**
