import bpy, sys, math
from mathutils import Vector
argv = sys.argv[sys.argv.index("--") + 1:]
out = argv[0]
sc = bpy.context.scene
sc.render.engine = 'CYCLES'
sc.cycles.samples = 24
try: sc.cycles.use_denoising = False
except Exception: pass
sc.render.resolution_x = 960; sc.render.resolution_y = 540; sc.render.resolution_percentage = 100
cam = sc.camera
cam.location = (9.0, -15.0, 9.0)
d = Vector((0, 0, 1.0)) - Vector(cam.location)
cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
cam.data.lens = 35
# 世界亮一点
w = bpy.data.worlds.new("W"); sc.world = w
w.use_nodes = True
w.node_tree.nodes["Background"].inputs[0].default_value = (0.6, 0.65, 0.7, 1)
sc.render.filepath = out
sc.render.image_settings.file_format = 'PNG'
bpy.ops.render.render(write_still=True)
