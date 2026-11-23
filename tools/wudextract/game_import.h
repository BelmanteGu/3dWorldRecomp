// The Android app's game import (Super Mario 3D World build): the extractor of wwhd_extract.cpp as
// functions, with a progress callback, cancellation and resumption, for the import service
// (android/.../GameImport.java through game_import_jni.cpp). Keys come as the contents of the key files
// the player picked (a Cemu keys.txt, or 16-byte / 32-hex-digit key files): every key found in them is
// tried as the disc key and as the Wii U common key. Keys never leave this code and are never printed.
#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace game_import {

// phase: 1 checking an archive's SHA-256, 2 extracting
using Progress = std::function<void(int phase, uint64_t done, uint64_t total)>;

struct Result {
    // 0 ok; wwhd_extract.cpp's exit codes (3 disc key, 5/6 common key, 7 not a Wii U image or unreadable,
    // 8 damaged, 9 cannot write, 10 title missing); 11 cancelled
    int code = 0;
    std::string message;           // English, for the log; the app shows its own text per code
    std::string format;            // "wud" or "wua"
    std::string title_id;          // 16 lower-case hex digits
    uint64_t files = 0, bytes = 0;  // of the game's title (what extract writes without a filter)
};

// The title in an image (needs keys) or archive: the code check and the sizes before anything is written.
// want_title: the title to take from an archive with several (16 hex digits); "" or absent: the only
// game title (00050000...) there is.
Result inspect(const std::string& path, const std::vector<std::string>& key_files, const std::string& want_title);

// Extracts the game (code, content, meta) into out_dir. only: glob patterns as --only (empty: all).
// Files already in out_dir with their full size are kept (an import that was interrupted resumes);
// each file is written as NAME.part and renamed when complete. verify: check an archive's SHA-256 first.
Result extract(const std::string& path, const std::vector<std::string>& key_files, const std::string& want_title,
               const std::string& out_dir, const std::vector<std::string>& only, bool verify,
               const Progress& progress, const std::atomic<bool>& cancel);

}  // namespace game_import
