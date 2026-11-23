# .blend 测试文件是怎么来的

`engine/src/test/resources/blend/` 里的四个 `.blend` 是用四个版本的 Blender 跑 `make_scene.py` 存出来的同一个场景：

```bash
blender -b --factory-startup --python make_scene.py -- <输出目录> <名字>
# 每个版本得到：<名字>.blend（不压缩）、<名字>_z.blend（压缩）、<名字>.json（Blender 自己算的面数、包围盒、材质）
```

用过的版本：2.79b、3.6.9、4.2.9 LTS、5.3.0（都是官方的 Linux 版，后台模式运行）。

- `check.py`：用 `desktop` 里的 `ModelInfoMain` 把 `.blend` 读进来，和 `.json` 里的数字对比。
- `render_ref.py`：让 Blender 用 Cycles 渲染一张参考图，和引擎离屏渲染的结果并排看（`release/screenshots/blend_vs_blender.jpg`）。
- `blend.py`：一个极简的 `.blend` 结构查看器（文件头、数据块、SDNA），写导入器时用来看各个版本的结构体长什么样。

`zstd_cases.bin` 是用参考实现（python-zstandard）在各种级别和写法下压出来的 35 组数据，每组带原文的长度和 CRC32。
