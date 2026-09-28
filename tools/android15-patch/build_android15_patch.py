#!/usr/bin/env python3
"""重建 APK：替换 classes.dex，并对齐所有条目（等价 zipalign -p 4K）。"""
import struct, zlib, sys, os

PAGE = 4096

def parse_zip(path):
    data = open(path, 'rb').read()
    eocd = data.rfind(b'PK\x05\x06')
    n, cd_size, cd_off = struct.unpack('<HII', data[eocd+10:eocd+20])
    entries, pos = [], cd_off
    for _ in range(n):
        assert data[pos:pos+4] == b'PK\x01\x02'
        (vmade, vneed, flags, method, mtime, mdate, crc, csize, usize,
         nlen, elen, clen, disk, iattr, eattr, lho) = struct.unpack('<HHHHHHIIIHHHHHII', data[pos+4:pos+46])
        name = data[pos+46:pos+46+nlen].decode('utf-8')
        lh = data[lho:lho+30]
        lnlen, lelen = struct.unpack('<HH', lh[26:30])
        dstart = lho + 30 + lnlen + lelen
        entries.append(dict(name=name, vmade=vmade, vneed=vneed, flags=flags,
                            method=method, mtime=mtime, mdate=mdate, crc=crc,
                            csize=csize, usize=usize, iattr=iattr, eattr=eattr,
                            blob=data[dstart:dstart+csize]))
        pos += 46 + nlen + elen + clen
    return entries

def raw_deflate(data, level=9):
    """zip 的 DEFLATE 条目使用 raw deflate（无 zlib 头/校验）。"""
    co = zlib.compressobj(level, zlib.DEFLATED, -15)
    return co.compress(data) + co.flush()

def extra_for(offset, name_len, align):
    """返回使 data offset 满足 align 的 extra field（长度 0 或 >=4）。"""
    base = offset + 30 + name_len
    rem = base % align
    if rem == 0:
        return b''
    p = align - rem
    if p < 4:
        p += align
    return struct.pack('<HH', 0xd935, p - 4) + b'\x00' * (p - 4)

def build(entries, out):
    buf = bytearray()
    central = bytearray()
    for e in entries:
        name_b = e['name'].encode('utf-8')
        if 'newraw' in e:                         # 被替换的条目（classes.dex）
            blob = raw_deflate(e['newraw'])
            crc = zlib.crc32(e['newraw']) & 0xffffffff
            csize, usize, method = len(blob), len(e['newraw']), 8
        else:                                     # 其余条目：原始压缩字节原样复用
            blob, crc, csize, usize, method = e['blob'], e['crc'], e['csize'], e['usize'], e['method']
        e_method = method
        align = PAGE if e['name'].startswith('lib/') and e['name'].endswith('.so') else 4
        extra = extra_for(len(buf), len(name_b), align)
        lho = len(buf)
        buf += struct.pack('<4sHHHHHIIIHH', b'PK\x03\x04', e['vneed'], e['flags'],
                           e_method, e['mtime'], e['mdate'], crc, csize, usize,
                           len(name_b), len(extra))
        buf += name_b + extra + blob
        central += struct.pack('<4sHHHHHHIIIHHHHHII', b'PK\x01\x02', e['vmade'], e['vneed'],
                               e['flags'], e_method, e['mtime'], e['mdate'], crc, csize,
                               usize, len(name_b), len(extra), 0, 0, e['iattr'],
                               e['eattr'], lho)
        central += name_b + extra
    cd_off = len(buf)
    buf += central
    buf += struct.pack('<4sHHHHIIH', b'PK\x05\x06', 0, 0, len(entries), len(entries),
                       len(central), cd_off, 0)
    open(out, 'wb').write(buf)

def main():
    src, newdex, out = sys.argv[1], sys.argv[2], sys.argv[3]
    entries = parse_zip(src)
    newdex_data = open(newdex, 'rb').read()
    hit = 0
    for e in entries:
        if e['name'] == 'classes.dex':
            e['newraw'] = newdex_data
            hit += 1
    assert hit == 1, hit
    build(entries, out)
    print("wrote %s (%d bytes, was %d)" % (out, os.path.getsize(out), os.path.getsize(src)))

main()
