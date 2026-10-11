"""Clear ACC_ABSTRACT on a class file (test fixtures only): a stand-in that implements a big interface only in part."""
import struct, sys
p = sys.argv[1]
b = bytearray(open(p, 'rb').read())
n = struct.unpack('>H', b[8:10])[0]
i, at = 1, 10
while i < n:
    t = b[at]
    if t == 1: at += 3 + struct.unpack('>H', b[at+1:at+3])[0]
    elif t in (3, 4): at += 5
    elif t in (5, 6): at += 9; i += 1
    elif t in (7, 8, 16, 19, 20): at += 3
    elif t in (9, 10, 11, 12, 17, 18): at += 5
    elif t == 15: at += 4
    else: raise SystemExit('tag %d' % t)
    i += 1
flags = struct.unpack('>H', b[at:at+2])[0] & ~0x0400
b[at:at+2] = struct.pack('>H', flags)
open(p, 'wb').write(b)
