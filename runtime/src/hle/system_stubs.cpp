// Libraries the game uses for system integration and online features.
// Online services (Miiverse, SpotPass, accounts) report "unavailable".
#include "../crashrec.h"
#include "../runtime.h"
#include "../input.h"
#include "../rumble.h"
#include "../motion/motion.h"

namespace interp { bool repeat_input(); bool fresh_sticks(); void trace_read(const char*); uint64_t logic_steps(); }

// nn::Result: bit 31 set = failure
static constexpr uint32_t kResultOk = 0;
static constexpr uint32_t kResultFail = 0xA0000000;

// ---- nn_act (accounts)
HLE(nn_act, Initialize__Q2_2nn3actFv) { ret(c, kResultOk); }
HLE(nn_act, Finalize__Q2_2nn3actFv) { ret(c, kResultOk); }
HLE(nn_act, GetSlotNo__Q2_2nn3actFv) { ret(c, 1); }
HLE(nn_act, GetPrincipalId__Q2_2nn3actFv) { ret(c, 0); }
HLE(nn_act, GetParentalControlSlotNoEx__Q2_2nn3actFPUcUc) { st8(arg(c, 0), 1); ret(c, kResultOk); }

// ---- nn_ac (network connection)
HLE(nn_ac, Initialize__Q2_2nn2acFv) { ret(c, kResultOk); }
HLE(nn_ac, Finalize__Q2_2nn2acFv) { ret(c, kResultOk); }
HLE(nn_ac, Connect__Q2_2nn2acFv) { ret(c, kResultFail); }
HLE(nn_ac, Close__Q2_2nn2acFv) { ret(c, kResultOk); }
HLE(nn_ac, GetLastErrorCode__Q2_2nn2acFPUi) { st32(arg(c, 0), 1100); ret(c, kResultOk); }

// ---- nn_boss (SpotPass)
HLE(nn_boss, Initialize__Q2_2nn4bossFv) { ret(c, kResultFail); }
HLE(nn_boss, IsInitialized__Q2_2nn4bossFv) { ret(c, 0); }
HLE(nn_boss, Finalize__Q2_2nn4bossFv) {}
// play report (usage statistics upload): accepted and dropped
HLE(nn_boss, __ct__Q3_2nn4boss17PlayReportSettingFv) { ret(c, arg(c, 0)); }
HLE(nn_boss, __dt__Q3_2nn4boss17PlayReportSettingFv) {}
HLE(nn_boss, Initialize__Q3_2nn4boss17PlayReportSettingFPvUi) {}
HLE(nn_boss, Set__Q3_2nn4boss17PlayReportSettingFUiT1) { ret(c, 1); }
HLE(nn_boss, __ct__Q3_2nn4boss4TaskFv) { ret(c, arg(c, 0)); }
HLE(nn_boss, __dt__Q3_2nn4boss4TaskFv) {}
HLE(nn_boss, Initialize__Q3_2nn4boss4TaskFPCcUi) { ret(c, kResultFail); }
HLE(nn_boss, IsRegistered__Q3_2nn4boss4TaskCFv) { ret(c, 0); }
HLE(nn_boss, Register__Q3_2nn4boss4TaskFRQ3_2nn4boss11TaskSetting) { ret(c, kResultFail); }
HLE(nn_boss, StartScheduling__Q3_2nn4boss4TaskFb) { ret(c, kResultFail); }

// ---- nn_olv (Miiverse)
HLE(nn_olv, Initialize__Q2_2nn3olvFPCQ3_2nn3olv15InitializeParam) { ret(c, kResultFail); }
HLE(nn_olv, IsInitialized__Q2_2nn3olvFv) { ret(c, 0); }
HLE(nn_olv, Finalize__Q2_2nn3olvFv) { ret(c, kResultOk); }

// OliveOperationMgrThread (Miiverse requests: Tingle Bottles, posts, Yeahs) runs a state machine that
// re-posts itself to its own message queue until Miiverse answers, which it never does here: about
// 100k steps a second, a whole host core (on the console it only soaks up idle time on its core).
// A 1 ms nap per step (releasing the guest core) keeps it to ~1000 steps a second.
extern "C" void f_0203DEEC_orig(Cpu* c);


