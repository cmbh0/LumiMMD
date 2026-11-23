# 把导入器的输出和 Blender 自己给的基准数据对比
import json, subprocess, sys, os
JAVA = "java -cp '/home/user/LumiMMD/desktop/build/install/desktop/lib/*' com.lumi.mmd.desktop.ModelInfoMainKt"
S = 12.5
def run(path):
    env = dict(os.environ); env['TRACE'] = '1'
    out = subprocess.run(JAVA + " '" + path + "'", shell=True, capture_output=True, text=True, env=env)
    line = [l for l in out.stdout.splitlines() if l.startswith('{')]
    if not line: print(out.stdout[-800:], out.stderr[-1500:]); return None
    r = json.loads(line[-1])
    if 'error' in r: print('ERROR', r['error']); print(out.stderr[-1500:])
    return r
def conv(p): return [p[0] * S, p[2] * S, p[1] * S]     # Blender (x, y, z) → MMD (x, z, y)
ok_all = True
for tag in sys.argv[1:]:
    truth = json.load(open('/opt/blender/out/%s.json' % tag.replace('_z', '')))
    r = run('/opt/blender/out/%s.blend' % tag)
    if r is None or 'error' in r: ok_all = False; continue
    ev = truth['evaluated']; base = truth['base']
    lo = conv(ev['min']); hi = conv(ev['max'])
    exp_tris = ev['tris']
    # 细分修改器读不了：基准里把它算成了细分后的面数，换回原始面数
    sub = [o for o in base if 'SUBSURF' in o['modifiers']]
    names = {}
    msg = []
    got_tris = r['triangles']
    base_tris = sum(o['tris'] for o in base)
    msg.append('tris got=%d eval=%d base=%d' % (got_tris, exp_tris, base_tris))
    dmin = max(abs(a - b) for a, b in zip(r['min'], lo)); dmax = max(abs(a - b) for a, b in zip(r['max'], hi))
    msg.append('bbox diff min=%.4f max=%.4f' % (dmin, dmax))
    mats = {m['name']: m for m in r['materials']}
    msg.append('materials=%d degenerate=%d ms=%s' % (len(mats), r['degenerate'], r['ms']))
    bad = [m['name'] for m in r['materials'] if m['normal_agree'] < 0.999]
    if bad: msg.append('NORMAL DISAGREE: %s' % [(m['name'], round(m['normal_agree'], 3)) for m in r['materials'] if m['normal_agree'] < 0.999])
    print('== %-8s %s | %s' % (tag, r['comment'], ' | '.join(msg)))
    if '-v' in os.environ.get('V', ''):
        for m in r['materials']: print('     %-28s tris=%-4d diffuse=%s tex=%s alpha=%s flags=%s em=%s vol=%.2f' % (m['name'], m['tris'], [round(x, 2) for x in m['diffuse']], m['texture'], m['alpha_mode'], m['flags'], [round(x, 2) for x in m['emissive']], m['volume']))
        for w in r['warnings']: print('     ! ' + w)
