// coreinit FS and nn_save: map Wii U volume paths onto host directories.
//   /vol/content/...  -> <game>/content/...
//   /vol/code/...     -> <game>/code/...
//   /vol/meta/...     -> <game>/meta/...
//   /vol/save/...     -> <save dir>/...
#include "../frame_causes.h"
#include <chrono>
#include "../bench.h"
#include "../platform/filesystem.h"
#include "../platform/host.h"

#include <cstdio>
#ifndef _WIN32
#include <strings.h>
#include <unistd.h>
#endif
#include <mutex>
#include <string>
#include <unordered_map>

#include "../runtime.h"
#include "../write_watch.h"
#include "../game_languages.h"

namespace {

enum FSStatus : int32_t {
    FS_OK = 0,
    FS_END = -2,
    FS_EXISTS = -5,
    FS_NOT_FOUND = -6,
    FS_NOT_FILE = -7,
    FS_NOT_DIR = -8,
    FS_ACCESS_ERROR = -10,
};

struct OpenFile {
    FILE* f;
    std::string path;  // guest path
    std::string mode;
    uint64_t limit = UINT64_MAX;  // the game's part of a save file with the story block (story_store.h)
    bool story = false;           // a save file that carries the story block
    std::string host;
    std::string final_host;       // a save file being written goes to host (<final>.part) and replaces this on close
};
struct OpenDir {
    DIR* d;
    std::string path;  // host path
    std::string gpath;
    uint32_t read = 0;  // entries returned so far
};


std::mutex g_fs_mutex;
std::unordered_map<uint32_t, OpenFile> g_files;
// FSChangeDir: the current directory belongs to the FSClient, as on Cafe OS (the game's sound engine adds its own
// client and changes it to /vol/content/LuigiBros before opening "snd/wiiu_shared.bfsar")
std::unordered_map<uint32_t, std::string> g_cwd;
constexpr const char* kDefaultCwd = "/vol/content";

// the guest path a client means: absolute paths as they are, relative ones under its current directory
std::string client_path(uint32_t client, const std::string& path) {
    if (!path.empty() && path[0] == '/') return path;
    std::string cwd = kDefaultCwd;
    {
        std::lock_guard<std::mutex> lk(g_fs_mutex);
        if (auto it = g_cwd.find(client); it != g_cwd.end()) cwd = it->second;
    }
    if (path.empty() || path == ".") return cwd;
    return cwd + (cwd.back() == '/' ? "" : "/") + path;
}

// flush and push a file to the storage (a save must be on the disk before it replaces the previous one)
void sync_file(FILE* f) {
    fflush(f);
#ifdef _WIN32
    _commit(_fileno(f));
#else
    fsync(fileno(f));
#endif
}
std::unordered_map<uint32_t, OpenDir> g_dirs;
uint32_t g_next_handle = 1;

std::string host_path_exact(const std::string& guest) {
    std::string p = guest;
    auto map = [&](const char* prefix, const std::string& root) -> bool {
        size_t n = strlen(prefix);
        if (p.compare(0, n, prefix) == 0) {
            p = root + p.substr(n);
            return true;
        }
        return false;
    };
    if (map("/vol/content", config::game_dir + "/content")) return p;
    if (map("/vol/code", config::game_dir + "/code")) return p;
    if (map("/vol/meta", config::game_dir + "/meta")) return p;
    if (map("/vol/save", config::save_dir)) return p;
    if (!p.empty() && p[0] != '/') return config::game_dir + "/content/" + p;  // relative to cwd (/vol/content)
    return config::game_dir + p;
}

#ifndef _WIN32
// Wii U volumes are case-insensitive (the game asks for Common/Audiores, the disc folder is AudioRes);
// case-sensitive host file systems (Linux, case-sensitive APFS) need the path resolved one component
// at a time. Exact matches cost one stat(); resolved directories are cached. Components that don't
// exist yet (new save files) keep the guest's spelling.
std::string resolve_case(const std::string& p) {
    struct stat st;
    if (stat(p.c_str(), &st) == 0) return p;
    static std::mutex mu;
    static std::unordered_map<std::string, std::string> dirs;  // lower-cased dir path -> host dir path
    std::lock_guard<std::mutex> lk(mu);
    auto lower = [](std::string s) { for (char& ch : s) ch = (char)tolower((unsigned char)ch); return s; };
    std::string cur = p.compare(0, 1, "/") == 0 ? "/" : "";
    size_t pos = cur.size();
    while (pos <= p.size()) {
        size_t e = p.find('/', pos);
        if (e == std::string::npos) e = p.size();
        std::string comp = p.substr(pos, e - pos);
        pos = e + 1;
        if (comp.empty()) { if (e == p.size()) break; continue; }
        std::string base = cur.empty() ? "" : (cur == "/" ? "/" : cur + "/");
        std::string cand = base + comp;
        std::string key = lower(cand);
        if (auto it = dirs.find(key); it != dirs.end()) { cur = it->second; continue; }
        if (stat(cand.c_str(), &st) != 0) {
            std::string found;
            if (DIR* d = opendir(cur.empty() ? "." : cur.c_str())) {
                while (dirent* de = readdir(d))
                    if (!strcasecmp(de->d_name, comp.c_str())) { found = de->d_name; break; }
                closedir(d);
            }
            if (found.empty()) return cand + (e < p.size() ? p.substr(e) : "");  // not there: keep the rest as asked
            cand = base + found;
        }
        if (e < p.size()) dirs[key] = cand;  // only directories are cached
        cur = cand;
    }
    return cur;
}
#endif

std::string host_path(const std::string& guest) {
#ifdef _WIN32
    return host_path_exact(guest);  // Windows file systems are case-insensitive
#else
    return resolve_case(host_path_exact(guest));
#endif
}

// Optional content mods affect read-only content access only. A content mod's file comes first, then
// the pack of the active language source (game_languages.h: the European or Japanese pack the player
// chose, read under the name the game asks for), then the installed game.
std::string read_path(const std::string& guest,const std::string& mode="rb") {
    if (bench::active() && (mode=="r"||mode=="rb")) {
        std::string host = bench::host_override(guest);
        if (!host.empty()) return host;
    }
    std::string override;
    if(mode=="r"||mode=="rb")override=game_lang::redirect(guest);
    return override.empty()?host_path(guest):override;
}

void make_parent_dirs(const std::string& path) {
    std::error_code ec; std::filesystem::create_directories(std::filesystem::path(path).parent_path(),ec);
}

void fill_stat(uint32_t out, const struct stat& st, uint64_t size = UINT64_MAX) {
    memset(mem::ptr(out), 0, 0x64);
    bool dir = S_ISDIR(st.st_mode);
    if (size == UINT64_MAX) size = (uint64_t)st.st_size;
    st32(out + 0x00, dir ? 0x80000000u : 0x01000000u);
    st32(out + 0x04, 0x666);
    st32(out + 0x10, dir ? 0 : (uint32_t)size);
    st32(out + 0x14, dir ? 0 : (uint32_t)size);
}
// the size the game sees: a save file's story block is not part of it
uint64_t game_size(const std::string& gpath, const std::string& hp, const struct stat& st) {
    (void)gpath; (void)hp;
    return (uint64_t)st.st_size;
}

int32_t open_file(const std::string& gpath, const std::string& mode, uint32_t out_handle) {
    std::string hp = read_path(gpath,mode);
    if (mode.find_first_of("wa") != std::string::npos) make_parent_dirs(hp);
    // A save file the game rewrites is written beside the old one and replaces it when the game closes it, so a
    // killed process or a dead battery leaves the previous save whole (a truncating "w" would leave half a file)
    std::string final_host;
    if (mode.find('w') != std::string::npos && gpath.compare(0, 9, "/vol/save") == 0) {
        final_host = hp;
        hp += ".part";
    }
    std::string m = mode;
    if (m.find('b') == std::string::npos) m += "b";
    FILE* f = fopen(hp.c_str(), m.c_str());
    TRACE("[fs] open %s (%s) -> %s", gpath.c_str(), mode.c_str(), f ? "ok" : "not found");
    if (!f) return FS_NOT_FOUND;
    bench::note_file_open(gpath.c_str());
    const bool story = false;
    uint64_t limit = UINT64_MAX;
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    uint32_t h = g_next_handle++;
    g_files[h] = {f, gpath, m, limit, story, hp, final_host};
    st32(out_handle, h);
    return FS_OK;
}

FILE* file(uint32_t h) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    auto it = g_files.find(h);
    return it == g_files.end() ? nullptr : it->second.f;
}

