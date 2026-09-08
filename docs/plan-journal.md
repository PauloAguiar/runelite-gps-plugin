# Plan journal

One entry per step of the plan from the 2026-09-06 codebase review (the "Now", "Next" and
"Later" tiers). Every step is test-first: the entry names the test that was red before the
change, what the change did, what was measured, and the commit that landed it.

Conventions: costs are in cost units (2 per tick, 0.6 s per tick); timings are from the gated
probes on the development machine (`-Dgps.searchBench=true`, `-Dgps.refreshCost=true`) unless
a field capture is named.

## Now tier (2026-09-07)

Executed in one pass, twelve commits `ab37ba8` through `080ecd8`, before this journal existed.
Summary: the seven live defects (parsers now record unknown quest names and malformed
coordinates; the route-affecting key list is derived and tested; the Wyrmscraig mooring is
deduplicated; the empty NPE catch is gone; a failed generation emits its terminal update;
plugin-message overrides reject undeclared keys), the refresh item-snapshot hoist (main-config
refresh 218 ms to 12 ms), the unreachable-target escape menu (three closest-approach routes
instead of nineteen floods, not in "+ Bank" mode), overlay scene culling and per-frame hoists,
panel mutators marshalled onto the client thread, parallel test forks, LF line endings, the
collision workflow schedule disabled, and `doors.tsv` regenerated. Afterwards: the Pandemonium
charter rows (`f986391`) and the Cabin Fever / Ring of charos fare discount (`619d6bd`).

## Next tier

### Step N1: distance field for multi-target queries (2026-09-07)

**Red first:** `MultiTargetFieldTest` generates a "nearest bank" page (the 296-tile bank set as
the target set) from Lumbridge with every teleport, and from the Feldip Hills walking only, and
asserts that at least 90% of the generation's searches ran with a heuristic and none flooded the
map. Before the change all seventeen Lumbridge searches were blind (`astar=false`).

