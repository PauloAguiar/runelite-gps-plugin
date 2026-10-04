package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * The item(s) the displayed route's NEXT step uses, for the widget highlight: the first method
 * edge the player has not reached, when its transport is an item teleport; every variant of the
 * item counts, so whichever the player carries lights up. Null when the next thing to do is not
 * an item step. Also the rule for "worn-only" items (the Camulet: its teleports are worn options),
 * whose bag copy gets an "Equip" tag.
 */
final class RouteItemCue {
    /** Inventory options that use an item where it lies; an item with none of them teleports worn. */
    private static final String[] USE_VERBS = {"Rub", "Teleport", "Break", "Operate", "Commune", "Invoke", "Activate"};

    final Set<Integer> itemIds;

    private RouteItemCue(Set<Integer> itemIds) {
        this.itemIds = itemIds;
    }

    static RouteItemCue next(RouteOption route, int reachedIndex, BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge) {
        if (route == null)
            return null;
        List<PathStep> path = route.getPath();
        for (int edge : route.getMethodEdgeIndexes()) {
            if (edge <= reachedIndex || edge <= 0 || edge >= path.size())
                continue;
            Set<Integer> ids = new HashSet<>();
            for (Transport transport : transportsForEdge.apply(path.get(edge - 1), path.get(edge))) {
                TransportType type = transport.getType();
                if ((type != TransportType.TELEPORTATION_ITEM && type != TransportType.QUETZAL_WHISTLE)
                    || transport.getItemRequirements() == null)
                    continue;
                for (int[] variants : transport.getItemRequirements().getItems())
                    for (int id : variants)
                        ids.add(id);
            }
            return ids.isEmpty() ? null : new RouteItemCue(ids);
        }
        return null;
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