int32_t stat_path(const std::string& gpath, uint32_t out) {
    struct stat st;
    const std::string hp = read_path(gpath);
    if (stat(hp.c_str(), &st) != 0) return FS_NOT_FOUND;
    fill_stat(out, st, game_size(gpath, hp, st));
    return FS_OK;
}

int32_t open_dir(const std::string& gpath, uint32_t out_handle) {
    DIR* d = opendir(host_path(gpath).c_str());
    TRACE("[fs] opendir %s -> %s", gpath.c_str(), d ? "ok" : "not found");
    if (!d) return FS_NOT_FOUND;
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    uint32_t h = g_next_handle++;
    g_dirs[h] = {d, host_path(gpath), gpath};
    st32(out_handle, h);
    return FS_OK;
}

}  // namespace

// ---------------------------------------------------------------- FS
HLE(coreinit, FSInit) {}
HLE(coreinit, FSShutdown) {}
HLE(coreinit, FSAddClient) { ret(c, 0); }
HLE(coreinit, FSDelClient) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    g_cwd.erase(arg(c, 0));
    ret(c, 0);
}
HLE(coreinit, FSInitCmdBlock) { memset(mem::ptr(arg(c, 0)), 0, 0xA80); }
HLE(coreinit, FSSetCmdPriority) { ret(c, 0); }
HLE(coreinit, FSSetStateChangeNotification) {}
HLE(coreinit, FSGetVolumeState) { ret(c, 1); }  // FS_VOLSTATE_READY
HLE(coreinit, FSGetLastError) { ret(c, 0); }
HLE(coreinit, FSGetLastErrorCodeForViewer) { ret(c, 0); }
HLE(coreinit, FSGetCwd) { mem::write_cstr(arg(c, 2), client_path(arg(c, 0), "").c_str(), arg(c, 3)); ret(c, FS_OK); }

