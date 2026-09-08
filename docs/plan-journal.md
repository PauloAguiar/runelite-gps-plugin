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
