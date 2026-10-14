package gps;

import java.util.*;
import java.util.TreeSet;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ObjectID;

/**
 * Maps the furniture object ids seen in a scanned player-owned house to the config declarations
 * they imply. Pure logic over object ids (the plugin supplies them from the live scene), so the
 * mapping is unit-testable.
 * <p>
 * Furniture with a stable object id is auto-detected: the jewellery box (its tier), fairy ring,
 * spirit tree and obelisk, which map 1:1 to a config declaration, and the portal chamber's
 * portals, read by the ids the portal rows name (one per destination and material), so only the
 * built ones are routed through. A portal nexus is recognised as present; which teleports it
 * holds is not read yet, so a house with one keeps every portal row. The functional
 * glory/Xeric's/digsite mounts have no stable id and stay manual ("Mounted items").
 */
public final class PohScanner {
    private PohScanner() {
    }

    /** What a house scan found: the present 1:1 features, and the jewellery-box tier. */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static final class Detected {
        final boolean fairyRing;
        final boolean spiritTree;
        final boolean obelisk;
        final JewelleryBoxTier jewelleryBox;
        /** The portal chamber portals seen, by object id (see {@code PathfinderConfig.getPohPortalNames}). */
        final Set<Integer> portals;
        final boolean nexus;

        boolean any() {
            return fairyRing || spiritTree || obelisk || jewelleryBox != JewelleryBoxTier.NONE || !portals.isEmpty() || nexus;
        }

        boolean sameAs(Detected other) {
            return other != null
                && fairyRing == other.fairyRing
                && spiritTree == other.spiritTree
                && obelisk == other.obelisk
                && jewelleryBox == other.jewelleryBox
                && portals.equals(other.portals)
                && nexus == other.nexus;
        }
    }

    /**
     * Serializes a scan result for cross-session storage: the jewellery-box tier followed by the
     * present feature flags and the portals' object ids, comma-separated (e.g.
     * {@code "ORNATE,fairyRing,obelisk,nexus,portal:60790"}).
     */
    public static String encode(Detected detected) {
        if (detected == null)
            return null;
        StringBuilder sb = new StringBuilder(detected.jewelleryBox.name());
        if (detected.fairyRing)
            sb.append(",fairyRing");
        if (detected.spiritTree)
            sb.append(",spiritTree");
        if (detected.obelisk)
            sb.append(",obelisk");
        if (detected.nexus)
            sb.append(",nexus");
        for (int id : new TreeSet<>(detected.portals))
            sb.append(",portal:").append(id);
        return sb.toString();
    }

    /** Parses {@link #encode}'s format. Null on missing or malformed data (treated as "never scanned"). */
    public static Detected decode(String encoded) {
        if (encoded == null || encoded.isEmpty())
            return null;
        String[] parts = encoded.split(",");
        JewelleryBoxTier tier;
        try {
            tier = JewelleryBoxTier.valueOf(parts[0]);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        Set<String> flags = new HashSet<>(Arrays.asList(parts));
        Set<Integer> portals = new HashSet<>();
        for (String flag : flags)
            if (flag.startsWith("portal:"))
                try {
                    portals.add(Integer.parseInt(flag.substring(7)));
                }
                catch (NumberFormatException e) {
                    return null;
                }
        return new Detected(flags.contains("fairyRing"), flags.contains("spiritTree"),
            flags.contains("obelisk"), tier, portals, flags.contains("nexus"));
    }

    /** Every furniture object id the scanner recognises — the spawn-event fast path filters on this. */
    private static final Set<Integer> RECOGNISED_IDS = Set.of(
        ObjectID.POH_FAIRY_RING, ObjectID.POH_FAIRY_HOUSE, ObjectID.POH_FAIRY_HOUSE_OPEN,
        ObjectID.POH_SPIRIT_TREE, ObjectID.POH_WILDERNESS_OBELISK,
        ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_2, ObjectID.POH_JEWELLERY_BOX_3);

    /** The portal nexus, teak to marble (game cache, 2026-10-13). */
    private static final Set<Integer> NEXUS_IDS = Set.of(33408, 33409, 33410);

    /**
     * Whether this object id is POH furniture the scanner recognises, {@code portalIds} being the
     * portal rows' objects. These ids only exist inside player-owned houses, so one spawning is
     * also proof the loaded scene is a house.
     */
    public static boolean isRecognised(int objectId, Set<Integer> portalIds) {
        return RECOGNISED_IDS.contains(objectId) || NEXUS_IDS.contains(objectId) || portalIds.contains(objectId);
    }

    public static Detected detect(Set<Integer> objectIds, Set<Integer> portalIds) {
        Set<Integer> portals = new HashSet<>(objectIds);
        portals.retainAll(portalIds);
        boolean nexus = false;
        for (int id : NEXUS_IDS)
            nexus |= objectIds.contains(id);
        boolean fairyRing = objectIds.contains(ObjectID.POH_FAIRY_RING)
            || objectIds.contains(ObjectID.POH_FAIRY_HOUSE)
            || objectIds.contains(ObjectID.POH_FAIRY_HOUSE_OPEN);
        boolean spiritTree = objectIds.contains(ObjectID.POH_SPIRIT_TREE);
        boolean obelisk = objectIds.contains(ObjectID.POH_WILDERNESS_OBELISK);
        // Tiers are cumulative (each includes the ones below); pick the highest built.
        JewelleryBoxTier box = JewelleryBoxTier.NONE;
        if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_3))
            box = JewelleryBoxTier.ORNATE;
        else if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_2))
            box = JewelleryBoxTier.FANCY;
        else if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_1))
            box = JewelleryBoxTier.BASIC;
        return new Detected(fairyRing, spiritTree, obelisk, box, portals, nexus);
    }
}
