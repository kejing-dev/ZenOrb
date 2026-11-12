# 呼吸禅境 ZenOrb: project guidance

A breathing orb on the desk for PICO OS 6 Shared Space (Spatial SDK BOM 6.1.9), package `io.embodit.zenorb`, v0.1.0.
Design contract: spatial-app-factory `apps/zen-orb/design-package.json` v4 (gate B round 2, 2026-11-12: default style
oat glaze & walnut, switchable brass & basalt / moss & slate; hints beside the orb + one control window; pinch, lift to
breathe in, lower to breathe out; own 3D models; v1 = desk volume only; focus timer kept, no "today" page).
Skeleton from aura-garden (same archetype A: volume + Placement.right control window, theme, SoundPool, board-input).
GitHub: `kejing-dev/ZenOrb` (private).

## Surfaces
- **Orb volume** (default container `ZenOrbVolume`, Volumetric 0.32 x 0.30 x 0.26 m, fixed scale, gravity aligned,
  uniform resize). Content root = volume floor centre, (0, -0.15, 0) from the volume centre; +Z toward the user.
  Cradle GLB, orb GLB under the `orb` holder (sphere collider r 0.060, Interactable, HoverEffect), guide ring (true
  torus, scaled to r(f*) + 0.012), focus arc (36 x 10 deg segments on the cradle rim), the tag right of the orb and the
  caption in front of the cradle (text drawn into textures, light text + dark outline, no plate), opaque contact shadow,
  warm key light (1500 lux-scale).
- **Control window** (`ZenOrbControl`, Planar 420 x 424 dp, `Placement.right(ZenOrbVolume)`), no TabBar: header
  (title, [静音], [设置]); pages home S02 / welcome S01 / breath S03r S03 S05 / summary S04 / focus S06 S06p / focus done
  S07 / settings S09 (pushed) / confirm (quit S11, clear, give up) / S00 loading / S10 volume closed.
  Closing it with the system x is fine: pinching the orb starts the last rhythm (T14); it comes back at focus end and
  when the launcher icon is tapped again.

## Code
| Path | What |
|---|---|
| `domain/Breath.kt` | geometry constants (`Geo`), rhythms (`PatternId`), the guide curve (`Curve`), the forgiving `SyncJudge` |
| `domain/Flow.kt` | the state machine: breath session x focus session, `OrbView` for the volume, `UiState` for the window |
| `domain/Hints.kt` | every word beside the orb (zh / en) |
| `domain/Settings.kt` | settings, styles, day log, focus block |
| `data/Stores.kt` | `files/zenorb.json` (atomic) + settings prefs |
| `ui/orb/*` | `OrbScene` (entities, per-frame copy from the ECS system), `OrbScreen` (SpatialView + input), `Meshes`, `HintTex`, `input/BoardInput.kt` (factory template) |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut |
| `tools/` | `build_models.py` (3 styles -> GLB, port of design `zen-orb_3d.js`), `make_sounds.py` (CC0 recordings), `build_icon.py`, `loudness.py`, `zo.sh` |

Input (container-agnostic, D53): the board-input template resolves hits to `orb`; a tap = start (T4), a drag = take the
orb: `Flow.dragBegin()` / `dragMove(dyContentMetres)` / `dragEnd()`. Only the vertical travel counts; it is divided by
the volume's world scale (view px -> content metres at the press). A Full Space hand input would call the same three.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest      # JVM tests: sync judge (13 sloppy scripts x 4 rhythms), curve, flow, save, layout / occlusion, hit replay, contrast
~/.factory-venv/bin/python tools/build_models.py ; ~/.factory-venv/bin/python tools/make_sounds.py ; ~/.factory-venv/bin/python tools/build_icon.py --icon512 icon.png
DEV=emulator-5564 tools/zo.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow zen-orb` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.zenorb.DEBUG -p io.embodit.zenorb --es cmd <cmd> --es a "<arg>"` (or
`tools/zo.sh cmd <cmd> [arg]`). Log tag `ZenOrb`. Every command calls the same Flow methods as the buttons / 3D input.

| State | How |
|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 12 s, `s00hold off` |
| S01 第一次 | `fresh` (or `pm clear`) + relaunch; demo loops while the welcome is up |
| S02 空闲 | `tap`, `tap`, `end`, `done` (gets past the welcome) |
| S03r 准备 | `start CALM` (or `tap` on idle) |
| S03 跟着 | `tap` (or `grab 0`) then `follow "lag=0.4 amp=0.9"` (drives the drag path every 33 ms); `hold "phase=in f=0.55 inband=true"` / `inband=false` for stills; `unhold` |
| S03p 出带 | `stopfollow`, `grab 0`, wait 2.5 s -> 「再提一点」; `release`, wait 2 s -> 「捏住接着来」 (then `hold "f=0.05"`) |
| S03h 闭眼跟 | `handsfree on`, `start CALM`, wait 4 s |
| S04 小结 | `end` during S03 (or let the round finish) |
| S05 暂停 | `pause` |
| S06 / S06p | `focus 25` / `focuspause`; `focusfast on` before `focus` = 1 min per second |
| S07 专注到点 | `focusfast on`, `focus 15`, wait 16 s |
| S09 设置 | `page settings` |
| S11 / 清空 / 放弃 | `confirm quit` / `confirm clear` / `giveup` (`giveupok` = its [确定]) |
| S10 光球收起 | `closevolume`; `bring` |
Other: `style glaze|brass|moss`, `pattern calm|box|sleep`, `lang zh|en|system`, `sound on|off`, `volume 0..2`,
`closepanel`, `openpanel`, `testf on|off` (F-up test on the words), `seek <s>`, `dump`.
Screenshot list: `apps/zen-orb/build/shots/<state>-<zh|en>.png` (script in the build station's scratchpad, steps above).

## Known
- 3D drags / pinches can't be sent by `pico-cli interact` on the Mac emulator: driven by the debug broadcast through
  the same Flow calls; hand ray / controller / eye-hand need the headset (D53: Shared Space controller ray / eye-hand on
  3D entities is a known platform risk, see factory feedback).
- Words beside the orb face +Z (the volume front; the system turns the volume to the user). No head pose is read.
- The volume does not know its distance, so the design's S01 「把光球拖近一点」 line (volume > 1.2 m) is not shown.