// ---- sockets / curl
HLE(nsysnet, socket_lib_init) { ret(c, 0); }
HLE(nsysnet, socket_lib_finish) { ret(c, 0); }
HLE(nsysnet, NSSLInit) { ret(c, 0); }
HLE(nsysnet, NSSLFinish) { ret(c, 0); }
HLE(nlibcurl, curl_global_init_mem) { ret(c, 0); }
HLE(nlibcurl, curl_global_cleanup) {}

// ---- proc_ui (foreground/background lifecycle)
HLE(proc_ui, ProcUIInit) {}
HLE(proc_ui, ProcUIShutdown) {}
HLE(proc_ui, ProcUIRegisterCallback) {}
HLE(proc_ui, ProcUIDrawDoneRelease) {}
HLE(proc_ui, ProcUIProcessMessages) { ret(c, 0); }  // PROCUI_STATUS_IN_FOREGROUND

// ---- vpad (GamePad): keyboard / host controllers
HLE(vpad, VPADRead) {
    // (chan, VPADStatus* buf, count, int32* error) -> samples written
    uint32_t chan = arg(c, 0), st = arg(c, 1), count = arg(c, 2), err = arg(c, 3);
    // debug: WWHD_TRACE_VPAD=n logs the guest call chain of the first n reads
    static int trace = getenv("WWHD_TRACE_VPAD") ? atoi(getenv("WWHD_TRACE_VPAD")) : 0;
    if (trace > 0) {
        trace--;
        char buf[256];
        int n = snprintf(buf, sizeof buf, "[vpad] read from lr=%08X", c->lr);
        for (uint32_t sp = c->r[1], i = 0; i < 8 && sp; i++) {
            uint32_t prev = ld32(sp);
            if (!prev || prev <= sp) break;
            n += snprintf(buf + n, sizeof buf - n, " <- %08X", ld32(prev + 4));
            sp = prev;
        }
        LOG("%s", buf);
    }
    if (chan != 0 || !st || (int32_t)count <= 0) {
        if (err) st32(err, (uint32_t)-2);  // VPAD_READ_INVALID_CONTROLLER
        ret(c, 0);
        return;
    }
    static uint32_t last_hold = 0;
    interp::trace_read("VPAD");
    // frame interpolation: the read after a logic pass repeats the last sample (interp.cpp)
    static input::PadState last_p;
    const bool repeat = interp::repeat_input();
    input::PadState p = repeat ? last_p : crashrec::read(0);  // live input, recorded/replayed by crash recovery
    if (repeat && interp::fresh_sticks()) {  // true 60: sticks every pass, buttons on full passes
        input::PadState f = input::read();
        p.lx = f.lx; p.ly = f.ly; p.rx = f.rx; p.ry = f.ry;
    }
    last_p = p;
    if (input::pro_controller()) {  // GamePad on the table: screen and touch only
        p.buttons = 0;
        p.lx = p.ly = p.rx = p.ry = 0;
    } else if (!repeat) {
        motion::right_stick(p.rx, p.ry);  // the game ignores the gyro while it is pushed (gyro diagnostics)
    }
    uint32_t hold = p.buttons;
    auto stick_dirs = [&](float x, float y, uint32_t up, uint32_t down, uint32_t left, uint32_t right) {
        // stick-as-button bits with hysteresis, like the real VPAD library
        auto dir = [&](float v, bool held, uint32_t bit) { if (v >= 0.5f || (held && v >= 0.1f)) hold |= bit; };
        dir(-x, last_hold & left, left); if (!(hold & left)) dir(x, last_hold & right, right);
        dir(-y, last_hold & down, down); if (!(hold & down)) dir(y, last_hold & up, up);
    };
    stick_dirs(p.lx, p.ly, 0x10000000, 0x08000000, 0x40000000, 0x20000000);
    stick_dirs(p.rx, p.ry, 0x01000000, 0x00800000, 0x04000000, 0x02000000);
    if (repeat) hold = last_hold;  // buttons (and the stick-as-button bits) change on full passes only

    memset(mem::ptr(st), 0, 0xAC);
    st32(st + 0x00, hold);
    st32(st + 0x04, hold & ~last_hold);   // trig
    st32(st + 0x08, last_hold & ~hold);   // release
    last_hold = hold;
    {  // debug: WWHD_PAD_TRACE=path logs each read: logic step, repeated, buttons, trigger
        static FILE* pt = getenv("WWHD_PAD_TRACE") ? fopen(getenv("WWHD_PAD_TRACE"), "w") : nullptr;
        if (pt) { fprintf(pt, "%llu %d %08X %08X\n", (unsigned long long)interp::logic_steps(), (int)repeat, hold, ld32(st + 4)); fflush(pt); }
    }
    stf32(st + 0x0C, p.lx); stf32(st + 0x10, p.ly);
    stf32(st + 0x14, p.rx); stf32(st + 0x18, p.ry);
    static FILE* sample_trace = getenv("WWHD_VPAD_TRACE") ? fopen(getenv("WWHD_VPAD_TRACE"), "w") : nullptr;
    if (sample_trace) {
        fprintf(sample_trace, "%llu %d %08X %08X %08X %.6f %.6f %.6f %.6f\n",
                (unsigned long long)interp::logic_steps(), (int)repeat, ld32(st),
                ld32(st + 4), ld32(st + 8), ldf32(st + 0x0C), ldf32(st + 0x10),
                ldf32(st + 0x14), ldf32(st + 0x18));
        fflush(sample_trace);
    }
    {  // motion sensors (motion/motion.h). WWHD reads only the direction matrix (0x6C..0x8F): its
       // first-person camera turns by the change from one frame to the next (dCamera_c::CalcSubjectAngle)
        const motion::VpadMotion m = motion::vpad(repeat);
        auto vec = [&](uint32_t at, const motion::Vec3& v) { stf32(at, v.x); stf32(at + 4, v.y); stf32(at + 8, v.z); };
        vec(st + 0x1C, m.acc);
        stf32(st + 0x28, m.acc_magnitude);
        stf32(st + 0x2C, m.acc_variation);
        stf32(st + 0x30, m.acc_xy[0]); stf32(st + 0x34, m.acc_xy[1]);
        vec(st + 0x38, m.gyro);
        vec(st + 0x44, m.angle);
        for (int i = 0; i < 3; i++) vec(st + 0x6C + i * 0xC, m.dir[i]);
    }
    // touch panel, raw coordinates as the hardware reports them (mapping from Cemu)
    static uint16_t last_tx = 0, last_ty = 0;
    if (p.touch) {
        last_tx = (uint16_t)(p.tx * 3883.0f + 92.0f);
        last_ty = (uint16_t)(4095.0f - p.ty * 3694.0f - 254.0f);
    }
    for (uint32_t tp = 0x52; tp <= 0x62; tp += 8) {
        st16(st + tp + 0, last_tx);
        st16(st + tp + 2, last_ty);
        st16(st + tp + 4, p.touch ? 1 : 0);
        st16(st + tp + 6, p.touch ? 0 : 3);  // validity: 0 = valid, 3 = invalid XY
    }
    st8(st + 0xA0, 0xFF); st8(st + 0xA3, 0xFF);                // slide volume
    st8(st + 0xA1, 0xC0);                                      // battery full
    if (err) st32(err, 0);
    ret(c, 1);
}
// raw touch coordinates -> 1280x720 screen space
static void tp_to_screen(uint32_t out, uint32_t raw, int w, int h) {
    int x = std::max<int>(ld16(raw) - 92, 0), y = std::max<int>(4095 - (int)ld16(raw + 2) - 254, 0);
    st16(out, (uint16_t)(x / 3883.0 * w));
    st16(out + 2, (uint16_t)(y / 3694.0 * h));
    st16(out + 4, ld16(raw + 4));
    st16(out + 6, ld16(raw + 6));
}
HLE(vpad, VPADGetTPCalibratedPoint) { input::note_touch_read(); tp_to_screen(arg(c, 1), arg(c, 2), 1280, 720); }
HLE(vpad, VPADGetTPCalibratedPointEx) {
    input::note_touch_read();
    int res = (int)arg(c, 1);  // 0 = 1920x1080, 1 = 1280x720, 2 = 854x480
    tp_to_screen(arg(c, 2), arg(c, 3), res == 0 ? 1920 : res == 2 ? 854 : 1280, res == 0 ? 1080 : res == 2 ? 480 : 720);
}
// The GamePad motor: the game sends a pattern of up to 120 bits (not bytes), played at 120 bits a
// second; it stops by itself at the end, and an empty pattern or VPADStopMotor stops it at once.
// rumble.h turns that into what the host controllers' motors do.
HLE(vpad, VPADControlMotor) {
    // (chan, uint8* pattern, uint8 length in bits) -> int32 error
    uint32_t chan = arg(c, 0), pattern = arg(c, 1), nbits = std::min<uint32_t>(arg(c, 2) & 0xFF, rumble::Motor::kMaxBits);
    uint8_t bits[rumble::Motor::kMaxBits / 8] = {};
    for (uint32_t i = 0; pattern && i < (nbits + 7) / 8; i++) bits[i] = ld8(pattern + i);
    TRACE("[pad] VPADControlMotor(%u, %u bits)", chan, nbits);
    rumble::gamepad_pattern(chan, pattern ? bits : nullptr, nbits);
    ret(c, 0);
}
HLE(vpad, VPADStopMotor) { rumble::gamepad_stop(arg(c, 0)); }
HLE(vpadbase, VPADBASEGetHeadphoneStatus) { ret(c, 0); }