// FSChangeDir(client, block, path, errorMask): the directory has to exist
HLE(coreinit, FSChangeDir) {
    const std::string dir = client_path(arg(c, 0), mem::read_cstr(arg(c, 2)));
    struct stat st;
    if (stat(host_path(dir).c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) { ret(c, FS_NOT_FOUND); return; }
    {
        std::lock_guard<std::mutex> lk(g_fs_mutex);
        g_cwd[arg(c, 0)] = dir;
    }
    TRACE("[fs] chdir %s", dir.c_str());
    ret(c, FS_OK);
}

HLE(coreinit, FSOpenFile) {
    ret(c, open_file(client_path(arg(c, 0), mem::read_cstr(arg(c, 2))), mem::read_cstr(arg(c, 3)), arg(c, 4)));
}

HLE(coreinit, FSCloseFile) {
    std::string written_guest, written_host, part, final_host;
    {
        std::lock_guard<std::mutex> lk(g_fs_mutex);
        auto it = g_files.find(arg(c, 2));
        if (it != g_files.end()) {
            if (!it->second.final_host.empty()) {
                sync_file(it->second.f);
                part = it->second.host, final_host = it->second.final_host;
            }
            fclose(it->second.f);
            if (it->second.story && it->second.mode.find_first_of("wa+") != std::string::npos)
                written_guest = it->second.path, written_host = final_host.empty() ? it->second.host : final_host;
            g_files.erase(it);
        }
    }
    if (!final_host.empty() && !host::replace_file(part, final_host)) LOG("[fs] cannot put %s in place of %s", part.c_str(), final_host.c_str());
    ret(c, FS_OK);
}

// FSReadFile (handle at arg 5) and FSReadFileWithPos (position at arg 5, handle at arg 6)
static void read_file_items(Cpu* c, uint32_t handle, bool positional) {
    uint32_t dst = arg(c, 2), size = arg(c, 3), count = arg(c, 4);
    FILE* f = file(handle);
    if (!f || size == 0) { ret(c, 0); return; }
    if (positional && host::file_seek(f, arg(c, 5), SEEK_SET) != 0) { ret(c, 0); return; }
    uint64_t limit = UINT64_MAX;
    {
        std::lock_guard<std::mutex> lk(g_fs_mutex);
        auto it = g_files.find(handle);
        if (it != g_files.end()) limit = it->second.limit;
    }
    if (limit != UINT64_MAX) {  // a save file's reads end at the game's part
        const int64_t pos = host::file_tell(f);
        const uint64_t left = pos < 0 || (uint64_t)pos >= limit ? 0 : limit - (uint64_t)pos;
        count = (uint32_t)std::min<uint64_t>(count, left / size);
        if (count == 0) { ret(c, 0); return; }
    }
    size_t n;
    const auto readStart = std::chrono::steady_clock::now();
    {
        BlockingScope b;  // the calling thread waits for the disc; others on its core run
        // the kernel writes guest memory here: write-protected texture pages would fail the read
        // (EFAULT) instead of faulting into the write tracker (write_watch.h)
        wwatch::HostWrite w(dst, (uint32_t)std::min<uint64_t>((uint64_t)size * count, 0x100000000ull - dst));
        n = fread(mem::ptr(dst), 1, (size_t)size * count, f);
    }
    {   // reads that stall the game (phones: storage behind FUSE): always logged
        const auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - readStart).count();
        frame_causes::add(frame_causes::kFileIo, uint64_t(ms * 1e6));
        if (ms > 40.0) LOG("[fs] slow read: %.0f ms for %llu bytes (%.1f MB/s)", ms, (unsigned long long)size * count, (double)size * count / 1048.576 / ms);
    }
    TRACE("[fs] read h=%08X size=%u count=%u -> %zu items", handle, size, count, n / size);
    ret(c, (uint32_t)(n / size));
}
HLE(coreinit, FSReadFile) { read_file_items(c, arg(c, 5), false); }
HLE(coreinit, FSReadFileWithPos) { read_file_items(c, arg(c, 6), true); }

