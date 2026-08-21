# Design notes

Reasoning behind the parts where the obvious implementation is wrong. Everything described here
lives in `core/` and is covered by tests.

## The control loop

### Steering on p95, not the mean

The mean frame time is the wrong signal to control on. A machine averaging 60fps while one frame in
ten takes 40ms feels bad, and the mean is precisely the statistic that conceals it. Steering on the
95th percentile means the target is met by nearly every frame, which is what a player perceives as
smoothness.

### Sensor lag is the whole problem

This is the mistake that makes naive adaptive quality unusable, and it is not "reacting too slowly".

Changing a setting does not change the measured frame time until frames rendered under the new
setting have filled the measurement window. A loop that re-decides before then is reading a frame time
that still describes the settings it just replaced. So it cuts again. And again. It arrives at the
floor, having destroyed the picture to correct an overshoot that one step would have covered — and
then, since it now has headroom but the window is full of slow frames, it sits there.

The first implementation of this governor did exactly that. The test that caught it
(`doesNotOvershootWhenLoadJumps`) is kept as a regression guard.

Two changes fix it:

- **Settling.** After any change the controller waits `settleFrames` before deciding again.
- **A separate control window.** `FrameStats` carries both a wide window, which is the right thing to
  show a player, and a short 64-frame `controlP95Micros`, which is the right thing to steer on. Using
  one window for both jobs means either the display is jumpy or the controller is blind.

### Asymmetry

Quality falls fast and returns slowly, requires spare headroom beyond the dead band to return at all,
and waits out a quiet period after a cut. The two errors are not equally costly: a late cut has
already shown the player a stutter, a late restoration costs nothing.

### Spikes are not load

A frame-time spike from a chunk rebuild, a texture upload or a GC pause is not draw load. Lowering
quality does not prevent the next one, so reacting to it pays permanent visual cost for noise. Spikes
are detected, excluded from the control signal, and reported separately on the HUD.

The subtlety is the *other* direction. A spike is by definition brief, so if slow frames keep
arriving they are not a hitch — they are the new normal. An early version classified a sustained 3×
slowdown as an unending series of spikes: the median never updated because spikes were excluded from
it, so the classification never corrected itself and the governor stayed frozen while the frame rate
sat on the floor. Capping the run length at three consecutive frames fixes it, and the counter runs
on the raw classification rather than the capped one — resetting it whenever the cap suppresses a
flag would flag three frames in every four, forever.

## The degradation ladder

Every knob is driven from one scalar in [0, 1]. What makes that work rather than turning everything
down at once is that each knob only responds inside its own *window* of the axis. The order of those
windows is the design opinion of the mod, expressed as data so it can be argued with.

Windows overlap deliberately: two knobs easing through the same stretch of the axis share the work,
which buys back more frame time per unit of visible change than driving one knob to its limit before
touching the next. Windows are eased with smoothstep so nothing has a corner — a governor hovering on
a window boundary would otherwise produce visible pulsing.

The property that matters most is monotonicity: no knob may improve as quality falls. A violation
would put a local trap in the surface the governor searches, so lowering quality could make the frame
rate *worse* — which reads to a player as the mod being broken. `everyKnobDegradesMonotonically`
checks all of it at 0.001 resolution.

## Visibility

### Reusing the renderer's own answer

Occlusion culling is usually presented as a trade: proving an entity is hidden costs work, and doing
it per entity per frame can cost more than drawing them.

Minecraft has already paid for the answer. It walks the chunk graph every frame and ends up with the
set of sections that survived frustum and occlusion culling, then ignores that conclusion for
entities. Reading `LevelRenderer.visibleSections` costs one hash lookup per entity and is exactly as
accurate as the terrain culling it comes from.

The capture point matters: within a frame the renderer culls terrain, then extracts entities, then
block entities. Snapshotting at the end of `cullTerrain` means every entity is tested against this
frame's answer. Reading it anywhere earlier silently tests against the last frame's, and that
one-frame lag is what produces flickering mobs at chunk edges.

### Asymmetric hysteresis

Appearing is instant; disappearing must persist for several frames. The two mistakes are not
symmetric — drawing something hidden costs microseconds, hiding something visible is a bug the player
watches happen.

### Staggering

Visibility changes over tens of milliseconds, not between frames, so re-tests are spread across
frames, offset by entity id. Without the offset every entity would fall due on the same frame and the
saved work would come back as a periodic hitch — worse than the steady cost it replaced.

### Allocation

The per-entity state lives in a primitive open-addressed `int -> long` map. With
`HashMap<Integer, Long>` it would be two boxes per entity per frame — on a busy server, thousands of
short-lived objects every frame, which is the allocation rate that becomes the GC pauses this mod
exists to remove.

Deletion uses backward-shift rather than tombstones, so a long session cycling through entity ids
never degrades into scanning a table full of gravestones. That algorithm fails in ways that look like
nothing until an entry becomes unreachable, hence the 200,000-operation oracle test.

## The render budget

"Collect every candidate, sort by importance, take the top N" is the wrong shape for a render loop:
it needs the whole set up front and it allocates and sorts every frame. Candidates arrive one at a
time and must be answered immediately.

So a score threshold adapts across frames, backed by a hard cap. The first implementation adapted on
the admission count and never moved, because the hard cap means admissions can *never* exceed the
budget — the raise branch was dead code. The signal that actually exists is the number of candidates
that cleared the bar and found no room.

The controller works in rate space: it compares the fraction clearing the bar against the fraction
the budget can afford. That makes it independent of how scores are distributed — it converges on the
right quantile whether importance is spread evenly or bunched up.

The approximation worth being honest about: within a frame it is still first-come-first-served among
candidates above the bar, and the bar is one frame stale. For a camera moving smoothly through a
world, that lag is invisible.

## The autotuner

A pure state machine: it never reads a clock, never touches a renderer, never blocks. The caller
pulls a trial, applies it, measures it, pushes the result back. That is what lets the same search be
driven by a real render loop and by a synthetic performance model under test.

### Why cost-aware

The first version bought quality in order of how much each setting is *missed*. It behaves badly, and
the test that caught it (`reachesDifferentAnswersOnDifferentHardware`) is instructive: on a
terrain-bound machine it spent the entire budget raising render distance and had nothing left for
entity distance, which on that machine was nearly free.

Measuring the price of one step up each axis first, then buying by quality-per-microsecond, is what
makes the result hardware-specific. It costs one extra trial per axis and it is the difference
between a search and a preset.

### Guarding the comparison

Two configurations can only be ranked if they were measured looking at the same thing. If the player
moves more than 3 blocks, turns more than 25°, opens a menu or leaves the world mid-trial, the sample
is discarded and retried rather than believed. A tuner that reads "I turned to face a wall" as "that
setting was cheaper" will confidently arrive at nonsense.

Warm-up frames are discarded for the same reason: a settings change does not take effect on the frame
it is applied, and folding those frames in would make every configuration look worse in proportion to
how large a change it was.

## Where the boundary is

`core/` has no Minecraft dependency. That is not tidiness — it is what makes the control loop, the
search and the culling policy testable at all, and it means a version bump or a second mod loader
touches only the adapter.

The rule the adapter follows: settle all state once at the top of the frame and hold it constant.
Letting mixins ask the governor for a fresh answer whenever they happen to run would mean one frame
rendered with two different sets of settings — entities culled at one distance, block entities at
another — which shows up as tearing between systems rather than a clean quality change.
