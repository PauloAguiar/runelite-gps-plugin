package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * What the displayed route's NEXT step uses, for the widget highlights: the first method edge the
 * player has not reached, its items when the transport is an item teleport (every variant of the
 * item counts, so whichever the player carries lights up) and its spell when it is a spell
 * teleport. Null when the next thing to do is neither. Also the route's bank step, whose pickup the bank highlight lights, and the rule for
 * "worn-only" items (the Camulet: its teleports are worn options), whose bag copy gets an "Equip" tag.
 */
final class RouteItemCue {
    /** Inventory options that use an item where it lies; an item with none of them teleports worn. */
    private static final String[] USE_VERBS = {"Rub", "Teleport", "Break", "Operate", "Commune", "Invoke", "Activate"};

    final Set<Integer> itemIds;
    /** The spell's Display info, for the spellbook; null unless the step casts one. */
    final String spell;

    private RouteItemCue(Set<Integer> itemIds, String spell) {
        this.itemIds = itemIds;
        this.spell = spell;
    }

    static RouteItemCue next(RouteOption route, int reachedIndex, BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge) {
        if (route == null)
            return null;
        List<PathStep> path = route.getPath();
        for (int edge : route.getMethodEdgeIndexes()) {
            if (edge <= reachedIndex || edge <= 0 || edge >= path.size())
                continue;
            Set<Integer> ids = new HashSet<>();
            String spell = null;
            for (Transport transport : transportsForEdge.apply(path.get(edge - 1), path.get(edge))) {
                TransportType type = transport.getType();
                if (type == TransportType.TELEPORTATION_SPELL) {
                    if (spell == null)
                        spell = transport.getDisplayInfo();
                    continue;
                }
                if ((type != TransportType.TELEPORTATION_ITEM && type != TransportType.QUETZAL_WHISTLE)
                    || transport.getItemRequirements() == null)
                    continue;
                for (int[] variants : transport.getItemRequirements().getItems())
                    for (int id : variants)
                        ids.add(id);
            }
            return ids.isEmpty() && spell == null ? null : new RouteItemCue(ids, spell);
        }
        return null;
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
