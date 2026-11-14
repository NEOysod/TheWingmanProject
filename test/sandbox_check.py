"""Every class a jar's classes refer to, checked against Starsector's script sandbox (ScriptClassLoader.loadClass)."""
import sys, zipfile, re, struct
IO_OK = {"java.io.BufferedInputStream","java.io.BufferedReader","java.io.FilterInputStream","java.io.InputStreamReader","java.io.Reader",
 "java.io.Serializable","java.io.InvalidClassException","java.io.ObjectStreamException","java.io.InputStream","java.io.IOException",
 "java.io.PrintStream","java.io.PrintWriter","java.io.ByteArrayInputStream","java.io.FilterOutputStream","java.io.OutputStream",
 "java.io.Closeable","java.io.Flushable","java.nio.ByteBuffer","java.nio.CharBuffer","java.nio.IntBuffer","java.io.StringReader","java.io.FileReader","java.lang.Class"}
REF_OK = {"java.lang.reflect.AnnotatedElement","java.lang.reflect.InvocationTargetException","java.lang.reflect.Type","java.lang.reflect.GenericDeclaration"}
def banned(n):
    if (n.startswith("java.io") or n.startswith("java.nio.file.File")) and n not in IO_OK: return True
    if n.startswith("javax.script") or n.startswith("java.util.prefs"): return True
    if n.startswith("java.lang.reflect") and n not in REF_OK: return True
    return n == "sun.reflect.misc.MethodUtil"
def refs(b):
    # constant pool: class entries and every descriptor/signature string
    n = struct.unpack(">H", b[8:10])[0]; i = 10; utf = {}; cls = []; k = 1
    while k < n:
        t = b[i]
        if t == 1:
            l = struct.unpack(">H", b[i+1:i+3])[0]; utf[k] = b[i+3:i+3+l].decode("utf-8", "replace"); i += 3 + l
        elif t == 7: cls.append(struct.unpack(">H", b[i+1:i+3])[0]); i += 3
        elif t in (8, 16, 19, 20): i += 3
        elif t == 15: i += 4
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): i += 5
        elif t in (5, 6): i += 9; k += 1
        else: raise ValueError("tag %d" % t)
        k += 1
    out = set()
    for c in cls:
        s = utf.get(c, "")
        out.add(s.lstrip("[").strip("L;").replace("/", "."))
    for s in utf.values():
        for m in re.finditer(r"L([\w/$]+);", s): out.add(m.group(1).replace("/", "."))
    return out
bad = 0
for jar in sys.argv[1:]:
    z = zipfile.ZipFile(jar)
    for e in z.namelist():
        if not e.endswith(".class"): continue
        hits = sorted(r for r in refs(z.read(e)) if banned(r))
        if hits: bad += 1; print("BLOCKED  %s  %s -> %s" % (jar.split("/")[-1], e, ", ".join(hits)))
print("SANDBOX OK" if bad == 0 else "%d classes the game would refuse" % bad)
