package com.lumi.mmd.engine.script

/** 脚本引擎自带的 Lua 代码：每个脚本启动时先运行的"前奏"、接口说明、示例脚本。 */
object ScriptLibrary {

    /** 定时器、补间、协程这些小工具，用 Lua 自己写的。 */
    const val PRELUDE = """
local timers = {}
local routines = {}

ease = {
  linear = function(t) return t end,
  in_quad = function(t) return t * t end,
  out_quad = function(t) return t * (2 - t) end,
  inout = function(t) return t * t * (3 - 2 * t) end,
  out_cubic = function(t) t = t - 1; return t * t * t + 1 end,
  out_back = function(t) local s = 1.70158; t = t - 1; return t * t * ((s + 1) * t + s) + 1 end,
  out_elastic = function(t)
    if t <= 0 then return 0 elseif t >= 1 then return 1 end
    return math.pow(2, -10 * t) * math.sin((t - 0.075) * 2 * math.pi / 0.3) + 1
  end,
  out_bounce = function(t)
    if t < 1 / 2.75 then return 7.5625 * t * t
    elseif t < 2 / 2.75 then t = t - 1.5 / 2.75; return 7.5625 * t * t + 0.75
    elseif t < 2.5 / 2.75 then t = t - 2.25 / 2.75; return 7.5625 * t * t + 0.9375
    else t = t - 2.625 / 2.75; return 7.5625 * t * t + 0.984375 end
  end,
}

function after(sec, fn)
  local t = { left = sec, fn = fn }
  timers[#timers + 1] = t
  return t
end

function every(sec, fn)
  local t = { left = sec, fn = fn, period = math.max(sec, 0.001) }
  timers[#timers + 1] = t
  return t
end

function tween(sec, fn, easing, done)
  local t = { tween = 0, dur = math.max(sec, 0.0001), fn = fn, ease = easing or ease.inout, done = done }
  timers[#timers + 1] = t
  return t
end

function cancel(t)
  if type(t) == "table" then t.dead = true end
end

function run(fn)
  local r = { co = coroutine.create(fn), wait = 0 }
  routines[#routines + 1] = r
  return r
end

function wait(sec)
  coroutine.yield(sec or 0)
end

function __tick(dt)
  for i = #timers, 1, -1 do
    local t = timers[i]
    if t.dead then
      table.remove(timers, i)
    elseif t.tween then
      t.tween = t.tween + dt
      local k = math.min(t.tween / t.dur, 1)
      t.fn(t.ease(k), k)
      if k >= 1 then
        table.remove(timers, i)
        if t.done then t.done() end
      end
    else
      t.left = t.left - dt
      if t.left <= 0 then
        if t.period then
          t.left = t.left + t.period
          if t.left < 0 then t.left = 0 end
        else
          table.remove(timers, i)
        end
        t.fn()
      end
    end
  end
  for i = #routines, 1, -1 do
    local r = routines[i]
    if r.dead then
      table.remove(routines, i)
    else
      r.wait = r.wait - dt
      if r.wait <= 0 then
        local ok, v = coroutine.resume(r.co)
        if not ok then
          table.remove(routines, i)
          error(v, 0)
        end
        if coroutine.status(r.co) == "dead" then
          table.remove(routines, i)
        else
          r.wait = tonumber(v) or 0
        end
      end
    end
  end
end
"""