// ---- padscore (Wii Remote / Pro Controller): see padscore.cpp

// ---- sysapp
HLE(sysapp, SYSLaunchSettings) { ret(c, 0); }
HLE(sysapp, SYSLaunchAccount) { ret(c, 0); }

// ---- Super Mario 3D World additions (docs/sm3dw-study.md) ----
// MCP: the game asks the console's product area (the USA game picks its UsEn/UsEs/UsFr language
// folders from it); an unimplemented MCP_GetSysProdSettings left it reading garbage (JpJa).
HLE(coreinit, MCP_Open) { ret(c, 1); }
HLE(coreinit, MCP_Close) { ret(c, 0); }
// The console's area follows the installed game's own region (meta/meta.xml <region>: 1 Japan,
// 2 USA, 4 Europe), so a European disc finds its Eu* language folders; USA when it can't be read.
static uint32_t game_region_bits() {
    static const uint32_t bits = [] {
        uint32_t r = 2;
        if (FILE* f = fopen((config::game_dir + "/meta/meta.xml").c_str(), "rb")) {
            std::string xml(1 << 16, '\0');
            xml.resize(fread(&xml[0], 1, xml.size(), f));
            fclose(f);
            size_t at = xml.find("<region");
            if (at != std::string::npos && (at = xml.find('>', at)) != std::string::npos) {
                const uint32_t v = (uint32_t)strtoul(xml.c_str() + at + 1, nullptr, 16);
                if (v == 1 || v == 2 || v == 4) r = v;
            }
        }
        LOG("[config] console area %s (from the game's meta.xml)", r == 1 ? "Japan" : r == 4 ? "Europe" : "USA");
        return r;
    }();
    return bits;
}

