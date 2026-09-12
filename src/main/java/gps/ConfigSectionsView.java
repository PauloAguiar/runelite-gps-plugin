package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

import static gps.PanelWidgets.BANNER_WARN_ACCENT;
import static gps.PanelWidgets.banner;
import static gps.PanelWidgets.sectionShell;
import static gps.PanelWidgets.wrappedLabel;

/**
 * The "Travel options" configuration sections (panel series P2, out of the panel class): the
 * player-stated facts routing cannot detect on its own (the house, the wilderness policy, the
 * walking and bank biases, balloons, sailing, planted spirit trees), each a collapsible section
 * whose header chip summarises its state. The controls mirror the plugin's config items (same
 * keys, kept in sync through the ConfigManager); any change regenerates the current routes.
 */
final class ConfigSectionsView
{
	private final ShortestPathPlugin plugin;
	// Rebuilds the sections slot after a header toggle.
	private final Runnable refresh;
	// Rebuilds the sections and the notes strip after a spinner change (the chip and the banner track it).
	private final Runnable refreshAll;

	private boolean pohExpanded;
	private boolean wildernessExpanded;
	private boolean walkingExpanded;
	private boolean bankExpanded;
	private boolean balloonExpanded;
	private boolean sailingExpanded;
	private boolean spiritTreeExpanded;

	ConfigSectionsView(ShortestPathPlugin plugin, Runnable refresh, Runnable refreshAll)
	{
		this.plugin = plugin;
		this.refresh = refresh;
		this.refreshAll = refreshAll;
	}

	/** The sections, freshly built, in display order. */
	List<JPanel> sections()
	{
		return List.of(poh(), wilderness(), walking(), bank(), balloon(), sailing(), spiritTree());
	}

	/**
	 * The seconds chip for a section header, in the SAME sign convention as the route cards'
	 * adjustment chips: green minus 15 s = "ranks as if 15 s cheaper". A preference of +15 s
	 * therefore displays as minus 15 s; showing the raw preference read as a surcharge.
	 */
	static String biasChip(int preferenceSeconds)
	{
		if (preferenceSeconds == 0)
		{
			return "neutral";
		}
		int adjustment = -preferenceSeconds;
		return (adjustment > 0 ? "+" : "−") + Math.abs(adjustment) + "s";
	}

	static Color biasColor(int seconds)
	{
		if (seconds == 0)
		{
			return ColorScheme.LIGHT_GRAY_COLOR;
		}
		return seconds > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR;
	}

	/** The balloon section's chip: off, on, or "low logs" when an unlocked route's storage is low. */
	static String balloonState(boolean balloonsOn, boolean lowLogs)
	{
		return !balloonsOn ? "off" : (lowLogs ? "low logs" : "on");
	}

	/** The spirit-tree section's chip: all (smart tracking off), on (not synced yet), none, or the count. */
	static String spiritTreeState(boolean smart, boolean synced, int planted)
	{
		if (!smart)
		{
			return "all";
		}
		if (!synced)
		{
			return "on";
		}
		return planted == 0 ? "none" : planted + " planted";
	}

	/**
	 * The Log-storage-low warning banner: like the bank warning, it lives in the notes strip so it
	 * is visible even while the balloon section is collapsed. Shows the low types as item icons.
	 */
	JPanel balloonLowBanner(List<String> lowTypes)
	{
		JPanel warning = banner(RouteIcons.BANNER_WARNING,
			"Log storage low", "Restock logs at a balloon station:", BANNER_WARN_ACCENT);
		int[] counts = plugin.getBalloonStoredCounts();
		JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		icons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		for (int i = 0; i < BalloonLogStorage.TYPE_NAMES.length; i++)
		{
			if (lowTypes.contains(BalloonLogStorage.TYPE_NAMES[i]))
			{
				icons.add(logIcon(i, counts[i]));
			}
		}
		warning.add(icons, BorderLayout.SOUTH);
		warning.setMaximumSize(new Dimension(Integer.MAX_VALUE, warning.getPreferredSize().height));
		return warning;
	}

