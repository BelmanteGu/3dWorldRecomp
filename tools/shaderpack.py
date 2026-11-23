#!/usr/bin/env python3
"""Inspect and merge shader packs (runtime/src/gfx/vulkan/shader_pack.h).

usage:
  shaderpack.py info  PACK
  shaderpack.py merge OUT PACK [PACK ...]    union by key; the first pack that has a key wins
  shaderpack.py diff  WANTED PACK            how much of WANTED's shaders PACK has (by key), vertex and pixel

A pack is the translator's output for the shaders a game really used (metadata the renderer reads from the
Latte decompiler, SPIR-V, identity hashes), keyed by content hashes: a pack made on a PC serves a phone.
Packs only merge when they come from the same translator (same header fingerprint).

Format (little endian), the same as shader_pack.cpp:
  "WWSP0001"  u64 fingerprint  u32 uses u32 shaders u32 blobs
  uses:    u64 linkage, u8 unitCount, u8 units[18], u32 exports, u8 streamout
  blobs:   u64 hash, u32 words, u32 zsize, zlib bytes
  shaders: u64 key, u32 size, record (its last 8 bytes: the SPIR-V blob hash)
  u64 checksum of everything before it
"""
import struct
import sys

MAGIC = b"WWSP0001"
M64 = (1 << 64) - 1
UNITS = 18
USE_SIZE = 8 + 1 + UNITS + 4 + 1


def fast_hash(data, seed=0x9E3779B97F4A7C15):
    h = seed
    n = len(data) & ~7
    for (w,) in struct.iter_unpack("<Q", memoryview(data)[:n]):
        h = ((h ^ w) * 0xFF51AFD7ED558CCD) & M64
        h ^= h >> 32
    for b in data[n:]:
        h = ((h ^ b) * 0x100000001B3) & M64
    return h ^ (h >> 29)


def parse(path):
    data = open(path, "rb").read()
    if len(data) < 8 + 8 + 12 + 8 or data[:8] != MAGIC:
        sys.exit("%s: not a shader pack" % path)
    if struct.unpack_from("<Q", data, len(data) - 8)[0] != fast_hash(data[:-8]):
        sys.exit("%s: damaged (checksum)" % path)
    fingerprint, uses_n, shaders_n, blobs_n = struct.unpack_from("<QIII", data, 8)
    at = 8 + 8 + 12
    uses = {}
    for _ in range(uses_n):
        uses[struct.unpack_from("<Q", data, at)[0]] = data[at:at + USE_SIZE]
        at += USE_SIZE
    blobs = {}
    for _ in range(blobs_n):
        h, words, zsize = struct.unpack_from("<QII", data, at)
        blobs[h] = data[at:at + 16 + zsize]
        at += 16 + zsize
    shaders = {}
    for _ in range(shaders_n):
        key, size = struct.unpack_from("<QI", data, at)
        shaders[key] = data[at + 12:at + 12 + size]
        at += 12 + size
    if at != len(data) - 8:
        sys.exit("%s: trailing bytes" % path)
    return fingerprint, uses, blobs, shaders


def info(path):
    fingerprint, uses, blobs, shaders = parse(path)
    raw = sum(struct.unpack_from("<I", b, 8)[0] * 4 for b in blobs.values())
    comp = sum(len(b) - 16 for b in blobs.values())
    vertex = sum(1 for r in shaders.values() if r[0])
    print("%s: %d shaders (%d vertex, %d pixel), %d programs, %d SPIR-V blobs" %
          (path, len(shaders), vertex, len(shaders) - vertex, len(uses), len(blobs)))
    print("  fingerprint %016x; SPIR-V %.1f MB, compressed %.1f MB" % (fingerprint, raw / 1e6, comp / 1e6))


def diff(wanted_path, pack_path):
    _, _, _, wanted = parse(wanted_path)
    _, _, _, have = parse(pack_path)
    for name, flag in (("vertex", 1), ("pixel", 0)):
        keys = [k for k, r in wanted.items() if r[0] == flag]
        found = sum(1 for k in keys if k in have)
        print("%s: %d of %d (%.1f%%)" % (name, found, len(keys), 100.0 * found / max(1, len(keys))))
    print("%s also has %d shaders %s lacks" % (pack_path, sum(1 for k in have if k not in wanted), wanted_path))


def merge(out, paths):
    fingerprint = None
    uses, blobs, shaders = {}, {}, {}
    for path in paths:
        f, u, b, s = parse(path)
        if fingerprint is None:
            fingerprint = f
        elif f != fingerprint:
            sys.exit("%s: made by another translator (fingerprint %016x, not %016x)" % (path, f, fingerprint))
        before = len(shaders)
        for k, v in u.items():
            uses.setdefault(k, v)
        for k, v in b.items():
            blobs.setdefault(k, v)
        for k, v in s.items():
            shaders.setdefault(k, v)
        print("%s: %d shaders (%d new)" % (path, len(s), len(shaders) - before))
    body = bytearray(MAGIC + struct.pack("<QIII", fingerprint, len(uses), len(shaders), len(blobs)))
    for v in uses.values():
        body += v
    for v in blobs.values():
        body += v
    for k, v in shaders.items():
        h = struct.unpack_from("<Q", v, len(v) - 8)[0]
        if h not in blobs:
            sys.exit("shader %016x lacks its SPIR-V blob" % k)
        body += struct.pack("<QI", k, len(v)) + v
    body += struct.pack("<Q", fast_hash(bytes(body)))
    open(out, "wb").write(body)
    print("%s: %d shaders, %d programs, %d blobs, %.1f MB" % (out, len(shaders), len(uses), len(blobs), len(body) / 1e6))


def main(argv):
    if len(argv) == 3 and argv[1] == "info":
        info(argv[2])
    elif len(argv) == 4 and argv[1] == "diff":
        diff(argv[2], argv[3])
    elif len(argv) >= 4 and argv[1] == "merge":
        merge(argv[2], argv[3:])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
