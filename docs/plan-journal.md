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

### Step N7: the field follows reverse edges out of blocked landings; the verdict in every mode (2026-09-07)

**Red first, twice.** `BankModeUnreachableVerdictTest` asked the "+ Bank" mode for a sealed
target (the Broken Raft deck) and expected the escape menu's short-circuit: no walk, seed or
tail pass. It was red because the unreachable verdict excluded bank mode on the theory that the
field floods the inventory-only availability. The field in fact floods both bank states in
every index it builds, so the exclusion was stale; removing it turned that test green and
`SameTailPrefixCapTest` red: Ardougne to Moss Giant Island, fare in the bank, lost its
walk-to-bank ship route because the verdict fired on a reachable target. A probe of the field
in that exact setup showed the flood never left the island: the rope swing's island landing
(2704,3209) is a blocked tile, the walking flood only steps onto blocked tiles that host a
transport origin, so the landing was valued by the post-flood patch after the loop had ended
and its reverse edge into the rope's origin (2709,3209) was never followed. That is a general
field bug, not a bank-mode one: for any target behind a transport that lands on a blocked tile
(a jetty, a platform), everything past the landing read as unreached, so the heuristic sent it
to the floor (overestimating, burying routes through it) and the verdict could call a reachable
target unreachable in any mode. `BlockedLandingReverseEdgeTest` pins it: the rope origin and
the Brimhaven dock must be flooded, and the dock's field value must not exceed the forward
walking cost.

**Change:** `DistanceField.expandWalking` floods a transport landing on a blocked tile in the
loop, at step-off cost, when it is adjacent to a settled unblocked tile (cardinal, or diagonal
with both flanking cardinals open, mirroring the forward blocked-tile rule), so its reverse
edges propagate; the post-flood patch keeps origin-free teleport landings and the horizon
edge. The unreachable verdict now applies in every mode.

**Measured (test output):** Moss Giant Island field: rope origin 15, Brimhaven dock 87, equal
to the forward walking cost from the dock (a tight bound where there was none). Bank mode on a
sealed target: one chain flood instead of two (2.1M nodes instead of 4.2M), the escape menu
instead of a page that skipped it. A* equivalence, hybrid page fill, multi-target field and
uncommon path shape tests unchanged.

### Step N8: parallel-search siblings carry everything the chain's config carries (2026-09-07)

**Red first:** `ParallelCopyFidelityTest` compares every declared field of a refreshed planning
config against its `copyForParallelSearch` sibling by content (reflection), with an explicit
per-copy list that names the reason for each exception. It found five omissions: the type
config's runtime state (the sibling rebuilt it from the live config off the client thread,
losing the refresh's adjustments), the boosted skill levels (a fresh zeroed array on the
sibling, read by the extras gate), the usable fingerprint and the two catalog maps.

**Change:** `TransportTypeConfig` gained a copy constructor that carries states as refreshed and
adjusted without touching the live config; the planning copy constructor uses it; the skill
levels, fingerprint and catalog maps are copied. A new field now fails the build until it is
either copied or listed as per-copy with a reason ("consistent over fast").

### Step N9: one ETA for the card, the overlay and the step list (issue #4) (2026-09-07)

**Red first:** `EtaConsistencyTest` builds a route whose path steps carry cumulative costs and
asserts the overlay's remaining-time table is derived from them (24 units at the end, 7 ticks
left at the step costing 10) and that the card's seconds equal the overlay's seconds at the
start; a second test generates Lumbridge to Varrock and asserts, for every route, that each
path step carries a cost, that the route's total cost is the last step's cost, that the card
and the overlay agree at the start, and that the remaining time never increases along the
path. Red at compile time: path steps had no cost and the overlay's table took only steps.

**Why they disagreed:** the card showed the search's total cost in seconds; the overlay summed
its own per-step estimates (walking legs rounded per leg, per-edge distance capped at ten
tiles, no cost modifiers), then the two were shown side by side for the same route.

**Change:** `PathStep` carries the search's cumulative cost (the graph emits it when the path is
unpacked; -1 for hand-built steps). `RouteDirectionsOverlay.buildRemainingTicks` is exact per
index from those costs and falls back to the step estimates only for cost-less paths; mid-ride
interpolation reads the same table instead of the step's own ticks. `RouteDirections` re-derives
each step's duration from the cost delta over its span, so the step list adds up to the same
number. Round-trip return legs are re-based onto the outbound total; the idle-bank-flip rewrite
preserves costs. The card is unchanged.

**Observed while running the suite:** `KeepSailingTest.aboardGenerationAlwaysCarriesAPureSailRoute`
fails intermittently, before and after this step (fails at the previous commit, passes on a
rerun). It is the next step.

### Step N10: the keep-sailing baseline is never evicted; seed acceptance is deterministic (2026-09-07)

