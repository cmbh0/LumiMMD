# 极简 .blend 解析器（探索用）：文件头、数据块、SDNA
import struct, sys, gzip, io, re

class Blend:
    def __init__(self, path):
        data = open(path, 'rb').read()
        if data[:2] == b'\x1f\x8b':
            data = gzip.decompress(data)
        self.data = data
        assert data[:7] == b'BLENDER', data[:12]
        if data[7:8] in (b'_', b'-'):
            self.ptr = 4 if data[7:8] == b'_' else 8
            self.le = data[8:9] == b'v'
            self.version = int(data[9:12])
            self.hsize = 12
            self.fmt = 0
        else:
            # BLENDER17-01v0500
            self.hsize = int(data[7:9])
            self.ptr = 8
            self.fmt = int(data[10:12])
            self.le = True
            self.version = int(data[13:17])
        self.e = '<' if self.le else '>'
        self.blocks = []
        self.by_addr = {}
        pos = self.hsize
        e = self.e
        while pos < len(data):
            if self.fmt >= 1:
                code = data[pos:pos + 4]; sdna, = struct.unpack_from(e + 'I', data, pos + 4); old, ln, nr = struct.unpack_from(e + 'Qqq', data, pos + 8); hs = 32
            elif self.ptr == 8:
                code = data[pos:pos + 4]; ln, old, sdna, nr = struct.unpack_from(e + 'iQii', data, pos + 4); hs = 24
            else:
                code = data[pos:pos + 4]; ln, old, sdna, nr = struct.unpack_from(e + 'iIii', data, pos + 4); hs = 20
            b = dict(code=code.rstrip(b'\0').decode('latin1'), len=ln, old=old, sdna=sdna, nr=nr, pos=pos + hs)
            self.blocks.append(b)
            if old: self.by_addr[old] = b
            pos += hs + ln
            if code == b'ENDB': break
        dna = [b for b in self.blocks if b['code'] == 'DNA1'][0]
        self.parse_dna(dna)

    def parse_dna(self, b):
        d = self.data; p = b['pos']; e = self.e; p0 = p
        assert d[p:p + 8] == b'SDNANAME'; p += 8
        n, = struct.unpack_from(e + 'i', d, p); p += 4
        names = []
        for _ in range(n):
            q = d.index(b'\0', p); names.append(d[p:q].decode('latin1')); p = q + 1
        p = p0 + ((p - p0 + 3) & ~3)
        assert d[p:p + 4] == b'TYPE'; p += 4
        n, = struct.unpack_from(e + 'i', d, p); p += 4
        types = []
        for _ in range(n):
            q = d.index(b'\0', p); types.append(d[p:q].decode('latin1')); p = q + 1
        p = p0 + ((p - p0 + 3) & ~3)
        assert d[p:p + 4] == b'TLEN'; p += 4
        tlen = list(struct.unpack_from(e + '%dH' % len(types), d, p)); p += 2 * len(types)
        p = p0 + ((p - p0 + 3) & ~3)
        assert d[p:p + 4] == b'STRC'; p += 4
        n, = struct.unpack_from(e + 'i', d, p); p += 4
        self.structs = []; self.struct_by_name = {}
        for i in range(n):
            t, nf = struct.unpack_from(e + 'HH', d, p); p += 4
            fields = []; off = 0
            for _ in range(nf):
                ft, fn = struct.unpack_from(e + 'HH', d, p); p += 4
                name = names[fn]; tname = types[ft]
                isptr = name.startswith('*') or name.startswith('(*')
                dims = [int(x) for x in re.findall(r'\[(\d+)\]', name)]
                cnt = 1
                for x in dims: cnt *= x
                size = (self.ptr if isptr else tlen[ft]) * cnt
                base = re.sub(r'\[.*', '', name).lstrip('*').strip('()').lstrip('*')
                if name.startswith('(*'): base = re.sub(r'\).*', '', name[2:])
                fields.append(dict(name=base, raw=name, type=tname, off=off, size=size, ptr=isptr, dims=dims))
                off += size
            s = dict(name=types[t], size=tlen[t], fields=fields, index=i)
            self.structs.append(s); self.struct_by_name[types[t]] = s
        self.tlen = dict(zip(types, tlen))

    def show(self, name, only=None):
        s = self.struct_by_name.get(name)
        if not s: print('  (no struct %s)' % name); return
        print('struct %s  size=%d' % (name, s['size']))
        for f in s['fields']:
            if only and not any(o in f['raw'] for o in only): continue
            print('   %4d %-28s %s' % (f['off'], f['type'], f['raw']))

    def of_code(self, code):
        return [b for b in self.blocks if b['code'] == code]

    def field(self, s, name):
        for f in self.struct_by_name[s]['fields']:
            if f['name'] == name: return f
        return None

    def get(self, b, sname, fname, idx=0, fmt=None):
        f = self.field(sname, fname)
        if f is None: return None
        st = self.struct_by_name[sname]
        p = b['pos'] + idx * st['size'] + f['off']
        d = self.data; e = self.e
        if f['ptr']:
            cnt = max(1, f['size'] // self.ptr)
            vals = struct.unpack_from(e + ('%dQ' if self.ptr == 8 else '%dI') % cnt, d, p)
            return vals[0] if cnt == 1 else list(vals)
        t = f['type']
        if t == 'char':
            raw = d[p:p + f['size']]
            if f['size'] == 1: return raw[0]
            return raw.split(b'\0')[0].decode('utf8', 'replace')
        code = {'float': 'f', 'int': 'i', 'short': 'h', 'double': 'd', 'uchar': 'B', 'ushort': 'H', 'int64_t': 'q', 'uint64_t': 'Q', 'int8_t': 'b', 'uint8_t': 'B', 'int16_t': 'h', 'uint16_t': 'H', 'int32_t': 'i', 'uint32_t': 'I', 'long': 'i', 'ulong': 'I'}.get(t)
        if code is None: return ('struct', t, p)
        cnt = f['size'] // struct.calcsize(code)
        vals = struct.unpack_from(e + '%d%s' % (cnt, code), d, p)
        return vals[0] if cnt == 1 else list(vals)

if __name__ == '__main__':
    bl = Blend(sys.argv[1])
    print('ptr', bl.ptr, 'le', bl.le, 'version', bl.version, 'fmt', bl.fmt, 'blocks', len(bl.blocks), 'structs', len(bl.structs))
    from collections import Counter
    c = Counter(b['code'] for b in bl.blocks)
    print(dict(c.most_common(30)))
    for name in sys.argv[2:]:
        if ':' in name:
            n, only = name.split(':'); bl.show(n, only.split(','))
        else: bl.show(name)
