# Super Mario 3D World at 120 fps (work in progress)

Optional, off by default. The game logic stays at 60 steps per second; every other presented
frame is an in-between ("hold") frame that runs no logic.

## Turning it on

- `WWHD_FPS=120` at start; `sm3dw_interp::set_fps(120 | 60)` (runtime/src/sm3dw/interp_sm3dw.h)
  switches it live from the next frame (for the Android menu).
- `WWHD_FPS_BLEND=0`: in-between frames repeat the last logic picture (pacing tests only).
- `WWHD_FPS_HOLD_DRC=1`: in-between frames replay the GamePad list too (default: TV only).
- `WWHD_FPS_STATS=1` (or `WWHD_PROFILE=1`): `[fps120]` line every 600 frames (frames/s, logic
  steps/s, share of in-between frames, late frames, game-thread ms per frame kind).
- Debug: `WWHD_FPS_TRACE=a-b`, `WWHD_FPS_CAMERA_TRACE=a-b`, `WWHD_FPS_NO_SHED=1`,
  `WWHD_FPS_TEST_ALTERNATE=1` (strict logic/hold alternation for picture tests; changes game
  speed), `WWHD_SM3DW_PROBE=1` (frame-loop and uniform probe), `WWHD_FPS_BONES=0`,
  `WWHD_FPS_CAMERA_CUT=units`.

## Frame loop (verified at run time, WWHD_SM3DW_PROBE)

`procFrame_` 0x024DB2B8 runs, in this order: `procDraw_` 0x024DB728 (GX2DirectCallDisplayList of
list[+0x388], TV then DRC, then +0x388 ^= 1), `procCalc_` 0x02374FA0 (logic), record 0x024DBA84
(into list[+0x388]; +0x380 = 1, display-list mode), vtable +0x6C, `swapBuffer_` 0x024DB93C
(GX2DrawDone, GX2SwapScanBuffers, GX2DrawDone), the vsync wait 0x02375030 (GX2WaitForVsync until
every swap flipped). Lists: TV 0x152484E0 / 0x15648520, DRC 0x15448500 / 0x15848540 on the map.
Models: calcWorld 0x024E7574 and calcView 0x024E762C run inside procCalc_ (48 models on the World 1
map), the uniform ring index (0x024E76DC) advances before calcView, one camera view matrix
(0x46438B94 on the map).

## In-between frame

Hook of procFrame_: at 120 fps a wall-clock accumulator decides per wake (one per presented frame)
between a logic frame (the original procFrame_) and a hold: draw lock, procDraw_'s TV half only
(0x024DB6B4 with the TV list of the current index), the index put back, swapBuffer_, vsync wait.
The virtual vsync ticks at 119.88 Hz (interp::vsync_rate() = 2). Frames that come late pause the
in-between frames (back to the 59.94 Hz grid) for 3 s, doubling up to 60 s.

## Camera and skeletons (partial)

- Skeletons: the hold reruns calcView(model, view) with the world matrices halfway between the
  last two logic steps (SkeletonObj +0x10, count u16 +0x2C) and puts them back after the replay.
- Camera: the draws do NOT take the camera from the game's calcView or from one known block. It is
  in uniform blocks of several layouts (0x150 block 8: view, projection, projection*view; 0x100
  block 2: projection*view, inverse view; 0x300 effect blocks) and, for most model shaders, in
  uniform registers set row by row (one vec4 per GX2SetVertexUniformReg) from display lists
  recorded elsewhere. So the blend runs on the render thread (gx2/interp_camera.cpp): in a hold,
  every uniform block and register-built uniform buffer is scanned for 4x4 windows equal to
  projection*view of a camera seen in the previous logic frame (projection^-1 * w rigid) and 3x4
  views close to it, and those are replaced by the camera halfway. Measured: the camera position in
  the replaced blocks is exactly halfway (WWHD_FPS_CAMERA_TRACE); in pictures the in-between frame
  moves 0.25 of a step instead of 0.5: some draws still read an unblended camera (open work).
