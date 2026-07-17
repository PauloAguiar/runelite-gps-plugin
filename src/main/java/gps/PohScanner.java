package gps;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.ObjectID;

/**
 * Maps the furniture object ids seen in a scanned player-owned house to the config declarations
 * they imply. Pure logic over object ids (the plugin supplies them from the live scene), so the
 * mapping is unit-testable.
 * <p>
 * Only furniture with an EXACT 1:1 config mapping is auto-detected — the jewellery box (its tier),
 * fairy ring, spirit tree and obelisk. The "Teleport portals & nexus" and "Mounted items" toggles
 * each bundle several furniture pieces, some with no stable object id (the portal nexus and the
 * functional glory/Xeric's/digsite mounts), so auto-enabling them would claim furniture GPS can't
 * verify; those stay manual.
 */
public final class PohScanner
{
	private PohScanner()
	{
	}

	/** What a house scan found: the present 1:1 features, and the jewellery-box tier. */
	public static final class Detected
	{
		final boolean fairyRing;
		final boolean spiritTree;
		final boolean obelisk;
		final JewelleryBoxTier jewelleryBox;

		Detected(boolean fairyRing, boolean spiritTree, boolean obelisk, JewelleryBoxTier jewelleryBox)
		{
			this.fairyRing = fairyRing;
			this.spiritTree = spiritTree;
			this.obelisk = obelisk;
			this.jewelleryBox = jewelleryBox;
		}

		boolean any()
		{
			return fairyRing || spiritTree || obelisk || jewelleryBox != JewelleryBoxTier.NONE;
		}

		boolean sameAs(Detected other)
		{
			return other != null
				&& fairyRing == other.fairyRing
				&& spiritTree == other.spiritTree
				&& obelisk == other.obelisk
				&& jewelleryBox == other.jewelleryBox;
		}
	}

	/**
	 * Serializes a scan result for cross-session storage: the jewellery-box tier followed by the
	 * present feature flags, comma-separated (e.g. {@code "ORNATE,fairyRing,obelisk"}).
	 */
	public static String encode(Detected detected)
	{
		if (detected == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder(detected.jewelleryBox.name());
		if (detected.fairyRing)
		{
			sb.append(",fairyRing");
		}
		if (detected.spiritTree)
		{
			sb.append(",spiritTree");
		}
		if (detected.obelisk)
		{
			sb.append(",obelisk");
		}
		return sb.toString();
	}

	/** Parses {@link #encode}'s format. Null on missing or malformed data (treated as "never scanned"). */
	public static Detected decode(String encoded)
	{
		if (encoded == null || encoded.isEmpty())
		{
			return null;
		}
		String[] parts = encoded.split(",");
		JewelleryBoxTier tier;
		try
		{
			tier = JewelleryBoxTier.valueOf(parts[0]);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
		Set<String> flags = new HashSet<>(Arrays.asList(parts));
		return new Detected(flags.contains("fairyRing"), flags.contains("spiritTree"),
			flags.contains("obelisk"), tier);
	}

	/** Every furniture object id the scanner recognises — the spawn-event fast path filters on this. */
	private static final Set<Integer> RECOGNISED_IDS = Set.of(
		ObjectID.POH_FAIRY_RING, ObjectID.POH_FAIRY_HOUSE, ObjectID.POH_FAIRY_HOUSE_OPEN,
		ObjectID.POH_SPIRIT_TREE, ObjectID.POH_WILDERNESS_OBELISK,
		ObjectID.POH_JEWELLERY_BOX_1, ObjectID.POH_JEWELLERY_BOX_2, ObjectID.POH_JEWELLERY_BOX_3);

	/**
	 * Whether this object id is POH furniture the scanner recognises. These ids only exist inside
	 * player-owned houses, so one spawning is also proof the loaded scene is a house.
	 */
	public static boolean isRecognised(int objectId)
	{
		return RECOGNISED_IDS.contains(objectId);
	}

	public static Detected detect(Set<Integer> objectIds)
	{
		boolean fairyRing = objectIds.contains(ObjectID.POH_FAIRY_RING)
			|| objectIds.contains(ObjectID.POH_FAIRY_HOUSE)
			|| objectIds.contains(ObjectID.POH_FAIRY_HOUSE_OPEN);
		boolean spiritTree = objectIds.contains(ObjectID.POH_SPIRIT_TREE);
		boolean obelisk = objectIds.contains(ObjectID.POH_WILDERNESS_OBELISK);
		// Tiers are cumulative (each includes the ones below); pick the highest built.
		JewelleryBoxTier box = JewelleryBoxTier.NONE;
		if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_3))
		{
			box = JewelleryBoxTier.ORNATE;
		}
		else if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_2))
		{
			box = JewelleryBoxTier.FANCY;
		}
		else if (objectIds.contains(ObjectID.POH_JEWELLERY_BOX_1))
		{
			box = JewelleryBoxTier.BASIC;
		}
		return new Detected(fairyRing, spiritTree, obelisk, box);
	}
}
