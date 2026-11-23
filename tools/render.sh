#!/usr/bin/env bash
# 无头渲染：tools/render.sh model=xxx.pmx motion=xxx.vmd frames=0,100 out=/tmp/shot
# 需要先 source 构建环境（JAVA_HOME / ANDROID_HOME），并安装 Mesa 的 EGL/GLES 库。
set -e
export LC_ALL=C.UTF-8
cd "$(dirname "$0")/.."
ARGS=""
for a in "$@"; do ARGS="$ARGS \"$a\""; done
gradle -PwithDesktop :desktop:run --console=plain -q --args="$ARGS"