HLE(coreinit, MCP_GetSysProdSettings) {
    uint32_t out = arg(c, 1);
    if (out) {
        const uint32_t area = game_region_bits();
        memset(mem::ptr(out), 0, 0x20);
        st32(out + 0, area);  // product_area
        st32(out + 4, area);
        st32(out + 8, area);  // game_region
    }
    ret(c, 0);
}
// SAVEGetSharedDataTitlePath(titleId (r3:r4), dir (r5), buf (r6), size (r7)): the path of another
// title's shared data (system fonts and the like); the folder does not exist here.
HLE(nn_save, SAVEGetSharedDataTitlePath) {
    uint32_t dir = arg(c, 2), buf = arg(c, 3), size = arg(c, 4);
    char path[256];
    snprintf(path, sizeof path, "/vol/storage_mlc01/sys/title/%08x/%08x/content/%s", arg(c, 0), arg(c, 1),
             dir ? mem::read_cstr(dir).c_str() : "");
    if (buf && size) mem::write_cstr(buf, path, size);
    ret(c, 0);
}

// Input extras the game calls at start (no pointing device / speaker here), and small answers it logs
HLE(padscore, KPADEnableDPD) { ret(c, 0); }
HLE(padscore, KPADDisableDPD) { ret(c, 0); }
HLE(padscore, WPADInit) {}
HLE(padscore, WPADRegisterAllocator) {}
HLE(padscore, WPADGetSpeakerVolume) { ret(c, 0); }
HLE(snd_core, AXSetPpcLoadLimit) { ret(c, 0); }
HLE(vpad, VPADInit) {}

