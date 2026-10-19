import sys, struct, os, zipfile, glob
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
OUT = r"D:\rea-airbnb\libs"
os.makedirs(OUT, exist_ok=True)
z = zipfile.ZipFile(r"D:\rea-airbnb\outer_split_config.arm64_v8a.apk")
for i in z.infolist():
    if i.filename.startswith("lib/"):
        z.extract(i, OUT)
        print("extracted", i.filename)

def elf_info(path):
    d = open(path, "rb").read()
    (magic, cls, data, ver, osabi) = d[0:4], d[4], d[5], d[6], d[7]
    (e_type, e_machine) = struct.unpack_from("<HH", d, 16)
    (e_entry,) = struct.unpack_from("<Q", d, 24)
    (e_shoff,) = struct.unpack_from("<Q", d, 40)
    (e_shentsize, e_shnum, e_shstrndx) = struct.unpack_from("<HHH", d, 58)
    secs = []
    for k in range(e_shnum):
        off = e_shoff + k*e_shentsize
        (name, typ, flags, addr, offset, size, link, info, align, entsize) = struct.unpack_from("<IIQQQQIIQQ", d, off)
        secs.append((name, typ, offset, size, entsize, flags))
    shstr = secs[e_shstrndx]
    def sname(nameoff):
        end = d.find(b"\0", shstr[2]+nameoff)
        return d[shstr[2]+nameoff:end].decode("latin1")
    named = {}
    for s in secs:
        named[sname(s[0])] = s
    build = ""
    n = named.get(".note.gnu.build-id")
    if n:
        noff = n[2]
        namesz, descsz, ntype = struct.unpack_from("<III", d, noff)
        build = d[noff+12:noff+12+descsz].hex()
    comment = ""
    c = named.get(".comment")
    if c:
        comment = d[c[2]:c[2]+c[3]].replace(b"\0", b" ").decode("latin1").strip()
    # dynamic
    needed, soname = [], ""
    dyn = named.get(".dynamic")
    dynstr_off = named.get(".dynstr")[2] if ".dynstr" in named else None
    if dyn:
        off = dyn[2]
        ents = 16
        while off < len(d):
            tag, val = struct.unpack_from("<qQ", d, off)
            if tag == 0:
                break
            if tag == 1 and dynstr_off is not None:  # DT_NEEDED
                e = d.find(b"\0", dynstr_off+val)
                needed.append(d[dynstr_off+val:e].decode("latin1"))
            if tag == 14 and dynstr_off is not None:  # DT_SONAME
                e = d.find(b"\0", dynstr_off+val)
                soname = d[dynstr_off+val:e].decode("latin1")
            off += ents
    # dynsym count
    nsym = 0
    dsym = named.get(".dynsym")
    if dsym and dsym[4]:
        nsym = dsym[3]//dsym[4]
    return dict(type=e_type, machine=e_machine, build=build, comment=comment,
                needed=needed, soname=soname, nsym=nsym,
                has_symtab=".symtab" in named, has_debug=".debug_info" in named)

MACH = {0xB7: "AArch64", 0x28: "ARM", 0x3E: "x86-64"}
for so in sorted(glob.glob(OUT + r"\lib\arm64-v8a\*.so")):
    info = elf_info(so)
    print("\n== %s  (%.0f KB)" % (os.path.basename(so), os.path.getsize(so)/1024))
    print("   machine=%s type=%d nsym=%d symtab=%s debug=%s" % (
        MACH.get(info["machine"], hex(info["machine"])), info["type"], info["nsym"], info["has_symtab"], info["has_debug"]))
    if info["comment"]:
        print("   comment:", info["comment"][:120])
    if info["needed"]:
        print("   needed:", ", ".join(info["needed"]))
    if info["build"]:
        print("   build-id:", info["build"])