**Probe first:** a gated probe ran the flaky generation three times in one JVM, printing every
search record. In every passing run the pure-sail route was the walk search's own result
(Disembark at Port Khazard, cost 458), appended last as the baseline; the nine cheaper routes
were port-then-teleport chains accepted from the seed pass in completion order.

**Why it flaked:** with ten routes already accepted, the baseline append loop evicts the
costliest unprotected route, and that was the baseline itself: it is not walk-only, and the
sole-port-first protection never applies when every route is port-first. Whether the page held
nine or ten routes before the append depended on the order the parallel seeds completed (the
page-fill ceiling ratchets as routes are accepted), which varies with JIT warmth and load.

**Red first:** `KeepSailingBaselineTest` runs the same generation with a limit of nine (more
than nine cheaper routes exist, so the page is full every time) and asserts a reached pure-sail
route survives, three runs in a row, and that the three pages are identical. Red: the first run
lost the sail route.

**Change:** the baseline append loop never evicts the route it just appended; the seed pass
collects every result first and accepts them in cost-then-signature order instead of
completion order (the seeds take milliseconds each, so nothing is lost by waiting for all of
them, and "consistent over fast" is the standing rule). The original test is stable again.

### Deferred: the harvest/compute split of the planning refresh (2026-09-07)

The review scheduled the split (client thread harvests game state; the row loop runs on the
worker) for the ~150-230 ms the planning refresh cost per generation. After N6 the whole refresh
is 13 to 23 ms of client-thread time per generation, of which the row loop is 12 to 17 ms, and
the two correctness items bundled with it (the bank-mode field verdict and the parallel-copy
omissions) are done as N7 and N8. The split would still touch every client read inside the row
loop (quest states, varbits, item definitions, boat state) for a prize of roughly one frame
per generation, so it is deferred until a capture shows the refresh in a stutter.

### Step N11: amenities searchable by name, aliases, and "nearest X" in the search box (2026-09-07)

**Red first:** `AmenitySearchTest` asserts that "Falador Bank" is one entry of the name index
carrying every booth access tile (27), that amenity categories are grouped one entry per site,
that "ge", "lumby", "wildy" and "fally bank" match what they mean without weakening literal
matches, that "nearest altar", "Nearest Altar", a bare "bank" and "nearest bank and back"
resolve to the nearest-of options while "falador bank" does not, and that category labels read
like the panel's. Red at compile time (no tiles, no parser, no labels).

**Why it was missing:** the index listed only places, landmarks, dungeons, minigames and training
spots; banks (1,345 rows), altars, furnaces, anvils, ranges, water sources, spinning and potter's
wheels were reachable only through the eleven fixed "Find nearest" buttons, while the search
field's own hint promised "Falador bank". There were no aliases and no way to type "nearest".

**Change:** `Destinations.Entry` carries a tile set (one tile for a place, every access tile for
a named amenity) and an optional nearest option; `searchable` groups the eight amenity
categories by site name and lists fairy rings and spirit trees by code; `parseNearest` reads a
leading "nearest" or a bare category word; `categoryLabel` names a category as the panel does.
`SearchAliases` holds the vocabulary (GE, Wildy, Lumby, Fally, Cammy, Ardy, Priff, PC, CW, GWD,
CoX, ToB, ToA, SW, MTA, WT, BF, NMZ, KQ, ZMI) and the matcher scores the expanded query too,
taking the better tier. The panel routes a multi-tile entry through the nearest-category flow
(the route ends at the nearest booth), re-resolves history and favourite entries to their tile
sets by name, prepends a "Nearest bank" row for nearest queries (round trip for "and back"),
and labels amenity rows with their category chip.

**Not done from the report's wording:** results still show straight-line tiles rather than an
ETA. An ETA per result needs a search per row on every keystroke (a field build per distinct
target set, 30 to 250 ms each), and a cheaper proxy would be the misleading number the item
warns about; deferred until results can be priced from one shared field.

### Step N12: first-run guidance and honest states (2026-09-07)

**Red first:** `UnreachableCauseTest` generates from Lumbridge in the owned-inventory mode to
Varrock (reached), to the Mos Le'Harmless charter landing (charter ships only, which need coins
the empty inventory lacks) and to the Broken Raft deck (sealed everywhere), then to the raft
deck in the all mode, and asserts the generator reports NONE, MISSING_UNLOCKS, NO_KNOWN_ROUTE and
NO_KNOWN_ROUTE. Red at compile time (no such verdict existed).

