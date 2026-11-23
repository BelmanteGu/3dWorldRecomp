// The Android app's game import: wwhd_extract.cpp's reader (disc images and Cemu archives) as functions
// with progress, cancellation and resumption (game_import.h). The extractor's code is compiled into this
// file, without its main(), so both stay one implementation.
#define WWHD_EXTRACT_NO_MAIN
#include "wwhd_extract.cpp"

#include "game_import.h"

namespace game_import {
namespace {

struct Cancelled {};

// Every key in the picked files: a 16-byte file is one raw key; a text file gives every run of exactly
// 32 hex digits outside comments ('#', ';' or "//" to the end of the line), as in Cemu's keys.txt.
std::vector<Key> parse_keys(const std::vector<std::string>& files) {
    std::vector<Key> keys;
    auto add = [&](const Key& k) {
        for (const Key& x : keys)
            if (!memcmp(x.b, k.b, 16)) return;
        keys.push_back(k);
    };
    for (const std::string& f : files) {
        if (f.size() == 16) {
            Key k;
            memcpy(k.b, f.data(), 16);
            k.set = true;
            add(k);
            continue;
        }
        size_t pos = 0;
        while (pos < f.size()) {
            size_t end = f.find('\n', pos);
            if (end == std::string::npos) end = f.size();
            std::string line = f.substr(pos, end - pos);
            pos = end + 1;
            for (const char* mark : {"#", ";", "//"}) {
                size_t c = line.find(mark);
                if (c != std::string::npos) line.resize(c);
            }
            size_t i = 0;
            while (i < line.size()) {
                if (!isxdigit((unsigned char)line[i])) {
                    i++;
                    continue;
                }
                size_t j = i;
                while (j < line.size() && isxdigit((unsigned char)line[j])) j++;
                Key k;
                if (j - i == 32 && parse_key(line.substr(i, 32), k)) add(k);
                i = j;
            }
        }
    }
    return keys;
}

struct Source {
    std::string format;
    std::unique_ptr<zarchive::Reader> zr;  // wua
    std::vector<Title> titles;
    const Title* title = nullptr;
    Disc disc;  // wud
};

// opens an image or archive and picks the title; fills r's format, title, files and bytes
void open_source(Source& s, Result& r, const std::string& path, const std::vector<std::string>& key_files,
                 const std::string& want_title) {
    fs::path p = upath(path);
    bool archive = zarchive::Reader::detect(p) || lower(ustr(p.extension())) == ".wua";
    if (archive) {
        s.format = r.format = "wua";
        try {
            s.zr = std::make_unique<zarchive::Reader>(p);
        } catch (const zarchive::Error& e) {
            fail(e.damaged ? 8 : 7, e.msg);
        }
        s.titles = archive_titles(*s.zr);
        if (!want_title.empty()) s.title = pick_title(s.titles, want_title);
        if (!s.title) {  // the only game (not an update or DLC) in it, whatever its region; its newest version
            const Title* best = nullptr;
            bool several = false;
            for (const Title& t : s.titles) {
                if (t.id.compare(0, 8, "00050000") != 0) continue;
                if (!best || (t.id == best->id && t.version > best->version)) best = &t;
                else if (t.id != best->id) several = true;
            }
            s.title = several ? nullptr : best;
        }
        if (!s.title) fail(10, "the archive has no single game title (" + describe(s.titles) + ")");
        r.title_id = s.title->id;
        r.files = s.title->files;
        r.bytes = s.title->bytes;
        return;
    }
    s.format = r.format = "wud";
    std::vector<Key> keys = parse_keys(key_files);
    if (keys.empty()) fail(3, "no keys in the key files");
    s.disc = open_disc(p, Key{}, Key{}, &keys);
    for (Key& k : keys) memset(k.b, 0, sizeof k.b);
    r.title_id = s.disc.title_id;
    for (auto& e : s.disc.gm->entries)
        if (selected(e)) r.files++, r.bytes += e.size;
}

// one file into out/rel: kept when already there with its full size, else written as .part and renamed
template <class Read>
void write_file(const fs::path& out, const std::string& rel, uint64_t size, uint64_t& done, uint64_t total,
                const Progress& progress, const std::atomic<bool>& cancel, uint64_t& last, Read&& read) {
    if (rel.empty() || rel.find("..") != std::string::npos || rel[0] == '/' || rel[0] == '\\')
        fail(7, "unsafe path in the file table: " + rel);
    fs::path dst = out / upath(rel);
    std::error_code ec;
    if (fs::is_regular_file(dst, ec) && fs::file_size(dst, ec) == size && !ec) {
        done += size;
        return;
    }
    fs::create_directories(dst.parent_path(), ec);
    if (ec) fail(9, "cannot create " + ustr(dst.parent_path()) + ": " + ec.message());
    fs::path part = dst;
    part += ".part";
    {
        std::ofstream o(part, std::ios::binary | std::ios::trunc);
        if (!o) fail(9, "cannot write " + ustr(part));
        read([&](const uint8_t* p, uint64_t n) {
            if (cancel.load(std::memory_order_relaxed)) throw Cancelled{};
            o.write((const char*)p, (std::streamsize)n);
            if (!o) fail(9, "cannot write " + ustr(dst) + " (disk full?)");
            done += n;
            if (done - last >= (8u << 20)) {
                last = done;
                if (progress) progress(2, done, total);
            }
        });
        o.close();
        if (!o) fail(9, "cannot write " + ustr(dst) + " (disk full?)");
    }
    fs::rename(part, dst, ec);
    if (ec) fail(9, "cannot rename " + ustr(part) + ": " + ec.message());
}

}  // namespace

Result inspect(const std::string& path, const std::vector<std::string>& key_files, const std::string& want_title) {
    Result r;
    try {
        Source s;
        open_source(s, r, path, key_files, want_title);
    } catch (const Fail& f) {
        r.code = f.code;
        r.message = f.msg;
    } catch (const zarchive::Error& e) {
        r.code = e.damaged ? 8 : 7;
        r.message = e.msg;
    } catch (const std::exception& e) {
        r.code = 7;
        r.message = e.what();
    }
    return r;
}

Result extract(const std::string& path, const std::vector<std::string>& key_files, const std::string& want_title,
               const std::string& out_dir, const std::vector<std::string>& only, bool verify,
               const Progress& progress, const std::atomic<bool>& cancel) {
    Result r;
    g_only = only;
    try {
        Source s;
        open_source(s, r, path, key_files, want_title);
        fs::path out = upath(out_dir);
        uint64_t done = 0, total = 0, last = 0;
        if (s.zr) {
            if (verify) {
                uint64_t vlast = 0;
                bool ok = s.zr->verify([&](uint64_t d, uint64_t t) {
                    if (cancel.load(std::memory_order_relaxed)) throw Cancelled{};
                    if (progress && (d == 0 || d == t || d - vlast >= (8u << 20))) {
                        vlast = d;
                        progress(1, d, t);
                    }
                });
                if (!ok) fail(8, "the archive's SHA-256 does not match");
            }
            std::vector<std::pair<std::string, uint32_t>> all;
            walk(*s.zr, s.title->node, "", all);
            for (auto& f : all)
                if (wanted(f.first)) total += s.zr->node(f.second).size;
            if (progress) progress(2, 0, total);
            for (auto& f : all) {
                if (!wanted(f.first)) continue;
                write_file(out, f.first, s.zr->node(f.second).size, done, total, progress, cancel, last,
                           [&](auto&& sink) { s.zr->read_file(f.second, sink); });
            }
        } else {
            FST& gm = *s.disc.gm;
            for (auto& e : gm.entries)
                if (selected(e) && wanted(e.path)) total += e.size;
            if (progress) progress(2, 0, total);
            for (auto& e : gm.entries) {
                if (!selected(e) || !wanted(e.path)) continue;
                write_file(out, e.path, e.size, done, total, progress, cancel, last,
                           [&](auto&& sink) { gm.read_file(e, sink); });
            }
        }
        if (progress) progress(2, done, total);
    } catch (const Cancelled&) {
        r.code = 11;
        r.message = "cancelled";
    } catch (const Fail& f) {
        r.code = f.code;
        r.message = f.msg;
    } catch (const zarchive::Error& e) {
        r.code = e.damaged ? 8 : 7;
        r.message = e.msg;
    } catch (const std::exception& e) {
        r.code = 9;
        r.message = e.what();
    }
    g_only.clear();
    return r;
}

}  // namespace game_import
