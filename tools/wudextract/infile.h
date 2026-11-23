// A read-only file for the extractor with the part of std::ifstream it uses (read, gcount, seekg, tellg,
// clear, operator bool), over pread. Opened from a path or, for "/proc/self/fd/N" (the descriptor of a
// document the Android system picker returned), by duplicating descriptor N: Android 16 refuses to reopen
// those /proc/self/fd links ("cannot open the disc image /proc/self/fd/144" on a Galaxy S23), while the
// descriptor itself reads fine. Windows keeps std::ifstream.
#pragma once
#include <filesystem>
#include <fstream>
#include <ios>
#include <string>

#ifdef _WIN32
using InFile = std::ifstream;
#else
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

class InFile {
public:
    InFile() = default;
    explicit InFile(const std::filesystem::path& p, std::ios::openmode = std::ios::in) { open(p); }
    InFile(const InFile&) = delete;
    InFile& operator=(const InFile&) = delete;
    ~InFile() { close(); }

    void open(const std::filesystem::path& p, std::ios::openmode = std::ios::in) {
        close();
        const std::string s = p.string(), pre = "/proc/self/fd/";
        if (s.compare(0, pre.size(), pre) == 0) {
            char* end = nullptr;
            const long n = std::strtol(s.c_str() + pre.size(), &end, 10);
            if (end && *end == 0 && n >= 0) fd_ = fcntl((int)n, F_DUPFD_CLOEXEC, 0);
        }
        if (fd_ < 0) fd_ = ::open(s.c_str(), O_RDONLY | O_CLOEXEC);
        struct stat st {};
        if (fd_ >= 0 && fstat(fd_, &st) == 0) size_ = (uint64_t)st.st_size;
        pos_ = 0;
        count_ = 0;
        fail_ = fd_ < 0;
    }
    void close() {
        if (fd_ >= 0) ::close(fd_);
        fd_ = -1;
    }
    explicit operator bool() const { return !fail_; }
    bool operator!() const { return fail_; }

    // reads n bytes from the position; fewer (the end of the file, an error) sets the fail state
    InFile& read(char* d, std::streamsize n) {
        count_ = 0;
        if (fail_) return *this;
        while (count_ < n) {
            const ssize_t r = pread(fd_, d + count_, (size_t)(n - count_), (off_t)(pos_ + (uint64_t)count_));
            if (r < 0 && errno == EINTR) continue;
            if (r <= 0) break;
            count_ += r;
        }
        pos_ += (uint64_t)count_;
        if (count_ < n) fail_ = true;
        return *this;
    }
    std::streamsize gcount() const { return count_; }
    InFile& seekg(std::streamoff off) { return seekg(off, std::ios::beg); }
    InFile& seekg(std::streamoff off, std::ios::seekdir dir) {
        if (fail_) return *this;  // as std::ifstream: clear() first
        const int64_t base = dir == std::ios::end ? (int64_t)size_ : dir == std::ios::cur ? (int64_t)pos_ : 0;
        if (base + off < 0) fail_ = true;
        else pos_ = (uint64_t)(base + off);
        return *this;
    }
    std::streamoff tellg() const { return fail_ ? -1 : (std::streamoff)pos_; }
    void clear() { if (fd_ >= 0) fail_ = false; }

private:
    int fd_ = -1;
    uint64_t pos_ = 0, size_ = 0;
    std::streamsize count_ = 0;
    bool fail_ = true;
};
#endif
