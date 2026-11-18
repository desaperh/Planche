#!/usr/bin/env python3
"""Fabrique des CBZ piégés et vérifie le lecteur ZIP de Planche (JVM)."""
import hashlib
import io
import os
import random
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = tempfile.mkdtemp(prefix="planche-tests-")
CLASSES = os.path.join(WORK, "classes")
failures = 0


def sha(b):
    return hashlib.sha256(b).hexdigest()


def compile_java():
    srcs = [os.path.join(ROOT, "src/fr/desaperh/planche", f) for f in ("ZipReader.java", "Pages.java", "NaturalOrder.java")]
    srcs.append(os.path.join(ROOT, "test/ZipReaderCli.java"))
    subprocess.run(["javac", "-proc:none", "-encoding", "UTF-8", "-d", CLASSES] + srcs, check=True)


def run(path, limit=None):
    cmd = ["java", "-cp", CLASSES, "ZipReaderCli", path] + ([str(limit)] if limit else [])
    out = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    return [line.split("\t") for line in out.splitlines()]


def check(name, cond, detail=""):
    global failures
    print(("OK   " if cond else "FAIL ") + name + (("  — " + detail) if detail and not cond else ""))
    if not cond:
        failures += 1


def fake_jpeg(seed, size=2000):
    rnd = random.Random(seed)
    return b"\xff\xd8\xff\xe0" + bytes(rnd.getrandbits(8) for _ in range(size)) + b"\xff\xd9"


def pages(rows):
    return [(r[1], r[2]) for r in rows if r[0] == "PAGE"]


compile_java()

# 1. Mélange stocké/deflate, sous-dossiers, fichiers parasites, tri naturel
p = os.path.join(WORK, "mix.cbz")
files = {
    "Tome 1/page10.jpg": fake_jpeg(10),
    "Tome 1/page2.JPG": fake_jpeg(2),
    "Tome 1/page1.png": fake_jpeg(1),
    "Tome 1/page001.webp": fake_jpeg(11),
    "Tome 2/page1.jpg": fake_jpeg(21),
    "ComicInfo.xml": b"<ComicInfo/>",
    "__MACOSX/Tome 1/._page1.png": b"junk",
    "Tome 1/._page3.jpg": b"junk",
    "Tome 1/.DS_Store": b"junk",
    "Tome 1/": b"",
}
with zipfile.ZipFile(p, "w") as z:
    for i, (n, d) in enumerate(files.items()):
        if n.endswith("/"):
            z.writestr(zipfile.ZipInfo(n), b"")
        else:
            z.writestr(n, d, compress_type=zipfile.ZIP_DEFLATED if i % 2 else zipfile.ZIP_STORED)
got = pages(run(p))
order = [n for n, _ in got]
check("tri naturel + filtrage", order == ["Tome 1/page1.png", "Tome 1/page001.webp", "Tome 1/page2.JPG",
                                          "Tome 1/page10.jpg", "Tome 2/page1.jpg"], str(order))
check("contenu identique", all(files[n] == files[n] and sha(files[n]) == h for n, h in got))

