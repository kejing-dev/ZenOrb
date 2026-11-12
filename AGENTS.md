# 禅沙庭园 Aura Garden: project guidance

A round kinetic sand table on the desk for PICO OS 6 Shared Space (Spatial SDK BOM 6.1.9), package `io.embodit.auragarden`,
v0.1.0. Design contract: spatial-app-factory `apps/aura-garden/design-package.json` v4 (gate B round 2, 2026-11-12: option A,
one table + [看它画 | 我来耙], first screen = watch; 46 cm desk piece; three frames; v1 = draw a line, the ball follows).
Skeleton from klotski-box (CloudAir lineage: two containers, theme, sound pool, board-input template); the sand,
patterns, ball and rake are new.

## Surfaces
- **Table volume** (default container `AuraTable`, Volumetric 0.52 x 0.22 x 0.52 m, fixed scale, gravity aligned, uniform
  resize). Table-local frame: origin at the sand base plane centre, (0, -0.060, 0) from the volume centre. Lathe frame
  (walnut / oak / black steel), 60 dynamic sand chunks (201 x 201 height field, 2 mm), steel ball (r 5 mm), LED band
  (unlit, 20 colours + off), three Tripo stones (rake mode only), rake (while raking), warm preview line.
- **Control window** (`AuraControl`, Planar 480 x 560 dp, `Placement.right(AuraTable)`), no TabBar: home (watch or rake),
  Adjust and Settings sub-pages with [返回]. [重开控制窗] closes and reopens beside the table; [退出应用] (tap twice) closes
  both containers, removes the tasks, exits.

## Code
| Path | What |
|---|---|
| `domain/Geom.kt` | constants, theta-rho helpers, `Track` (densified polyline + arc length) |
| `domain/Patterns.kt` | P1..P6 + clearing spiral E generators, `Seg` (deterministic path piece: rebuildable after restart) |
| `domain/Sand.kt` | height field, capsule stamping with the 24 mm nearest-segment window, ball / rake profiles, wells, smoothing, dirty chunks |
| `domain/Flow.kt` | the state machine S00..S25 (synchronized; ticked by the volume's ECS system), stones / rake / line view |
| `domain/Save.kt` | `files/sand.bin` codec (header + uint16 µm heights, deflated) |
| `ui/table/*` | `TableScene` (entities, materials, per-frame copy, <= 2 chunk rebuilds / frame), `TableScreen` (SpatialView + input), `Meshes`, `Palette` |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut |
| `tools/` | `build_stones.py` (Tripo stones calibrated, D25), `build_textures.py`, `make_sounds.py` (CC0 library), `build_icon.py`, `loudness.py`, `ag.sh` |

Volume input goes through the factory board-input template (`ui/table/input/BoardInput.kt`, + `onPress` for the press point):
holders `sand` (box collider over the disc) and `ball` (sphere r 0.031, table mode only). Press point = raw pointer
`position3D` converted with `convertPosition(Local -> localSpatial)`; drag = start + (dX, dZ - dY) x (entity m / physical m).

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # 35 JVM tests: patterns, sand profiles / stamp rule / masks / smoothing, flow, save, hit replay, look
~/.factory-venv/bin/python tools/build_stones.py ; ~/.factory-venv/bin/python tools/make_sounds.py ; ~/.factory-venv/bin/python tools/build_icon.py --icon512 icon.png
DEV=emulator-5560 tools/ag.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow aura-garden` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.auragarden.DEBUG -p io.embodit.auragarden --es cmd <cmd> --es a "<arg>"` (or `tools/ag.sh cmd <cmd> [arg]`). Log tag `AuraGarden`.

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 12 s, `s00hold off` | loading page + flat sand |
| S01 接着上次 | force-stop, relaunch (hint for 6 s) | watch and rake saves |
| S10 看它画 | `goto S10` · `pattern P1..P6` + `progress 0.7` | first run = five petals at 60 % |
| S11 暂停 | `goto S11` (= the pause button) · `tapball` | |
| S12 清空中 | `goto S12` (= [下一幅]) · `progress 0.5` | |
| S13 照你画 | `goto S13` (S-line through dragBegin/Move/End) · `hline "x,z;x,z;…"` | |
| S14 调整 | `goto S14` / `page adjust` · `light T B` (0..4 0..4) · `speed 1..5` · `choose P3` | |
| S20 落石 | `goto S20` (holds with stones in the air) · `hold off` | |
| S21 我来耙 | `goto S21` / `mode rake` | |
| S22 耙沙中 | `goto S22` (rake held mid-stroke; `hold off` releases) · `stroke "x,z;…"` | |
| S23 抹平确认 | `goto S23` | 3 s |
| S24 抹平中 | `goto S24` (held halfway) · `hold off` | |
| S25 收石 | `goto S25` (held) · `hold off` | |
| S30 设置 | `goto S30` / `page settings` · `exitask` (quit armed) | |
| S31 桌子收起 | `goto S31` (closes the default container) · `bring` | |
Other: `frame walnut|oak|steel`, `lang zh|en|system`, `sound on|off`, `volume 0..2`, `reduced on|off`, `closepanel`, `reopen`,
`save`, `fresh` (clears the save, first-run sand), `dump`.

## Known
- 3D drags / pinches can't be sent by `pico-cli interact` on the Mac emulator: driven by the debug broadcast through the
  same Flow calls; hand ray / controller / eye-hand need the headset.
- Ball loop: Spatial SDK audio on the ball entity when `Entity.playAudio` works (log `PROBE spatial audio`), else SoundPool.
- Sounds are CC0 Kenney recordings; ball_roll / rake are stand-ins (paper / snow textures), see build/sound-sources.md.
