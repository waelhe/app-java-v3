import sys, struct
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

def strtab(d, off, i):
    e = d.find(b"\0", off + i)
    return d[off + i:e].decode("latin1")

def dump(path):
    d = open(path, "rb").read()
    (e_shoff,) = struct.unpack_from("<Q", d, 40)
    (e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from("<HHH", d, 58)
    secs = {}
    for k in range(e_shnum):
        off = e_shoff + k * e_shentsize
        f = struct.unpack_from("<IIQQQQIIQQ", d, off)
        secs[k] = f
    def sname(k):
        f = secs[e_shstrndx]
        return strtab(d, f[4], secs[k][0])
    byname = {sname(k): secs[k] for k in secs}
    dsym = byname[".dynsym"]; dstr = byname[".dynstr"]
    n = dsym[5] // dsym[9] if dsym[9] else 0
    funcs, objs = [], []
    for i in range(n):
        off = dsym[4] + i * dsym[9]
        name, info, other, shndx, value, size = struct.unpack_from("<IBBHQQ", d, off)
        nm = strtab(d, dstr[4], name)
        if not nm:
            continue
        typ = info & 0xF
        if typ == 2:
            funcs.append(nm)
        elif typ == 1:
            objs.append(nm)
    return funcs, objs

import os
for so in [r"libs\lib\arm64-v8a\libb8c6.so", r"libs\lib\arm64-v8a\libfp.so"]:
    p = r"D:\rea-airbnb\%s" % so
    funcs, objs = dump(p)
    print("\n############ %s  funcs=%d objs=%d" % (os.path.basename(p), len(funcs), len(objs)))
    jni = [f for f in funcs if f.startswith("Java_") or f.startswith("JNI_")]
    print("  -- JNI/Java_* (%d) --" % len(jni))
    for f in sorted(jni)[:60]:
        print("     ", f)
    other = [f for f in funcs if not f.startswith("Java_") and not f.startswith("JNI_")]
    print("  -- other exported funcs (%d) --" % len(other))
    for f in sorted(other)[:80]:
        print("     ", f)
    if objs:
        print("  -- exported objects (%d) --" % len(objs))
        for f in sorted(objs)[:30]:
            print("     ", f)
