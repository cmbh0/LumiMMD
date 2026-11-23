# 生成 .blend 测试场景 + 基准数据（各版本 Blender 都能跑：2.79 / 2.8+ / 4.x / 5.x）
import bpy, sys, os, json, math
from mathutils import Matrix, Vector

argv = sys.argv[sys.argv.index("--") + 1:]
out_dir = argv[0]; tag = argv[1]
V = bpy.app.version
NEW = V >= (2, 80, 0)
os.makedirs(os.path.join(out_dir, "textures"), exist_ok=True)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene

def mul(a, b):
    return (a @ b) if NEW else (a * b)

def link(obj, coll=None):
    if NEW:
        (coll or scene.collection).objects.link(obj)
    else:
        scene.objects.link(obj)

# ---------------------------------------------------------------- 图片
def make_image(name, w, h, fn):
    img = bpy.data.images.new(name, w, h, alpha=True)
    px = []
    for y in range(h):
        for x in range(w):
            px.extend(fn(x, y))
    img.pixels = px
    return img

checker = make_image("checker", 16, 16, lambda x, y: (1, 1, 1, 1) if ((x // 4 + y // 4) % 2 == 0) else (0.2, 0.3, 0.8, 1))
checker.filepath_raw = os.path.join(out_dir, "textures", "checker.png")
checker.file_format = 'PNG'
checker.save()
checker.filepath = "//textures/checker.png"

packed = make_image("packed_tex", 8, 8, lambda x, y: (x / 7.0, y / 7.0, 0.25, 1))
tmp = os.path.join(out_dir, "textures", "_packed_tmp.png")
packed.filepath_raw = tmp
packed.file_format = 'PNG'
packed.save()
if NEW:
    packed.pack()
else:
    packed.pack(as_png=True)
packed.filepath = "//textures/packed_tex.png"   # 这个文件不存在：只有包里那份

# ---------------------------------------------------------------- 材质
def make_material(name, color, image=None, alpha=1.0, emission=None):
    mat = bpy.data.materials.new(name)
    if NEW:
        mat.use_nodes = True
        nt = mat.node_tree
        bsdf = None
        for n in nt.nodes:
            if n.bl_idname == "ShaderNodeBsdfPrincipled":
                bsdf = n
        if bsdf is None:
            bsdf = nt.nodes.new("ShaderNodeBsdfPrincipled")
            outn = [n for n in nt.nodes if n.bl_idname == "ShaderNodeOutputMaterial"]
            outn = outn[0] if outn else nt.nodes.new("ShaderNodeOutputMaterial")
            nt.links.new(outn.inputs["Surface"], bsdf.outputs["BSDF"])
        bsdf.inputs["Base Color"].default_value = (color[0], color[1], color[2], 1.0)
        bsdf.inputs["Alpha"].default_value = alpha
        bsdf.inputs["Roughness"].default_value = 0.4
        bsdf.inputs["Metallic"].default_value = 0.1
        if emission is not None:
            key = "Emission Color" if "Emission Color" in bsdf.inputs else "Emission"
            bsdf.inputs[key].default_value = (emission[0], emission[1], emission[2], 1.0)
            if "Emission Strength" in bsdf.inputs:
                bsdf.inputs["Emission Strength"].default_value = 1.0
        if image is not None:
            tex = nt.nodes.new("ShaderNodeTexImage")
            tex.image = image
            nt.links.new(bsdf.inputs["Base Color"], tex.outputs["Color"])
        mat.diffuse_color = (color[0], color[1], color[2], 1.0)
        if alpha < 1.0:
            try: mat.blend_method = 'BLEND'
            except Exception: pass
    else:
        mat.diffuse_color = (color[0], color[1], color[2])
        mat.specular_intensity = 0.3
        if alpha < 1.0:
            mat.use_transparency = True
            mat.alpha = alpha
        if image is not None:
            tex = bpy.data.textures.new(name + "_tex", 'IMAGE')
            tex.image = image
            slot = mat.texture_slots.add()
            slot.texture = tex
            slot.texture_coords = 'UV'
            slot.use_map_color_diffuse = True
    return mat

m_floor = make_material("FloorMat", (0.8, 0.7, 0.6), checker)
m_red = make_material("Red", (0.9, 0.1, 0.1))
m_blue = make_material("Blue", (0.1, 0.2, 0.9), emission=(0.0, 0.1, 0.5))
m_ball = make_material("BallMat", (1.0, 1.0, 1.0), packed)
m_glass = make_material("Glass", (0.6, 0.9, 1.0), alpha=0.35)
m_plain = make_material("Plain", (0.5, 0.5, 0.5))

# ---------------------------------------------------------------- 网格
def make_mesh(name, verts, faces, uvs=None, smooth=False, mats=(), face_mats=None):
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.update(calc_edges=True)
    if uvs is not None:
        if NEW:
            me.uv_layers.new(name="UVMap")
        else:
            me.uv_textures.new("UVMap")
        layer = me.uv_layers[0]
        i = 0
        for p in me.polygons:
            for li in p.loop_indices:
                layer.data[li].uv = uvs[i]
                i += 1
    for m in mats:
        me.materials.append(m)
    for p in me.polygons:
        p.use_smooth = smooth
        if face_mats is not None:
            p.material_index = face_mats[p.index]
    me.update()
    return me

def cube(s=1.0):
    v = [(-s, -s, -s), (s, -s, -s), (s, s, -s), (-s, s, -s), (-s, -s, s), (s, -s, s), (s, s, s), (-s, s, s)]
    f = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)]
    uv = []
    for _ in f:
        uv += [(0, 0), (1, 0), (1, 1), (0, 1)]
    return v, f, uv

def plane(s=1.0):
    return [(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0)], [(0, 1, 2, 3)], [(0, 0), (4, 0), (4, 4), (0, 4)]

def sphere(r=1.0, seg=12, rings=6):
    v = [(0, 0, r)]
    for i in range(1, rings):
        t = math.pi * i / rings
        for j in range(seg):
            p = 2 * math.pi * j / seg
            v.append((r * math.sin(t) * math.cos(p), r * math.sin(t) * math.sin(p), r * math.cos(t)))
    v.append((0, 0, -r))
    f = []; uv = []
    def U(j, i): return (j / seg, 1 - i / rings)
    for j in range(seg):
        a = 1 + j; b = 1 + (j + 1) % seg
        f.append((0, a, b)); uv += [U(j + 0.5, 0), U(j, 1), U(j + 1, 1)]
    for i in range(1, rings - 1):
        for j in range(seg):
            a = 1 + (i - 1) * seg + j; b = 1 + (i - 1) * seg + (j + 1) % seg
            c = a + seg; d = b + seg
            f.append((a, c, d, b)); uv += [U(j, i), U(j, i + 1), U(j + 1, i + 1), U(j + 1, i)]
    last = len(v) - 1
    base = 1 + (rings - 2) * seg
    for j in range(seg):
        a = base + j; b = base + (j + 1) % seg
        f.append((last, b, a)); uv += [U(j + 0.5, rings), U(j + 1, rings - 1), U(j, rings - 1)]
    return v, f, uv

def cone(r=1.0, h=2.0, seg=8):
    v = [(r * math.cos(2 * math.pi * j / seg), r * math.sin(2 * math.pi * j / seg), 0) for j in range(seg)]
    v.append((0, 0, h))
    f = [tuple(reversed(range(seg)))]            # 底面：一个八边形
    for j in range(seg):
        f.append((j, (j + 1) % seg, seg))
    uv = [(0.5 + 0.5 * math.cos(2 * math.pi * j / seg), 0.5 + 0.5 * math.sin(2 * math.pi * j / seg)) for j in reversed(range(seg))]
    for j in range(seg):
        uv += [(j / seg, 0), ((j + 1) / seg, 0), ((j + 0.5) / seg, 1)]
    return v, f, uv

def lshape():
    # 一个凹的六边形（L 形）+ 一个四边形 + 一个三角形
    v = [(0, 0, 0), (2, 0, 0), (2, 1, 0), (1, 1, 0), (1, 2, 0), (0, 2, 0), (2, 2, 0), (3, 0, 0), (3, 1, 0)]
    # 六边形从 (2,0) 这个角起头：从它拉扇形会切到 L 形外面去，只有真正的凹多边形三角化才对
    f = [(1, 2, 3, 4, 5, 0), (3, 2, 6, 4), (1, 7, 8)]
    uv = [(x / 3.0, y / 2.0) for (x, y, z) in [v[i] for face in f for i in face]]
    return v, f, uv

def add(name, mesh, loc=(0, 0, 0), rot=(0, 0, 0), scale=(1, 1, 1), parent=None, coll=None):
    ob = bpy.data.objects.new(name, mesh)
    link(ob, coll)
    ob.location = loc; ob.rotation_euler = rot; ob.scale = scale
    if parent is not None:
        ob.parent = parent
    return ob

v, f, uv = plane(5.0)
floor = add("Floor", make_mesh("FloorMesh", v, f, uv, mats=[m_floor]))

v, f, uv = cube(1.0)
cube_ob = add("Cube", make_mesh("CubeMesh", v, f, uv, mats=[m_red, m_blue], face_mats=[0, 1, 0, 0, 0, 0]), loc=(3, 0, 1), rot=(0, 0, math.radians(45)))

v, f, uv = cube(0.5)
child = add("Child", make_mesh("ChildMesh", v, f, uv, mats=[m_plain]), loc=(0, 0, 1.75), scale=(0.5, 0.5, 0.5), parent=cube_ob)

v, f, uv = sphere(1.0, 12, 6)
ball_mesh = make_mesh("BallMesh", v, f, uv, smooth=True, mats=[m_ball])
ball = add("Ball", ball_mesh, loc=(-3, 1, 1))
ball2 = add("BallLinked", ball_mesh, loc=(-3, -3, 0.5), scale=(0.5, 0.5, 0.5))      # 共用同一份网格

v, f, uv = lshape()
ngon = add("Ngon", make_mesh("NgonMesh", v, f, uv, mats=[m_glass]), loc=(0, -3, 0.5))

v, f, uv = cone(1.0, 2.0, 8)
mirrored = add("Mirrored", make_mesh("ConeMesh", v, f, uv, mats=[m_red]), loc=(0, 3, 0), scale=(-1, 1.5, 1))   # 负缩放

# 用四元数旋转的物体
v, f, uv = cube(0.4)
quat_ob = add("QuatBox", make_mesh("QuatMesh", v, f, uv, mats=[m_blue]), loc=(2, 3, 2))
quat_ob.rotation_mode = 'QUATERNION'
quat_ob.rotation_quaternion = (0.9238795, 0.0, 0.3826834, 0.0)

# 没有材质的物体
v, f, uv = cube(0.3)
nomat = add("NoMat", make_mesh("NoMatMesh", v, f, None), loc=(-1, 4, 0.3))

# 物体级材质（材质挂在物体上而不是网格上）
v, f, uv = cube(0.3)
objmat_mesh = make_mesh("ObjMatMesh", v, f, uv, mats=[m_plain])
objmat = add("ObjMat", objmat_mesh, loc=(1, 4, 0.3))
objmat.material_slots[0].link = 'OBJECT'
objmat.material_slots[0].material = m_blue

# 不渲染的物体：不应该导入
v, f, uv = cube(2.0)
hidden = add("HiddenBox", make_mesh("HiddenMesh", v, f, uv, mats=[m_red]), loc=(0, 0, 8))
hidden.hide_render = True

# 镜像修改器：只建了一半
half = add("MirrorHalf", make_mesh("HalfMesh", [(0, -1, 0), (1, -1, 0), (1, 1, 0), (0, 1, 0), (1, 1, 1), (0, 1, 1)], [(0, 1, 2, 3), (3, 2, 4, 5)],
                                   [(0, 0), (1, 0), (1, 1), (0, 1), (0, 0), (1, 0), (1, 1), (0, 1)], mats=[m_plain]), loc=(6, -3, 0.01))
mod = half.modifiers.new("Mirror", 'MIRROR')
try: mod.use_axis[0] = True
except Exception: mod.use_x = True

# 阵列修改器
v, f, uv = cube(0.25)
arr = add("ArrayPost", make_mesh("PostMesh", v, f, uv, mats=[m_red]), loc=(-6, -4, 0.25))
mod = arr.modifiers.new("Array", 'ARRAY')
mod.count = 4
mod.use_relative_offset = False
mod.use_constant_offset = True
mod.constant_offset_displace = (0, 1.5, 0)

# 细分修改器（不支持：按原始网格导入并提醒）
v, f, uv = cube(0.5)
sub = add("SubsurfBox", make_mesh("SubMesh", v, f, uv, mats=[m_plain]), loc=(6, 3, 0.5))
sub.modifiers.new("Subdivision", 'SUBSURF')

# 圆柱：全部平滑着色，但两圈盖子的边标成了锐边（盖子和侧面之间应当是硬边）
def cylinder(r=0.5, h=1.0, seg=16):
    v = [(r * math.cos(2 * math.pi * j / seg), r * math.sin(2 * math.pi * j / seg), z) for z in (0, h) for j in range(seg)]
    f = [tuple(reversed(range(seg))), tuple(range(seg, 2 * seg))] + [(j, (j + 1) % seg, seg + (j + 1) % seg, seg + j) for j in range(seg)]
    return v, f
v, f = cylinder()
sharp_mesh = make_mesh("SharpMesh", v, f, None, smooth=True, mats=[m_plain])
for e in sharp_mesh.edges:
    a, b_ = e.vertices
    if (a < 16) == (b_ < 16): e.use_edge_sharp = True
if V < (4, 1, 0):
    sharp_mesh.use_auto_smooth = True; sharp_mesh.auto_smooth_angle = math.pi
sharp_ob = add("SharpCyl", sharp_mesh, loc=(-5, -1, 0))

# 圆柱：自动平滑 40°（侧面之间 22.5° 是平滑的，盖子和侧面 90° 是硬边）
angle_mesh = make_mesh("AngleMesh", v, f, None, smooth=True, mats=[m_plain])
angle_ob = add("AngleCyl", angle_mesh, loc=(-5, 1, 0))
if V < (4, 1, 0):
    angle_mesh.use_auto_smooth = True; angle_mesh.auto_smooth_angle = math.radians(40)
else:
    try:
        bpy.context.view_layer.objects.active = angle_ob
        with bpy.context.temp_override(active_object=angle_ob, object=angle_ob, selected_objects=[angle_ob], selected_editable_objects=[angle_ob]):
            bpy.ops.object.shade_auto_smooth(angle=math.radians(40))
    except Exception as ex:
        print("shade_auto_smooth failed", ex)
    if not any(m.type == 'NODES' for m in angle_ob.modifiers):
        # 后台模式下有的版本找不到自带的节点组：手工放一个同名的（内容不重要，导入器只认名字和夹角）
        md = angle_ob.modifiers.new("Smooth by Angle", 'NODES')
        md.node_group = bpy.data.node_groups.new("Smooth by Angle", 'GeometryNodeTree')
    print("AUTO_SMOOTH", [(m.name, m.node_group.name if m.node_group else None) for m in angle_ob.modifiers if m.type == 'NODES'])

# 集合实例 / 组实例
v, f, uv = cube(0.5)
prop_mesh = make_mesh("PropMesh", v, f, uv, mats=[m_blue])
if NEW:
    props = bpy.data.collections.new("Props")
    prop = add("PropCube", prop_mesh, loc=(0, 0, 0.5), coll=props)
    prop2 = add("PropTop", ball_mesh, loc=(0, 0, 1.3), scale=(0.3, 0.3, 0.3), coll=props)
    inst = bpy.data.objects.new("PropInst", None)
    link(inst)
    inst.instance_type = 'COLLECTION'
    inst.instance_collection = props
    # 另一个集合：挂在场景里但被排除（不渲染）
    off = bpy.data.collections.new("Disabled")
    scene.collection.children.link(off)
    offbox = add("OffBox", make_mesh("OffMesh", v, f, uv, mats=[m_red]), loc=(0, 0, 12), coll=off)
    off.hide_render = True
    # 正常的子集合
    sub_coll = bpy.data.collections.new("Decor")
    scene.collection.children.link(sub_coll)
    decor = add("DecorBox", make_mesh("DecorMesh", v, f, uv, mats=[m_floor]), loc=(-6, 4, 0.5), coll=sub_coll)
else:
    grp = bpy.data.groups.new("Props")
    prop = bpy.data.objects.new("PropCube", prop_mesh); prop.location = (0, 0, 0.5)
    prop2 = bpy.data.objects.new("PropTop", ball_mesh); prop2.location = (0, 0, 1.3); prop2.scale = (0.3, 0.3, 0.3)
    grp.objects.link(prop); grp.objects.link(prop2)
    inst = bpy.data.objects.new("PropInst", None)
    link(inst)
    inst.dupli_type = 'GROUP'
    inst.dupli_group = grp
    decor = add("DecorBox", make_mesh("DecorMesh", v, f, uv, mats=[m_floor]), loc=(-6, 4, 0.5))
inst.location = (5, 5, 0); inst.rotation_euler = (0, 0, math.radians(30)); inst.scale = (2, 2, 2)

# 曲线、灯、相机：不是网格
cu = bpy.data.curves.new("TextCurve", 'FONT'); cu.body = "Lumi"
link(bpy.data.objects.new("Text", cu))
if NEW:
    li = bpy.data.lights.new("Sun", 'SUN')
else:
    li = bpy.data.lamps.new("Sun", 'SUN')
sun = bpy.data.objects.new("Sun", li); link(sun); sun.location = (4, -4, 9); sun.rotation_euler = (math.radians(40), 0, math.radians(30))
cam = bpy.data.objects.new("Camera", bpy.data.cameras.new("Camera")); link(cam); cam.location = (12, -12, 8)
scene.camera = cam

if NEW:
    bpy.context.view_layer.update()
else:
    scene.update()

# ---------------------------------------------------------------- 基准数据
def tri_count(me):
    return sum(len(p.vertices) - 2 for p in me.polygons)

def stats_base():
    """不算修改器、不算实例：每个要导入的网格物体自己的数据。"""
    out = []
    for ob in scene.objects:
        if ob.type != 'MESH' or ob.hide_render:
            continue
        if NEW and any(c.hide_render for c in ob.users_collection):
            continue
        me = ob.data
        mw = ob.matrix_world
        pts = [mul(mw, v.co) for v in me.vertices]
        slots = []
        for i, s in enumerate(ob.material_slots):
            slots.append(s.material.name if s.material else None)
        per = {}
        for p in me.polygons:
            name = slots[p.material_index] if p.material_index < len(slots) else None
            per[str(name)] = per.get(str(name), 0) + len(p.vertices) - 2
        out.append({"name": ob.name, "mesh": me.name, "tris": tri_count(me), "polys": len(me.polygons), "verts": len(me.vertices),
                    "min": [min(p[i] for p in pts) for i in range(3)], "max": [max(p[i] for p in pts) for i in range(3)],
                    "mat_tris": per, "modifiers": [m.type for m in ob.modifiers],
                    "matrix": [list(r) for r in mw], "negative": mw.determinant() < 0})
    return out

def stats_eval():
    """Blender 自己算出来的最终结果：修改器、实例都算上。"""
    tris = 0; lo = [1e30] * 3; hi = [-1e30] * 3; per = {}; names = []
    def acc(me, mw, slots, name):
        nonlocal tris
        names.append(name)
        for v in me.vertices:
            p = mul(mw, v.co)
            for i in range(3):
                lo[i] = min(lo[i], p[i]); hi[i] = max(hi[i], p[i])
        for p in me.polygons:
            n = len(p.vertices) - 2
            tris += n
            mat = slots[p.material_index] if p.material_index < len(slots) else None
            per[str(mat)] = per.get(str(mat), 0) + n
    if NEW:
        dg = bpy.context.evaluated_depsgraph_get()
        for oi in dg.object_instances:
            ob = oi.object
            if ob.type != 'MESH':
                continue
            if not oi.is_instance and (ob.original.hide_render or any(c.hide_render for c in ob.original.users_collection)):
                continue
            me = ob.to_mesh()
            slots = [s.material.name if s.material else None for s in ob.material_slots]
            acc(me, oi.matrix_world.copy(), slots, ob.name + ("@inst" if oi.is_instance else ""))
            ob.to_mesh_clear()
    else:
        def one(ob, mw):
            me = ob.to_mesh(scene, True, 'RENDER')
            slots = [s.material.name if s.material else None for s in ob.material_slots]
            acc(me, mw, slots, ob.name)
            bpy.data.meshes.remove(me)
        for ob in scene.objects:
            if ob.hide_render:
                continue
            if ob.type == 'MESH':
                one(ob, ob.matrix_world.copy())
            if ob.dupli_type == 'GROUP' and ob.dupli_group:
                ob.dupli_list_create(scene)
                for d in ob.dupli_list:
                    if d.object.type == 'MESH':
                        one(d.object, d.matrix.copy())
                ob.dupli_list_clear()
    return {"tris": tris, "min": lo, "max": hi, "mat_tris": per, "names": sorted(names)}

def mat_info():
    out = {}
    for m in bpy.data.materials:
        col = list(m.diffuse_color)[:3]
        out[m.name] = {"color": col}
    return out

truth = {"version": list(V), "tag": tag, "base": stats_base(), "evaluated": stats_eval(), "materials": mat_info(),
         "unit_scale": scene.unit_settings.scale_length}
with open(os.path.join(out_dir, tag + ".json"), "w") as fp:
    json.dump(truth, fp, indent=1)

bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out_dir, tag + ".blend"), compress=False)
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(out_dir, tag + "_z.blend"), compress=True, copy=True)
try: os.remove(tmp)
except Exception: pass
print("SAVED", tag, V)
