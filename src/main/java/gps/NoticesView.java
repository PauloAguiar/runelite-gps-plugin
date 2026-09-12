package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import static gps.PanelWidgets.BANNER_INFO_ACCENT;
import static gps.PanelWidgets.BANNER_OK_ACCENT;
import static gps.PanelWidgets.BANNER_TEXT_WIDTH;
import static gps.PanelWidgets.BANNER_WARN_ACCENT;
import static gps.PanelWidgets.banner;
import static gps.PanelWidgets.verticalGap;
import static gps.PanelWidgets.verticallyCentered;

/**
 * The notices strip below the header (panel series P5, out of the panel class). Banners are for
 * NOTICES only: the status banner (unreachable, nothing found, arrived, no destination; routine
 * result state lives in the routes header instead) and the warnings (Quest Helper not routing
 * through GPS, Shortest Path also enabled, stale exclusions, low balloon logs, the sync hints),
 * grouped behind a compact "N warnings" row that hides and shows them. With no notices at all,
 * the common "routes found" case, the strip collapses entirely.
 */
final class NoticesView extends JPanel
{
	/** The status banner's flavour, which picks its icon and accent. */
	enum Kind
	{
		WARNING, OK, INFO
	}

	/** What the status banner says. */
	static final class Status
	{
		final String text;
		final Kind kind;

		Status(String text, Kind kind)
		{
			this.text = text;
			this.kind = kind;
		}
	}

	private final ShortestPathPlugin plugin;
	// A full re-render after the warnings toggle (it is a config change the whole panel reflects).
	private final Runnable rerender;
	// The Log-storage-low banner is built by the balloon section, which knows the item icons.
	private final Function<List<String>, JPanel> balloonLowBanner;
	// Set by the plugin the instant it clears the target on arrival, so the status shows an arrival
	// banner rather than "No destination set". Cleared when a new destination is set.
	private boolean showingArrival;
	private boolean arrivalImmediate;