HLE(coreinit, FSWriteFile) {
    uint32_t src = arg(c, 2), size = arg(c, 3), count = arg(c, 4);
    FILE* f = file(arg(c, 5));
    if (!f || size == 0) { ret(c, 0); return; }
    size_t n;
    {
        BlockingScope b;
        n = fwrite(mem::ptr(src), 1, (size_t)size * count, f);
        fflush(f);
    }
    ret(c, (uint32_t)(n / size));
}

HLE(coreinit, FSSetPosFile) {
    FILE* f = file(arg(c, 2));
    ret(c, f && host::file_seek(f,arg(c,3),SEEK_SET) == 0 ? FS_OK : FS_ACCESS_ERROR);
}

HLE(coreinit, FSGetStat) { ret(c, stat_path(client_path(arg(c, 0), mem::read_cstr(arg(c, 2))), arg(c, 3))); }

HLE(coreinit, FSGetStatFile) {
    FILE* f = file(arg(c, 2));
    struct stat st;
    if (!f || fstat(fileno(f), &st) != 0) { ret(c, FS_NOT_FOUND); return; }
    uint64_t size = UINT64_MAX;
    {
        std::lock_guard<std::mutex> lk(g_fs_mutex);
        auto it = g_files.find(arg(c, 2));
        if (it != g_files.end() && it->second.story) size = game_size(it->second.path, it->second.host, st);
    }
    fill_stat(arg(c, 3), st, size);
    ret(c, FS_OK);
}

HLE(coreinit, FSOpenDir) { ret(c, open_dir(client_path(arg(c, 0), mem::read_cstr(arg(c, 2))), arg(c, 3))); }

HLE(coreinit, FSReadDir) {
    uint32_t out = arg(c, 3);
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    auto it = g_dirs.find(arg(c, 2));
    if (it == g_dirs.end()) { ret(c, FS_NOT_DIR); return; }
    struct dirent* de;
    while ((de = readdir(it->second.d))) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
        struct stat st;
        auto path=it->second.path + "/" + de->d_name;
        if (stat(path.c_str(), &st) != 0) continue;
        const std::string entry = it->second.gpath + "/" + de->d_name;
        fill_stat(out, st, game_size(entry, path, st));
        mem::write_cstr(out + 0x64, de->d_name, 256);
        it->second.read++;
        ret(c, FS_OK);
        return;
    }
    ret(c, FS_END);
}

HLE(coreinit, FSCloseDir) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    auto it = g_dirs.find(arg(c, 2));
    if (it != g_dirs.end()) {
        closedir(it->second.d);
        g_dirs.erase(it);
    }
    ret(c, FS_OK);
}

// Remove a file or an empty directory (a game save only; the game's own files are never touched)
static int32_t remove_path(const std::string& gpath) {
    const std::string hp = host_path(gpath);
    std::error_code ec;
    if (!std::filesystem::exists(std::filesystem::path(hp), ec)) return FS_NOT_FOUND;
    if (gpath.compare(0, 9, "/vol/save") != 0) return FS_ACCESS_ERROR;
    if (!std::filesystem::remove(std::filesystem::path(hp), ec)) return FS_ACCESS_ERROR;
    std::filesystem::remove(std::filesystem::path(hp + ".part"), ec);
    TRACE("[fs] remove %s", gpath.c_str());
    return FS_OK;
}
HLE(coreinit, FSRemove) { ret(c, remove_path(client_path(arg(c, 0), mem::read_cstr(arg(c, 2))))); }
HLE(coreinit, FSFlushQuota) { ret(c, FS_OK); }            // the host writes through
HLE(coreinit, FSGetErrorCodeForViewer) { ret(c, 0); }     // the code the error viewer would show: none

// ---------------------------------------------------------------- nn_save
// Saves live in <save dir>/<account slot or "common">/<path>
static std::string save_path(uint32_t slot, const std::string& p) {
    std::string base = slot == 0xFF ? "/vol/save/common" : "/vol/save/user";
    return base + (p.empty() || p[0] == '/' ? "" : "/") + p;
}

