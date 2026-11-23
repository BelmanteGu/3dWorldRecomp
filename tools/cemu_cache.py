#!/usr/bin/env python3
"""Reader for Cemu's shader caches (shaderCache/transferable/<title id>_shaders.bin and _vkpipeline.bin).

usage:
  cemu_cache.py info  SHADERS.bin [PIPELINES.bin]
  cemu_cache.py check SHADERS.bin [PIPELINES.bin]      decode every record; report any that does not

What a record is (Cemu src/Cafe/HW/Latte/Core/LatteShaderCache.cpp, Common/RegisterSerializer.cpp,
Common/ShaderSerializer.cpp, Cemu/FileCache/FileCache.cpp; MPL-2.0):
  container   FileCache v3: header u32 magic 0x8371b696, u32 extra version, u64 data offset, u64 file table offset,
              u32 file table size (little endian); the file table is 32-byte entries
              {u64 name1, u64 name2, u64 offset, u32 size, u8 flags, 3 reserved}; flag 1 = [u32 BE size][zlib]
  shader      u8 (version | type << 4), u64 base hash, u64 aux hash, u8 uses geometry shader (all big endian), the
              register state, then the programs (vertex: fetch shader, vertex shader; pixel: pixel shader)
  pipeline    u8 version 1, u8 mask (1 vs, 2 gs, 4 ps), {u64 base, u64 aux} per stage in the mask, register state
              (which pairs a vertex and a pixel shader, with the registers of the draw they were first used in)
  registers   u8 version 1, u16 size, zstd (dictionary) of a zero-run-length coding of 1854 big-endian words,
              which are the register ranges of REGISTER_RANGES in order
  program     u8 version 1, u32 size, u32 compressed size, zstd (dictionary) of the program bytes

The two zstd dictionaries are Cemu's (tools/cemu/*.zst, cut from the arrays in its sources). Needs Python 3.14
(compression.zstd). Nothing here is the game's: a cache holds the game's shader code, so keep it to yourself.
"""
import os
import struct
import sys
import zlib
from collections import Counter

from compression import zstd

HERE = os.path.dirname(os.path.abspath(__file__))
MAGIC_V3 = 0x8371b696
HEADER_RESERVED = 128
FILETABLE_NAME = (0xEFEFEFEFEFEFEFEF, 0xFEFEFEFEFEFEFEFE)
TYPE_VERTEX, TYPE_GEOMETRY, TYPE_PIXEL = 0, 1, 2

# the register ranges of Cemu's g_gpuRegSerializerMapping_v1: (first register, count)
REGISTER_RANGES = [
    (0x2232, 0x2), (0x2235, 0x1), (0x223A, 0x1), (0x2256, 0x1), (0x22C8, 0x1), (0x2300, 0x6), (0x2310, 0xC),
    (0x2363, 0x1), (0x2404, 0x2), (0x2542, 0x1), (0x25C5, 0x1), (0x260C, 0x1), (0x2900, 0x48), (0x2980, 0x48),
    (0x2A00, 0x48), (0xA000, 0x2), (0xA003, 0x3), (0xA00A, 0x4), (0xA010, 0x38), (0xA050, 0x34), (0xA08C, 0x1),
    (0xA08E, 0x4), (0xA094, 0x40), (0xA0D5, 0x1), (0xA0E0, 0x20), (0xA100, 0x9), (0xA10C, 0x3), (0xA10F, 0x60),
    (0xA185, 0xA), (0xA191, 0x27), (0xA1E0, 0x9), (0xA200, 0x1), (0xA202, 0x7), (0xA210, 0x29), (0xA250, 0x34),
    (0xA284, 0xC), (0xA290, 0x1), (0xA292, 0x2), (0xA29B, 0x1), (0xA2A1, 0x1), (0xA2A5, 0x1), (0xA2A8, 0x2),
    (0xA2AC, 0x3), (0xA2B4, 0x3), (0xA2B8, 0x3), (0xA2BC, 0x3), (0xA2C0, 0x3), (0xA2C8, 0x1), (0xA2CA, 0x1),
    (0xA2CC, 0x1), (0xA2CE, 0x1), (0xA300, 0x9), (0xA30C, 0x1), (0xA312, 0x1), (0xA316, 0x2), (0xA343, 0x2),
    (0xA349, 0x3), (0xA34C, 0x2), (0xA351, 0x1), (0xA37E, 0x6), (0xE000, 0x70), (0xE380, 0x70), (0xE460, 0x70),
    (0xE7E0, 0x70), (0xE8B9, 0x7), (0xE8C0, 0x70), (0xE930, 0x70), (0xECB0, 0x70), (0xED89, 0x7), (0xF000, 0x36),
    (0xF036, 0x36), (0xF06C, 0x36), (0xF880, 0x60),
]
NUM_REGS = sum(count for _, count in REGISTER_RANGES)
assert NUM_REGS == 1854


def _dictionary(name):
    return zstd.ZstdDict(zstd.decompress(open(os.path.join(HERE, "cemu", name), "rb").read()))


_REG_DICT = _SHADER_DICT = None


class Stream:
    """Big-endian reader over one record, like Cemu's MemStreamReader."""

    def __init__(self, data):
        self.data, self.at = data, 0

    def take(self, n):
        if n < 0 or self.at + n > len(self.data):
            raise ValueError("record is truncated")
        out = self.data[self.at:self.at + n]
        self.at += n
        return out

    def u8(self): return self.take(1)[0]
    def u16(self): return struct.unpack(">H", self.take(2))[0]
    def u32(self): return struct.unpack(">I", self.take(4))[0]
    def u64(self): return struct.unpack(">Q", self.take(8))[0]
    def end(self): return self.at == len(self.data)


