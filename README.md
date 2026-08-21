# Mandela

An adaptive performance engine for Minecraft, for Fabric on 1.21.11.

Most performance mods make the game faster and stop there. Mandela starts from a different
question: **what frame rate do you want, and what is the least you can give up to hold it?** It
measures the frame time continuously, spends a visual budget to keep the target, and can search your
actual machine for the best settings it can genuinely sustain.

It is not a renderer rewrite and does not try to be one. It sits alongside mods like Sodium: they
make each frame cheaper, Mandela decides how much work to put in a frame. Both are useful and they
solve different problems.

---

## What it does

### 1. Holds a frame-rate target

A closed loop measures the 95th percentile frame time and trades detail to keep it under budget. It
steers on p95 rather than an average because an average that meets the target while one frame in ten
misses it still feels bad — and the average is exactly the statistic that hides it.

When quality has to fall, it falls in a designed order: rain thins before particles, particles before
entity distance, entity distance before render distance. Render distance is what people set
deliberately and stare at, so it moves last and never falls more than 6 chunks.

Three things stop it oscillating, which is the failure that makes adaptive quality unbearable:

- **It settles before re-deciding.** After a change, it waits for frames rendered under the *new*
  settings to fill the measurement window. Skipping this is why naive implementations cut, read a
  frame time that still describes the old settings, cut again, and end up at the floor when one step
  would have done.
- **Steps are proportional.** Each cut corrects a fraction of the measured overshoot, so the step
  shrinks as the error does.
- **It ignores spikes.** A chunk rebuild or a GC pause is not draw load, and turning quality down
  does not prevent the next one. Spikes are detected, reported separately, and kept out of the
  control signal.

### 2. Culls entities hidden behind terrain

Minecraft already works out which chunk sections are hidden — that is how terrain behind a hill
avoids being drawn — and then ignores that conclusion for entities. A hundred mobs in a cave under a
mountain are all in front of the camera and all invisible, and every one costs a render state
extraction and a draw.

Mandela reads the renderer's own visible-section set and skips entities that are not in it. It costs
one hash lookup per entity, needs no occlusion queries, and is exactly as accurate as the terrain
culling it comes from. Re-tests are staggered across frames, and an entity must be hidden for several
consecutive frames before it is dropped — appearing is instant, disappearing is not, because a
wrongly drawn entity costs microseconds and a wrongly culled one is a visible bug.

### 3. Budgets block entities

A storage room is a few hundred chests with animated lids; a shop district is a wall of signs whose
text is laid out every frame. Vanilla offers one distance slider that keeps all of them or none.

Mandela scores each by how much of the screen it can account for and admits them against a per-frame
budget, so what survives under pressure is the near and the large rather than whatever came first in
iteration order. The threshold adapts in *rate* space — comparing the fraction of candidates that
clear the bar against the fraction the budget can afford — which converges on the right cutoff
regardless of how the scores happen to be distributed.

### 4. Tunes itself to your machine — `/mandela autotune`

This is the part with no real equivalent elsewhere.

Every performance guide and "optimised config" answers the wrong question. They rank settings by how
expensive they are *in general*, when what matters is how expensive they are on *your* GPU, at *your*
resolution, with *your* other mods, in the kind of place *you* actually play. Those differ enough
that general advice is often exactly backwards: render distance is nearly free on a machine that is
CPU-bound on entities, and ruinous on the machine next to it.

So Mandela measures instead. It:

1. measures the cheapest configuration, to check your target is reachable at all;
2. measures what one step up *each* setting costs on your machine;
3. repeatedly buys whichever step gives the most perceived quality per microsecond of frame time,
   re-measuring as it goes, until the budget runs out.

That is a budgeted knapsack solved against measured prices, and it is why the result is
hardware-specific rather than a preset with extra steps. Buying in order of how much a setting is
*missed* — the obvious approach — behaves badly: on a terrain-bound machine it spends the whole
budget on render distance and has nothing left for entity distance, which on that machine was nearly
free. Dividing by the measured price is what fixes that, and there is a test that holds it to it.

If your target is out of reach even at the lowest settings, it says so plainly instead of handing you
a bare world and letting you wonder.

### 5. Throttles when nobody is looking

Vanilla already caps the frame rate when minimised and after a long idle. It does not cover the
common two-monitor case — window visible, player reading something else, machine rendering 300 frames
a second nobody sees — and its thresholds are fixed constants, which is no help on a laptop battery.
Mandela adds that case and makes the thresholds configurable, and always takes the *lower* of its cap
and vanilla's, so it can only ever ask for less work than the game already decided to do.