**Change:** when every route of a page stops short and the mode is an owned one, the generator
refreshes an all-everything planning copy on the client thread and floods its distance field
from the targets; the target is "reachable with everything" when that field reaches the start or
a teleport landing. The panel now says "Not reachable with what you have: a route exists with
items or unlocks you lack, switch to All to see it" or "No known route to this destination: the
spot may be sealed off, or the map may be missing a connection", instead of one sentence for
both. The empty state lists the four ways to set a destination (search a place or amenity, a
Nearest button, right-click on the world map, shift right-click a tile). The support
affordances (report an issue, GitHub, Discord) moved from the header row into the burger next
to the snapshot and reset items, so a first look at the panel is the search box and the routes.
The best route's card no longer promises "click to hide" (hiding the fallback shows the fallback
again); it says "Showing on map (the best route)", other selected cards say "click to hide".
The bank-mode tooltips name the button ("+ Bank") instead of a mode label that appears nowhere,
"more alternative routes" became "more routes", and two em dashes left user-visible text.

**Measured:** the cause probe runs only for an unreached page in an owned mode: one planning
refresh (13 to 23 ms on the client thread) and one full flood; the test's three owned-mode
generations plus one all-mode generation complete in 2.9 s together.

**Not done:** the menu entries still read "Set GPS Target" and "Clear Path" (a rename changes
what players have learned to click; left for a deliberate decision), and "Travel options" still
parents "Travel methods".

### Step N13: the TSV lint gate is green and actually runs (2026-09-07)

**Red first:** `scripts/check_tsv.py` against `src/main/resources`: 23 of 40 files failed. The
checker knew one file family (a '#'-prefixed transport header, every row with exactly the
header's column count) and none of the others: the `Meta` and `Note` columns, the plain
headers of `destinations*.tsv`, `doors.tsv` and the generated sailing files, the free comment
blocks before those headers, the two headerless box lists, and the fact that every loader
splits without a limit so a row may omit its trailing empty cells (6,482 such rows in
`transports.tsv` alone were "missing tabs"). And none of it mattered: the CSV lint, test and
checkstyle workflows all trigger on a branch named `master` while the repository's default
branch is `main`, so no gate has run on a push here.

**Change:** the checker finds the header per family (comment lines without tabs are skipped; a
'#'-prefixed or plain tab-separated line is the header; a one-column "# Destination" counts;
`destination-exclusions.tsv` and `destination-remaps.tsv` are declared headerless with fixed
column counts), knows every column the loaders read (with integer and plane validators for the
coordinate tables), accepts omitted trailing cells but never extra ones, and skips '#' rows as
the loaders do. The three workflows now trigger on `main`.

**Measured:** 40 of 40 files pass; the per-cell validators (coordinates, skills, items, var
requirements, durations, wilderness levels, consumable flags) run on every transport row for
the first time in weeks and found nothing to report.

### Step N14: the unreachable-pin ratchet became a named list (2026-09-07)

**Red first:** `DestinationsReachableTest.importedDestinationsDoNotRegress` now loads
`src/test/resources/expected-unreachable.tsv` and fails when the file is missing; that was the
red run. A gated run (`-Dgps.writeExpectedUnreachable=true`) wrote the list from the current
audit: 569 pins, the exact number the ratchet held, one row per pin (category, name, x, y,
plane), sorted. The plain run is green.

**Why a list:** a number let any pin trade places with any other (a new data regression hidden
by an unrelated fix), and never said which pins were the backlog. The list fails by name in
both directions: a pin not on it that is unreachable is a regression (or a world update, in
which case the list is regenerated deliberately), and a listed pin that has become reachable
must be removed, so the backlog can only shrink honestly.

### Step N15: every transport endpoint must be standable (2026-09-07)

**Red first:** `TransportEndpointLintTest` walks every loaded transport row (sea legs, sailable
tiles and the instance template band excluded) and requires each origin to be unblocked or
blocked with an unblocked cardinal neighbour (the forward rule for platform origins), and each
destination to be unblocked or blocked with a step-off neighbour (the rule the distance field
mirrors). First run: 218 of 26,961 endpoints fail. The bulk is 142 plain transport rows (Kourend
staircases on plane 1, cave mouths, house portals such as Pollnivneach at 3339,3001 where every
neighbour is blocked), 26 league-season shortcut landings, 11 agility shortcut ends, a few boat,
canoe and ship tiles. Each is a row the search can never use.

**Change:** the offenders live in `src/test/resources/expected-unstandable-endpoints.tsv` (one
audit line each, 196 distinct), the same two-way contract as N14: a new one fails by name, a
fixed one must be removed, `-Dgps.writeExpectedEndpoints=true` regenerates after a cache
refresh. `PathfinderConfig.getAllTransports()` exposes the loaded rows for the lint.

**Not done:** the rows themselves. Each entry needs a field check (is the tile really
unstandable, or is the collision map wrong there); the list is the worklist.

**Next tier status:** N1 to N15 close every item of the review's Next tier except two
deliberate deferrals (the harvest/compute split, and ETA-priced search results), both recorded
above with their numbers. The Later tier (route acceptance extraction, services out of the
plugin class, one config source of truth, type switches to data, the leagues decision, product
features) is untouched and is a choice to make, not a list to run.