def read_registers(s):
    """-> {register address: value} for every register in the compacted state that is not zero."""
    global _REG_DICT
    _REG_DICT = _REG_DICT or _dictionary("regdict.zst")
    if s.u8() != 1:
        raise ValueError("unknown register state version")
    rle = zstd.decompress(s.take(s.u16()), zstd_dict=_REG_DICT)
    words, at = [], 0
    while len(words) < NUM_REGS:
        pattern = rle[at]
        at += 1
        count = pattern & 0x7F
        if not count or len(words) + count > NUM_REGS:
            raise ValueError("bad register run")
        if pattern & 0x80:
            words.extend([0] * count)
        else:
            words.extend(struct.unpack(">%dI" % count, rle[at:at + 4 * count]))
            at += 4 * count
    if at != len(rle):
        raise ValueError("register state has trailing bytes")
    regs, i = {}, 0
    for first, count in REGISTER_RANGES:
        for k in range(count):
            if words[i]:
                regs[first + k] = words[i]
            i += 1
    return regs


def read_program(s):
    global _SHADER_DICT
    _SHADER_DICT = _SHADER_DICT or _dictionary("shaderdict.zst")
    if s.u8() != 1:
        raise ValueError("unknown program version")
    size, csize = s.u32(), s.u32()
    if not 0 < size < 1 << 27 or not 0 < csize < 1 << 27:
        raise ValueError("bad program size")
    program = zstd.decompress(s.take(csize), zstd_dict=_SHADER_DICT)
    if len(program) != size:
        raise ValueError("program size mismatch")
    return program


def read_container(path):
    """-> [(name1, name2, bytes)] for every file in a Cemu FileCache."""
    data = open(path, "rb").read()
    magic, extra, data_offset, table_offset, table_size = struct.unpack_from("<IIQQI", data, 0)
    if magic != MAGIC_V3:
        raise ValueError("%s: not a FileCache v3 (magic %08x)" % (path, magic))
    if table_size % 32:
        raise ValueError("%s: bad file table size" % path)
    files = []
    base = data_offset + table_offset
    for i in range(table_size // 32):
        name1, name2, offset, size, flags = struct.unpack_from("<QQQIB", data, base + i * 32)
        if (name1, name2) == (0, 0) or (name1, name2) == FILETABLE_NAME:
            continue
        raw = data[data_offset + offset:data_offset + offset + size]
        if len(raw) != size:
            raise ValueError("%s: entry %d is cut off" % (path, i))
        if flags & 1:
            expected = struct.unpack(">I", raw[:4])[0]
            raw = zlib.decompress(raw[4:])
            if len(raw) != expected:
                raise ValueError("%s: entry %d has the wrong size" % (path, i))
        files.append((name1, name2, raw))
    return files


def read_shaders(path):
    """-> list of dicts: type, base, aux, uses_gs, regs, programs ([fetch, vertex] or [pixel])."""
    out = []
    for name1, name2, raw in read_container(path):
        s = Stream(raw)
        first = s.u8()
        version, kind = first & 15, first >> 4
        if version != 1 or kind not in (TYPE_VERTEX, TYPE_GEOMETRY, TYPE_PIXEL):
            raise ValueError("unknown shader record %02x" % first)
        base, aux = s.u64(), s.u64()
        record = {"type": kind, "base": base, "aux": aux, "name": (name1, name2)}
        if kind == TYPE_GEOMETRY:
            record["ring"] = s.u16()
            record["uses_gs"] = True
        else:
            record["uses_gs"] = s.u8() != 0
        record["regs"] = read_registers(s)
        record["programs"] = [read_program(s)] if kind == TYPE_PIXEL else [read_program(s), read_program(s)]
        if not s.end():
            raise ValueError("shader record has trailing bytes")
        out.append(record)
    return out


def read_pipelines(path):
    """-> list of dicts: vs, gs, ps ((base, aux) or None), regs."""
    out = []
    for name1, name2, raw in read_container(path):
        s = Stream(raw)
        if s.u8() != 1:
            raise ValueError("unknown pipeline record version")
        mask = s.u8()
        record = {"vs": None, "gs": None, "ps": None}
        for bit, key in ((1, "vs"), (2, "gs"), (4, "ps")):
            if mask & bit:
                record[key] = (s.u64(), s.u64())
        record["regs"] = read_registers(s)
        if not s.end():
            raise ValueError("pipeline record has trailing bytes")
        out.append(record)
    return out


def info(shaders_path, pipelines_path=None):
    shaders = read_shaders(shaders_path)
    kinds = Counter(r["type"] for r in shaders)
    print("%s: %d shaders (%d vertex, %d pixel, %d geometry)" %
          (shaders_path, len(shaders), kinds[TYPE_VERTEX], kinds[TYPE_PIXEL], kinds[TYPE_GEOMETRY]))
    print("  distinct programs: %d vertex, %d pixel" % (
        len({r["programs"][-1] for r in shaders if r["type"] == TYPE_VERTEX}),
        len({r["programs"][-1] for r in shaders if r["type"] == TYPE_PIXEL})))
    if pipelines_path:
        pipes = read_pipelines(pipelines_path)
        have = {(r["base"] & ~(7 << 61) | r["type"] << 61, r["aux"]) for r in shaders}
        by = {(r["type"], r["base"], r["aux"]) for r in shaders}
        paired = sum(1 for p in pipes if p["vs"] and p["ps"] and (TYPE_VERTEX, *p["vs"]) in by and (TYPE_PIXEL, *p["ps"]) in by)
        print("%s: %d pipelines; %d pair a vertex and a pixel shader both found in the shader cache" %
              (pipelines_path, len(pipes), paired))


def main(argv):
    if len(argv) >= 3 and argv[1] in ("info", "check"):
        info(*argv[2:4])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
