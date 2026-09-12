package gps;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Clears the hidden transport-type toggles a pre-0.13 build left behind. The eighteen {@code use*}
 * items below are hidden config items with no panel control since 0.13.0: the panel's method
 * catalog is the one customization surface, and the type toggles must read their default (on),
 * so a stale {@code false} from an older version cannot silently keep a whole network out of
 * routing (the field report: charters never appeared, {@code useCharterShips=false} with no
 * checkbox anywhere). The items cannot simply be deleted: RuneLite's config proxy returns null
 * for a method without the annotation (plan step L4).
 * <p>
 * RuneLite itself writes every default-method item's default into the store when the key is
 * absent ({@code ConfigManager.setDefaultConfiguration}, at plugin load), so a stored value equal
 * to the default is the normal state, not a stranded one. Only a differing value is cleared, and
 * that is the only case logged; the previous form cleared and logged RuneLite's own default write
 * at every start (review of 2026-09-12).
 */
@Slf4j
final class HiddenToggleMigration
{
	/** Hidden type toggles without a panel control; every one defaults to on (pinned by test). */
	static final String[] HIDDEN_TYPE_TOGGLES = {
		"useAgilityShortcuts", "useGrappleShortcuts", "useBoats", "useCanoes", "useCharterShips",
		"useShips", "useFairyRings", "useGnomeGliders", "useMagicCarpets", "useMagicMushtrees",
		"useMinecarts", "useMountainGuides", "useQuetzals", "useTeleportationLevers",
		"useTeleportationPortals", "useTeleportationSpells", "useTeleportationMinigames",
		"useWildernessObelisks"};

	/** How RuneLite stores the toggles' default ({@code Boolean.toString(true)}). */
	static final String DEFAULT = "true";

	private HiddenToggleMigration()
	{
	}

	/** Clears every listed toggle whose stored value differs from the default; the keys cleared. */
	static List<String> clearStranded(ConfigManager configManager, String configGroup)
	{
		List<String> cleared = new ArrayList<>();
		for (String key : HIDDEN_TYPE_TOGGLES)
		{
			String stored = configManager.getConfiguration(configGroup, key);
			if (stored == null || DEFAULT.equals(stored))
			{
				continue;
			}
			log.info("clearing stranded hidden toggle {}={} (no panel control; the default applies)", key, stored);
			configManager.unsetConfiguration(configGroup, key);
			cleared.add(key);
		}
		return cleared;
	}
}
