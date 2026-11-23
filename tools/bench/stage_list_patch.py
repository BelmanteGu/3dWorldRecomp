#!/usr/bin/env python3
"""Stage tour helper (tools/bench/sm3dw_stage_tour.sh): a copy of the game's SystemData/StageList.szs in which one
course node of the world map loads another stage.

usage: stage_list_patch.py GAME_STAGELIST.szs OUT.szs FROM_STAGE TO_STAGE     (e.g. FlipCircusStage SnowBallParkStage)
       stage_list_patch.py GAME_STAGELIST.szs --list                           the stage names the list can point to

StageList.byml names every course's stage (WorldList[].StageList[].StageName). Pointing the circus node (world 1-5,
the one the benchmark walks into) at another stage loads that stage under its own name, so every file and table the
game derives from the name agrees (redirecting only the stage archives crashed the game). Only names already in the
list's string table can be used (all the courses of the 12 worlds); the value is an index, so nothing moves. The
output is derived from your game files: keep it out of the repository (the tour writes it into its work dir).
"""
import struct
import sys


def yaz0_decode(d):
    if d[:4] != b"Yaz0":
        return d
    n = struct.unpack(">I", d[4:8])[0]
    o = bytearray()
    i = 16
    while len(o) < n:
        code = d[i]
        i += 1
        for b in range(8):
            if len(o) >= n:
                break
            if code & (0x80 >> b):
                o.append(d[i])
                i += 1
            else:
                a, c = d[i], d[i + 1]
                i += 2
                dist = ((a & 0xF) << 8 | c) + 1
                length = a >> 4
                if length == 0:
                    length = d[i] + 0x12
                    i += 1
                else:
                    length += 2
                for _ in range(length):
                    o.append(o[-dist])
    return bytes(o)


def yaz0_store(d):
    """Yaz0 with every byte a literal: valid for any decoder, no compression needed."""
    out = bytearray(b"Yaz0" + struct.pack(">I", len(d)) + bytes(8))
    for i in range(0, len(d), 8):
        chunk = d[i:i + 8]
        out.append((0xFF << (8 - len(chunk))) & 0xFF)
        out += chunk
    return bytes(out)


def sarc_files(d):
    if d[:4] != b"SARC":
        raise ValueError("not a SARC archive")
    e = ">" if d[6:8] == b"\xfe\xff" else "<"
    data_offset = struct.unpack(e + "I", d[12:16])[0]
    count = struct.unpack(e + "H", d[0x14 + 6:0x14 + 8])[0]
    names = 0x14 + 12 + 16 * count + 8
    out = {}
    for k in range(count):
        _, attr, start, end = struct.unpack(e + "IIII", d[0x14 + 12 + 16 * k:0x14 + 28 + 16 * k])
        at = names + (attr & 0xFFFF) * 4
        out[d[at:d.index(b"\0", at)].decode()] = (data_offset + start, data_offset + end)
    return out


class Byml:
    def __init__(self, d, base):
        self.d, self.base = d, base
        self.e = ">" if d[base:base + 2] == b"BY" else "<"
        _, keys, strings, self.root = struct.unpack(self.e + "HIII", d[base + 2:base + 16])
        self.keys = self.table(keys)
        self.strings = self.table(strings)

    def u32(self, o):
        return struct.unpack(self.e + "I", self.d[self.base + o:self.base + o + 4])[0]

    def table(self, o):
        n = self.u32(o) & 0xFFFFFF
        out = []
        for i in range(n):
            at = self.base + o + self.u32(o + 4 + 4 * i)
            out.append(self.d[at:self.d.index(b"\0", at)].decode("latin-1"))
        return out

    def items(self, o):
        """(key or index, type, value, absolute offset of the value word) of a container node."""
        kind, n = self.d[self.base + o], self.u32(o) & 0xFFFFFF
        if kind == 0xC0:
            values = o + 4 + ((n + 3) & ~3)
            return [(i, self.d[self.base + o + 4 + i], self.u32(values + 4 * i), self.base + values + 4 * i)
                    for i in range(n)]
        if kind == 0xC1:
            out = []
            for i in range(n):
                w = self.u32(o + 4 + 8 * i)
                key = w >> 8 if self.e == ">" else w & 0xFFFFFF
                out.append((self.keys[key], self.d[self.base + o + 4 + 8 * i + 3], self.u32(o + 8 + 8 * i),
                            self.base + o + 8 + 8 * i))
            return out
        raise ValueError("node type %x" % kind)

    def stage_names(self):
        """[(world id, course id, stage name, absolute offset of the name's value word)]"""
        out = []
        for _, _, world, _ in self.child(self.root, "WorldList"):
            wid = None
            courses = []
            for key, kind, value, _ in self.items(world):
                if key == "WorldId":
                    wid = value
                if key == "StageList":
                    courses = [c for _, _, c, _ in self.items(value)]
            for course in courses:
                cid, name, at = None, None, None
                for key, kind, value, word in self.items(course):
                    if key == "CourseId":
                        cid = value
                    if key == "StageName" and kind == 0xA0:
                        name, at = self.strings[value], word
                out.append((wid, cid, name, at))
        return out

    def child(self, o, name):
        for key, _, value, _ in self.items(o):
            if key == name:
                return self.items(value)
        raise ValueError("no %s" % name)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    sarc = yaz0_decode(open(argv[1], "rb").read())
    start, _ = sarc_files(sarc)["StageList.byml"]
    byml = Byml(sarc, start)
    names = byml.stage_names()
    if argv[2] == "--list":
        for wid, cid, name, _ in names:
            print(wid, cid, name)
        return 0
    if len(argv) != 5:
        print(__doc__)
        return 2
    source, target = argv[3], argv[4]
    if target not in byml.strings:
        print("%s is not in the stage list's strings" % target)
        return 1
    patched = bytearray(sarc)
    hits = 0
    for _, _, name, word in names:
        if name == source:
            patched[word:word + 4] = struct.pack(byml.e + "I", byml.strings.index(target))
            hits += 1
    if not hits:
        print("no course loads %s" % source)
        return 1
    open(argv[2], "wb").write(yaz0_store(bytes(patched)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
