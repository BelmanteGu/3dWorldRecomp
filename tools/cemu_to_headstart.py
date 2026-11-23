#!/usr/bin/env python3
"""Turn a Cemu shader cache into a head start this runtime can translate (tools/cemu_cache.py reads the cache).

usage: cemu_to_headstart.py SHADERS.bin PIPELINES.bin OUT [--limit N]

Cemu records every shader with the registers it was first used with, and every pipeline with the vertex and pixel
shader it joins and the registers of that draw. Each pipeline becomes one "linked shader" record (type 6) here: the
pixel shader, the vertex shader it was drawn with, that vertex shader's fetch program and the registers. Running
the game with `--warm-shaders` and WWHD_HEADSTART=OUT then translates each one through the runtime's own translate(),
which fills the shader pack (docs/sm3dw-shader-pack.md) without anyone playing.

Registers the cache does not keep (ALU constants) are not read by the translation. The program addresses in the
registers are Cemu's: the runtime replaces them with its own when it loads a record.
"""
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cemu_cache  # noqa: E402

TYPE_LINKED = 6


def linked_record(pixel, fetch, vertex, regs):
    return (struct.pack("<5I", 0, len(pixel), len(fetch), len(regs), len(vertex)) + pixel + fetch + vertex +
            b"".join(struct.pack("<II", r, v) for r, v in sorted(regs.items())))


def convert(shaders_path, pipelines_path, out_path, limit=None):
    shaders = cemu_cache.read_shaders(shaders_path)
    by = {(r["type"], r["base"], r["aux"]): r for r in shaders}
    seen, written, skipped = set(), 0, {"no shader": 0, "geometry": 0, "odd size": 0, "duplicate": 0}
    with open(out_path, "wb") as out:
        for p in cemu_cache.read_pipelines(pipelines_path):
            if p["gs"]:
                skipped["geometry"] += 1
                continue
            vs = by.get((cemu_cache.TYPE_VERTEX, *p["vs"])) if p["vs"] else None
            ps = by.get((cemu_cache.TYPE_PIXEL, *p["ps"])) if p["ps"] else None
            if not vs or not ps:
                skipped["no shader"] += 1
                continue
            fetch, vertex = vs["programs"]
            pixel = ps["programs"][0]
            if len(pixel) % 8 or len(vertex) % 8 or len(fetch) % 8 or not fetch:
                skipped["odd size"] += 1
                continue
            raw = linked_record(pixel, fetch, vertex, p["regs"])
            digest = zlib.crc32(raw) | len(raw) << 32
            if digest in seen:
                skipped["duplicate"] += 1
                continue
            seen.add(digest)
            comp = zlib.compress(raw, 6)
            out.write(struct.pack("<III", TYPE_LINKED, len(raw), len(comp)) + comp)
            written += 1
            if limit and written >= limit:
                break
    print("%s: %d linked shader records (skipped %s)" % (out_path, written, ", ".join("%d %s" % (n, k) for k, n in skipped.items() if n)))
    return written


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    limit = int(argv[argv.index("--limit") + 1]) if "--limit" in argv else None
    if limit is not None:
        args.remove(str(limit))
    if len(args) != 3:
        sys.exit(__doc__)
    convert(args[0], args[1], args[2], limit)


if __name__ == "__main__":
    main(sys.argv)