// Account / online queries the game makes at boot; no account, no network
HLE(nn_act, IsNetworkAccount__Q2_2nn3actFv) { ret(c, 0); }
HLE(nn_act, GetTransferableIdEx__Q2_2nn3actFPULUiUc) {  // (uint64_t* out, ...): nothing to give, say so
    if (uint32_t out = arg(c, 0)) { st32(out, 0); st32(out + 4, 0); }
    ret(c, kResultFail);
}
HLE(sysapp, _SYSGetSystemApplicationTitleId) { ret64(c, 0); }
// Miiverse data holders: the constructors return `this` (r3 stays), the result query reports failure
HLE(nn_olv, __ct__Q3_2nn3olv18DownloadedPostDataFv) {}
HLE(nn_olv, __ct__Q3_2nn3olv19DownloadedTopicDataFv) {}
HLE(nn_olv, GetResultByPostApp__Q2_2nn3olvFv) { ret(c, kResultFail); }

// ---- the rest of the console's services the game calls (docs/native-levels.md): each one has an answer, so the log of a
// boot carries no "unimplemented" and no output the game reads is left as stack garbage

// SAVEGetSharedSaveDataPath(titleId (r3:r4), dir (r5), buf (r6), size (r7)): where another title's shared save data
// would be (the Mii database); the folder does not exist, opening it fails like on a console without it
HLE(nn_save, SAVEGetSharedSaveDataPath) {
    uint32_t dir = arg(c, 2), buf = arg(c, 3), size = arg(c, 4);
    char path[256];
    snprintf(path, sizeof path, "/vol/storage_mlc01/usr/save/%08x/%08x/user/common/%s", arg(c, 0), arg(c, 1),
             dir ? mem::read_cstr(dir).c_str() : "");
    if (buf && size) mem::write_cstr(buf, path, size);
    ret(c, 0);
}

// Microphone (libmic): runtime/src/hle/libmic.cpp

// ---- accounts: one local account without a network identity or a Mii
// GetMiiName(wchar16* out): the name shown for the player
HLE(nn_act, GetMiiName__Q2_2nn3actFPw) {
    static const char16_t name[] = u"Player";
    if (uint32_t out = arg(c, 0)) {
        for (uint32_t i = 0; i < 7; i++) st16(out + 2 * i, i < 6 ? (uint16_t)name[i] : 0);
    }
    ret(c, kResultOk);
}
HLE(nn_act, GetMii__Q2_2nn3actFP12FFLStoreData) { ret(c, kResultFail); }  // no Mii: the game goes on without one
HLE(nn_act, GetPersistentIdEx__Q2_2nn3actFUc) { ret(c, 0x80000001u); }    // the account's id, constant (it can end up in the save)
HLE(nn_act, GetAccountId__Q2_2nn3actFPc) {
    if (uint32_t out = arg(c, 0)) st8(out, 0);
    ret(c, kResultFail);
}
HLE(nn_act, GetTransferableId__Q2_2nn3actFUi) { ret64(c, 0); }
HLE(nn_act, AcquireNexServiceToken__Q2_2nn3actFP26ACTNexAuthenticationResultUi) { ret(c, kResultFail); }
HLE(nn_act, GetErrorCode__Q2_2nn3actFRCQ2_2nn6Result) { ret(c, 0); }
HLE(nn_act, HasNfsAccount__Q2_2nn3actFv) { ret(c, 0); }

