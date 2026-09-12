package gps;

import gps.transport.TransportType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

import static gps.PanelWidgets.CATALOG_MAX_HEIGHT;
import static gps.PanelWidgets.addClickRecursively;
import static gps.PanelWidgets.control;
import static gps.PanelWidgets.escapeHtml;
import static gps.PanelWidgets.methodTooltip;
import static gps.PanelWidgets.priorityHoverIcon;
import static gps.PanelWidgets.priorityRestIcon;
import static gps.PanelWidgets.priorityTooltip;
import static gps.PanelWidgets.sectionShell;
import static gps.PanelWidgets.statusMarker;
import static gps.PanelWidgets.verticallyCentered;
import static gps.PanelWidgets.wrappedLabel;

/**
 * The "Travel methods" catalog (panel series P3, out of the panel class): every teleport and
 * transport method for the current mode, grouped into collapsible categories with per-method
 * priority menus (prefer, avoid, exclude) and per-category include/exclude toggles, a text
 * filter and a funnel filter by unavailability kind. The rows scroll inside their own bounded
 * box so a long catalog never pushes the route list off screen. The route cards and the catalog
 * share one exclusion set: a route's method menu and the catalog row flip the same state.
 */
final class MethodCatalogView
{
	/** The funnel-filter options. */
	enum Filter
	{
		ALL("Show all methods", null, false),
		DISABLED("Disabled (excluded)", null, true),
		MISSING_ITEM("Missing an item", MethodAvailability.MISSING_ITEM, false),
		IN_BANK("Item in the bank", MethodAvailability.IN_BANK, false),
		MISSING_LEVEL("Missing a skill level", MethodAvailability.MISSING_LEVEL, false),
		MISSING_QUEST("Missing a quest", MethodAvailability.MISSING_QUEST, false),
		LOCKED("Not unlocked yet", MethodAvailability.LOCKED, false);

		final String label;
		// The availability kind this filter keeps (null when it does not filter by availability).
		final MethodAvailability availability;
		// True for the "disabled" filter, which keeps user-excluded methods regardless of availability.
		final boolean disabled;

		Filter(String label, MethodAvailability availability, boolean disabled)
		{
			this.label = label;
			this.availability = availability;
			this.disabled = disabled;
		}

		boolean isActive()
		{
			return this != ALL;
		}
	}

	private final ShortestPathPlugin plugin;
	// Rebuilds the whole Travel options slot (a tier or funnel change alters none of the inputs
	// the slot's dirty check watches, so the change would otherwise wait for the next render).
	private final Runnable rebuild;
	// Re-lays out the slot after the rows box changed height in place.
	private final Runnable relayout;
	// The filter box: a persistent component so typing keeps focus while only the rows below
	// repopulate. Shown only while the catalog is expanded.
	private final IconTextField search = new IconTextField();
	// The scrollable rows box of the expanded catalog; repopulated in place when the filter changes.
	private JPanel rowsPanel;
	private JScrollPane rowsScroll;
	private final Set<String> expandedCategories = new HashSet<>();
	// Collapsed by default so the routes stay the focus; the user opens it to browse and toggle.
	private boolean expanded;
	private Filter filter = Filter.ALL;
	// The inputs of the next build (see update).
	private List<TeleportMethod> catalog = List.of();
	private Set<TeleportMethod> exclusions = Set.of();
	private Map<TeleportMethod, MethodAvailability> unavailable = Map.of();

