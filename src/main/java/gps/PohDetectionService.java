package gps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Smart house-furniture detection (plan step L11, out of the plugin class). While the player is
 * inside their house, the loaded scene is scanned for the furniture GPS can recognise (jewellery
 * box, fairy ring, spirit tree, obelisk) and the matching declarations are turned ON, never off,
 * so detection can only add routes, never silently drop one. Being inside is judged by two
 * independent signals (single-signal attempts failed in the field): the instance's template
 * regions are house regions, or recognised house furniture spawned in this scene. The scan
 * repeats each tick until something is found (the scene can still be populating on the entry
 * tick), capped so a bare house does not rescan forever; leaving the house or leaving building
 * mode re-arms it. The result persists per character so the next session starts scanned.
 */
@Slf4j
final class PohDetectionService
{
	/** RSProfile-scoped: the last scan's furniture (see PohScanner.encode); present = scanned. */
	static final String CONFIG_KEY_POH_FURNITURE = "pohFurniture";
	/** Bounds the "scene still loading" retries. */
	static final int MAX_ATTEMPTS = 6;

	/** What the scan reads from the loaded scene. */
	interface Scene
	{
		boolean isHouse();

		boolean isInstance();

		String describeChunks();

		Set<Integer> objectIds();
	}

	/** The house declarations a scan may raise (a feature on, a higher jewellery tier), never lower. */
	interface Declarations
	{
		boolean fairyRing();

		boolean spiritTree();

		boolean obelisk();

		JewelleryBoxTier jewelleryBoxTier();

		void raise(String key, Object value);
	}

	private final Supplier<Scene> scene;
	private final BooleanSupplier buildingMode;
	private final BooleanSupplier smartDetect;
	private final Declarations declarations;
	private final ConfigManager configManager;
	private final String configGroup;
	private final Runnable onChanged;

	private volatile boolean scanned;
	private volatile PohScanner.Detected detected;
	// Reset when the player leaves the house, so the next visit re-scans (catching new furniture).
	private boolean foundThisVisit;
	private int attempts;
	// Recognised furniture ids seen spawning in the current scene: proof of being in a house.
	private final Set<Integer> spawned = new HashSet<>();
	// One decoded chunk dump per scene when an instance is judged NOT a house.
	private boolean chunksLogged;
	// Tracks building mode so leaving it re-arms the scan.
	private boolean building;

	PohDetectionService(Supplier<Scene> scene, BooleanSupplier buildingMode, BooleanSupplier smartDetect,
		Declarations declarations, ConfigManager configManager, String configGroup, Runnable onChanged)
	{
		this.scene = scene;
		this.buildingMode = buildingMode;
		this.smartDetect = smartDetect;
		this.declarations = declarations;
		this.configManager = configManager;
		this.configGroup = configGroup;
		this.onChanged = onChanged;
	}

	/** Whether the house has been scanned (this session or restored from the last). */
	boolean isScanned()
	{
		return scanned;
	}

	PohScanner.Detected detected()
	{
		return detected;
	}

	/** The furniture the last scan recognised, as display names (empty until scanned). */
	List<String> detectedNames()
	{
		PohScanner.Detected found = detected;
		if (found == null)
		{
			return List.of();
		}
		List<String> names = new ArrayList<>();
		if (found.jewelleryBox != JewelleryBoxTier.NONE)
		{
			names.add(found.jewelleryBox + " jewellery box");
		}
		if (found.fairyRing)
		{
			names.add("Fairy ring");
		}
		if (found.spiritTree)
		{
			names.add("Spirit tree");
		}
		if (found.obelisk)
		{
			names.add("Obelisk");
		}
		return names;
	}

	/** A scene rebuild: the spawn evidence belonged to the old scene; the chunk-dump log re-arms. */
	void onSceneLoading()
	{
		spawned.clear();
		chunksLogged = false;
	}

	/** Logged out: the next character starts from its own snapshot. */
	void reset()
	{
		scanned = false;
		detected = null;
		foundThisVisit = false;
		attempts = 0;
		spawned.clear();
	}

	/** A game object spawned: recognised house furniture is unambiguous in-house evidence. */
	void furnitureSpawned(int objectId)
	{
		if (PohScanner.isRecognised(objectId) && spawned.add(objectId))
		{
			log.debug("[poh] recognised furniture spawned: {}", objectId);
		}
	}

	/** Restores the persisted scan when none has run this session. */
	void restore()
	{
		if (scanned)
		{
			return;
		}
		PohScanner.Detected stored = PohScanner.decode(
			configManager.getRSProfileConfiguration(configGroup, CONFIG_KEY_POH_FURNITURE));
		if (stored != null)
		{
			detected = stored;
			scanned = true;
		}
	}

	/** Client thread, once per game tick. */
	void onTick()
	{
		Scene current = scene.get();
		boolean sceneIsHouse = current.isHouse();
		boolean inside = sceneIsHouse || !spawned.isEmpty();
		if (!inside)
		{
			// Diagnosability: when an instance is judged not-a-house, log its decoded template
			// chunks once per scene, so a missed house shows in the client log.
			if (!chunksLogged && log.isDebugEnabled() && current.isInstance())
			{
				chunksLogged = true;
				log.debug("[poh] instance not judged a house; template chunks: {}", current.describeChunks());
			}
			foundThisVisit = false;
			attempts = 0;
			return;
		}
		// Leaving building mode re-arms the scan: furniture built this visit gets detected without
		// exiting the house.
		boolean buildingNow = buildingMode.getAsBoolean();
		if (building && !buildingNow)
		{
			foundThisVisit = false;
			attempts = 0;
		}
		building = buildingNow;
		if (!smartDetect.getAsBoolean() || foundThisVisit || attempts >= MAX_ATTEMPTS)
		{
			return;
		}
		attempts++;
		log.debug("[poh] scan attempt {} (sceneIsHouse={}, spawned={})", attempts, sceneIsHouse, spawned);
		scan(current);
		foundThisVisit = detected != null && detected.any();
	}

	private void scan(Scene current)
	{
		Set<Integer> ids = new HashSet<>(current.objectIds());
		// Spawn-event evidence joins the tile scan: authoritative even if the tile walk missed it.
		ids.addAll(spawned);
		PohScanner.Detected found = PohScanner.detect(ids);
		log.debug("[poh] scanned {} object ids, detected: {}", ids.size(), PohScanner.encode(found));
		boolean changed = !scanned || !found.sameAs(detected);
		scanned = true;
		detected = found;
		if (!changed)
		{
			return; // nothing new this scan: do not churn the config or the panel
		}
		// Persist per character, so next session's panel starts in the "scanned" state.
		configManager.setRSProfileConfiguration(configGroup, CONFIG_KEY_POH_FURNITURE, PohScanner.encode(found));
		// Only ever raise declarations (turn a feature on, raise the jewellery tier): a partial
		// scene load that missed a piece can never wipe an existing declaration.
		if (found.fairyRing && !declarations.fairyRing())
		{
			declarations.raise("usePohFairyRing", true);
		}
		if (found.spiritTree && !declarations.spiritTree())
		{
			declarations.raise("usePohSpiritTree", true);
		}
		if (found.obelisk && !declarations.obelisk())
		{
			declarations.raise("usePohObelisk", true);
		}
		if (found.jewelleryBox.ordinal() > declarations.jewelleryBoxTier().ordinal())
		{
			declarations.raise("pohJewelleryBoxTier", found.jewelleryBox);
		}
		if (onChanged != null)
		{
			onChanged.run();
		}
	}
}