# 2. Gros deflate (5 Mo compressible)
p = os.path.join(WORK, "big.cbz")
big = (b"BD" * 1000 + fake_jpeg(5, 3000)) * 900
with zipfile.ZipFile(p, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("big.jpg", big)
got = pages(run(p))
check("deflate 5 Mo", got == [("big.jpg", sha(big))])

# 3. ZIP64 : plus de 65535 entrées
p = os.path.join(WORK, "zip64.cbz")
with zipfile.ZipFile(p, "w", zipfile.ZIP_STORED) as z:
    for i in range(70000):
        z.writestr(f"p{i}.jpg", str(i).encode())
rows = run(p, limit=3)
head = rows[0]
check("ZIP64 : 70000 entrées", head[1] == "70000" and head[3] == "70000", str(head))
check("ZIP64 : ordre naturel", [n for n, _ in pages(rows)] == ["p0.jpg", "p1.jpg", "p2.jpg"])
check("ZIP64 : lecture", pages(rows)[2][1] == sha(b"2"))

# 4. Noms CP437 sans drapeau UTF-8, et UTF-8 sans drapeau
p = os.path.join(WORK, "names.cbz")
with zipfile.ZipFile(p, "w") as z:
    z.writestr("XX.jpg", fake_jpeg(4))
    z.writestr("été.jpg", fake_jpeg(5))
raw = bytearray(open(p, "rb").read())
raw = raw.replace(b"XX.jpg", b"\x82\x8a.jpg")  # « éè » en CP437
# retire le drapeau UTF-8 (bit 11) de toutes les entrées
for sig, off in ((b"PK\x03\x04", 6), (b"PK\x01\x02", 8)):
    i = raw.find(sig)
    while i >= 0:
        flags = int.from_bytes(raw[i + off:i + off + 2], "little") & ~0x800
        raw[i + off:i + off + 2] = flags.to_bytes(2, "little")
        i = raw.find(sig, i + 4)
open(p, "wb").write(bytes(raw))
names = sorted(n for n, _ in pages(run(p)))
check("noms CP437 et UTF-8 sans drapeau", names == sorted(["éè.jpg", "été.jpg"]), str(names))

# 5. Données préfixées (offsets non corrigés)
src = os.path.join(WORK, "mix.cbz")
p = os.path.join(WORK, "prefixed.cbz")
open(p, "wb").write(b"\x00" * 1234 + open(src, "rb").read())
check("données préfixées", len(pages(run(p))) == 5)

# 6. Descripteurs de données (archive écrite en flux)
class Unseekable(io.RawIOBase):
    def __init__(self):
        self.buf = bytearray()
    def writable(self):
        return True
    def write(self, b):
        self.buf += b
        return len(b)
    def seekable(self):
        return False
u = Unseekable()
with zipfile.ZipFile(u, "w", zipfile.ZIP_DEFLATED) as z:
    for i in range(3):
        with z.open(f"s{i}.jpg", "w") as f:
            f.write(fake_jpeg(30 + i))
p = os.path.join(WORK, "stream.cbz")
open(p, "wb").write(bytes(u.buf))
got = pages(run(p))
check("descripteurs de données", [h for _, h in got] == [sha(fake_jpeg(30 + i)) for i in range(3)])

# 7. Compression non prise en charge (bzip2, lzma)
p = os.path.join(WORK, "bz.cbz")
with zipfile.ZipFile(p, "w") as z:
    z.writestr("a.jpg", fake_jpeg(1), compress_type=zipfile.ZIP_BZIP2)
    z.writestr("b.jpg", fake_jpeg(2), compress_type=zipfile.ZIP_LZMA)
rows = [r for r in run(p) if r[0] == "PAGEERR"]
check("bzip2/lzma : message clair", len(rows) == 2 and "BZIP2" in rows[0][2] and "LZMA" in rows[1][2], str(rows))

# 8. Faux CBZ (RAR renommé, PDF, n'importe quoi)
for label, data, expect in (("RAR renommé", b"Rar!\x1a\x07\x01\x00" + b"x" * 100, "RAR"),
                            ("PDF renommé", b"%PDF-1.7" + b"x" * 100, "PDF"),
                            ("fichier quelconque", os.urandom(500), "pas une archive")):
    p = os.path.join(WORK, "fake.cbz")
    open(p, "wb").write(data)
    r = run(p)[0]
    check(label, r[0] == "UNSUPPORTED" and expect in r[1], str(r))

# 9. Archive tronquée
p = os.path.join(WORK, "trunc.cbz")
open(p, "wb").write(open(os.path.join(WORK, "mix.cbz"), "rb").read()[:-40])
r = run(p)[0]
check("archive tronquée : erreur propre", r[0] in ("UNSUPPORTED", "ERROR"), str(r))

# 10. Archive vide
p = os.path.join(WORK, "empty.cbz")
with zipfile.ZipFile(p, "w"):
    pass
r = run(p)[0]
check("archive vide", r[0] == "ENTRIES" and r[1] == "0", str(r))

print()
print("Échecs :", failures)
sys.exit(1 if failures else 0)