**Change:** `DistanceField.buildIfCompact` no longer refuses target sets wider than 256 tiles;
`SearchHeuristic.MAX_TARGET_SPAN` is gone. The old rationale ("such searches are already cheap,
and h ~ 0 everywhere") only holds when the nearest target is close; when it is far, each blind
search floods its whole cost ball. The multi-source flood costs the same as a single-target one
and bounds itself the same way.

**Measured (test output):** Lumbridge nearest bank, all teleports: 17 searches, all guided,
154 nodes in total (was ~230k blind). Feldip Hills nearest bank, walk only: 6 searches, all
guided, worst 9,429 nodes, 27k total.

**Not changed:** the single-target unreachable case keeps the escape menu from the Now tier;
"+ Bank" mode still gets a field, but the field's reverse flood ignores banked items (that gap
is on the harvest/compute step's list).

### Step N2: the search hot loop stops allocating (2026-09-07)

**Red first:** `SearchAllocationTest` measures bytes allocated on the search thread per settled
node (HotSpot's thread allocation counter) for a 660k-node blind walk and for a full flood with
120 unreachable targets. Baseline: 131 bytes per node, 87 MB for one walk search.

**Change:** `NodeGraph` is paged (16K nodes per page, no copy on growth; the old flat arrays
copied ~36 MB a dozen times per big search and then released it all); the target check is a
binary search over a sorted `int[]` instead of `Set<Integer>.contains`; door masks come from a
primitive-keyed index (`ClosedDoors.edgeMaskIndex`); bank tiles from a primitive-keyed index;
the cutoff clock is read once per 1,024 nodes; the closest-tile post-pass hoists its unpacks out
of the target loop; tentative-cost pruning now runs in A* mode too (a tile-only heuristic keeps
the ordering exact, and the graph stops holding 3-4x the settled count).

**Measured:** 131 to 42 bytes per node (single target) and the same order with 120 targets.
Search benchmark, blind Lumbridge to Ardougne: 169 ms / 334 ns per node before, 143 ms / 265
ns per node after; the unreachable full flood 351 to 299 ms. A* equivalence tests unchanged.

**Test-design lesson recorded:** an "unreachable" multi-target set must be genuinely unmapped;
one reachable bank ends the search at Lumbridge castle's after ~1,800 nodes, and plane-3 copies
of the banks still land on a palace roof.

**Not changed:** `IntDeque` / `IntMinHeap` still double by copy (a few bytes per node); the
visited/tentative scratch is still allocated per search. Both are small next to the graph.

### Step N3: the per-search availability rebuild stops re-grouping the world (2026-09-07)

**Red first:** `RebuildAllocationTest` builds an "all teleports" planning copy, excludes eight
catalog methods (four origin-less teleports, four origin-bound transports), and measures bytes
allocated by one `rebuildAvailabilityWithExclusions` with and without exclusions. It also checks
the semantics: excluded transports are gone from their origin, and the base availability is
intact after a rebuild with nothing excluded. Baseline: 4,017,408 bytes per rebuild with
exclusions and 3,040,336 with none, at roughly nineteen rebuilds per generation (every chain
iteration, seed, tail and walk search). `TransportAvailabilityFilteredTest` pins the view
semantics (touched origins get a new array, untouched ones share the base array, extras are
appended, the method identity is cached and follows a remapped destination).

**Change:** the refresh captures the two base `TransportAvailability` objects (no exclusions, no
extras) instead of two base transport lists. A rebuild with nothing to remove or add shares those
objects as-is. Otherwise it takes a copy-on-write `filtered` view: `PrimitiveIntHashMap` gained a
clone constructor and an allocation-free `forEach`, only origins holding an excluded transport
get a new array, a map is cloned only once an entry changes, and extras are appended per origin.
`Transport.method()` caches the catalog identity (the old rebuild built a `TeleportMethod` per
usable transport per search just to test set membership); `setDestination` is now explicit and
resets the cache, since the POH remap is the only post-parse mutation of an identity input. All
per-row `TeleportMethod.fromTransport` call sites use the cache.

**Measured (test output):** with eight exclusions 4,017,408 to 535,472 bytes (the four map
clones at 16K capacity); with none 3,040,336 to 0 bytes. Per generation that is roughly 10 MB
instead of 76 MB of rebuild garbage.

**Not changed:** the seed searches still run against a fixed cost ceiling snapshot rather than
one that tightens as cheaper routes are accepted, and the distance field is still rebuilt for a
generation with the same targets as the last. Both are the next step.

### Step N4: the distance field survives across generations (2026-09-07)

**Red first:** `FieldReuseTest` runs seven generations through one service and asserts, via a
new package-private `lastFieldReused()`, that a generation whose inputs match the previous one
reuses its field (same target from another start; same target and mode again) and that any
changed input rebuilds it (a different target; a mode that admits a different usable set; a
skill level drop in an owned mode). The accessor did not exist, so the test was red at compile
time; with a first implementation the "changed usable set" case failed because the test tried
to flip the fairy ring toggle in the all-everything mode, which bypasses every gate but the
structural ones. The test now changes the mode and the skill level instead.

**Change:** the refresh computes a content fingerprint of the usable transports (a commutative
mix of row index and resolved destination per admitted row and bank state), exposed as
`getUsableFingerprint()`. The generator keys its last field on that fingerprint, the user
exclusions, a fingerprint of the synthesized sea legs, the filtered target set and the cost
multiple (a complete field, horizon at MAX, serves any multiple), and reuses the field when the
key matches. The field is immutable once built and only the generation thread touches the cache.

**Correctness fix found on the way:** a resumed generation ("+ more routes") built its field
over the chain's grown exclusion set from the previous generation. A reverse flood over fewer
transports can only overestimate, so that field was not a valid lower bound for the seed and
tail searches, which run with the user exclusions alone. The field is now always built over the
user exclusions, and the chain's first iteration rebuilds its own availability when the two
sets differ.

**Measured (test output):** field builds of 33 to 65 ms in the all-everything mode and 205 to
256 ms in the owned mode (a flood with no cheap teleport floor runs wider); a reused field
costs 0 ms. In the field, a player walking toward a pin regenerates every few tiles with the
same key, so this removes the largest fixed cost of those regenerations.

**Not changed:** the round-trip return field is still built per generation; the seed cost
ceiling is still a snapshot (next).

### Step N5: a tightening seed cost ceiling, dropped on data (2026-09-07)

**Probe first:** `SeedSearchProfileProbeTest` (gated, `-Dgps.seedProfile=true`) prints every
search record of five typical generations. In the all-everything mode the six seed searches of
a generation settle 290 to 476 nodes together, 0 to 1 ms each, already guided by the field and
already capped; a ceiling that tightens as cheaper routes are accepted would save nothing
measurable, so the step is dropped and the walk search keeps its dynamic ceiling alone.

**Recorded for the Later tier (the same probe):** an unreachable exact target (the Broken Raft
deck) still costs two blind floods of 1.4M nodes, ~500 ms each, to produce the escape menu's
closest-approach routes: the field is complete and never reaches the start, so no heuristic
applies and each closest-approach search must exhaust the start's component. And in the owned
mode with nothing in the inventory, Lumbridge to Ardougne runs a 1.4 s generation whose later
chain iterations settle 430k to 460k nodes each (routes at cost 640 to 767 against a best of
261): the field's guidance is exact for the base availability and erodes as the chain excludes
the cheap transports. Neither has a cheap fix; both are noted with numbers.

### Step N6: the planning refresh stops re-reading the config per row (2026-09-07)

**Red first:** `PlanningRefreshCostTest` counts the config and client reads one planning
refresh makes (Mockito invocation counts, deterministic) and asserts budgets of 120 and 1,200.
Baseline: 7,588 config reads and 3,637 client reads per refresh, 143 to 224 ms. The sampling
probe `RefreshProfileProbeTest` (gated, `-Dgps.refreshCost=true`, a stack-trace histogram over
forty refreshes) attributed 63% of the refresh to `TransportTypeConfig.isEnabledInConfig`,
which asked the live config interface for the toggle on every one of the ~14,000 rows (a
ConfigManager lookup each in the client), 25% to a direct fairy ring varbit read in the
classification, and 7% to the detail strings resolving item definitions per requirement.

**Change:** the type config snapshots the as-configured toggles once per refresh and answers
`isEnabledInConfig` from the snapshot; the fairy ring varbit goes through the per-pass memo
(`varbitValue`); item names are cached across refreshes (they never change, an empty string
marks an unresolvable id).

**Measured (test output):** config reads 7,588 to 70, client reads 3,637 to 221, planning
refresh 143 to 224 ms down to 19 to 46 ms (the transport loop alone 12 to 17 ms). This is
client-thread time, paid once per generation, so it is the largest main-loop win of the plan so
far. The remaining profile is the row loop itself (the once-per-id varbit and quest reads, the
availability builder, the primitive map puts); the harvest/compute split would move the loop
off the client thread entirely and is still on the list, at a much smaller prize now.