// ---- network: never connected, so anything that needs an address says there is none
HLE(nn_ac, GetAssignedAddress__Q2_2nn2acFPUl) {
    if (uint32_t out = arg(c, 0)) st32(out, 0);
    ret(c, kResultFail);
}
HLE(nn_ac, GetAssignedSubnet__Q2_2nn2acFPUl) {
    if (uint32_t out = arg(c, 0)) st32(out, 0);
    ret(c, kResultFail);
}
HLE(nn_acp, ACPMiiWrap) { ret(c, 0xFFFFFFFFu); }
HLE(nn_acp, ACPMiiUnwrap) { ret(c, 0xFFFFFFFFu); }
HLE(nn_ndm, Finalize__Q2_2nn3ndmFv) { ret(c, kResultOk); }
HLE(nn_ndm, ResumeDaemons__Q2_2nn3ndmFUi) { ret(c, kResultOk); }
// SpotPass: a task result built from a Result, whose error code is none; unregistering a task that never was fails
HLE(nn_boss, __ct__Q3_2nn4boss10TaskResultFQ2_2nn6Result) {
    if (arg(c, 0)) st32(arg(c, 0), arg(c, 1));
    ret(c, arg(c, 0));
}
HLE(nn_boss, ErrorCode__Q3_2nn4boss10TaskResultCFv) { ret(c, 0); }
HLE(nn_boss, Unregister__Q3_2nn4boss4TaskFv) { ret(c, kResultFail); }

// ---- friends (nn_fp): the player never signs in. The async calls (LoginAsync, RegisterAccountAsync) are in
// sm3dw_system.cpp: they answer through a callback.
static bool g_fp_initialized = false;
HLE(nn_fp, Initialize__Q2_2nn2fpFv) { g_fp_initialized = true; ret(c, kResultOk); }
HLE(nn_fp, IsInitialized__Q2_2nn2fpFv) { ret(c, g_fp_initialized ? 1 : 0); }
HLE(nn_fp, GetMyPrincipalId__Q2_2nn2fpFv) { ret(c, 0); }
HLE(nn_fp, GetFriendList__Q2_2nn2fpFPUiT1UiT3) {  // (pids*, count*, start, max)
    if (uint32_t count = arg(c, 1)) st32(count, 0);
    ret(c, kResultFail);
}
HLE(nn_fp, ResultToErrorCode__Q2_2nn2fpFQ2_2nn6Result) { ret(c, 0); }

// ---- lifecycle and hardware the game touches once at start
HLE(proc_ui, ProcUIInitEx) {}
HLE(proc_ui, ProcUIClearCallbacks) {}
HLE(coreinit, OSEnableForegroundExit) {}
HLE(coreinit, OSSetScreenCapturePermission) { ret(c, 0); }
// IMIsAPDEnabledBySysSettings(int* enabled): auto power down is not enabled by the system settings here
HLE(coreinit, IMIsAPDEnabledBySysSettings) {
    if (uint32_t out = arg(c, 0)) st32(out, 0);
    ret(c, 0);
}
// bspGetHardwareVersion(uint32_t* version): the game reads a byte of what it gets back
HLE(coreinit, bspGetHardwareVersion) {
    if (uint32_t out = arg(c, 0)) st32(out, 0);
    ret(c, 0);
}
// a driver library the game carries registers itself with the OS and reads hardware registers through these
HLE(coreinit, OSDriver_Register) { ret(c, 0); }
HLE(coreinit, OSEnforceInorderIO) {}
HLE(coreinit, OSReadRegister16) { ret(c, 0); }
HLE(coreinit, OSWriteRegister16) {}
HLE(coreinit, __OSReadRegister32Ex) { ret(c, 0); }
HLE(coreinit, __OSWriteRegister32Ex) {}
// Wii Remote rumble encoding (the 3D World Pro Controller does not use it)
HLE(padscore, WENCGetEncodeData) { ret(c, 0); }
