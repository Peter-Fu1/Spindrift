"""Offline APK identity parsing; no SDK, private data or network."""
import struct,zipfile
def manifest(data):
    strings = []
    pos = 8
    out = {}

    def l8(p):
        x = data[p]
        return ((x & 127) << 8 | data[p + 1], p + 2) if x & 128 else (x, p + 1)

    def l16(p):
        x = struct.unpack_from('<H', data, p)[0]
        return ((x & 32767) << 16 | struct.unpack_from('<H', data, p + 2)[0], p + 4) if x & 32768 else (x, p + 2)
    while pos < len(data):
        typ, header, size = struct.unpack_from('<HHI', data, pos)
        if not size:
            break
        if typ == 1:
            count, styles, flags, start, styleStart = struct.unpack_from('<IIIII', data, pos + 8)
            for offset in struct.unpack_from('<' + 'I' * count, data, pos + header):
                p = pos + start + offset
                if flags & 256:
                    _, p = l8(p)
                    n, p = l8(p)
                    strings.append(data[p:p + n].decode('utf-8'))
                else:
                    n, p = l16(p)
                    strings.append(data[p:p + 2 * n].decode('utf-16le'))
        if typ == 258:
            ns, name, ast, asz, num = struct.unpack_from('<IIHHH', data, pos + 16)
            tag = strings[name]
            for i in range(num):
                p = pos + 16 + ast + i * asz
                _, name, raw = struct.unpack_from('<III', data, p)
                kind = data[p + 15]
                value = struct.unpack_from('<I', data, p + 16)[0]
                key = strings[name]
                if tag == 'manifest' and key in {'package', 'versionName', 'versionCode'} or (tag == 'application' and key in {'label', 'debuggable', 'testOnly'}) or (tag == 'uses-sdk' and key in {'minSdkVersion','targetSdkVersion'}):
                    out[key] = strings[value] if kind == 3 else value
        pos += size
    return out

def apk_metadata(path):
 with zipfile.ZipFile(path) as z:
  value=manifest(z.read("AndroidManifest.xml"))
  value["debuggable"]=bool(value.get("debuggable",0))
  value["testOnly"]=bool(value.get("testOnly",0))
  return value
