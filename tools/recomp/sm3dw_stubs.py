#!/usr/bin/env python3
"""Stubs for the Wind Waker HD addresses the shared runtime still names, for the sm3dw target.

usage: sm3dw_stubs.py GENDIR [LINKLOG]

The runtime refers to functions of the Zelda executable (f_XXXXXXXX, f_XXXXXXXX_orig) from its hooks,
true60, interp and the like. In a Super Mario 3D World build those addresses are not game code: this
writes GENDIR/code_zstubs.c (the CMake glob is code_*.c) defining every such name the recompiled game does not, as a function that
halts via ppc_unimplemented. The names come from the linker's "Undefined symbols" list in LINKLOG
(default stdin); those features are meant to be switched off in the sm3dw target, and each stub goes
away as its caller does.
"""
import os
import re
import sys


def main(gendir, log):
    text = open(log).read() if log else sys.stdin.read()
    # ld64 (macOS) lists "_f_X", referenced from:; GNU ld writes undefined reference to `f_X' (quotes vary
    # with the locale; run it with LC_ALL=C), lld undefined symbol: f_X. GNU ld also names the callers
    # ("in function `hook_X'"), which are defined, so only the undefined names count.
    sym = r'((?:f|hook|site)_[0-9A-F]{8}(?:_orig)?)'
    names = sorted(set(re.findall(r'"_%s", referenced from' % sym, text)
                       + re.findall(r'undefined reference to [`"\']%s' % sym, text)
                       + re.findall(r'undefined symbol: %s' % sym, text)))
    path = os.path.join(gendir, "code_zstubs.c")
    with open(path, "w") as f:
        f.write('#include "funcs.h"\n\n/* written by tools/recomp/sm3dw_stubs.py: not game code */\n')
        for n in names:
            addr = re.search(r"[0-9A-F]{8}", n).group(0)
            if n.startswith("f_"):
                f.write("void %s(Cpu* __restrict c) { ppc_unimplemented(c, 0x%su, 0); }\n" % (n, addr))
            else:  # hook_X / site_X: no-ops (the feature that calls them is off)
                f.write("void %s(Cpu* c) { (void)c; }\n" % n)
    print("wrote %s: %d stubs" % (path, len(names)))


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else None)
