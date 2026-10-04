package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import gps.transport.requirement.ItemRequirement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * What the displayed route's NEXT step uses, for the widget highlights: the first method edge the
 * player has not reached, its items when the transport is an item teleport (every variant of the
 * item counts, so whichever the player carries lights up) and its spell when it is a spell
 * teleport, plus the carried staff or tome that stands in for runes the player lacks, since the
 * engine counts it from the bag but the game only when wielded; a fairy ring asks for the Dramen
 * or Lunar staff until the Lumbridge Elite diary waives it, and asks one method ahead, so the
 * staff is wielded before the ring is reached. Null when the next thing to do is none of these. Also the route's bank step, whose pickup the bank highlight lights, and the rule for
 * "worn-only" items (the Camulet: its teleports are worn options), whose bag copy gets an "Equip" tag.
 */
final class RouteItemCue {
    /** Inventory options that use an item where it lies; an item with none of them teleports worn. */
    private static final String[] USE_VERBS = {"Rub", "Teleport", "Break", "Operate", "Commune", "Invoke", "Activate"};

    final Set<Integer> itemIds;
    /** The spell's Display info, for the spellbook; null unless the step casts one. */
    final String spell;
    /** The items that do their work worn (the fairy-ring staff): the equipment tab has nothing to point at. */
    final Set<Integer> wornDone;

    private RouteItemCue(Set<Integer> itemIds, String spell, Set<Integer> wornDone) {
        this.itemIds = itemIds;
        this.spell = spell;
        this.wornDone = wornDone;
    }

    static RouteItemCue next(RouteOption route, int reachedIndex, BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge,
        boolean fairyRingsNeedStaff, Map<Integer, Integer> carried) {
        if (route == null)
            return null;
        List<PathStep> path = route.getPath();
        Set<Integer> ids = new HashSet<>();
        Set<Integer> wornDone = new HashSet<>();
        String spell = null;
        int ahead = 0;
        for (int edge : route.getMethodEdgeIndexes()) {
            if (edge <= reachedIndex || edge <= 0 || edge >= path.size())
                continue;
            if (ahead++ > 1)
                break;
            for (Transport transport : transportsForEdge.apply(path.get(edge - 1), path.get(edge))) {
                TransportType type = transport.getType();
                if (type == TransportType.FAIRY_RING) {
                    if (fairyRingsNeedStaff)
                        for (int id : ItemVariations.DRAMEN_STAFF.getIds()) {
                            ids.add(id);
                            wornDone.add(id);
                        }
                    continue;
                }
                if (ahead > 1)
                    continue; // only the staff is asked for ahead of its step
                if (type == TransportType.TELEPORTATION_SPELL) {
                    if (spell == null)
                        spell = transport.getDisplayInfo();
                    if (transport.getItemRequirements() != null)
                        for (ItemRequirement req : transport.getItemRequirements().getRequirements())
                            if (req.getQuantity() > 0 && !held(carried, req.getItemIds(), req.getQuantity())) {
                                carriedOf(carried, req.getStaffIds(), ids, wornDone);
                                carriedOf(carried, req.getOffhandIds(), ids, wornDone);
                            }
                    continue;
                }
                if ((type != TransportType.TELEPORTATION_ITEM && type != TransportType.QUETZAL_WHISTLE
                    && type != TransportType.TELEPORTATION_BOX) || transport.getItemRequirements() == null)
                    continue;
                for (int[] variants : transport.getItemRequirements().getItems())
                    for (int id : variants)
                        ids.add(id);
            }
        }
        return ids.isEmpty() && spell == null ? null : new RouteItemCue(ids, spell, wornDone);
    }

    private static boolean held(Map<Integer, Integer> carried, int[] ids, int quantity) {
        if (ids != null)
            for (int id : ids)
                if (carried.getOrDefault(id, 0) >= quantity)
                    return true;
        return false;
    }

    /** Adds the ids of {@code candidates} the player carries to both sets: a stand-in that must be wielded. */
    private static void carriedOf(Map<Integer, Integer> carried, int[] candidates, Set<Integer> ids, Set<Integer> wornDone) {
        if (candidates != null)
            for (int id : candidates)
                if (carried.getOrDefault(id, 0) > 0) {
                    ids.add(id);
                    wornDone.add(id);
                }
    }

    /** The index of the bank the route withdraws at (the first banked step), or -1 when it withdraws nothing. */
    static int bankStep(RouteOption route) {
        if (route == null || !route.isViaBank())
            return -1;
        List<PathStep> path = route.getPath();
        for (int i = 0; i < path.size(); i++)
            if (path.get(i).isBankVisited())
                return i;
        return -1;
    }

    /** Whether the item teleports only from the worn slot: none of its inventory options uses it. */
    static boolean wornOnly(String[] inventoryActions) {
        if (inventoryActions == null)
            return true;
        for (String action : inventoryActions) {
            if (action == null)
                continue;
            for (String verb : USE_VERBS)
                if (action.contains(verb))
                    return false;
        }
        return true;
    }
}