### 6. Tells you what it is doing

An adaptive system that changes the picture without explaining itself is indistinguishable from a
bug. The overlay (`/mandela hud full`) shows the frame-time trace with the target drawn across it,
the percentiles, what the governor is doing and why, and how much is being culled.

---

## Commands

| Command | Effect |
| --- | --- |
| `/mandela` | Current state: frame rate, 1% low, quality, what is being culled |
| `/mandela target <fps>` | Set the frame-rate target |
| `/mandela autotune [fps]` | Search for the best settings this machine can sustain |
| `/mandela mode adaptive\|fixed\|observe` | Steer continuously, use a fixed profile, or measure only |
| `/mandela hud off\|compact\|full` | Diagnostic overlay |
| `/mandela on` / `off` | Master switch |
| `/mandela cancel` | Stop a running tune |

`observe` mode is worth knowing about: it measures and reports without changing anything, which is
how you find out whether Mandela would help before letting it touch the picture.

Settings live in `config/mandela.properties` as commented, hand-editable text. Bad values fall back
to defaults with a warning in the log rather than stopping the game from starting.

---

## How it is built

Two modules, split along a line that matters:

- **`core/`** — the engine. Frame-time statistics, the control loop, the degradation ladder, the
  visibility cache, the render budget, the autotuner. **Zero dependencies, no Minecraft.** Every
  decision Mandela makes is made here, which is why all of it can be tested properly.
- **`fabric/`** — the adapter. Mixins, the HUD, commands, config plumbing. It converts game state
  into engine input and engine output into rendering decisions, and holds no logic of its own.

### Testing

110 tests, all passing, against the engine rather than against mocks of the game:

- The control loop is driven against a **synthetic machine** whose frame cost is a function of the
  settings, so convergence, settling, spike rejection and recovery are all verified end to end. One
  test specifically pins the overshoot behaviour, because that bug is invisible in an
  average-frame-rate measurement and ruins the picture.
- The autotuner is run against machines with **opposite bottlenecks**, and asserted to reach
  different answers on each — if it converged on the same settings regardless, it would be a preset
  wearing a costume. Another test asserts it keeps more visual quality than the best the fixed
  degradation ladder can manage at the same frame rate.
- The primitive hash map behind the visibility cache is checked against a `HashMap` oracle over
  200,000 randomised operations, because backward-shift deletion fails in ways that look like nothing
  until an entry becomes unreachable.

### Build-time mixin verification

`./gradlew build` fails if any mixin targets a method that does not exist in the game.

This guards against the specific way optimisation mods die after a game update: a renamed vanilla
method makes the mixin compile, ship, and silently never apply — so the mod loads, reports itself as
working, and does nothing. The annotation processor knows, but only says so as a warning in a
thousand-line log. `verifyMixinTargets` reads back what it resolved and turns a miss into a build
failure. All 12 targets currently resolve against 1.21.11.

```bash
./gradlew build     # jar at fabric/build/libs/mandela-0.1.0.jar
./gradlew test      # engine tests
```

Requires JDK 21. The Gradle wrapper handles the rest.

---

## Honest limitations

- **It has not been run in a live game.** It was developed in a headless environment with no display
  and no game assets. Everything here is verified by the engine tests and by build-time resolution of
  every mixin target against the real 1.21.11 classes — which is a great deal more than "it compiles",
  and still not the same as playing it. Treat the first session as a shakedown, and run with
  `mode observe` first if you want to see the measurements before anything is changed.
- **Culling is section-granular.** An entity leaning out of a hidden section into a visible one could
  be culled wrongly. The grace frames and the AABB spanning exist to make that rare rather than
  impossible.
- **Autotune needs you to stand still.** Configurations can only be ranked if they were measured
  looking at the same thing. Moving or turning mid-trial invalidates the sample and it is retried
  rather than believed, but that means walking around during a tune just makes it take longer.
- **Weather is on/off, not continuous.** The weather pass is skipped below a density threshold rather
  than drawn at reduced density; editing the weather state in place risks leaving last frame's
  droplets frozen on screen.
- **JVM advice is advice.** Heap size and garbage collector are launcher settings a mod cannot change
  at runtime. Mandela logs what is set and what would be better, and does not pretend otherwise.
- **`1.21.11`, not the newest release.** Minecraft 26.2 is newer, but it publishes neither Mojang
  mappings nor Yarn, so no mod can currently be built against it. See `docs/TARGETING.md`.

## Licence

MIT.
