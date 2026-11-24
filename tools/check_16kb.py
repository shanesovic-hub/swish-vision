"""Fails if any 64-bit native library in an .apk/.aab isn't built for 16 KB memory pages.

Google Play requires apps targeting Android 15+ to support 16 KB page sizes. A library is fine when
every LOAD segment is aligned to at least 16 KB (0x4000). 32-bit libraries are exempt.
"""
import struct
import sys
import zipfile


def load_aligns(data):
    if data[:4] != b"\x7fELF" or data[4] != 2:  # only 64-bit ELF
        return None
    end = "<" if data[5] == 1 else ">"
    phoff = struct.unpack_from(end + "Q", data, 0x20)[0]
    phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type = struct.unpack_from(end + "I", data, off)[0]
        if p_type == 1:  # PT_LOAD
            out.append(struct.unpack_from(end + "Q", data, off + 0x30)[0])
    return out


def main(path):
    bad, ok = [], 0
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.endswith(".so") or ("arm64-v8a" not in name and "x86_64" not in name):
                continue
            aligns = load_aligns(z.read(name))
            if aligns is None:
                continue
            if all(a >= 0x4000 for a in aligns):
                ok += 1
            else:
                bad.append(f"{name} (LOAD align {', '.join(hex(a) for a in aligns)})")
    print(f"{path}: {ok} native libraries OK for 16 KB pages")
    for b in bad:
        print(f"::error::Not 16 KB page ready: {b}")
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
