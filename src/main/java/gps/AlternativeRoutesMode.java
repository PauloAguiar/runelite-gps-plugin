package gps;

/**
 * Which teleport/transport methods the alternative-routes feature considers when searching — an
 * inclusiveness scale with three steps:
 * <ul>
 * <li>{@link #OWNED_INVENTORY} — <b>Inventory</b>: only methods usable with what the player
 * carries (inventory + equipment).</li>
 * <li>{@link #OWNED_WITH_BANK} — <b>+ Bank</b>: also items stored in the bank, routing through a
 * bank to withdraw them (mirrors Shortest Path's {@code INVENTORY_AND_BANK} +
 * {@code includeBankPath}).</li>
 * <li>{@link #ALL_EVERYTHING} — <b>All</b>: every method in the game, regardless of items or
 * character unlocks — the planning view; the availability markers show what each method is
 * missing.</li>
 * </ul>
 * The enum names are the persisted config values, kept stable across the UI's renames (the old
 * ALL_UNLOCKED middle mode was folded into ALL_EVERYTHING and is mapped on load).
 */
public enum AlternativeRoutesMode {
	/**
	 * Inventory: items in inventory or equipment only (forces the INVENTORY teleport-item setting).
	 */
	OWNED_INVENTORY,
	/**
	 * + Bank: inventory + equipment + bank, routing through a bank to withdraw banked items.
	 */
	OWNED_WITH_BANK,
	/**
	 * All: every method in the game, including ones this character can't use yet.
	 */
	ALL_EVERYTHING;

	public boolean isOwned() {
		return this == OWNED_INVENTORY || this == OWNED_WITH_BANK;
	}
}