	private JPanel shell(String title, String tooltip, boolean expanded, Runnable toggle, String stateText, Color stateColor)
	{
		return sectionShell(title, tooltip, expanded, toggle, stateText, stateColor, false, refresh);
	}

	/**
	 * The player-owned-house declarations: which POH teleport features GPS should assume exist.
	 * Unlike the catalog's include/exclude (what the user WANTS used), these describe what is BUILT
	 * in the house, facts GPS cannot detect from outside the house, so the player states them once.
	 */
	private JPanel poh()
	{
		final boolean pohOn = plugin.getGpsConfig().usePoh();
		JPanel section = shell("Player-owned house",
			"Declare which teleport features are built in your house so routes can use them",
			pohExpanded, () -> pohExpanded = !pohExpanded,
			pohOn ? "on" : "off",
			pohOn ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		if (!pohExpanded)
		{
			return section;
		}

		JPanel body = body();

		// What GPS detected on its own: the house location (varbit), a confidence hint that the
		// location-gated entries/exits will resolve correctly.
		String house = plugin.getHouseLocationName();
		body.add(statusLabel(house != null
				? "Your house: " + house
				: "No house detected (log in, or you don't own one)",
			house != null ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.MEDIUM_GRAY_COLOR));

		JCheckBox master = configCheckBox("Use my house for routes", pohOn,
			"Master switch: with this off, no POH teleport is ever routed",
			v -> plugin.setPanelConfig("usePoh", v));
		body.add(iconRow("house_portal", 0, master));

		// Smart detection: while inside your house GPS auto-fills the furniture it can recognise.
		final boolean smartDetect = plugin.getGpsConfig().pohSmartDetect();
		JCheckBox smartBox = configCheckBox("Auto-detect furniture", smartDetect,
			"<html><body style='width:220px'>While you are inside your house, fill the checkboxes below"
				+ " from the furniture GPS recognises: jewellery box, fairy ring, spirit tree and"
				+ " obelisk. It only ever ticks boxes (never unticks), and you can still edit any of"
				+ " them.<br><br>Portals &amp; nexus and mounted items can't be auto-detected; set those"
				+ " yourself.</body></html>",
			v -> plugin.setPanelConfig("pohSmartDetect", v));
		body.add(iconRow("house_portal", 18, smartBox));
		if (smartDetect)
		{
			List<String> detected = plugin.getDetectedPohFurniture();
			if (!plugin.isPohScanned())
			{
				body.add(warningBanner("Enter your house once to auto-detect its furniture."));
			}
			else if (detected.isEmpty())
			{
				body.add(note("No auto-detectable furniture found in your house.", ColorScheme.MEDIUM_GRAY_COLOR));
			}
			else
			{
				body.add(note("Detected: " + String.join(", ", detected), ColorScheme.LIGHT_GRAY_COLOR));
			}
		}

		JPanel tierInner = new JPanel(new BorderLayout(5, 0));
		tierInner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel tierLabel = new JLabel("Jewellery box:");
		tierLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		tierInner.add(tierLabel, BorderLayout.WEST);
		JComboBox<JewelleryBoxTier> tierBox = new JComboBox<>(JewelleryBoxTier.values());
		tierBox.setSelectedItem(plugin.getGpsConfig().pohJewelleryBoxTier());
		tierBox.setEnabled(pohOn);
		tierBox.setToolTipText("The tier built in your house (each tier includes the ones below it)");
		tierBox.addActionListener(e -> plugin.setPanelConfig("pohJewelleryBoxTier", tierBox.getSelectedItem()));
		tierInner.add(tierBox, BorderLayout.CENTER);
		body.add(iconRow("jewellery_box", 18, tierInner));

		JCheckBox portals = configCheckBox("Teleport portals & nexus", plugin.getGpsConfig().useTeleportationPortalsPoh(),
			"Portal chamber and portal nexus destinations",
			v -> plugin.setPanelConfig("useTeleportationPortalsPoh", v));
		JCheckBox mounted = configCheckBox("Mounted items", plugin.getGpsConfig().usePohMountedItems(),
			"Mounted glory, Xeric's talisman, digsite pendant, mythical cape",
			v -> plugin.setPanelConfig("usePohMountedItems", v));
		JCheckBox fairy = configCheckBox("Fairy ring", plugin.getGpsConfig().usePohFairyRing(),
			"Requires 85 Construction to build",
			v -> plugin.setPanelConfig("usePohFairyRing", v));
		JCheckBox spirit = configCheckBox("Spirit tree", plugin.getGpsConfig().usePohSpiritTree(),
			"Requires 75 Construction and 83 Farming to build",
			v -> plugin.setPanelConfig("usePohSpiritTree", v));
		JCheckBox obelisk = configCheckBox("Wilderness obelisk", plugin.getGpsConfig().usePohObelisk(),
			"Requires 80 Construction to build",
			v -> plugin.setPanelConfig("usePohObelisk", v));
		String[] icons = {"portal_chamber", "mounted_glory", "fairy_ring", "spirit_tree", "obelisk"};
		JCheckBox[] boxes = {portals, mounted, fairy, spirit, obelisk};
		for (int i = 0; i < boxes.length; i++)
		{
			boxes[i].setEnabled(pohOn);
			body.add(iconRow(icons[i], 18, boxes[i]));
			if (boxes[i] == mounted)
			{
				// The mounts cannot be scene-detected (no stable object ids), so each is its own
				// assumption: pick exactly the ones built in your house.
				boolean mountsOn = pohOn && plugin.getGpsConfig().usePohMountedItems();
				String[][] mounts = {
					{"pohMountGlory", "Amulet of glory", "Mounted Amulet of glory (Edgeville, Karamja, Draynor, Al Kharid)"},
					{"pohMountXerics", "Xeric's talisman", "Mounted Xeric's talisman (Lookout, Glade, Inferno, Heart, Honour)"},
					{"pohMountDigsite", "Digsite pendant", "Mounted Digsite pendant (Digsite, Fossil Island, Lithkren)"},
					{"pohMountMythical", "Mythical cape", "Mounted Mythical cape (Myths' Guild)"},
				};
				boolean[] values = {plugin.getGpsConfig().pohMountGlory(), plugin.getGpsConfig().pohMountXerics(),
					plugin.getGpsConfig().pohMountDigsite(), plugin.getGpsConfig().pohMountMythical()};
				for (int m = 0; m < mounts.length; m++)
				{
					final String key = mounts[m][0];
					JCheckBox mount = configCheckBox(mounts[m][1], values[m], mounts[m][2],
						v -> plugin.setPanelConfig(key, v));
					mount.setEnabled(mountsOn);
					mount.setBorder(new EmptyBorder(2, 36, 2, 0));
					body.add(mount);
				}
			}
		}

		section.add(body);
		return section;
	}

	/** The wilderness travel policy: whether routes may cross the wilderness at all. */
	private JPanel wilderness()
	{
		final boolean avoid = plugin.getGpsConfig().avoidWilderness();
		JPanel section = shell("Wilderness",
			"Choose whether routes may cross the wilderness",
			wildernessExpanded, () -> wildernessExpanded = !wildernessExpanded,
			avoid ? "avoided" : "allowed",
			avoid ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		if (!wildernessExpanded)
		{
			return section;
		}

		JPanel body = body();
		body.add(configCheckBox("Avoid the wilderness", avoid,
			"Route around the wilderness whenever possible (e.g. skip the Edgeville lever to Ardougne)",
			v -> plugin.setPanelConfig("avoidWilderness", v)));
		body.add(note("Routes still enter the wilderness when the destination itself is inside it.",
			ColorScheme.MEDIUM_GRAY_COLOR));
		section.add(body);
		return section;
	}

	/** A "Bias (seconds)" spinner row: minus 120 to 120 in steps of 5, wired to the given setter. */
	private static JPanel biasSpinnerRow(String caption, String tooltip, int value, IntConsumer setter)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(caption);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setToolTipText(tooltip);
		row.add(label, BorderLayout.CENTER);
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, -120, 120, 5));
		spinner.setToolTipText(tooltip);
		spinner.addChangeListener(e -> setter.accept((Integer) spinner.getValue()));
		row.add(spinner, BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, spinner.getPreferredSize().height + 4));
		return row;
	}

	/**
	 * Walking as a travel POLICY (not a method): a seconds bias with finer control than the
	 * method tiers. Positive = walking keeps the top spot unless a method beats it by more than
	 * the bias; negative penalises the pure-walk route the same way.
	 */
	private JPanel walking()
	{
		int bias = plugin.getWalkPreferenceSeconds();
		JPanel section = shell("Walking",
			"How plain walking ranks against travel methods",
			walkingExpanded, () -> walkingExpanded = !walkingExpanded,
			biasChip(bias), biasColor(bias));
		if (!walkingExpanded)
		{
			return section;
		}
		JPanel body = body();
		body.add(statusLabel(bias == 0
				? "No preference: routes rank purely by time."
				: (bias > 0
					? "Walking wins unless a method is more than " + bias + "s faster."
					: "Walking is ranked as if " + (-bias) + "s slower."),
			ColorScheme.LIGHT_GRAY_COLOR));
		body.add(biasSpinnerRow("Prefer walking by (s):",
			"<html>Ranking bias for the pure-walk route, in seconds.<br>"
				+ "Positive: walking keeps the top spot unless a method beats it by more.<br>"
				+ "Negative: walking ranks as if slower. Changes re-sort instantly.</html>",
			bias, v ->
			{
				plugin.setWalkPreferenceSeconds(v);
				SwingUtilities.invokeLater(refreshAll);
			}));
		body.add(note("Ranking only: the walk route's ETA and path never change.", ColorScheme.MEDIUM_GRAY_COLOR));
		section.add(body);
		return section;
	}

	/**
	 * Bank memory: whether GPS saves the bank's contents when the bank closes and restores them at
	 * login, so "+ Bank" routes and in-bank availability work without opening the bank first. GPS
	 * only ever sees the bank while it is open; this fills the gap between sessions.
	 */
	private JPanel bank()
	{
		final boolean remember = plugin.getGpsConfig().rememberBank();
		// The header chip is the ranking bias (same convention as Walking and the card chips);
		// the remember-between-sessions state is a detail inside the body.
		int headerBias = plugin.getBankPreferenceSeconds();
		JPanel section = shell("Bank",
			"How bank-detour routes rank, and remembering your bank between sessions",
			bankExpanded, () -> bankExpanded = !bankExpanded,
			biasChip(headerBias), biasColor(headerBias));
		if (!bankExpanded)
		{
			return section;
		}

		JPanel body = body();

		// What GPS currently knows, and where that knowledge came from: a restored snapshot can be
		// stale if the bank changed on another client since it was saved.
		String state;
		Color stateColor;
		if (!plugin.isBankContentsKnown())
		{
			state = "Bank contents: unknown, open your bank once";
			stateColor = ColorScheme.MEDIUM_GRAY_COLOR;
		}
		else if (plugin.isBankRestored())
		{
			state = "Bank contents: restored from your last session";
			stateColor = ColorScheme.LIGHT_GRAY_COLOR;
		}
		else
		{
			state = "Bank contents: seen this session";
			stateColor = ColorScheme.LIGHT_GRAY_COLOR;
		}
		body.add(statusLabel(state, stateColor));

		body.add(configCheckBox("Remember between sessions", remember,
			"<html><body style='width:220px'>Save a snapshot of your bank each time you close it, and"
				+ " load it back at login, so \"+ Bank\" routes can see banked items without opening"
				+ " the bank first. Saved per character in your RuneLite profile.</body></html>",
			v -> plugin.setPanelConfig("rememberBank", v)));
		body.add(note("Opening the bank always refreshes the snapshot; turning this off deletes it.",
			ColorScheme.MEDIUM_GRAY_COLOR));

		// Ranking bias for via-bank routes: finer control than the method tiers, and separate
		// from the withdrawal time already counted inside those routes' ETAs.
		int bankBias = plugin.getBankPreferenceSeconds();
		body.add(biasSpinnerRow("Prefer bank routes by (s):",
			"<html>Ranking bias for routes that detour via a bank, in seconds.<br>"
				+ "Positive: bank routes rank as if faster; negative: as if slower.<br>"
				+ "Separate from the withdrawal time, which is already in their ETA.</html>",
			bankBias, v ->
			{
				plugin.setBankPreferenceSeconds(v);
				SwingUtilities.invokeLater(refreshAll);
			}));
		section.add(body);
		return section;
	}

	/**
	 * Balloon travel: the master toggle plus smart mode, which tracks the stations' log storage
	 * from chat so flights can be paid from storage, including a low-storage warning (threshold
	 * configurable; only routes the player has unlocked are considered) and a first-time sync hint,
	 * since the counts only become known once a storage message has been seen.
	 */
	private JPanel balloon()
	{
		final ShortestPathConfig config = plugin.getGpsConfig();
		final boolean balloonsOn = config.useHotAirBalloons();
		final boolean smart = config.balloonSmartMode();
		List<String> lowTypes = plugin.getBalloonLowLogTypes();

		Color stateColor = !balloonsOn ? ColorScheme.LIGHT_GRAY_COLOR
			: (lowTypes.isEmpty() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		JPanel section = shell("Hot air balloons",
			"Balloon travel and smart tracking of the stations' Log storage",
			balloonExpanded, () -> balloonExpanded = !balloonExpanded,
			balloonState(balloonsOn, !lowTypes.isEmpty()), stateColor);
		if (!balloonExpanded)
		{
			return section;
		}

		JPanel body = body();

		JCheckBox master = configCheckBox("Use balloon routes", balloonsOn,
			"<html><body style='width:220px'>Master switch: include hot air balloon flights in routes"
				+ " (requires Enlightened Journey).<br><br>Each flight consumes one log of its"
				+ " destination's type, paid from your inventory or from the stations' Log"
				+ " storage.</body></html>",
			v -> plugin.setPanelConfig("useHotAirBalloons", v));
		body.add(master);

		JCheckBox smartBox = configCheckBox("Smart Log storage", smart,
			"<html><body style='width:220px'>Detect and keep track of the logs in the stations' Log"
				+ " storage (read from chat messages); flights can then be paid from storage without"
				+ " carrying logs.<br><br>When off, GPS ignores the Log storage: a flight is only"
				+ " routed while you carry its log type (the All modes assume flights are available"
				+ " either way).</body></html>",
			v -> plugin.setPanelConfig("balloonSmartMode", v), 18);
		smartBox.setEnabled(balloonsOn);
		body.add(smartBox);

		JPanel warnRow = new JPanel(new BorderLayout(5, 0));
		warnRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		warnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		warnRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		warnRow.setBorder(new EmptyBorder(2, 28, 2, 0));
		String warnTooltip = "Warn when an unlocked route's Log storage count falls below this (0 = never warn)";
		// Deliberately terse: the full wording clipped at this indent on the sidebar's width.
		JLabel warnLabel = new JLabel("Warn below:");
		warnLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		warnLabel.setToolTipText(warnTooltip);
		warnRow.add(warnLabel, BorderLayout.CENTER);
		JSpinner warnSpinner = new JSpinner(new SpinnerNumberModel(config.balloonLogWarningThreshold(), 0, 100, 1));
		warnSpinner.setEnabled(balloonsOn && smart);
		warnSpinner.setToolTipText(warnTooltip);
		warnSpinner.addChangeListener(e -> plugin.setPanelConfig("balloonLogWarningThreshold", warnSpinner.getValue()));
		warnRow.add(warnSpinner, BorderLayout.EAST);
		body.add(warnRow);

		if (balloonsOn && smart)
		{
			if (!config.balloonStorageSynced())
			{
				body.add(warningBanner("Not synced yet: check the Log storage at a balloon station"
					+ " once to import your stored log counts."));
			}
			else
			{
				body.add(note("Log storage:", ColorScheme.LIGHT_GRAY_COLOR));
				int[] counts = plugin.getBalloonStoredCounts();
				JPanel storageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
				storageRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				storageRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				storageRow.setBorder(new EmptyBorder(0, 14, 2, 0));
				for (int i = 0; i < counts.length; i++)
				{
					storageRow.add(logIcon(i, counts[i]));
				}
				storageRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, storageRow.getPreferredSize().height));
				body.add(storageRow);
			}
		}

		section.add(body);
		return section;
	}

	/** Sailing your own boat: the master switch, the boarding assumptions and the known berths. */
	private JPanel sailing()
	{
		final ShortestPathConfig config = plugin.getGpsConfig();
		final boolean sailingOn = config.useSailing();
		JPanel section = shell("Sailing (beta)",
			"Sail your own boat between mooring points and port berths",
			sailingExpanded, () -> sailingExpanded = !sailingExpanded,
			sailingOn ? "on" : "off",
			sailingOn ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		if (!sailingExpanded)
		{
			return section;
		}

		JPanel body = body();
		JCheckBox master = configCheckBox("Use sailing routes", sailingOn,
			"<html><body style='width:220px'>Master switch: include sailing your own boat between"
				+ " mooring points and port berths.<br><br>Assumes you own a boat; travel times"
				+ " assume a mid-tier hull speed. Where routes may board is governed by your boat's"
				+ " detected berth and the Summon Boat assumption below.</body></html>",
			v -> plugin.setPanelConfig("useSailing", v));
		body.add(master);
		JCheckBox abandon = configCheckBox("Teleports may abandon the boat",
			config.sailingTeleportAbandon(),
			"<html><body style='width:220px'>Aboard, teleport routes leave the boat where it"
				+ " floats.<br><br>Off: routes from the water only disembark at moorings and port"
				+ " berths; the boat is never left at sea.</body></html>",
			v -> plugin.setPanelConfig("sailingTeleportAbandon", v), 18);
		abandon.setEnabled(sailingOn);
		body.add(abandon);
		JCheckBox helm = configCheckBox("Keep sailing while at the helm",
			config.sailingKeepSailing(),
			"<html><body style='width:220px'>Aboard, routes that stay on the water rank first;"
				+ " disembark-and-teleport chains stay listed below as alternatives.</body></html>",
			v -> plugin.setPanelConfig("sailingKeepSailing", v), 18);
		helm.setEnabled(sailingOn);
		body.add(helm);

		JCheckBox summon = configCheckBox("Assume Summon Boat spell",
			plugin.getGpsConfig().sailingAssumeSummon(),
			"<html><body style='width:220px'>Routes may board at ANY mooring: the boat is"
				+ " summoned there first (56 Magic, Pandemonium, teleport focus).<br><br>Off:"
				+ " sailing legs start only where a boat is actually moored, and Teleport to"
				+ " Boat (67 Magic, greater focus) covers the distance.</body></html>",
			v -> plugin.setPanelConfig("sailingAssumeSummon", v), 18);
		summon.setEnabled(sailingOn);
		body.add(summon);

		// Latest known berths: live varbits once seen this session, the stored snapshot from
		// the last one before that. One two-column row per boat: no glyphs (the panel font has
		// no boat, it fell back to a warning triangle) and no wrapping; the name clips with a
		// tooltip, the port keeps its own column and its colour.
		List<String[]> berths = plugin.getBoatBanner();
		if (berths == null || berths.isEmpty())
		{
			JLabel none = wrappedLabel(berths == null
				? "No boat seen yet. Berths appear after login."
				: "No owned boat detected.");
			none.setBorder(new EmptyBorder(4, 18, 2, 0));
			body.add(none);
		}
		else
		{
			for (String[] row : berths)
			{
				JPanel berthRow = new JPanel(new BorderLayout(8, 0));
				berthRow.setOpaque(false);
				berthRow.setBorder(new EmptyBorder(3, 18, 0, 0));
				// A raw JPanel defaults to CENTER alignmentX (0.5); one such row in a vertical
				// BoxLayout shifts every LEFT-aligned sibling toward mid-column: the sailing
				// checkboxes rendered half-indented AND clipped off the right edge.
				berthRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				String type = row.length > 2 ? row[2] : "";
				JLabel name = new JLabel(row[0]);
				name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				BoatHull hull = BoatHull.fromName(type);
				if (hull != null)
				{
					name.setIcon(RouteIcons.hullIcon(hull));
				}
				name.setIconTextGap(6);
				name.setToolTipText(row[0] + (type.isEmpty() ? "" : " (" + type + ")") + ", moored at " + row[1]);
				berthRow.add(name, BorderLayout.CENTER);
				JLabel port = new JLabel(row[1]);
				port.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
				port.setToolTipText(name.getToolTipText());
				berthRow.add(port, BorderLayout.EAST);
				body.add(berthRow);
			}
			if (!plugin.isBoatBannerLive())
			{
				JLabel stale = wrappedLabel("(berths from last session)");
				stale.setBorder(new EmptyBorder(1, 18, 2, 0));
				body.add(stale);
			}
		}

		section.add(body);
		return section;
	}

	/**
	 * Planted spirit trees: ONLY the five farmable patches; the permanent spirit trees are always
	 * available and toggled in the Travel methods catalog like every other method. Smart tracking
	 * reads the travel menu to learn which farmable trees you have grown; the detected list shows
	 * here (or a sync hint, since GPS cannot see a farming patch until the menu has been opened).
	 */
	private JPanel spiritTree()
	{
		final boolean smart = plugin.getGpsConfig().spiritTreeSmartMode();
		final boolean synced = plugin.isSpiritTreeSynced();
		List<String> planted = smart && synced ? plugin.getAvailablePlantedSpiritTrees() : List.of();

		Color stateColor = smart && !planted.isEmpty()
			? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR;
		JPanel section = shell("Planted spirit trees",
			"Smart detection of the farmable spirit trees you have grown (permanent trees are in Travel methods)",
			spiritTreeExpanded, () -> spiritTreeExpanded = !spiritTreeExpanded,
			spiritTreeState(smart, synced, planted.size()), stateColor);
		if (!spiritTreeExpanded)
		{
			return section;
		}

		JPanel body = body();

		JCheckBox smartBox = configCheckBox("Smart tracking", smart,
			"<html><body style='width:220px'>Detect which farmable spirit trees you have planted and"
				+ " grown (read from the travel menu) and route only through those.<br><br>When off, all"
				+ " farmable spirit trees are assumed available; the Spirit trees category in Travel"
				+ " methods still turns them on or off.</body></html>",
			v -> plugin.setPanelConfig("spiritTreeSmartMode", v));
		body.add(iconRow("spirit_tree", 0, smartBox));

		if (!smart)
		{
			body.add(note("All farmable spirit trees assumed available.", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else if (!synced)
		{
			body.add(warningBanner("Not synced yet: open a spirit tree travel menu once to detect"
				+ " your planted trees."));
		}
		else if (planted.isEmpty())
		{
			body.add(note("No planted spirit trees detected.", ColorScheme.MEDIUM_GRAY_COLOR));
		}
		else
		{
			body.add(note("Detected:", ColorScheme.LIGHT_GRAY_COLOR));
			for (String name : planted)
			{
				JLabel label = new JLabel(name);
				label.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
				label.setFont(FontManager.getRunescapeSmallFont());
				body.add(iconRow("spirit_tree", 18, label));
			}
		}

		section.add(body);
		return section;
	}

	/** The body box of an expanded configuration section. */
	private static JPanel body()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		body.setBorder(new EmptyBorder(4, 6, 6, 6));
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		return body;
	}

	/**
	 * A status line at the top of a section body ("Your house: ...", "Bank contents: ...").
	 * HTML-wrapped so text longer than the narrow panel wraps instead of clipping.
	 */
	private static JLabel statusLabel(String text, Color color)
	{
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(new EmptyBorder(0, 0, 4, 0));
		return label;
	}

	/** A small wrapped note line inside a section body. */
	private static JLabel note(String text, Color color)
	{
		JLabel note = new JLabel("<html>" + text + "</html>");
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(color);
		note.setAlignmentX(Component.LEFT_ALIGNMENT);
		note.setBorder(new EmptyBorder(2, 18, 2, 0));
		return note;
	}

	/**
	 * An attention note inside a section rendered as a warning banner (amber accent bar plus
	 * warning glyph), matching the panel's other banners; used for the "needs a sync" hints.
	 */
	private static JPanel warningBanner(String text)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrap.setBorder(new EmptyBorder(3, 0, 1, 0));
		JPanel warning = banner(RouteIcons.BANNER_WARNING, text, BANNER_WARN_ACCENT);
		wrap.add(warning, BorderLayout.CENTER);
		wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, warning.getPreferredSize().height + 4));
		return wrap;
	}

	/**
	 * A POH construction icon (bundled OSRS-wiki furniture images under resources/poh/), scaled to
	 * fit the row height and centred in a fixed-width slot so the row labels align.
	 */
	private static JLabel pohIcon(String name)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(26, 20));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		BufferedImage img = ImageUtil.loadImageResource(ConfigSectionsView.class, "/poh/" + name + ".png");
		double scale = Math.min(1.0, Math.min(26.0 / img.getWidth(), 20.0 / img.getHeight()));
		if (scale < 1.0)
		{
			img = ImageUtil.resizeImage(img,
				(int) Math.round(img.getWidth() * scale), (int) Math.round(img.getHeight() * scale));
		}
		label.setIcon(new ImageIcon(img));
		return label;
	}

	/** A configuration row decorated with a small icon to the left of its control. */
	private static JPanel iconRow(String pohIconName, int leftInset, JComponent control)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		row.setBorder(new EmptyBorder(2, leftInset, 2, 0));
		row.add(pohIcon(pohIconName), BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	/**
	 * An item icon for one Log storage log type, with the stored count drawn as the stack quantity
	 * (the same rendering the inventory uses) and spelled out in the tooltip.
	 */
	private JLabel logIcon(int typeIndex, int count)
	{
		JLabel icon = new JLabel();
		icon.setToolTipText(count + " " + BalloonLogStorage.TYPE_NAMES[typeIndex] + " logs in storage");
		plugin.getItemManager().getImage(BalloonLogStorage.ITEM_IDS[typeIndex], count, true).addTo(icon);
		return icon;
	}

	/** A configuration checkbox: writes its config key on change; the ConfigChanged regenerates. */
	private static JCheckBox configCheckBox(String label, boolean value, String tooltip, Consumer<Boolean> onChange)
	{
		return configCheckBox(label, value, tooltip, onChange, 0);
	}

	/**
	 * As above, indented {@code indent} px as a sub-toggle; the border is set here so the HTML
	 * wrap width can shrink by the same amount. The section body offers ~177px of text beside
	 * the glyph, so the old fixed 168px body fit top-level boxes but CLIPPED indented ones (the
	 * sailing sub-toggles rendered as "Teleports may aban": a fixed-width HTML view never
	 * reflows, it just loses its right edge, with no ellipsis). Indent-aware width makes a long
	 * label wrap onto a second line instead.
	 */
	private static JCheckBox configCheckBox(String label, boolean value, String tooltip,
		Consumer<Boolean> onChange, int indent)
	{
		JCheckBox box = new JCheckBox(
			"<html><body style='width:" + (168 - indent) + "px'>" + label + "</body></html>", value);
		if (indent > 0)
		{
			box.setBorder(new EmptyBorder(2, indent, 2, 0));
		}
		box.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		// HTML text ignores the look-and-feel's disabled dimming: mirror it by hand.
		box.addPropertyChangeListener("enabled", e -> box.setForeground(
			box.isEnabled() ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.MEDIUM_GRAY_COLOR));
		box.setToolTipText(tooltip);
		box.setAlignmentX(Component.LEFT_ALIGNMENT);
		box.setFocusPainted(false);
		// The look-and-feel's box is nearly invisible on the dark background: use the catalog's
		// toggle glyphs instead (green check = on, grey cross = off, red on hover), dimmed while
		// disabled.
		box.setIcon(RouteIcons.CROSS);
		box.setRolloverIcon(RouteIcons.CROSS_HOVER);
		box.setSelectedIcon(RouteIcons.CHECK);
		box.setRolloverSelectedIcon(RouteIcons.CHECK_HOVER);
		box.setDisabledIcon(RouteIcons.CROSS_DIM);
		box.setDisabledSelectedIcon(RouteIcons.CHECK_DIM);
		box.addActionListener(e -> onChange.accept(box.isSelected()));
		return box;
	}
}