    /** 接口说明：给写脚本的人看，也通过 MCP 交给 AI。 */
    val REFERENCE = """
Lumi MMD 的 Lua 脚本接口（Lua 5.2；没有 io / os.execute / 文件 / 网络）
======================================================================

脚本的形状
----------
  function init()            end   -- 可选：脚本启动时调用一次（文件最外层的代码也是启动时运行）
  function update(dt)        end   -- 可选：每一帧调用；dt = 距上一帧的真实秒数（暂停时间轴时也在走）
  function on_button(id, down) end -- 可选：屏幕按钮按下 / 松开（操控模式的 jump crouch prone sprint，或 ui.button 自己加的）
  function on_event(name)    end   -- 可选：name = "play" "pause" "seek" "loop"
  function on_exit()         end   -- 可选：脚本被关掉时调用
脚本改过的一切（骨骼、材质、透明度、层、物理参数、镜头、画面设置）和它创建的特效 / 模型 / 文字 / 画布 / 网格 / 物理世界，
在脚本关掉或出错时都会自动恢复 / 清除，不需要自己收拾。
这些改动也不会被存进工程：存下来的永远是脚本没动过时的样子，下次打开时脚本重新运行、重新生效。
角度一律用"度"，颜色分量 0..1，距离单位和 MMD 一样（一个角色大约 20 高）。坐标：+Y 向上，角色默认面朝 -Z。
一次调用最多执行约三百万条指令，超过会被中止（防止死循环卡住画面）。

time（每帧自动更新的数）
  time.now       时间轴当前的秒数          time.frame   当前帧（30fps，可以是小数）
  time.dt        同 update 的 dt            time.real    脚本启动以来的真实秒数
  time.playing   时间轴是否在播放           time.duration 时间轴总长（秒）
  time.local     距脚本时间段开头的秒数     time.progress 在脚本时间段里走了多少（0..1）
  time.play()  time.pause()  time.seek(秒)

scene
  scene.main()            脚本作用的模型：界面里给脚本指定的那个，否则是当前选中的 / 第一个角色。场景是空的时返回 nil
  scene.models([kind])    全部模型（数组）；kind 可填 "character" "stage" "accessory"
  scene.find("名字")      按名字找（先找完全相同的，再找包含的）；找不到返回 nil
  scene.get(id)
  scene.create(shape, {name=, size={x,y,z}, radius=, height=, thickness=, segments=, color={r,g,b,a}, pos={x,y,z}})
                          新建一个几何体模型：shape = "box" "plane" "sphere" "cylinder" "cone" "torus"
  scene.clone(m)          复制出一个分身（跟着同样的动作）
  scene.remove(m)         只能移除脚本自己创建的

模型 m
  读写：m.visible  m.opacity(0..1 整体透明度)  m.layer(整数：大的盖在小的上面，默认 0)  m.scale
        m.x m.y m.z  m.edge(描边)  m.shadow(投影)  m.physics(物理开关)
        m.collision   碰撞属性："none"（默认，不挡东西）/ "box"（按包围盒挡）/ "mesh"（按模型的每个面挡，舞台、台阶、桌子用这个）
                      挡的是：别的模型的头发裙子、sim 的粒子 / 布 / 水 / 刚体、操控模式里的角色、sim.raycast。按没有动作时的形状算，跟着位置 / 旋转 / 缩放走
  只读：m.name  m.id  m.kind  m.valid
  m:pos() -> x,y,z          m:set_pos(x,y,z)
  m:rot() -> rx,ry,rz       m:set_rot(rx,ry,rz)
  m:bounds() -> minx,miny,minz,maxx,maxy,maxz
  m:bone("名字") -> 骨骼 或 nil       m:bones() -> 全部骨骼名字       m:bones("裙") -> 名字里含"裙"的骨骼
  m:morph("名字") -> 当前值            m:morph("名字", 0.8) 设值（取代动作里的值）      m:morphs() -> 全部表情名字
  m:material(序号或名字) -> 材质       m:materials() / m:materials("裙") -> 材质数组
  m:bodies() / m:bodies("裙") -> 刚体数组
  m:root(x,y,z, yaw, pitch, roll)   挪动 / 转动整个骨架（走的是骨骼，头发裙子会跟着甩；m:set_pos 挪的是整个模型，物理不会有反应）
  m:attach(另一个模型, "骨骼名")  m:detach()
  m:physics_set("参数名", 值)     只对这个模型生效的物理倍数（参数名见 physics.set）
  m:physics_reset()               让头发裙子回到原位重新落下
  m:reset()                        清掉脚本对这个模型骨骼 / 表情 / 材质的全部改动

骨骼 b
  b.name  b.index  b.parent
  b:rotate(rx,ry,rz)      在动作的基础上再转这么多（每帧调用可以做出摆动）
  b:set_rot(rx,ry,rz)     不管动作，直接定成这个角度         b:set_quat(x,y,z,w)
  b:move(x,y,z)           在动作的基础上再移这么多           b:set_move(x,y,z)
  b:scale(s) / b:scale(sx,sy,sz)   缩放：绑在这根骨上的部分变大变小，子骨离它的距离跟着变（裙子变长 = 把裙子骨骼的 y 放大）
  b:clear()               清掉脚本对这根骨的改动
  b:pos() -> x,y,z        世界坐标（上一帧的）               b:rot() -> rx,ry,rz
  b:children() -> 骨骼数组
  注意：被物理带着动的骨骼（头发、裙子）转不动——先把对应刚体改成跟骨骼：for _, r in ipairs(m:bodies("裙")) do r.mode = "bone" end

材质 mat
  mat.name  mat.index
  mat.alpha(0..1)  mat.visible  mat.edge(描边粗细倍数)
  mat.shading             光照方式："mmd"（toon 贴图）/ "lit"（普通光照）/ "unlit"（不受光，贴图原色）；赋 nil = 回到材质自己的
  mat.cel                 这块材质单独的三渲二级数：数字 / false（这块不要三渲二，比如脸）/ nil（跟着 render 的设置）
  mat:color(r,g,b)        颜色乘上这个（1,1,1 = 原样）
  mat:glow(r,g,b)         加上这个颜色（发光）
  mat:tint(r,g,b, amount) 往这个颜色染，amount 0..1
  mat:reset()

刚体 r
  r.name  r.bone  r.mode("bone" 跟骨骼 / "physics" 物理 / "aligned" 物理+位置对齐骨骼)
  r.mass  r.damping  r.angular_damping  r.friction  r.bounce
  r:impulse(x,y,z)        推一下          r:pos() -> x,y,z

physics（全局）
  physics.set("参数名", 值)   physics.get("参数名")
     gravity(重力倍数) gravityX gravityZ windX windY windZ turbulence(阵风 0..1) mass damping angularDamping
     stiffness(弹簧硬度) limits(关节活动范围) colliderSize hz(每秒步数) maxSubSteps iterations ground(true/false)
  physics.wind(x,y,z [, turbulence])    风（加速度，几十到两百比较明显）
  physics.gravity(倍数)  physics.enabled(true/false)  physics.reset()

camera / light / post
  camera.get() -> {x,y,z, tx,ty,tz, distance, fov, pitch, yaw, roll}
  camera.set{tx=,ty=,tz=, distance=, fov=, pitch=, yaw=, roll=}   接管镜头（只改给出的项）   camera.release()
  camera.shake(幅度, 秒)      camera.project(x,y,z) -> sx,sy（屏幕位置 0..1；在镜头后面返回 nil）
  light.dir(x,y,z)  light.color(r,g,b)  light.get()
  post.set{bloom=0.8, exposure=1, saturation=1, vignette=0.2, rim=0.3, grid=false}     和 render.set 是同一个函数（老名字）
  post.background(r,g,b [, r2,g2,b2])   背景色（两组 = 上下渐变）

render（画面怎么画：影子、描边、三渲二、调色。脚本停掉时全部回到原样）
  render.set{ ... }       只改写了的项：
     cel=2                 三渲二：光照分几级。0 / false = 关（用模型自己的 toon 和普通光照），1 = 亮 / 暗两种颜色，2 = 多一级中间色 …（最多 8）
     cel_soft=0.04         明暗交界线的柔和度（0 一刀切 .. 1 平滑）        cel_shift=0   交界线往亮处(正) / 暗处(负)挪（-1..1）
     shade={0.70,0.64,0.80}  暗部乘的颜色（偏冷 = 日系，偏暖 = 黄昏）     cel_specular=0.6  硬边高光的强度（0 = 不要）
     edges=true / 1.5      描边开关；给数字 = 粗细倍数                    edge_width=1.5
     edge_color={r,g,b}    所有描边统一成这个颜色（false = 各材质自己的）  edge_all=true  没有描边的模型（glTF / OBJ / FBX）也描
     shadows=true / 0.7    影子开关；给数字 = 影子有多黑（0..1）          ground_shadow=true  地板上的影子
     rim=0.4               边缘光（0 = 关）                               rim_color={r,g,b}（false = 跟灯光的颜色）
     specular=1            高光倍数（0 = 哑光）                           sky={r,g,b} ground={r,g,b}  上 / 下环境光（glTF 这类普通材质用）
     bloom=0.8  bloom_threshold=0.82  exposure=1  saturation=1  contrast=1  vignette=0.2
     posterize=6           色调分离：每个通道只留几级颜色（0 = 关）        grade={1,0.95,0.9}  整个画面乘的颜色
     background={r,g,b} 或 {{r,g,b},{r,g,b}}   背景（纯色 / 上下渐变）    grid=false  不画网格
  render.get() -> 上面这些项现在的值
  render.toon{bands=2, shade={..}, edge=1.3, edge_color={..}, rim=0.4, soft=0.03, shift=0, specular=0, saturation=1.08}
                          一句话换成三渲二（都可以不写）：分级光照 + 所有模型描边
  render.background(r,g,b [, r2,g2,b2])       render.reset()   回到脚本动它之前的样子

floor（默认地板：画网格、接影子、挡住掉下来的东西的那块无限大的平面）
  floor.get() -> {enabled, height, grid, shadow, collide, cell, color={r,g,b,a}, grid_color={r,g,b,a}}
  floor.set{height=0, grid=true, shadow=true, collide=true, cell=5, color={r,g,b,a}, grid_color={r,g,b,a}, enabled=true}
                          只改写了的项。color 的 a = 0（或 color=false）是透明的地板，只有网格和影子；collide=false = 东西会穿过去
  floor.remove()          删掉地板（什么都不画，东西一直往下掉）       floor.reset()   加回一块默认的地板

fx（特效）
  local e = fx.emitter{                    粒子发射器
     texture="soft",        内置：soft dot spark star ring petal smoke heart square flare snow bubble；也可以写资源库里图片的文件名
     additive=true,         true = 叠加发光（火花、光点），false = 普通半透明（烟、花瓣）
     rate=30,               每秒多少个
     life={0.8,1.4}, speed={1,2}, dir={0,1,0}, spread=180,   寿命、初速、方向、散开的角度（度）
     gravity={0,-5,0}, drag=0,
     size={0.6,0.1}, size_jitter=0.3,       大小从多少变到多少
     color={1,0.8,0.3,1}, color_end={1,0.2,0,0}, spin=90,
     pos={0,10,0}, area={2,0,2},            位置、随机范围（半边长）
     model=m, bone="右手首", offset={0,0,0},  跟着模型的骨骼走
     max=1500 }                             最多同时存在多少个（只在创建时有效）
  e:set{...}  e:burst(n)  e:move(x,y,z)  e:attach(m, "骨骼名")  e:stop()  e:start()  e:clear()  e:remove()  e.count
  local s = fx.sprite{texture=, size=, rotation=, color=, additive=, pos=, model=, bone=}   一张朝着镜头的贴片   s:set{...} s:move(x,y,z) s:remove()
  local t = fx.trail{model=m, bone="左手首", width=0.6, life=0.5, color={r,g,b,a}, color_end=}    拖尾   t:set{...} t:move(x,y,z) t:clear() t:remove()
  fx.flash(r,g,b,a, 秒)      整屏闪一下            fx.screen(r,g,b,a)    一直盖着一层颜色（转场）
  fx.line(x1,y1,z1, x2,y2,z2 [, r,g,b,a [, 粗细]])   画一条线，只画当前这一帧
  内置特效不够用时：canvas 自己画贴图，mesh 自己搭形状，sim 自己做物理（布、绳、液体、刚体、自己写的力），见下面三节。

canvas（自己画贴图）
  local c = canvas.new(256, 256 [, {repeat=true, nearest=true}])     一块 RGBA 画布，边长 1..1024。坐标是像素，左上角 (0,0)，x 向右、y 向下（和贴图的 UV 同向）
  c.width  c.height  c.name（"canvas:编号"，凡是写贴图名字的地方都能用；也可以直接把 c 传进去）
  画笔：c:color(r,g,b,a)   纯色
        c:linear(x0,y0, x1,y1, {r,g,b,a}, {r,g,b,a})    线性渐变
        c:radial(cx,cy, r0,r1, {r,g,b,a}, {r,g,b,a})    径向渐变：r0 以内是第一个颜色，到 r1 变成第二个（虹膜就是这么画的）
        c:blend("over")      混合方式：over 叠加 / add 提亮 / multiply 压暗 / screen 滤色 / erase 擦成透明 / replace 直接替换
        c:feather(3)         边缘虚化多少像素（0 = 只抗锯齿）
  图形：c:clear(r,g,b,a)   c:circle(cx,cy,r)   c:ring(cx,cy,r, 线宽)   c:ellipse(cx,cy, rx,ry [, 角度])
        c:rect(x,y,w,h [, 圆角])   c:line(x0,y0, x1,y1, 线宽)   c:poly({x0,y0, x1,y1, x2,y2, ...})
  像素：c:pixel(x,y) -> r,g,b,a      c:pixel(x,y, r,g,b,a) 直接写
        c:each(function(x,y, r,g,b,a) return r,g,b,a end)    逐像素算（不返回 = 不改）；256×256 以内比较合适
  整张：c:blur(半径)   c:noise(强度, 粗细, 种子)
  拿图：c:image(来源 [, x,y,w,h [, alpha]])    来源 = 另一块画布 / 一块材质（取它原来的贴图）/ 资源库里图片的文件名；默认铺满
  用在哪：mat:texture(c)       这块材质改用画布当贴图（mat:texture(nil) 换回原图）；之后再往 c 上画，模型上马上跟着变
          mat:uv_bounds() -> u0,v0,u1,v1    这块材质用到贴图的哪一块（0..1），乘上 c.width / c.height 就是像素位置
          fx.sprite{texture=c}  fx.emitter{texture=c}  mesh.new{texture=c}  sim.world{texture=c}
  改瞳孔的做法：找到眼睛的材质 → c:image(mat) 把原图铺上去 → 按 uv_bounds 找到位置，用 radial / circle / ring 画虹膜、瞳孔、高光 → mat:texture(c)
  c:remove()

mesh（自己搭网格：任何形状，每帧都能改）
  local g = mesh.new{ texture=, color={r,g,b,a}, lit=true, additive=false, double_sided=true, transparent=false,
                      shine=0.2, glow=0, mode="triangles", point_size=6, auto_normals=false, visible=true,
                      pos={x,y,z}, rot={rx,ry,rz}, scale=1, model=m, bone="頭" }
     mode: "triangles" 三角面 / "lines" 线段 / "points" 点。transparent=true：贴图带透明或 color 的 a<1 时用。model/bone：跟着骨骼（pos/rot 变成相对骨骼的）
  现成的形状：g:grid(nx, nz, 宽, 深)   XZ 平面上的网，(nx+1)×(nz+1) 个顶点，按行排（水面、布、地形）
              g:sphere(半径 [, 段数])   g:box(w,h,d)
  自己给：g:build{ positions={x,y,z, ...}, normals={...}, uvs={u,v, ...}, colors={r,g,b,a, ...}, indices={1,2,3, ...} }
          顶点编号从 1 起；不给 normals 会自动算；不给 indices 就按顺序每三个顶点一个面
  逐个改：g:vertex(i) -> x,y,z   g:vertex(i, x,y,z)   g:normal(i [, x,y,z])   g:uv(i [, u,v])   g:vcolor(i [, r,g,b,a])
          g:positions({x,y,z, ...}) 一次全给   g:positions() -> 全部坐标   g:indices({...})   g:resize(n)   g:recalc() 重算法线
  g.count（顶点数）  g.triangles
  g:set{...}  g:move(x,y,z)  g:rotate(rx,ry,rz)  g:scale(s)  g:attach(m, "骨骼名")  g:remove()
  g:follow(w, first)      顶点直接跟着物理世界 w 的质点走（第 1 个顶点 = 第 first 个质点），法线每帧自动重算 → 布料、软体
  一个脚本里所有网格的顶点改动是直接生效的；每帧改几千个顶点没有问题，上万个就要省着用。脚本网格不投影子。

sim（自己做物理：质点、约束、液体、刚体、碰撞查询）
  local w = sim.world{ max=2000,            最多多少个质点（上限 6000，只在创建时有效）
       gravity=-98,                          重力，一个数 = 向下，或 {x,y,z}
       substeps=4, iterations=2,             每 1/60 秒分成几个子步、每个子步解几遍约束（越大越准、越硬，也越慢）
       damping=0.02, wind={x,y,z}, drag=0,   空气阻力（每秒损失的比例）；风和被风带着走的强度
       ground=true,                          地面：不写 = 跟着场景的地板（floor），false = 没有，一个数 = 地面高度
       friction=0.3, bounce=0,               摩擦、反弹（0..1）
       collide=true,                         和场景里模型的刚体碰撞（角色的身体、舞台）
       bounds={x0,y0,z0, x1,y1,z1},          容器：粒子出不去（装水的缸）
       spacing=0.4, viscosity=0.02, cohesion=0.1,     液体：粒子间距、粘性（0.3 以上像蜂蜜）、内聚（表面张力）
       render="liquid",                      怎么画："none" / "points" 贴图方片 / "balls" 小球 / "liquid" 融成一片的液面 / "lines" 把约束画成线
       color={r,g,b,a}, radius=, texture=, additive=,
       auto=true }                           true = 跟着每一帧自动推进；false = 脚本自己调 w:step(dt)
  求解方法：约束用 XPBD（compliance 是柔度，0 = 完全不伸长，结果不随步长变）；液体用 PBF（基于位置的流体：密度约束 + XSPH 粘性）。
  质点：local i = w:add(x,y,z, {mass=1, radius=0.2, collide=false, fluid=false, vel={x,y,z}, color={r,g,b,a}})   mass=0 固定不动；collide=true 质点之间互相碰
        w:pos(i) -> x,y,z   w:pos(i, x,y,z)   w:vel(i) -> x,y,z   w:vel(i, x,y,z)   w:mass(i [, 质量])   w:radius(i [, 半径])   w:tint(i, r,g,b,a)
        w:force(i, fx,fy,fz)      加力（只管下一个子步，所以要在 on_step 里加）     w:impulse(i, x,y,z)  冲量
        w:pin(i)  钉在原地        w:pin(i, m, "骨骼名")  钉到骨骼上跟着走        w:unpin(i)
  约束：local k = w:link(i, j, {rest=长度, compliance=0})   两点保持距离（不给 rest = 现在的距离）   w:unlink(k) 剪断   w:rest(k [, 长度])   w:ends(k) -> i,j
        local first, n = w:rope(x0,y0,z0, x1,y1,z1, 段数, {mass=, radius=, compliance=, bend=, collide=})      一条绳子 / 头发 / 链子
        local first, n = w:cloth(nx, nz, 宽, 深, {pos={x,y,z}, vertical=false, mass=, radius=, compliance=, bend=0.0005, collide=})
                         一块布，质点的排法和 g:grid(nx, nz, 宽, 深) 一样 → g:follow(w, first) 就能画出来
  液体：local first, n = w:fill(x0,y0,z0, x1,y1,z1)    用液体粒子填满一个盒子
        w:emit(x,y,z, vx,vy,vz) -> i      喷出一个液体粒子；放满以后回收最早的，所以可以一直倒
        w:density(i)     这个粒子处的密度 / 静止密度（1 = 正好）     w:neighbors(i) -> 附近粒子的编号
  自己写物理：w.on_step = function(h) ... end     每个子步开始时调用（h = 子步长，秒）。在里面用 w:pos / w:vel / w:force 读写任何质点，
              就能实现内置没有的东西：万有引力、弹簧、电荷、磁场、浮力、涡流、自定义约束……内置的碰撞和约束照常生效（不想要就别建约束、collide=false、ground=false）
              想完全自己掌握节奏：sim.world{auto=false}，在 update 里 w:step(dt)
  w.count  w.links  w.time  w.steps  w.contacts（上一步的接触数）  w.energy（总动能，检查守恒用）
  w:set{...}  w:clear()  w:remove()
  碰撞体：local sh = sim.collider{shape="sphere", size=半径, pos={x,y,z}, rot={rx,ry,rz}, friction=0.5, model=m, bone="右手首"}
          shape = "sphere"（size=半径）/ "box"（size={宽,高,深}）/ "capsule"（size={半径, 中段长度}，竖着的）；质点和脚本刚体都会撞上它
          sh:move(x,y,z)  sh:rotate(rx,ry,rz)  sh:set{...}  sh:remove()
  刚体：local rb = sim.body{shape="box", size={2,2,2}, mass=1, pos={x,y,z}, rot={rx,ry,rz}, vel={x,y,z},
                            friction=0.5, bounce=0, damping=0.02, angular_damping=0.05, color={r,g,b,a}, texture=, visible=true}
        和模型头发裙子用的是同一个求解器；会落在地上、被角色的身体撞开、互相堆叠。mass=0 = 不受力，用 rb:move 带着走
        rb:pos() -> x,y,z   rb:pos(x,y,z)   rb:rot() -> rx,ry,rz   rb:rot(rx,ry,rz)   rb:vel([x,y,z])   rb:spin([x,y,z])   rb:impulse(x,y,z)   rb:move(x,y,z [, rx,ry,rz])
        rb.mesh（自动配的那个网格，可以 rb.mesh:set{...} 改外观）   rb.mass   rb:remove()
        sim.joint(rb1, rb2, {pos={x,y,z}, lin_lo={0,0,0}, lin_hi={0,0,0}, ang_lo={-30,0,0}, ang_hi={30,0,0}, lin_spring={0,0,0}, ang_spring={0,0,0}})
                            关节（和 PMX 的 Joint 同一种）：默认在 pos 把两个刚体焊死；给某个轴上下限就放开它
  查询：sim.raycast(x,y,z, dx,dy,dz [, 最远]) -> 距离, hx,hy,hz, nx,ny,nz, 模型      打到地面 / 模型刚体 / 碰撞体 / 脚本刚体；没打到返回 nil
        sim.distance(x,y,z) -> 距离, nx,ny,nz, 模型      离最近的表面多远（在里面为负）
        带碰撞属性的模型（m.collision = "box" / "mesh"）也算在里面：粒子、布、水、脚本刚体都会被它挡住
  注意：粒子和脚本刚体会被模型撞开，但不会反过来推动模型（单向）。液体是粒子做的，一两千个粒子是一捧水的量，不是一池水。

ui / input
  ui.text("id", "文字", {x=0.5, y=0.88, size=16, color={1,1,1,1}})    屏幕上的字（字幕）；文字给 nil = 去掉
  ui.button("id", "标签", {x=0.5, y=0.75})    加一个屏幕按钮 → on_button("id", down)；标签给 nil = 去掉
  ui.toast("一句话")      ui.clear()
  input.x  input.y        摇杆（-1..1，右 / 前为正）       input.down("jump") 某个按钮是否按着

小工具
  after(秒, fn)   every(秒, fn)   cancel(t)
  tween(秒, function(k) ... end [, ease.out_back [, 结束时调用的函数]])     k 从 0 走到 1
  run(function() ... wait(1.5) ... end)      写成一步一步的顺序：wait 会停下来等
  ease.linear in_quad out_quad inout out_cubic out_back out_elastic out_bounce
  math.clamp(v,lo,hi)  math.lerp(a,b,t)  math.smoothstep(e0,e1,x)  math.sign  math.round  math.noise(x[,y[,z]])（平滑噪声 -1..1）
  print(...)      输出到脚本面板的日志
  require("另一个脚本的名字")
""".trim()

