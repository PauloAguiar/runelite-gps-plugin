package gps.transport.requirement;


import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Represents an item requirement with its variations and quantity.
 * For example: AIR_RUNE=3 where AIR_RUNE can be substituted by DUST_RUNE,
 * SMOKE_RUNE, etc.
 */
@Getter
@EqualsAndHashCode
public class ItemRequirement {
    /**
     * The item IDs that satisfy this requirement (variations)
     */
    private final int[] itemIds;

    /**
     * Staff IDs that can substitute runes for this requirement
     */
    private final int[] staffIds;

    /**
     * Offhand IDs that can substitute for this requirement
     */
    private final int[] offhandIds;

    /**
     * The quantity required
     */
    private final int quantity;

    public ItemRequirement(int[] itemIds, int[] staffIds, int[] offhandIds, int quantity) {
        this.itemIds = itemIds;
        this.staffIds = staffIds;
        this.offhandIds = offhandIds;
        this.quantity = quantity;
    }

}
