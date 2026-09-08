package gps;

import lombok.Getter;
import gps.transport.Transport;
import gps.transport.TransportType;

/**
 * A stable, human-meaningful identity for a single teleport/transport option, used by the
 * alternative-routes feature both as the row shown in the side panel and as the key that the user
 * can exclude from the next search.
 * <p>
 * Identity is {@code (type, displayInfo, destination)}: the {@link TransportType} groups options
 * into a category (Spells, Fairy Rings, Items, ...), {@code displayInfo} is the per-option label the
 * data files carry (e.g. "Varrock Teleport", "Ardougne cloak: Monastery", or a fairy-ring code), and
 * the packed {@code destination} disambiguates options that carry no display info.
 */
@Getter
public final class TeleportMethod
{
	private final TransportType type;
	private final String displayInfo;
	private final int destination;
	// Carried metadata (NOT part of identity/equals): whether using this consumes a charge or the
	// item itself (teleport tabs, charged jewellery) versus being permanent/unlimited. Only
	// meaningful for item methods; defaults false for methods built without a transport.
	private final boolean consumable;
	// Carried metadata (NOT identity): for the jewellery-box transport type, WHICH piece of
	// furniture the row is — the same type covers the box itself and the mounted Xeric's
	// talisman / glory / digsite pendant / mythical cape (issue #19: a Xeric's route read
	// "Jewellery box to 2: Glade"). Null for every other type.
	private final String mount;

	public TeleportMethod(TransportType type, String displayInfo, int destination)
	{
		this(type, displayInfo, destination, false, null);
	}

	public TeleportMethod(TransportType type, String displayInfo, int destination, boolean consumable)
	{
		this(type, displayInfo, destination, consumable, null);
	}

	public TeleportMethod(TransportType type, String displayInfo, int destination, boolean consumable,
		String objectInfo)
	{
		this.type = type;
		this.displayInfo = (displayInfo == null || displayInfo.isEmpty()) ? null : displayInfo;
		this.destination = destination;
		this.consumable = consumable;
		this.mount = TransportType.TELEPORTATION_BOX.equals(type) ? mountOf(objectInfo) : null;
	}

	public static TeleportMethod fromTransport(Transport transport)
	{
		return new TeleportMethod(transport.getType(), transport.getDisplayInfo(),
			transport.getDestination(), transport.isConsumable(), transport.getObjectInfo());
	}

	/** The furniture a jewellery-box-type row belongs to, from its "menuOption menuTarget id". */
	static String mountOf(String objectInfo)
	{
		if (objectInfo == null)
		{
			return null;
		}
		String lower = objectInfo.toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("xeric"))
		{
			return "Xeric's talisman";
		}
		if (lower.contains("glory"))
		{
			return "Amulet of glory";
		}
		if (lower.contains("digsite"))
		{
			return "Digsite pendant";
		}
		if (lower.contains("mythical"))
		{
			return "Mythical cape";
		}
		return null;
	}

	/**
	 * The grouping bucket shown as a section header in the panel.
	 */
	public String category()
	{
		return categoryOf(type);
	}

	/**
	 * The per-option label shown in the panel row. Falls back to the category plus the destination
	 * tile when the data file carries no display info (common for spells whose info is the spell name).
	 */
	public String label()
	{
		if (displayInfo != null)
		{
			// A mounted item under the jewellery-box type names its furniture, or the catalog
			// shows "2: Glade" under a "Jewellery box" heading it never belonged to.
			if (mount != null)
			{
				return mount + ": " + displayInfo;
			}
			// Charters share the "Boats & ships" bucket with ships to the same towns — two bare
			// "Port Sarim" rows were indistinguishable, and searching "charter" found nothing.
			if (TransportType.CHARTER_SHIP.equals(type))
			{
				return "Charter: " + displayInfo;
			}
			return displayInfo;
		}
		int x = WorldPointUtil.unpackWorldX(destination);
		int y = WorldPointUtil.unpackWorldY(destination);
		return category() + " (" + x + ", " + y + ")";
	}

	/**
	 * The label used where the method appears WITHOUT its category heading (route cards, direction
	 * steps): network methods' data labels are bare destinations ("Varrock", "6: Kourend Woodland"),
	 * which read as places rather than travel methods there, so the vehicle is named. Catalog rows,
	 * which sit under their category header, keep the bare {@link #label()}.
	 */
	public String routeLabel()
	{
		String vehicle = displayInfo == null ? null : (mount != null ? mount : vehiclePhrase(type));
		return vehicle == null ? label() : vehicle + " to " + displayInfo;
	}

	/** The vehicle name for network methods whose data label is a bare destination; null otherwise. */
	private static String vehiclePhrase(TransportType type)
	{
		return gps.transport.TransportTypePresentation.vehicleOf(type);
	}

	/**
	 * Whether a transport type counts as a travel "method" worth listing and excluding. Plain local
	 * connectors (doors/ladders/stairs and agility/grapple shortcuts) are walking, not a method.
	 */
	public static boolean isMethodType(TransportType type)
	{
		return type != null
			&& type != TransportType.TRANSPORT
			&& type != TransportType.AGILITY_SHORTCUT
			&& type != TransportType.GRAPPLE_SHORTCUT
			// Sailing is deliberately NOT a catalog method: 2,756 generated port-pair rows
			// would drown the hand-curated methods, and it has its own Travel options section.
			&& type != TransportType.SAILING;
	}

	public static String categoryOf(TransportType type)
	{
		return gps.transport.TransportTypePresentation.categoryOf(type);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof TeleportMethod))
		{
			return false;
		}
		TeleportMethod other = (TeleportMethod) o;
		return destination == other.destination
			&& type == other.type
			&& (displayInfo == null ? other.displayInfo == null : displayInfo.equals(other.displayInfo));
	}

	@Override
	public int hashCode()
	{
		int result = type == null ? 0 : type.hashCode();
		result = 31 * result + (displayInfo == null ? 0 : displayInfo.hashCode());
		result = 31 * result + destination;
		return result;
	}

	@Override
	public String toString()
	{
		return category() + ": " + label();
	}
}