	MethodCatalogView(ShortestPathPlugin plugin, Runnable rebuild, Runnable relayout)
	{
		this.plugin = plugin;
		this.rebuild = rebuild;
		this.relayout = relayout;
		search.setIcon(IconTextField.Icon.SEARCH);
		search.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		search.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		search.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				populateRows();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				populateRows();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				populateRows();
			}
		});
	}

	/** The inputs the next {@link #section()} builds from. */
	void update(List<TeleportMethod> catalog, Set<TeleportMethod> exclusions, Map<TeleportMethod, MethodAvailability> unavailable)
	{
		this.catalog = catalog;
		this.exclusions = exclusions;
		this.unavailable = unavailable;
	}

	List<TeleportMethod> catalog()
	{
		return catalog;
	}

	Set<TeleportMethod> exclusions()
	{
		return exclusions;
	}

	Map<TeleportMethod, MethodAvailability> unavailable()
	{
		return unavailable;
	}

	boolean isExpanded()
	{
		return expanded;
	}

	/** The methods a search can actually use: usable in the current mode and not excluded. */
	int enabledCount()
	{
		int enabled = 0;
		for (TeleportMethod method : catalog)
		{
			if (isUsable(method) && !exclusions.contains(method))
			{
				enabled++;
			}
		}
		return enabled;
	}

	/** The rows box's scroll position, to carry over a rebuild (0 when there is none). */
	int scrollPosition()
	{
		return rowsScroll != null ? rowsScroll.getVerticalScrollBar().getValue() : 0;
	}

	/**
	 * Restores a carried-over scroll position after the rebuilt pane has been laid out (nested
	 * invokeLater), since a fresh scrollbar clamps everything to 0 until validation.
	 */
	void restoreScroll(int position)
	{
		if (position > 0 && rowsScroll != null)
		{
			final JScrollPane scroll = rowsScroll;
			SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(
				() -> scroll.getVerticalScrollBar().setValue(position)));
		}
	}

	/** The enclosing Travel options section collapsed: the rows box is gone with it. */
	void slotCollapsed()
	{
		rowsPanel = null;
		rowsScroll = null;
	}

	/** The catalog section a method is grouped under: teleport items split by charge model. */
	static String groupKey(TeleportMethod method)
	{
		if (method.getType() == TransportType.TELEPORTATION_ITEM)
		{
			return method.isConsumable() ? "Items (charged)" : "Items (permanent)";
		}
		return method.category();
	}

	/**
	 * Whether a method with {@code status} is usable in {@code mode}. The availability map is
	 * mode-independent (a banked item is always recorded IN_BANK); a banked item counts as usable
	 * in the "Inventory + bank" mode, whose route walks to a bank to withdraw it.
	 */
	static boolean usable(MethodAvailability status, AlternativeRoutesMode mode)
	{
		return status == null || (status == MethodAvailability.IN_BANK && mode == AlternativeRoutesMode.OWNED_WITH_BANK);
	}

	/**
	 * Whether a method passes the funnel filter (only excluded methods, or one unavailability
	 * kind) and the text filter, which matches the category or the label, case-insensitively; an
	 * empty text keeps everything.
	 */
	static boolean matches(TeleportMethod method, Filter filter, String text,
		Set<TeleportMethod> exclusions, Map<TeleportMethod, MethodAvailability> unavailable)
	{
		if (filter.disabled && !exclusions.contains(method))
		{
			return false;
		}
		if (filter.availability != null && unavailable.get(method) != filter.availability)
		{
			return false;
		}
		String needle = text.trim().toLowerCase(Locale.ROOT);
		return needle.isEmpty()
			|| method.category().toLowerCase(Locale.ROOT).contains(needle)
			|| method.label().toLowerCase(Locale.ROOT).contains(needle);
	}

	private boolean isUsable(TeleportMethod method)
	{
		return usable(unavailable.get(method), plugin.getRoutesMode());
	}

	/** The collapsible "Travel methods" section for the current inputs. */
	JPanel section()
	{
		// The headline count is the methods a search can ACTUALLY use: usable right now (not
		// missing an item, level, quest or unlock) AND not excluded, so it responds to the
		// toggles. Broken down into permanent (unlimited use) and charged (consumes a charge or
		// the item itself: tabs, charged jewellery).
		int enabled = 0;
		int usable = 0;
		int included = 0;
		int permanent = 0;
		int charged = 0;
		for (TeleportMethod method : catalog)
		{
			boolean canUse = isUsable(method);
			boolean isIncluded = !exclusions.contains(method);
			if (canUse)
			{
				usable++;
			}
			if (isIncluded)
			{
				included++;
			}
			if (canUse && isIncluded)
			{
				enabled++;
				if (method.isConsumable())
				{
					charged++;
				}
				else
				{
					permanent++;
				}
			}
		}
		// Same collapsible shell as the other Travel options sub-sections; the enabled count is
		// the state text.
		JPanel section = sectionShell("Travel methods",
			enabled + " enabled (usable and included) · " + usable + " usable now · "
				+ included + " included in searches · " + catalog.size() + " total",
			expanded, () -> expanded = !expanded,
			enabled + "/" + catalog.size(), ColorScheme.LIGHT_GRAY_COLOR, false, rebuild);

		if (!expanded)
		{
			slotCollapsed();
			section.setBorder(new EmptyBorder(0, 0, 4, 0));
			return section;
		}

		// Enabled breakdown, permanent (unlimited) against charged (consumes a charge or the
		// item). Only shown while expanded, where the split matters; the header count already
		// carries the total collapsed.
		if (enabled > 0)
		{
			JLabel breakdown = new JLabel(permanent + " permanent · " + charged + " charged");
			breakdown.setFont(FontManager.getRunescapeSmallFont());
			breakdown.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			breakdown.setToolTipText("Of the enabled methods: " + permanent + " permanent (unlimited use) · "
				+ charged + " charged (teleport tabs, charged jewellery: consumed or lose a charge)");
			breakdown.setAlignmentX(Component.LEFT_ALIGNMENT);
			breakdown.setBorder(new EmptyBorder(0, 0, 4, 0));
			section.add(breakdown);
		}

		// The filter box (persistent, see the field comment), only mounted while expanded, with a
		// funnel that opens a menu to narrow by disabled or unavailability kind.
		search.setAlignmentX(Component.LEFT_ALIGNMENT);
		search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JPanel filterWrap = new JPanel(new BorderLayout());
		filterWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterWrap.setBorder(new EmptyBorder(0, 4, 0, 2));
		filterWrap.add(control(funnel()), BorderLayout.CENTER);
		JPanel searchWrap = new JPanel(new BorderLayout());
		searchWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		searchWrap.setBorder(new EmptyBorder(0, 0, 4, 0));
		searchWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		searchWrap.add(search, BorderLayout.CENTER);
		searchWrap.add(filterWrap, BorderLayout.EAST);
		section.add(searchWrap);

		// The method rows scroll inside their own bounded box with their own scrollbar, so a long
		// (or fully expanded) catalog never pushes the route list off screen. The rows panel tracks
		// the viewport width so the scrollbar sits beside the rows instead of clipping them.
		ScrollableBox rows = new ScrollableBox(null);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JScrollPane scroll = new JScrollPane(rows,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
		rowsPanel = rows;
		rowsScroll = scroll;
		populateRows();
		section.add(scroll);
		section.setBorder(new EmptyBorder(0, 0, 8, 0));
		return section;
	}

	/**
	 * The tier context menu for {@code method}. Preferences re-rank the current list instantly
	 * (no recalculation); Exclude keeps its existing semantics (applies on the next refresh).
	 */
	void showPriorityMenu(Component anchor, TeleportMethod method)
	{
		JPopupMenu menu = new JPopupMenu();
		MethodPriority current = plugin.getMethodPriority(method);
		for (MethodPriority tier : new MethodPriority[]{
			MethodPriority.PREFER_3, MethodPriority.PREFER_2, MethodPriority.PREFER_1,
			MethodPriority.NORMAL,
			MethodPriority.AVOID_1, MethodPriority.AVOID_2, MethodPriority.AVOID_3})
		{
			String text = tier.label + (tier.chipText().isEmpty() ? "" : "  " + tier.chipText());
			JMenuItem entry = new JMenuItem(text, priorityRestIcon(tier));
			entry.setFont(tier == current ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
			// The slot only rebuilds when catalog, exclusions or availability change; a tier
			// change alters none of them, so rebuild here or the row keeps the old tier until
			// something else re-renders the panel.
			entry.addActionListener(e ->
			{
				plugin.setMethodPriority(method, tier);
				rebuild.run();
			});
			menu.add(entry);
		}
		menu.addSeparator();
		JMenuItem exclude = new JMenuItem(MethodPriority.EXCLUDED.label, RouteIcons.CROSS);
		exclude.setFont(current == MethodPriority.EXCLUDED
			? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
		exclude.addActionListener(e ->
		{
			plugin.setMethodPriority(method, MethodPriority.EXCLUDED);
			rebuild.run();
		});
		menu.add(exclude);
		menu.show(anchor, 0, anchor.getHeight());
	}

	/** The funnel icon that opens the filter menu; orange while a filter is active. */
	private JLabel funnel()
	{
		boolean active = filter.isActive();
		JLabel funnel = new JLabel(active ? RouteIcons.FILTER_ACTIVE : RouteIcons.FILTER);
		funnel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		funnel.setToolTipText("Filter: " + filter.label + " (click to change)");
		funnel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				showFilterMenu(funnel);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				funnel.setIcon(active ? RouteIcons.FILTER_ACTIVE_HOVER : RouteIcons.FILTER_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				funnel.setIcon(active ? RouteIcons.FILTER_ACTIVE : RouteIcons.FILTER);
			}
		});
		return funnel;
	}

	private void showFilterMenu(JComponent anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		menu.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		ButtonGroup group = new ButtonGroup();
		for (Filter option : Filter.values())
		{
			JRadioButtonMenuItem item = new JRadioButtonMenuItem(option.label, option == filter);
			item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			item.setForeground(Color.WHITE);
			item.setFont(FontManager.getRunescapeSmallFont());
			item.addActionListener(e ->
			{
				filter = option;
				rebuild.run();
			});
			group.add(item);
			menu.add(item);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/**
	 * (Re)fills the expanded rows box from the current filters. Called on every filter keystroke;
	 * repopulates in place so the search field keeps focus. While a filter is active, matching
	 * categories are shown force-expanded (a filter that only matched collapsed categories would
	 * otherwise look like it found nothing).
	 */
	private void populateRows()
	{
		JPanel rows = rowsPanel;
		JScrollPane scroll = rowsScroll;
		if (rows == null || scroll == null)
		{
			return;
		}
		rows.removeAll();

		String text = search.getText() == null ? "" : search.getText().trim();
		boolean filtering = !text.isEmpty();

		Map<String, List<TeleportMethod>> grouped = new TreeMap<>();
		for (TeleportMethod method : catalog)
		{
			if (matches(method, filter, text, exclusions, unavailable))
			{
				grouped.computeIfAbsent(groupKey(method), k -> new ArrayList<>()).add(method);
			}
		}
		for (List<TeleportMethod> items : grouped.values())
		{
			items.sort(Comparator.comparing(m -> m.label().toLowerCase(Locale.ROOT)));
		}

		if (grouped.isEmpty())
		{
			String message = filter.isActive() ? "No methods: " + filter.label.toLowerCase(Locale.ROOT)
				: "No methods match \"" + escapeHtml(text) + "\"";
			JLabel none = wrappedLabel("<i>" + message + "</i>");
			none.setBorder(new EmptyBorder(2, 4, 2, 0));
			none.setAlignmentX(Component.LEFT_ALIGNMENT);
			rows.add(none);
		}
		for (Map.Entry<String, List<TeleportMethod>> entry : grouped.entrySet())
		{
			String category = entry.getKey();
			List<TeleportMethod> items = entry.getValue();
			// A text filter or an active funnel filter force categories open so the matches show.
			boolean open = filtering || filter.isActive() || expandedCategories.contains(category);
			rows.add(categoryHeader(category, items, open));
			if (open)
			{
				for (TeleportMethod item : items)
				{
					rows.add(itemRow(item));
				}
			}
		}

		// Bounded height: natural size for short lists, capped so the routes below stay visible.
		int height = Math.min(rows.getPreferredSize().height + 2, CATALOG_MAX_HEIGHT);
		scroll.setPreferredSize(new Dimension(10, height));
		scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		rows.revalidate();
		rows.repaint();
		relayout.run();
	}

	private JPanel categoryHeader(String category, List<TeleportMethod> items, boolean open)
	{
		int excludedCount = 0;
		for (TeleportMethod method : items)
		{
			if (exclusions.contains(method))
			{
				excludedCount++;
			}
		}
		boolean allIncluded = excludedCount == 0;
		boolean allExcluded = excludedCount == items.size();

		JPanel row = new JPanel(new BorderLayout(3, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(3, 4, 3, 4)));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		ImageIcon icon;
		ImageIcon hover;
		String tip;
		Runnable action;
		if (allIncluded)
		{
			icon = RouteIcons.CHECK;
			hover = RouteIcons.CHECK_HOVER;
			tip = "All included: click to exclude every " + category.toLowerCase(Locale.ROOT);
			action = () -> plugin.excludeMethods(items);
		}
		else if (allExcluded)
		{
			icon = RouteIcons.CROSS;
			hover = RouteIcons.CROSS_HOVER;
			tip = "All excluded: click to include every " + category.toLowerCase(Locale.ROOT);
			action = () -> plugin.includeMethods(items);
		}
		else
		{
			icon = RouteIcons.DASH;
			hover = RouteIcons.DASH_HOVER;
			tip = (items.size() - excludedCount) + " of " + items.size() + " included: click to include all";
			action = () -> plugin.includeMethods(items);
		}
		// Chevron on the left (matching the section headers above), the include/exclude toggle at
		// the row's right edge: with the toggle up front it sat exactly where users click to
		// expand, so category toggles kept getting flipped by accident.
		row.add(control(new JLabel(open ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)), BorderLayout.WEST);

		String count = allIncluded
			? " (" + items.size() + ")"
			: " (" + (items.size() - excludedCount) + "/" + items.size() + ")";
		JLabel name = new JLabel(category + count);
		name.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		row.add(name, BorderLayout.CENTER);

		row.add(control(new IconActionLabel(icon, hover, tip, action)), BorderLayout.EAST);

		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText(open ? "Collapse" : "Expand to toggle individual methods");
		addClickRecursively(row, new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggleCategory(category);
			}
		});
		return row;
	}

	private JPanel itemRow(TeleportMethod item)
	{
		boolean excluded = exclusions.contains(item);

		JPanel row = new JPanel(new BorderLayout(3, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(2, 18, 2, 4));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

		// Priority control (replaces the old include/exclude toggle): the icon shows the current
		// tier (check = normal, stacked arrows = prefer/avoid, cross = excluded) and clicking
		// opens the tier menu (exclude is its bottom entry).
		MethodPriority tier = plugin.getMethodPriority(item);
		final IconActionLabel[] toggleHolder = new IconActionLabel[1];
		toggleHolder[0] = new IconActionLabel(priorityRestIcon(tier), priorityHoverIcon(tier),
			priorityTooltip(item.label(), tier),
			() -> showPriorityMenu(toggleHolder[0], item));
		IconActionLabel toggle = toggleHolder[0];
		// The status marker (lock/bank) stays by the name; the toggle sits at the row's right edge,
		// aligned with the category toggles, away from where users click to expand.
		MethodAvailability status = unavailable.get(item);
		if (status != null)
		{
			JLabel statusMarker = statusMarker(plugin, status, item);
			statusMarker.setBorder(new EmptyBorder(0, 0, 0, 3));
			row.add(verticallyCentered(statusMarker), BorderLayout.WEST);
		}

		JLabel text = wrappedLabel(escapeHtml(item.label()));
		text.setToolTipText(methodTooltip(item));
		if (excluded)
		{
			text.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		}
		// Centre the label at its preferred height instead of letting BorderLayout stretch it: a
		// stretched html JLabel top-anchors its text (the html view claims the full height), which
		// left the text floating high beside the vertically-centred icons.
		row.add(verticallyCentered(text), BorderLayout.CENTER);

		row.add(verticallyCentered(control(toggle)), BorderLayout.EAST);

		return row;
	}

	private void toggleCategory(String category)
	{
		if (!expandedCategories.add(category))
		{
			expandedCategories.remove(category);
		}
		// Repopulate the rows in place: cheaper than a full rebuild, and the slot's dirty check
		// (which does not track per-category expansion) would skip the rebuild anyway.
		populateRows();
	}
}
