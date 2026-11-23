// GX2 surface layout (Cemu's AddrLib). A surface whose format has no size (0, or a code the hardware lacks) used to divide by
// zero inside the library and stop the process on x86; Super Mario 3D World hands one over at the end of a course. Such a surface
// has nothing to lay out and must not take the game down; a normal one still gets its real size.
#include "cemu_shim.h"
#include "Cafe/OS/libs/gx2/GX2_Surface.h"
#include "gx2_surface_calc.h"
#include <cassert>
#include <iostream>
#include <string>

static int g_logged = 0;
void cemu_shim_log(const std::string&) { ++g_logged; }

static GX2Surface make(uint32 format, uint32 width, uint32 height, uint32 aa = 0) {
    GX2Surface s;
    memset(&s, 0, sizeof s);
    s.dim = Latte::E_DIM::DIM_2D;
    s.width = width; s.height = height; s.depth = 1; s.numLevels = 1;
    s.format = (Latte::E_GX2SURFFMT)format;
    s.aa = aa;
    s.tileMode = Latte::E_GX2TILEMODE::TM_2D_TILED_THIN1;
    return s;
}

int main() {
    // a normal surface: RGBA8 (0x1A), 256x256: 256 KiB
    GX2Surface good = make(0x1A, 256, 256);
    gx2calc::CalcSurfaceSizeAndAlignment(&good);
    assert((uint32)good.imageSize == 256 * 256 * 4 && (uint32)good.alignment > 0 && (uint32)good.pitch >= 256);
    assert(g_logged == 0);
    // formats the library has no size for: 0 (never set) and the codes that used to stop the process (found by running every
    // code in its own process: 0x04, 0x21, 0x24-0x30, 0x36-0x3F, with or without the flag bits above 6)
    for (uint32 format : {0u, 0x04u, 0x21u, 0x24u, 0x27u, 0x2Cu, 0x2Fu, 0x30u, 0x36u, 0x3Fu, 0x44u, 0x84u}) {
        GX2Surface bad = make(format, 256, 256);
        gx2calc::CalcSurfaceSizeAndAlignment(&bad);  // used to be SIGFPE
        assert((uint32)bad.imageSize == 0 && (uint32)bad.alignment >= 1);
        GX2Surface mips = make(format, 256, 256);
        mips.numLevels = 5;                          // the mip loop takes the alignment modulo: it must not be zero
        gx2calc::CalcSurfaceSizeAndAlignment(&mips);
        assert((uint32)mips.imageSize == 0);
    }
    assert(g_logged >= 1);                           // said what it got, once (the rest stay quiet)
    assert(g_logged == 1);
    std::cout << "surface_layout_test: ok\n";
    return 0;
}