HLE(nn_save, SAVEInit) { ret(c, 0); }
HLE(nn_save, SAVEShutdown) {}
HLE(nn_save, SAVEInitSaveDir) {
    make_parent_dirs(host_path(save_path(arg(c, 0), "x")));
    ret(c, 0);
}
HLE(nn_save, SAVEFlushQuota) { ret(c, 0); }
HLE(nn_save, SAVERemove) { ret(c, remove_path(save_path(arg(c, 2), mem::read_cstr(arg(c, 3))))); }
HLE(nn_save, SAVEOpenFile) {
    ret(c, open_file(save_path(arg(c, 2), mem::read_cstr(arg(c, 3))), mem::read_cstr(arg(c, 4)), arg(c, 5)));
}
// Another title's save data (the game probes the saves of sibling titles): there is none here
HLE(nn_save, SAVEGetStatOtherApplication) { ret(c, FS_NOT_FOUND); }
HLE(nn_save, SAVEOpenFileOtherApplication) { ret(c, FS_NOT_FOUND); }
// SAVEGetFreeSpaceSize(client, block, slot, uint64_t* out, errHandling): the game refuses to save when it
// cannot read the free space; report 1 GiB
HLE(nn_save, SAVEGetFreeSpaceSize) {
    if (uint32_t out = arg(c, 3)) { st32(out, 0); st32(out + 4, 0x40000000u); }
    ret(c, FS_OK);
}
HLE(nn_save, SAVEGetStat) { ret(c, stat_path(save_path(arg(c, 2), mem::read_cstr(arg(c, 3))), arg(c, 4))); }
HLE(nn_save, SAVEOpenDir) {
    std::string gp = save_path(arg(c, 2), mem::read_cstr(arg(c, 3)));
    make_parent_dirs(host_path(gp) + "/");
    ret(c, open_dir(gp, arg(c, 4)));
}

// ---------------------------------------------------------------- save states: open files
#include <algorithm>
#include <vector>

#include "../savestate.h"

void fs_ss_save(ss::Writer& w) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    w.u32(g_next_handle);
    std::vector<uint32_t> keys;
    for (auto& [h, f] : g_files) keys.push_back(h);
    std::sort(keys.begin(), keys.end());
    w.u32((uint32_t)keys.size());
    for (uint32_t h : keys) {
        OpenFile& f = g_files[h];
        w.u32(h);
        w.str(f.path);
        w.str(f.mode);
        w.u64((uint64_t)host::file_tell(f.f));
    }
    keys.clear();
    for (auto& [h, d] : g_dirs) keys.push_back(h);
    std::sort(keys.begin(), keys.end());
    w.u32((uint32_t)keys.size());
    for (uint32_t h : keys) {
        w.u32(h);
        w.str(g_dirs[h].gpath);
        w.u32(g_dirs[h].read);
    }
}

void fs_ss_load(ss::Reader& r) {
    std::lock_guard<std::mutex> lk(g_fs_mutex);
    uint32_t next = r.u32();
    for (auto& [h, f] : g_files) fclose(f.f);
    g_files.clear();
    for (auto& [h, d] : g_dirs) closedir(d.d);
    g_dirs.clear();
    uint32_t n = r.u32();
    for (uint32_t i = 0; i < n && r.ok; i++) {
        uint32_t h = r.u32();
        std::string gp = r.str(), mode = r.str();
        uint64_t pos = r.u64();
        // reopening must not truncate or create: writers continue in update mode
        std::string m = mode.find_first_of("wa+") != std::string::npos ? "r+b" : "rb";
        FILE* f = fopen(read_path(gp,m).c_str(), m.c_str());
        if (!f) {
            LOG("[savestate] cannot reopen %s", gp.c_str());
            continue;
        }
        host::file_seek(f,pos,SEEK_SET);
        const std::string hp = read_path(gp,m);
        const bool story = false;
        uint64_t limit = UINT64_MAX;
        g_files[h] = {f, gp, mode, limit, story, hp};
    }
    n = r.u32();
    for (uint32_t i = 0; i < n && r.ok; i++) {
        uint32_t h = r.u32();
        std::string gp = r.str();
        uint32_t read = r.u32();
        DIR* d = opendir(host_path(gp).c_str());
        if (!d) continue;
        OpenDir od{d, host_path(gp), gp, 0};
        while (od.read < read) {
            struct dirent* de = readdir(d);
            if (!de) break;
            if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, "..")) continue;
            od.read++;
        }
        g_dirs[h] = od;
    }
    g_next_handle = std::max(g_next_handle, next);
}