	NoticesView(ShortestPathPlugin plugin, Runnable rerender, Function<List<String>, JPanel> balloonLowBanner)
	{
		this.plugin = plugin;
		this.rerender = rerender;
		this.balloonLowBanner = balloonLowBanner;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(4, 0, 6, 0));
	}

	/**
	 * The plugin reached (or cleared an already-at) destination: the next show says so instead
	 * of "No destination set". {@code elapsedMillis} is about 0 when the destination was set
	 * while already there. Marshalled onto the EDT.
	 */
	void markArrived(long elapsedMillis)
	{
		SwingUtilities.invokeLater(() ->
		{
			showingArrival = true;
			arrivalImmediate = elapsedMillis < 3000;
		});
	}

	/**
	 * The status banner, or null when nothing needs saying: unreachable (every route stops short,
	 * with WHY: a route exists with items or unlocks you lack, or no known route at all), nothing
	 * found for the target (with a broader-mode hint unless already in All), arrived (or already
	 * there), or no destination set. Nothing while calculating, nothing when a route reaches.
	 */
	static Status status(boolean calculating, boolean anyRoute, boolean allStopShort, boolean missingUnlocks,
		boolean hasTarget, boolean arrived, boolean arrivedImmediately, boolean allMode)
	{
		if (calculating)
		{
			return null;
		}
		if (anyRoute)
		{
			if (!allStopShort)
			{
				return null;
			}
			// Plan step N12: the generator tells "reachable with everything, not with what you
			// have" from "no known route" (a sealed tile, or a gap in the map data).
			return new Status(missingUnlocks
				? "<b>Not reachable with what you have.</b><br>A route exists with items or unlocks you lack: "
					+ "switch to All to see it. Showing the closest reachable point."
				: "<b>No known route to this destination.</b><br>The spot may be sealed off, or the map may be "
					+ "missing a connection. Showing the closest reachable point.", Kind.WARNING);
		}
		if (hasTarget)
		{
			// A search ran for the current target but produced nothing: distinct from "no target set".
			return new Status("<b>No routes found to the target.</b>"
				+ (allMode ? "" : "<br>Try a broader mode (+ Bank, or All)."), Kind.WARNING);
		}
		if (arrived)
		{
			return new Status(arrivedImmediately ? "You're already at your destination." : "Arrived at your destination.", Kind.OK);
		}
		// GPS has no active target. (Quest Helper draws its own line for some steps and does not
		// hand GPS a destination: set one on the map to find routes.)
		return new Status("<b>No destination set.</b><br>Search a place or amenity above, pick a Nearest button, "
			+ "right-click a spot on the world map, or shift right-click a tile in the game.", Kind.INFO);
	}

	/** Rebuilds the strip for the current routes and the plugin's state. EDT. */
	void show(List<RouteOption> routes, boolean calculating, boolean hasTarget)
	{
		// A live destination (or its routes) supersedes any lingering arrival banner.
		if (hasTarget)
		{
			showingArrival = false;
		}
		removeAll();
		Status status = status(calculating, !routes.isEmpty(),
			routes.stream().noneMatch(plugin::routeReachesTarget),
			plugin.getUnreachableCause() == AlternativeRoutesService.UnreachableCause.MISSING_UNLOCKS,
			hasTarget, showingArrival, arrivalImmediate,
			plugin.getRoutesMode() == AlternativeRoutesMode.ALL_EVERYTHING);
		if (status != null)
		{
			add(banner(icon(status.kind), status.text, accent(status.kind)));
		}

		// The sync hints (house, spirit trees, balloon logs) live here at the top: inside their
		// (collapsed) sections they were easy to miss.
		List<JPanel> warnings = new ArrayList<>();
		// Quest Helper only hands its quest-step destinations to GPS when its own "Use Shortest
		// Path plugin" option is on; with it off, quest steps silently never arrive. Dismissable
		// (the x persists via config) for users who prefer it that way.
		if (plugin.isQuestHelperPathingOff() && !plugin.getGpsConfig().questHelperBannerDismissed())
		{
			warnings.add(withDismiss(banner(RouteIcons.BANNER_WARNING,
				"Quest Helper isn't routing through GPS",
				"Turn on <b>Use Shortest Path plugin</b> in Quest Helper's settings so quest"
					+ " steps hand their destinations to GPS.",
				BANNER_WARN_ACCENT), "questHelperBannerDismissed"));
		}
		// Running the original Shortest Path plugin alongside GPS doubles the path rendering and
		// the plugin-message integrations (both answer Quest Helper's destinations).
		if (plugin.isShortestPathConflict())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Shortest Path is also enabled",
				"Both plugins draw paths and respond to the same integrations. GPS includes its "
					+ "functionality: disable Shortest Path to avoid doubled rendering.",
				BANNER_WARN_ACCENT));
		}
		// Method toggles no longer recalculate; flag a route list generated with different exclusions.
		if (!calculating && hasTarget && plugin.isRouteListStale())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Exclusions changed: press \"Refresh routes\" to apply.", BANNER_WARN_ACCENT));
		}
		// Log storage running low at the balloon stations (smart mode, synced, unlocked routes only).
		List<String> lowLogs = plugin.getBalloonLowLogTypes();
		if (!lowLogs.isEmpty())
		{
			warnings.add(balloonLowBanner.apply(lowLogs));
		}
		ShortestPathConfig cfg = plugin.getGpsConfig();
		if (cfg.usePoh() && cfg.pohSmartDetect() && !plugin.isPohScanned())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"House furniture not detected",
				"Enter your house once to auto-detect its teleport furniture.",
				BANNER_WARN_ACCENT));
		}
		if (cfg.useSpiritTrees() && cfg.spiritTreeSmartMode() && !plugin.isSpiritTreeSynced())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Planted spirit trees not synced",
				"Open a spirit tree's travel menu once to detect which trees you have planted.",
				BANNER_WARN_ACCENT));
		}
		if (cfg.useHotAirBalloons() && cfg.balloonSmartMode() && !cfg.balloonStorageSynced())
		{
			warnings.add(banner(RouteIcons.BANNER_WARNING,
				"Balloon log storage not synced",
				"Check the Log storage at a balloon station once so flights can be paid from it.",
				BANNER_WARN_ACCENT));
		}
		if (!warnings.isEmpty())
		{
			boolean hidden = cfg.hideWarningBanners();
			if (getComponentCount() > 0)
			{
				add(verticalGap(4));
			}
			add(warningToggleRow(warnings.size(), hidden));
			if (!hidden)
			{
				for (JPanel warning : warnings)
				{
					add(verticalGap(4));
					add(warning);
				}
			}
		}
		setVisible(getComponentCount() > 0);
		revalidate();
		repaint();
	}

	private static Icon icon(Kind kind)
	{
		switch (kind)
		{
			case OK:
				return RouteIcons.CHECK;
			case INFO:
				return RouteIcons.BANNER_INFO;
			case WARNING:
			default:
				return RouteIcons.BANNER_WARNING;
		}
	}

	private static Color accent(Kind kind)
	{
		switch (kind)
		{
			case OK:
				return BANNER_OK_ACCENT;
			case INFO:
				return BANNER_INFO_ACCENT;
			case WARNING:
			default:
				return BANNER_WARN_ACCENT;
		}
	}

	/**
	 * The compact row heading the warning group: a warning glyph, the count, and a chevron.
	 * Clicking it hides the banners below (leaving just this row as the reminder that warnings
	 * exist) or shows them again; the choice persists in config.
	 */
	private JPanel warningToggleRow(int count, boolean hidden)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 8, 3, 8));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(count + (count == 1 ? " warning" : " warnings") + (hidden ? " hidden" : ""),
			RouteIcons.BANNER_WARNING, JLabel.LEFT);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		row.add(label, BorderLayout.WEST);
		row.add(new JLabel(hidden ? RouteIcons.CHEVRON_RIGHT : RouteIcons.CHEVRON_DOWN), BorderLayout.EAST);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(hidden ? "Show the warnings" : "Hide the warnings (the count stays visible)");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				plugin.setPanelConfig("hideWarningBanners", !hidden);
				rerender.run();
			}
		});
		return row;
	}

	/**
	 * Adds a small persistent-dismiss x to a banner's right edge: clicking writes the given
	 * boolean config key and the next render drops the banner for good.
	 */
	private JPanel withDismiss(JPanel banner, String dismissedConfigKey)
	{
		JLabel close = new JLabel("✕");
		close.setFont(FontManager.getRunescapeSmallFont());
		close.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		close.setToolTipText("Dismiss permanently");
		close.setBorder(new EmptyBorder(0, 4, 0, 2));
		close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		close.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				plugin.setPanelConfig(dismissedConfigKey, true);
			}
		});
		// The x eats ~18px of the CENTER label's fixed HTML width: renarrow the text or its last
		// word clips under the button (screenshot report: 'plugin' became 'plu').
		for (Component comp : banner.getComponents())
		{
			if (comp instanceof JLabel && ((JLabel) comp).getText() != null
				&& ((JLabel) comp).getText().startsWith("<html>"))
			{
				JLabel text = (JLabel) comp;
				text.setText(text.getText().replace(
					"width:" + BANNER_TEXT_WIDTH + "px",
					"width:" + (BANNER_TEXT_WIDTH - 18) + "px"));
			}
		}
		banner.add(verticallyCentered(close), BorderLayout.EAST);
		// The narrower text may wrap one line further: recompute the height cap.
		banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height));
		return banner;
	}
}