    /** 新建脚本时填进去的样子。 */
    val TEMPLATE = """
-- 新脚本。最外层的代码在脚本启动时运行一次；update 每一帧运行。
-- 能用的东西见菜单「脚本 → 接口说明」。
local m = scene.main()          -- 这个脚本作用的角色（场景是空的时为 nil）

function update(dt)
    if not m then return end
    -- 例：让头跟着时间左右转
    -- m:bone("頭"):rotate(0, math.sin(time.real * 2) * 20, 0)
end
""".trimStart()

    class Example(val name: String, val about: String, val source: String)

    val EXAMPLES: List<Example> = listOf(
        Example("呼吸和眨眼", "没有动作的模型也会微微起伏、眨眼，看起来是活的", """
-- 呼吸和眨眼：给站着不动的角色加一点生气
local m = scene.main()
local chest, head, blink
local next_blink, blink_t = 1.5, -1

function init()
  if not m then print("场景里还没有角色") return end
  chest = m:bone("上半身2") or m:bone("上半身")
  head = m:bone("頭")
  for _, name in ipairs(m:morphs()) do
    if name == "まばたき" or name == "眨眼" or name:lower() == "blink" then blink = name end
  end
end

function update(dt)
  if not m or not m.valid then return end
  local t = time.real
  -- 呼吸：胸口前后轻轻摆，头跟着反方向找一点平衡
  local breath = math.sin(t * 1.6)
  if chest then chest:rotate(breath * 1.2, 0, 0) end
  if head then head:rotate(-breath * 0.6 + math.noise(t * 0.4) * 2, math.noise(t * 0.3, 5) * 4, 0) end
  -- 眨眼：隔几秒快速闭一下
  if blink then
    next_blink = next_blink - dt
    if next_blink <= 0 then next_blink = 2 + math.random() * 4; blink_t = 0 end
    if blink_t >= 0 then
      blink_t = blink_t + dt
      local k = blink_t / 0.16
      m:morph(blink, k < 1 and math.sin(k * math.pi) or 0)
      if k >= 1 then blink_t = -1 end
    end
  end
end
"""),
        Example("裙摆飘动", "一阵一阵的风吹动裙子和头发（靠物理），风向会慢慢转", """
-- 裙摆飘动：用物理里的"风"吹裙子和头发
local strength = 120     -- 风有多大（加速度）
local gusty = 0.8        -- 阵风的起伏 0..1

function update(dt)
  local t = time.real
  -- 风向绕着角色慢慢转，强弱再跟着噪声起伏
  local a = t * 0.35
  local k = strength * (0.55 + 0.45 * math.noise(t * 0.5))
  physics.wind(math.cos(a) * k, 0, math.sin(a) * k, gusty)
end
"""),
        Example("裙子变换", "裙子自己变长变短、张开收拢，颜色跟着流动（不靠物理，直接驱动裙子骨骼）", """
-- 裙子变换：直接驱动裙子的骨骼和材质
local m = scene.main()
local roots, all, mats = {}, {}, {}

function init()
  if not m then print("场景里还没有角色") return end
  -- 裙子的骨骼平时归物理管，先接过来
  -- 名字里带这些字的骨骼 / 刚体 / 材质算作"裙子"：你的模型叫法不一样的话，在这里加上
  for _, key in ipairs({ "スカート", "裙", "skirt", "Skirt", "后摆", "裾" }) do
    for _, r in ipairs(m:bodies(key)) do r.mode = "bone" end
    for _, b in ipairs(m:bones(key)) do all[#all + 1] = b end
    for _, mat in ipairs(m:materials(key)) do mats[#mats + 1] = mat end
  end
  -- 每一串裙骨最上面的那一根（它的父骨不在裙骨里）
  local is_skirt = {}
  for _, b in ipairs(all) do is_skirt[b.index] = true end
  for _, b in ipairs(all) do
    local p = b.parent
    if not p or not is_skirt[p.index] then roots[#roots + 1] = b end
  end
  print(("裙子：%d 根骨骼，%d 串，%d 块材质"):format(#all, #roots, #mats))
  if #all == 0 then print("没找到名字里带 スカート / 裙 / skirt / 后摆 的骨骼：打开脚本，把这个模型裙子骨骼名字里的字加到上面的列表里") end
end

function update(dt)
  if not m or not m.valid then return end
  local t = time.real
  -- 长度：在 0.7 倍到 1.5 倍之间来回
  local len = 1.1 + 0.4 * math.sin(t * 1.3)
  for _, b in ipairs(all) do b:scale(1, len, 1) end
  -- 张开：每一串往外掀，沿着一圈有一个波在转
  for i, b in ipairs(roots) do
    local x, y, z = b:pos()
    local mx, my, mz = m:pos()
    local ang = math.atan2(z - mz, x - mx)
    local open = 18 + 16 * math.sin(t * 2.2 + ang * 2)
    -- 朝着"离开身体"的方向转：绕水平的切线方向
    b:rotate(-math.sin(ang) * open, 0, math.cos(ang) * open)
  end
  -- 颜色：色相慢慢流动
  local r = 0.75 + 0.25 * math.sin(t * 0.9)
  local g = 0.75 + 0.25 * math.sin(t * 0.9 + 2.1)
  local bl = 0.75 + 0.25 * math.sin(t * 0.9 + 4.2)
  for _, mat in ipairs(mats) do mat:color(r * 1.2, g * 1.2, bl * 1.2) end
end
"""),
        Example("手上的光点", "两只手带着光点和拖尾，挥手时很明显", """
-- 手上的光点：粒子 + 拖尾跟着手走
local m = scene.main()

function init()
  if not m then print("场景里还没有角色") return end
  local hands = {
    { "右手首", { 1.0, 0.75, 0.3 } },
    { "左手首", { 0.45, 0.7, 1.0 } },
  }
  for _, h in ipairs(hands) do
    local bone, c = h[1], h[2]
    if m:bone(bone) then
      fx.emitter{ model = m, bone = bone, texture = "spark", rate = 40, life = { 0.4, 0.9 }, speed = { 0.5, 2.5 },
                  size = { 0.9, 0.0 }, gravity = { 0, 2, 0 }, drag = 1.5, spin = 180,
                  color = { c[1], c[2], c[3], 1 }, color_end = { c[1], c[2], c[3], 0 } }
      fx.trail{ model = m, bone = bone, width = 0.9, life = 0.45, color = { c[1], c[2], c[3], 0.8 } }
      fx.sprite{ model = m, bone = bone, texture = "soft", size = 2.2, color = { c[1], c[2], c[3], 0.5 } }
    else
      print("没有找到骨骼 " .. bone)
    end
  end
end
"""),
        Example("樱花飘落", "整个场景飘着花瓣，被风带着斜着落", """
-- 樱花飘落
local petals

function init()
  petals = fx.emitter{
    texture = "petal", additive = false, rate = 45, max = 900,
    pos = { 0, 32, 0 }, area = { 30, 2, 30 },
    dir = { 0.3, -1, 0.1 }, spread = 25, speed = { 2, 5 },
    gravity = { 0, -1.5, 0 }, drag = 0.4,
    life = { 6, 10 }, size = { 0.9, 0.7 }, size_jitter = 0.4, spin = 140,
    color = { 1.0, 0.72, 0.82, 0.95 }, color_end = { 1.0, 0.8, 0.88, 0.0 },
  }
end

function update(dt)
  -- 发射方向跟着一阵阵的风摆
  local t = time.real
  petals:set{ dir = { 0.4 + 0.4 * math.noise(t * 0.3), -1, 0.3 * math.noise(t * 0.25, 7) } }
end
"""),
        Example("出场特效", "角色从透明渐渐显现，脚下炸开一圈光，镜头震一下", """
-- 出场特效：按顺序一步一步演
local m = scene.main()

function init()
  if not m then print("场景里还没有角色") return end
  local x, y, z = m:pos()
  m.opacity = 0
  run(function()
    wait(0.4)
    -- 脚下先聚起一圈光点
    local ring = fx.emitter{ texture = "spark", rate = 120, pos = { x, y + 0.3, z }, area = { 4, 0, 4 },
                             dir = { 0, 1, 0 }, spread = 10, speed = { 3, 9 }, life = { 0.5, 1.1 },
                             size = { 0.8, 0 }, color = { 0.75, 0.6, 1, 1 } }
    -- 角色渐渐显现
    tween(1.2, function(k) m.opacity = k end, ease.out_cubic)
    wait(1.2)
    ring:stop()
    -- 炸开：一圈星星 + 闪光 + 镜头震动
    local burst = fx.emitter{ texture = "star", rate = 0, pos = { x, y + 8, z }, spread = 180, speed = { 8, 22 },
                              life = { 0.6, 1.3 }, size = { 1.4, 0.2 }, gravity = { 0, -12, 0 }, drag = 1.2, spin = 300,
                              color = { 1, 0.9, 0.6, 1 }, color_end = { 1, 0.5, 0.9, 0 } }
    burst:burst(160)
    fx.flash(1, 1, 1, 0.7, 0.35)
    camera.shake(1.2, 0.5)
    ui.text("title", m.name, { y = 0.2, size = 26 })
    wait(2.5)
    ui.text("title", nil)
  end)
end
"""),
        Example("上下层与透明", "两个角色（或分身）一前一后：后面的盖到前面来、再渐渐变透明", """
-- 上下层与透明：layer 大的盖在 layer 小的上面，不管谁离镜头更近
local a = scene.main()
local b

function init()
  if not a then print("场景里还没有角色") return end
  local list = scene.models("character")
  b = list[2] or scene.clone(a)         -- 只有一个角色就复制一个分身
  local x, y, z = a:pos()
  b:set_pos(x + 4, y, z + 6)             -- 站到后面偏右一点
  run(function()
    while true do
      ui.text("tip", "正常的远近：后面的被前面的挡住", { y = 0.12 })
      b.layer = 0; b.opacity = 1
      wait(2.5)
      ui.text("tip", "b.layer = 1：后面的盖到了前面", { y = 0.12 })
      b.layer = 1
      wait(2.5)
      ui.text("tip", "再让它变透明：b.opacity 从 1 到 0.25", { y = 0.12 })
      tween(1.5, function(k) b.opacity = 1 - 0.75 * k end)
      wait(3)
    end
  end)
end
"""),
        Example("环绕的几何体", "新建几个几何体绕着角色转，演示怎么驱动自己建的模型", """
-- 环绕的几何体：脚本创建模型，再每帧驱动它们
local m = scene.main()
local items = {}

function init()
  local shapes = { "box", "sphere", "torus", "cone", "cylinder", "box" }
  for i, shape in ipairs(shapes) do
    local hue = i / #shapes * 2 * math.pi
    items[i] = scene.create(shape, {
      name = "环绕" .. i, size = { 1.6, 1.6, 1.6 }, radius = 0.9, height = 1.8,
      color = { 0.6 + 0.4 * math.sin(hue), 0.6 + 0.4 * math.sin(hue + 2.1), 0.6 + 0.4 * math.sin(hue + 4.2), 1 },
    })
  end
end

function update(dt)
  local cx, cy, cz = 0, 0, 0
  if m and m.valid then cx, cy, cz = m:pos() end
  local t = time.real
  for i, it in ipairs(items) do
    local a = t * 0.8 + i / #items * 2 * math.pi
    it:set_pos(cx + math.cos(a) * 9, cy + 10 + math.sin(t * 1.7 + i) * 2.5, cz + math.sin(a) * 9)
    it:set_rot(t * 60 + i * 40, t * 90, 0)
    it.opacity = 0.65 + 0.35 * math.sin(t * 2 + i)
  end
end
"""),
        Example("看向镜头", "头和眼睛一直跟着镜头转", """
-- 看向镜头：算出镜头在头的哪个方向，把头转过去（有限度，转得平滑）
local m = scene.main()
local head, neck, eyes
local yaw, pitch = 0, 0

function init()
  if not m then print("场景里还没有角色") return end
  head = m:bone("頭"); neck = m:bone("首"); eyes = m:bone("両目")
end

function update(dt)
  if not head or not m.valid then return end
  local c = camera.get()
  local hx, hy, hz = head:pos()
  local _, body_yaw = m:rot()
  -- 镜头相对头的方向，换到角色自己的朝向里
  local dx, dy, dz = c.x - hx, c.y - hy, c.z - hz
  local a = math.rad(body_yaw)
  local lx = dx * math.cos(a) - dz * math.sin(a)
  local lz = dx * math.sin(a) + dz * math.cos(a)
  local want_yaw = math.deg(math.atan2(-lx, -lz))       -- 角色面朝 -Z
  local want_pitch = math.deg(math.atan2(dy, math.sqrt(lx * lx + lz * lz)))
  want_yaw = math.clamp(want_yaw, -65, 65)
  want_pitch = math.clamp(want_pitch, -30, 35)
  -- 平滑地跟过去
  local k = 1 - math.exp(-dt * 6)
  yaw = yaw + (want_yaw - yaw) * k
  pitch = pitch + (want_pitch - pitch) * k
  if neck then neck:rotate(-pitch * 0.3, yaw * 0.3, 0) end
  head:rotate(-pitch * 0.5, yaw * 0.5, 0)
  if eyes then eyes:rotate(-pitch * 0.2, yaw * 0.2, 0) end
end
"""),
        Example("字幕", "按时间轴显示歌词 / 台词，可以当模板改", """
-- 字幕：到了时间显示对应的一句（时间用秒）
local lines = {
  { 0.0, "（把这里换成你的歌词）" },
  { 3.0, "第二句" },
  { 6.0, "第三句" },
  { 9.0, "" },
}
local shown = nil

function update(dt)
  local now = time.now
  local text = ""
  for _, l in ipairs(lines) do
    if now >= l[1] then text = l[2] end
  end
  if text ~= shown then
    shown = text
    ui.text("sub", text ~= "" and text or nil, { y = 0.9, size = 18 })
  end
end
"""),
        Example("自定义按钮", "在屏幕上加两个按钮：按一下放烟花、按住变透明", """
-- 自定义按钮：ui.button 加按钮，on_button 里响应
local m = scene.main()

function init()
  ui.button("boom", "烟花", { x = 0.86, y = 0.45 })
  ui.button("ghost", "隐身", { x = 0.86, y = 0.58 })
end

function on_button(id, down)
  if id == "boom" and down then
    local x, y, z = 0, 12, 0
    if m and m.valid then x, y, z = m:pos(); y = y + 22 end
    local e = fx.emitter{ texture = "spark", rate = 0, pos = { x, y, z }, spread = 180, speed = { 6, 20 },
                          life = { 0.8, 1.6 }, size = { 1.2, 0 }, gravity = { 0, -9, 0 }, drag = 0.8,
                          color = { math.random(), 0.6 + 0.4 * math.random(), 1, 1 } }
    e:burst(220)
    after(2, function() e:remove() end)
  elseif id == "ghost" and m and m.valid then
    local from, to = m.opacity, down and 0.2 or 1
    tween(0.3, function(k) m.opacity = from + (to - from) * k end)
  end
end
"""),
        Example("重画瞳孔", "用画布重新画眼睛的贴图：虹膜、瞳孔、高光，颜色还会慢慢变", """
-- 重画瞳孔：把眼睛材质的贴图换成自己画的
local m = scene.main()
if not m then return end
-- 找眼睛的材质：不同模型叫法不一样，这里按常见的名字找；找不到就看「当前场景」里列出的材质名，改成你的
local eye
for _, key in ipairs({ "瞳", "目", "eye", "Eye", "hitomi" }) do
  eye = m:materials(key)[1]
  if eye then break end
end
if not eye then print("没找到眼睛的材质，请把上面的名字改成这个模型的"); return end

local S = 256
local c = canvas.new(S, S)
local ok = pcall(function() c:image(eye) end)       -- 把原来的贴图铺上去（没有贴图也没关系）
if not ok then c:clear(1, 1, 1, 1) end
-- 这块材质用到贴图的哪一块：瞳孔画在它的正中间
local u0, v0, u1, v1 = eye:uv_bounds()
local cx, cy = (u0 + u1) / 2 * S, (v0 + v1) / 2 * S
local r = math.min(u1 - u0, v1 - v0) / 2 * S * 0.8
local base = canvas.new(S, S); base:image(c)        -- 留一份原图，每次从它开始重画

local function draw(hue)
  c:image(base)
  local a, b = 0.5 + 0.5 * math.sin(hue), 0.5 + 0.5 * math.sin(hue + 2.1)
  c:radial(cx, cy, r * 0.25, r, { a, b, 1, 1 }, { a * 0.2, b * 0.2, 0.35, 1 })   -- 虹膜：里亮外暗
  c:circle(cx, cy, r)
  c:color(0.02, 0.02, 0.05, 1); c:circle(cx, cy, r * 0.42)                       -- 瞳孔
  c:color(0, 0, 0, 0.6); c:ring(cx, cy, r, r * 0.12)                             -- 外圈
  c:feather(r * 0.1); c:color(1, 1, 1, 0.95)
  c:circle(cx - r * 0.35, cy - r * 0.4, r * 0.2)                                 -- 高光
  c:circle(cx + r * 0.3, cy + r * 0.35, r * 0.09)
  c:feather(0)
end

draw(0)
eye:texture(c)
local t = 0
every(0.1, function() t = t + 0.1; draw(t * 0.6) end)   -- 颜色慢慢流转；不想动就删掉这一行
"""),
        Example("倒水", "一股水从上面倒下来，落在地上和角色身上，积在一个看不见的缸里", """
-- 倒水：液体是一颗颗粒子，按「基于位置的流体」求解，画的时候融成一片
local m = scene.main()
local x, y, z = 0, 0, 0
if m then x, y, z = m:pos() end
local w = sim.world{
  max = 1800, spacing = 0.45, viscosity = 0.03,
  bounds = { x - 6, 0, z - 6, x + 6, 60, z + 6 },      -- 缸的范围；去掉这一行水就流满地
  render = "liquid", color = { 0.3, 0.6, 1, 0.75 },
}
local t = 0
function update(dt)
  t = t + dt
  -- 每帧喷几颗：一小股，稍微晃一晃
  for i = 1, 4 do
    local a = math.random() * 6.28
    local r = math.random() * 0.5
    w:emit(x + 2 + math.cos(a) * r, y + 26, z + math.sin(a) * r, -3 + math.sin(t) * 2, -8, 0)
  end
end
"""),
        Example("布料披风", "一块真的在算的布，钉在肩膀上，跟着动作甩，会搭在身体上", """
-- 布料披风：sim 里的一块布 + 一张跟着它的网格
local m = scene.main()
if not m then return end
local neck = m:bone("首") or m:bone("上半身2") or m:bone("上半身")
if not neck then print("没找到脖子 / 上半身的骨骼"); return end
local nx, ny, nz = neck:pos()
local W, H, COLS, ROWS = 7, 11, 10, 14

local w = sim.world{ max = 400, substeps = 6, damping = 0.25, friction = 0.4 }
-- 竖着的一块布，上边在肩膀的高度，稍微靠后一点
local first = w:cloth(COLS, ROWS, W, H, { pos = { nx, ny - H / 2 + 0.5, nz + 1.6 }, vertical = true, radius = 0.25, mass = 0.2 })
-- 最上面一排钉在骨骼上：角色动，布跟着走
for c = 0, COLS do w:pin(first + c, m, neck.name) end

-- 画出来：一张同样排法的网，贴一张自己画的渐变
local tex = canvas.new(64, 64)
tex:linear(0, 0, 0, 64, { 0.85, 0.1, 0.2, 1 }, { 0.35, 0.02, 0.12, 1 })
tex:rect(0, 0, 64, 64)
tex:color(1, 0.85, 0.4, 1); tex:rect(0, 56, 64, 4)      -- 下摆的金边
local g = mesh.new{ texture = tex, shine = 0.1 }
g:grid(COLS, ROWS, W, H)
g:follow(w, first)

function update(dt)
  -- 一点点风，让它不死板
  w:set{ wind = { math.noise(time.real * 0.5) * 30, 0, 20 + math.noise(time.real * 0.7, 3) * 20 }, drag = 0.6 }
end
"""),
        Example("自己写的物理", "内置里没有的力：几十颗小球互相用万有引力吸着转，力是脚本在 on_step 里自己算的", """
-- 自己写的物理：世界只负责积分，力由脚本给。这里是万有引力（平方反比），可以换成任何公式
local m = scene.main()
local cx, cy, cz = 0, 12, 0
if m then local x, y, z = m:pos(); cx, cy, cz = x, y + 12, z end

local w = sim.world{ max = 64, gravity = 0, damping = 0, ground = false, collide = false,
                     substeps = 8, render = "balls" }
local G = 60
local N = 40
-- 中间一颗重的，周围一圈轻的，初速度正好够绕着转：v = sqrt(G·M / r)
local M = 50
local sun = w:add(cx, cy, cz, { mass = M, radius = 0.9, color = { 1, 0.8, 0.3, 1 } })
for i = 1, N do
  local a = i / N * math.pi * 2
  local r = 4 + (i % 5) * 1.2
  local v = math.sqrt(G * M / r)
  w:add(cx + math.cos(a) * r, cy + math.sin(a * 3) * 0.6, cz + math.sin(a) * r,
        { mass = 0.05, radius = 0.25, vel = { -math.sin(a) * v, 0, math.cos(a) * v },
          color = { 0.4 + 0.6 * math.random(), 0.6, 1, 1 } })
end

w.on_step = function(h)
  -- 每个子步：所有小球受到太阳的引力 F = G·M·m / r²，方向指向太阳
  local sx, sy, sz = w:pos(sun)
  for i = 2, w.count do
    local x, y, z = w:pos(i)
    local dx, dy, dz = sx - x, sy - y, sz - z
    local d2 = dx * dx + dy * dy + dz * dz + 0.01
    local f = G * M * w:mass(i) / (d2 * math.sqrt(d2))
    w:force(i, dx * f, dy * f, dz * f)
  end
end

ui.text("e", "", { y = 0.1 })
every(0.5, function() ui.text("e", string.format("动能 %.1f", w.energy), { y = 0.1, size = 13 }) end)
"""),
        Example("积木", "一堆箱子和球掉下来，堆在地上，会被角色的身体撞开", """
-- 积木：脚本刚体，和模型的头发裙子用的是同一个物理求解器
local m = scene.main()
local x, y, z = 0, 0, 0
if m then x, y, z = m:pos() end
local colors = { { 1, 0.4, 0.4, 1 }, { 0.4, 0.8, 1, 1 }, { 1, 0.85, 0.3, 1 }, { 0.6, 1, 0.5, 1 } }
local n = 0
every(0.25, function()
  if n >= 40 then return end
  n = n + 1
  local shape = (n % 3 == 0) and "sphere" or "box"
  local s = 1 + math.random()
  sim.body{ shape = shape, size = shape == "sphere" and s * 0.6 or { s, s, s }, mass = s,
            pos = { x + 5 + math.random() * 2, y + 22, z + math.random() * 2 - 1 },
            rot = { math.random() * 90, math.random() * 90, 0 },
            vel = { -6, 0, 0 }, bounce = 0.2, color = colors[n % #colors + 1] }
end)
"""),
        Example("三渲二", "把整个画面换成动画片的画法：光照分成亮 / 暗两级、暗部偏冷、所有模型都描边，再加一点边缘光", """
-- 三渲二（卡通渲染）：一句 render.toon 就够了，下面是把每一项都写出来的版本
render.toon{
  bands = 2,                     -- 光照分几级：1 = 只有亮和暗，2 = 多一级中间色
  shade = { 0.68, 0.62, 0.82 },  -- 暗部的颜色（偏紫的冷色，日系动画常见）
  edge = 1.4,                    -- 描边粗细
  edge_color = { 0.16, 0.10, 0.14 },
  rim = 0.35,                    -- 轮廓上的一圈亮边
}
render.set{ shadows = 0.9, bloom = 0.25, contrast = 1.06 }
render.background(0.98, 0.93, 0.90, 0.80, 0.88, 0.98)

-- 脸和眼睛通常不要硬的明暗分界：单独关掉它们的三渲二（名字按你的模型改）
local m = scene.main()
if m then
  for _, mat in ipairs(m:materials()) do
    local n = mat.name
    if n:find("顔") or n:find("颜") or n:find("脸") or n:find("face") or n:find("目") or n:find("眼") or n:find("eye") then
      mat.cel = false
    end
  end
end

-- 光从哪边来，明暗分界就在哪边：让灯慢慢绕一圈，看分界线怎么走
function update(dt)
  local a = time.now * 0.4
  light.dir(math.sin(a) * 0.7, -1, math.cos(a) * 0.7)
end
"""),
        Example("水墨与漫画", "同一个场景三种画法轮流换：黑白漫画、复古海报、普通——演示 render.set 能动的东西", """
-- 每 4 秒换一种画法；脚本停掉后画面自己回到原样
local looks = {
  { name = "黑白漫画", set = { cel = 1, cel_soft = 0, shade = { 0.25, 0.25, 0.25 }, saturation = 0, contrast = 1.5,
                              edges = 1.8, edge_all = true, edge_color = { 0, 0, 0 }, rim = 0, bloom = false, posterize = 0, grade = { 1, 1, 1 },
                              background = { 1, 1, 1 } } },
  { name = "复古海报", set = { cel = 2, cel_soft = 0.02, shade = { 0.55, 0.40, 0.45 }, saturation = 1.25, contrast = 1.15,
                              edges = 1.2, edge_all = true, edge_color = { 0.25, 0.08, 0.05 }, posterize = 5, grade = { 1.05, 0.96, 0.86 },
                              background = { { 0.96, 0.82, 0.62 }, { 0.90, 0.55, 0.42 } } } },
  { name = "原样" },
}
local i = 0
local function nextLook()
  i = i % #looks + 1
  render.reset()
  if looks[i].set then render.set(looks[i].set) end
  ui.text("look", looks[i].name, { y = 0.1, size = 15 })
end
nextLook()
every(4, nextLook)
"""),
        Example("台阶和地板", "给舞台加上碰撞、把地板抬高或删掉：掉下来的球会停在台子上，没有地板时一直往下掉", """
-- 建一张桌子（脚本自己建的几何体），给它「碰撞」属性；球落在桌面上而不是穿过去
local desk = scene.create("box", { name = "桌子", size = { 14, 1, 10 }, pos = { 0, 8, 6 }, color = { 0.75, 0.6, 0.45, 1 } })
desk.collision = "mesh"          -- "box" 按包围盒，"mesh" 按每个面（舞台、台阶用 mesh）

-- 地板：换个底色、抬高一点；想删掉就 floor.remove()，想加回来就 floor.reset()
floor.set{ height = 0, color = { 0.92, 0.93, 0.96, 1 }, cell = 4 }

local n = 0
every(0.3, function()
  if n >= 30 then return end
  n = n + 1
  sim.body{ shape = "sphere", size = 0.7, mass = 1, bounce = 0.3,
            pos = { math.random() * 16 - 8, 20, 6 + math.random() * 8 - 4 },
            color = { 0.4 + math.random() * 0.6, 0.6, 1, 1 } }
end)

-- 8 秒后把地板抽走：没被桌子接住的球掉下去
after(8, function() floor.remove(); ui.toast("地板没了") end)
after(12, function() floor.reset(); ui.toast("地板回来了") end)
"""),
    )
}
