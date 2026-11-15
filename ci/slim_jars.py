#!/usr/bin/env python3
"""CI : reduit les jars de compilation a leurs classes (les jars de mods contiennent des Mo d'assets)."""
import os, sys, zipfile

d = sys.argv[1]
total = 0
for name in sorted(os.listdir(d)):
    if not name.endswith(".jar"):
        continue
    src = os.path.join(d, name)
    tmp = src + ".tmp"
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            n = info.filename
            if n.endswith(".class") or n in ("fabric.mod.json", "META-INF/MANIFEST.MF") or n.endswith(".kotlin_module"):
                zout.writestr(n, zin.read(n))
    os.replace(tmp, src)
    size = os.path.getsize(src)
    total += size
    print(f"{size // 1024:8d} KB  {name}")
print(f"total {total // (1024 * 1024)} MB")
