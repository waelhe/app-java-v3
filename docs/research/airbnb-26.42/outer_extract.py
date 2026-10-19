import sys, struct, zlib, zipfile, io
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
p = r"C:\Users\w-co\Desktop\app\Airbnb_26.42.apks"
d = open(p, "rb").read()
OUT = r"C:\Users\w-co\AppData\Local\Temp\opencode\airbnb"

off = 0
idx = 0
while off + 30 <= len(d) and d[off:off+4] == b"PK\x03\x04":
    (ver, flags, meth, tm, dt, crc, cs, us, nl, el) = struct.unpack_from("<HHHHHIIIHH", d, off + 4)
    name = d[off+30:off+30+nl].decode("latin1")
    data = off + 30 + nl + el
    if meth == 8:
        dec = zlib.decompressobj(-15)
        raw = dec.decompress(d[data:])
        consumed = len(d) - data - len(dec.unused_data)
    else:
        raw = d[data:data+cs]
        consumed = cs
    desc = data + consumed
    if d[desc:desc+4] == b"PK\x07\x08":
        dcrc, dcs, dus = struct.unpack_from("<III", d, desc + 4)
        desc += 16
    else:
        dcrc, dcs, dus = struct.unpack_from("<III", d, desc)
        desc += 12
    print("[%d] %-16s meth=%d flags=%#06x consumed=%-10d us=%-10d crc16=%s@%d" % (
        idx, name, meth, flags, consumed, len(raw), hex(dcrc), desc - (16 if d[desc-16:desc-12] == b'PK\x07\x08' else 12)))
    try:
        fn = OUT + "\\" + ("outer_" + name if name else "outer_%d.bin" % idx)
        open(fn, "wb").write(raw)
        z = zipfile.ZipFile(io.BytesIO(raw))
        print("     -> %s  entries=%d  testzip=%s" % (fn, len(z.namelist()), z.testzip()))
    except Exception as e:
        print("     -> not a zip:", e)
    off = desc
    idx += 1
print("next bytes at", off, d[off:off+12])
