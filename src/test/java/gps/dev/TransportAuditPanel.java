package gps.dev;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar panel for the dev transport audit: the current scene's findings as a state-colored
 * list — red missing, yellow armed, orange unregistered door, green captured this session, teal
 * captured in an earlier session — nearest first, each with a Copy button for the dossier.
 * Updated with immutable snapshots pushed from the client thread once per game tick.
 */
class TransportAuditPanel extends PluginPanel
{
	private final TransportAuditPlugin plugin;
	// Selected row key (packedTile<<20|id), or 0: highlighted and shown in the detail card.
	private long selectedKey;
	private static final Color SELECTED_BG = new Color(0x2E, 0x3E, 0x5E);
	// Set when the selection moves (click/arrows) so the next rebuild scrolls the row into view.
	private boolean scrollSelectionIntoView;
	private final JLabel summary = new JLabel();
	private final JLabel capture = new JLabel();
	private final JPanel list = new JPanel();
	// The detail card must never drive the panel's size: a wrapped text area still REPORTS its
	// longest line as preferred width, and BorderLayout propagates SOUTH's preference upward —
	// which stretched the whole sidebar when a card popped in. Width is pinned to zero here
	// (BorderLayout stretches SOUTH to the real container width regardless).
	private final JPanel detail = new JPanel()
	{
		@Override
		public Dimension getPreferredSize()
		{
			Dimension size = super.getPreferredSize();
			size.width = 0;
			return size;
		}
	};
	private List<TransportAuditPlugin.Row> currentRows = List.of();
	private String lastSignature = "";
	private final net.runelite.client.ui.components.IconTextField searchBar =
		new net.runelite.client.ui.components.IconTextField();
	private String filterText = "";

	// Transport builder controls (values are the operator's; only the labels track the plugin).
	private final JLabel builderOrigin = new JLabel("origin: — (shift right-click)");
	private final JLabel builderDest = new JLabel("dest: —");
	private final JLabel builderObject = new JLabel("object: —");
	private final javax.swing.JTextField builderSkills = new javax.swing.JTextField();
	private final javax.swing.JTextField builderItems = new javax.swing.JTextField();
	private final javax.swing.JTextField builderQuests = new javax.swing.JTextField();
	private final javax.swing.JTextField builderDuration = new javax.swing.JTextField("1");
	private final javax.swing.JTextField builderDisplay = new javax.swing.JTextField();
	private final javax.swing.JTextField builderNote = new javax.swing.JTextField();
	private final javax.swing.JCheckBox builderBothWays = new javax.swing.JCheckBox("also save reverse row", true);
	private final JLabel builderStatus = new JLabel(" ");

	TransportAuditPanel(TransportAuditPlugin plugin)
	{
		super(false);
		this.plugin = plugin;
		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Transport audit");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(title);
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(summary);
		capture.setFont(FontManager.getRunescapeSmallFont());
		capture.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(capture);
		javax.swing.JCheckBox knownToggle = new javax.swing.JCheckBox("Show known data nearby", false);
		knownToggle.setFocusable(false);
		knownToggle.setBackground(ColorScheme.DARK_GRAY_COLOR);
		knownToggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		knownToggle.setFont(FontManager.getRunescapeSmallFont());
		knownToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
		knownToggle.setToolTipText("Debugging: list the nearest curated transport rows (cyan) and"
			+ " mark their origins in the world; select one to spotlight its origin and landing");
		knownToggle.addActionListener(e -> plugin.showKnown = knownToggle.isSelected());
		top.add(knownToggle);
		// ONE search box over the whole list — findings, backlog, meta and known rows alike.
		// Deliberately focusable (typing is the point); clicking the game world releases focus.
		searchBar.setIcon(net.runelite.client.ui.components.IconTextField.Icon.SEARCH);
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		searchBar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				applyFilter();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				applyFilter();
			}
		});
		top.add(searchBar);
		top.add(buildBuilderSection());
		add(top, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel listAnchor = new JPanel(new BorderLayout());
		listAnchor.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listAnchor.add(list, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(listAnchor,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		// The detail card: one place for the selected entry's full story and its actions.
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setVisible(false);
		add(detail, BorderLayout.SOUTH);

		// NO keyboard navigation and NO focus grabs: the sidebar shares the window with the game
		// canvas, so any component holding keyboard focus kills camera keys until the player
		// clicks back into the world. Mouse only: click selects, double-click routes.
	}

	/** How many backlog (not-in-scene) rows to show before truncating. */
	private static final int MAX_BACKLOG_ROWS = 30;
	/** Per-group ceiling while a search filter is active — matched rows, not a fixed window. */
	private static final int MAX_FILTERED_ROWS = 100;

	/**
	 * The manual transport builder: origin/destination/object come from shift right-clicks in
	 * the world; requirements, duration and display info are typed here; Save appends a
	 * review-ready transports.tsv row (plus the reverse when two-way) to transport-captures.tsv.
	 */
	private JPanel buildBuilderSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		section.setBorder(new EmptyBorder(6, 6, 6, 6));
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel title = new JLabel("Transport builder");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeSmallFont());
		section.add(title);
		for (JLabel label : new JLabel[]{builderOrigin, builderDest, builderObject})
		{
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setAlignmentX(Component.LEFT_ALIGNMENT);
			section.add(label);
		}
		section.add(fieldRow("Skills", builderSkills, "e.g. \"72 Agility\""));
		section.add(fieldRow("Items", builderItems, "e.g. \"Rope\" or \"Coins>=10\""));
		section.add(fieldRow("Quests", builderQuests, "e.g. \"Underground Pass\""));
		section.add(fieldRow("Ticks", builderDuration, "traversal duration in game ticks"));
		section.add(fieldRow("Info", builderDisplay, "Display info (route/card label)"));
		section.add(fieldRow("Note", builderNote,
			"Advisory shown on the route step (\"fire arrow needed\", \"can fail\") — does NOT gate the route"));
		builderBothWays.setFocusable(false);
		builderBothWays.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		builderBothWays.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		builderBothWays.setFont(FontManager.getRunescapeSmallFont());
		builderBothWays.setAlignmentX(Component.LEFT_ALIGNMENT);
		builderBothWays.setToolTipText("Rows are directional: a one-way transport is one row; "
			+ "two-way transports need the reverse row too");
		section.add(builderBothWays);

		JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton save = new JButton("Save row");
		save.setFocusable(false);
		save.setFont(FontManager.getRunescapeSmallFont());
		save.addActionListener(e -> builderStatus.setText(plugin.saveBuilderRow(
			builderSkills.getText().trim(), builderItems.getText().trim(),
			builderQuests.getText().trim(), builderDuration.getText().trim(),
			builderDisplay.getText().trim(), builderNote.getText().trim(),
			builderBothWays.isSelected())));
		JButton clear = new JButton("Clear");
		clear.setFocusable(false);
		clear.setFont(FontManager.getRunescapeSmallFont());
		clear.addActionListener(e -> {
			plugin.clearBuilder();
			builderStatus.setText(" ");
		});
		buttons.add(save);
		buttons.add(clear);
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, save.getPreferredSize().height + 4));
		section.add(buttons);
		builderStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		builderStatus.setFont(FontManager.getRunescapeSmallFont());
		builderStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(builderStatus);
		return section;
	}

	private JPanel fieldRow(String caption, javax.swing.JTextField field, String tooltip)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(caption);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setPreferredSize(new Dimension(40, label.getPreferredSize().height));
		row.add(label, BorderLayout.WEST);
		field.setFont(FontManager.getRunescapeSmallFont());
		field.setToolTipText(tooltip);
		row.add(field, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 4));
		return row;
	}

	/** EDT. Rebuilds only when the content signature changed, so scrolling isn't reset per tick. */
	void update(List<TransportAuditPlugin.Row> rows, String captureLine, Color captureColor,
		String builderOriginText, String builderDestText, String builderMenuText)
	{
		capture.setText(captureLine == null ? " " : captureLine);
		capture.setForeground(captureColor == null ? ColorScheme.LIGHT_GRAY_COLOR : captureColor);
		builderOrigin.setText("origin: " + (builderOriginText == null ? "\u2014 (shift right-click)" : builderOriginText));
		builderDest.setText("dest: " + (builderDestText == null ? "\u2014" : builderDestText));
		builderObject.setText("object: " + (builderMenuText == null ? "\u2014" : builderMenuText));

		long missing = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.MISSING
			|| r.state == TransportAuditPlugin.FindingState.ARMED).count();
		long doors = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.DOOR).count();
		long resolved = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.RESOLVED).count();
		long known = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.KNOWN).count();
		long captured = rows.size() - missing - doors - resolved - known;
		summary.setText(rows.isEmpty()
			? "Nothing recorded yet"
			: missing + " missing, " + doors + " door(s), " + captured + " captured, " + resolved + " resolved");

		currentRows = rows;
		String signature = computeSignature(rows);
		if (signature.equals(lastSignature))
		{
			return;
		}
		lastSignature = signature;
		rebuildList();
	}

	private String computeSignature(List<TransportAuditPlugin.Row> rows)
	{
		StringBuilder signature = new StringBuilder();
		for (TransportAuditPlugin.Row row : rows)
		{
			signature.append(row.id).append(':').append(row.packedTile).append(':')
				.append(row.state).append(':').append(row.live).append(':')
				.append(row.distance / 16).append(';');
		}
		return signature.append("sel=").append(selectedKey)
			.append(";f=").append(filterText).toString();
	}

	/** Search keystrokes re-render immediately and hand the text to the plugin's known-data trim. */
	private void applyFilter()
	{
		filterText = searchBar.getText().trim().toLowerCase(java.util.Locale.ROOT);
		plugin.listFilter = filterText;
		lastSignature = computeSignature(currentRows);
		rebuildList();
	}

	private boolean matchesFilter(TransportAuditPlugin.Row row)
	{
		if (filterText.isEmpty())
		{
			return true;
		}
		String haystack = (row.name + ' ' + row.action + ' ' + row.id + ' '
			+ gps.WorldPointUtil.unpackWorldX(row.packedTile) + ','
			+ gps.WorldPointUtil.unpackWorldY(row.packedTile) + ','
			+ gps.WorldPointUtil.unpackWorldPlane(row.packedTile) + ' '
			+ stateText(row.state)).toLowerCase(java.util.Locale.ROOT);
		return haystack.contains(filterText);
	}

	private static String groupOf(TransportAuditPlugin.Row row)
	{
		if (row.live)
		{
			return "In this scene";
		}
		return row.state == TransportAuditPlugin.FindingState.KNOWN
			? "Known data (curated)" : "Recorded elsewhere";
	}

	private void rebuildList()
	{
		list.removeAll();
		// Caps are PER GROUP so the backlog can't starve the known-data section (KNOWN sorts
		// last), and searching lifts them \u2014 a filtered list should show what it matched.
		boolean filtering = !filterText.isEmpty();
		int backlogShown = 0;
		int shown = 0;
		String currentGroup = null;
		boolean truncated = false;
		JPanel selectedPanel = null;
		for (TransportAuditPlugin.Row row : currentRows)
		{
			if (!matchesFilter(row))
			{
				continue;
			}
			String group = groupOf(row);
			if (!group.equals(currentGroup))
			{
				list.add(groupLabel(group));
				currentGroup = group;
				truncated = false;
			}
			if (truncated)
			{
				continue;
			}
			boolean backlog = !row.live
				&& row.state != TransportAuditPlugin.FindingState.KNOWN;
			if ((!filtering && backlog && ++backlogShown > MAX_BACKLOG_ROWS)
				|| (filtering && ++shown > MAX_FILTERED_ROWS))
			{
				JLabel more = groupLabel(filtering
					? "\u2026 narrow the search to see the rest"
					: "\u2026 more in transport-audit.tsv (see audit_diff.py)");
				more.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				list.add(more);
				truncated = true;
				continue;
			}
			JPanel rowPanel = buildRow(row);
			if (keyOf(row) == selectedKey)
			{
				selectedPanel = rowPanel;
			}
			list.add(rowPanel);
		}
		if (currentGroup == null && filtering)
		{
			JLabel none = groupLabel("No matches");
			none.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			list.add(none);
		}
		list.revalidate();
		list.repaint();
		renderDetail();
		// Scroll only on selection changes — snapping the viewport on every content rebuild
		// would fight the user's own scrolling.
		if (selectedPanel != null && scrollSelectionIntoView)
		{
			scrollSelectionIntoView = false;
			final JPanel target = selectedPanel;
			javax.swing.SwingUtilities.invokeLater(() ->
				list.scrollRectToVisible(target.getBounds()));
		}
	}

	private static JLabel groupLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setBorder(new EmptyBorder(6, 0, 3, 0));
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static long keyOf(TransportAuditPlugin.Row row)
	{
		return ((long) row.packedTile << 20) | row.id;
	}

	/**
	 * ONE compact line per entry: a state dot, the name, and the distance \u2014 details and
	 * actions live in the detail card below. Click selects, double-click routes, arrows navigate.
	 */
	private JPanel buildRow(TransportAuditPlugin.Row row)
	{
		final long key = keyOf(row);
		final boolean selected = key == selectedKey;
		JPanel panel = new JPanel(new BorderLayout(5, 0));
		panel.setBackground(selected ? SELECTED_BG : ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 6, 2, 6));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

		JLabel dot = new JLabel("\u25CF");
		dot.setForeground(stateColor(row.state));
		dot.setFont(FontManager.getRunescapeSmallFont());
		panel.add(dot, BorderLayout.WEST);

		JLabel name = new JLabel(row.name);
		name.setForeground(selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		name.setFont(FontManager.getRunescapeSmallFont());
		panel.add(name, BorderLayout.CENTER);

		JLabel distance = new JLabel(row.distance == Integer.MAX_VALUE
			? "\u2191\u2193" : row.distance + "t");
		distance.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		distance.setFont(FontManager.getRunescapeSmallFont());
		panel.add(distance, BorderLayout.EAST);

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		panel.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				if (e.getClickCount() >= 2)
				{
					plugin.routeTo(row.packedTile);
					builderStatus.setText("Routing GPS to " + row.name);
					return;
				}
				selectRow(key);
			}
		});
		return panel;
	}

	/** Selects a row by key: highlight, detail card, and (for known rows) the world spotlight. */
	private void selectRow(long key)
	{
		selectedKey = key;
		scrollSelectionIntoView = true;
		TransportAuditPlugin.Row row = selectedRow();
		if (row != null && row.state == TransportAuditPlugin.FindingState.KNOWN)
		{
			plugin.spotlightKnownAt(row.packedTile);
		}
		// Immediate feedback: restyle now instead of waiting for the next tick snapshot.
		lastSignature = computeSignature(currentRows);
		rebuildList();
	}

	private TransportAuditPlugin.Row selectedRow()
	{
		for (TransportAuditPlugin.Row row : currentRows)
		{
			if (keyOf(row) == selectedKey)
			{
				return row;
			}
		}
		return null;
	}

	/** The detail card: everything about the selected row, plus its actions \u2014 rendered ONCE. */
	private void renderDetail()
	{
		detail.removeAll();
		TransportAuditPlugin.Row row = selectedRow();
		if (row == null)
		{
			detail.setVisible(false);
			detail.revalidate();
			detail.repaint();
			return;
		}
		detail.setVisible(true);
		Color color = stateColor(row.state);
		detail.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			javax.swing.BorderFactory.createMatteBorder(0, 3, 0, 0, color),
			new EmptyBorder(4, 6, 6, 6)));

		JLabel title = new JLabel(htmlWrap(
			row.name + "  (" + row.action + (row.id > 0 ? " id=" + row.id : "") + ")"));
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(title);

		JLabel state = new JLabel(stateText(row.state));
		state.setForeground(color);
		state.setFont(FontManager.getRunescapeSmallFont());
		state.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(state);

		JLabel where = new JLabel("@" + gps.WorldPointUtil.unpackWorldX(row.packedTile)
			+ "," + gps.WorldPointUtil.unpackWorldY(row.packedTile)
			+ "," + gps.WorldPointUtil.unpackWorldPlane(row.packedTile)
			+ (row.distance == Integer.MAX_VALUE ? " (another floor)" : " \u2014 " + row.distance + " tiles"));
		where.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		where.setFont(FontManager.getRunescapeSmallFont());
		where.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(where);

		// HTML label, not a JTextArea: a wrapped text area still reports its longest line as
		// preferred width (stretching the sidebar), and its preferred height is computed
		// pre-wrap (clipping). A width-constrained HTML body wraps AND sizes correctly.
		JLabel info = new JLabel(htmlWrap(row.dossier));
		info.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		info.setFont(FontManager.getRunescapeSmallFont());
		info.setAlignmentX(Component.LEFT_ALIGNMENT);
		info.setBorder(new EmptyBorder(3, 0, 3, 0));
		detail.add(info);

		JPanel actions = new JPanel(new java.awt.GridLayout(0, 3, 4, 3));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.add(rowButton("Go", "Route GPS to this tile (double-click a row does this too)",
			() -> plugin.routeTo(row.packedTile)));
		actions.add(rowButton("Copy", "Copy the dossier to the clipboard",
			() -> copyText(row.dossier)));
		if (row.state != TransportAuditPlugin.FindingState.KNOWN)
		{
			actions.add(rowButton("Builder", "Load into the transport builder",
				() -> builderStatus.setText(plugin.loadIntoBuilder(row))));
			actions.add(rowButton("Ignore", "Not a transport \u2014 never flag this object again",
				() -> plugin.ignoreEntry(row.id, row.packedTile, row.name)));
		}
		if (row.state == TransportAuditPlugin.FindingState.CAPTURED_ONE_WAY
			|| row.state == TransportAuditPlugin.FindingState.DATA_ONE_WAY)
		{
			actions.add(rowButton("1-way", "No reverse exists in-game \u2014 mark complete",
				() -> builderStatus.setText(plugin.markNoReverse(row.id, row.packedTile))));
		}
		actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, actions.getPreferredSize().height));
		detail.add(actions);
		detail.revalidate();
		detail.repaint();
	}

	/**
	 * Wraps text for a JLabel at a width that fits the sidebar: escaped, newlines kept, and
	 * the body width pinned so long dossier lines fold instead of widening the panel.
	 */
	static String htmlWrap(String text)
	{
		String escaped = text
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\n", "<br>");
		return "<html><body style='width:165px'>" + escaped + "</body></html>";
	}

	private JButton rowButton(String label, String tooltip, Runnable action)
	{
		JButton button = new JButton(label);
		button.setMargin(new Insets(2, 6, 2, 6));
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setToolTipText(tooltip);
		button.setFocusable(false); // keep keyboard focus on the game canvas after a click
		button.addActionListener(e -> action.run());
		return button;
	}

	private static void copyText(String text)
	{
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new java.awt.datatransfer.StringSelection(text), null);
	}

	/**
	 * EDT (from the shift right-click "Builder: add item req" menu entry). Appends an item
	 * requirement in the transports.tsv Items format; & = AND (edit to | for alternatives, and
	 * adjust the quantity as needed).
	 */
	void appendBuilderItem(int itemId, String itemName)
	{
		String current = builderItems.getText().trim();
		builderItems.setText(current.isEmpty() ? itemId + "=1" : current + "&" + itemId + "=1");
		builderStatus.setText("added item: " + itemName + " (" + itemId + ")");
	}

	/**
	 * EDT (from a refusal message while a capture was armed). Fills empty requirement fields
	 * with what the game just said; never overwrites operator-typed values.
	 */
	void suggestRequirements(String skill, String quest, String rawMessage)
	{
		if (skill != null && builderSkills.getText().trim().isEmpty())
		{
			builderSkills.setText(skill);
		}
		if (quest != null && builderQuests.getText().trim().isEmpty())
		{
			builderQuests.setText(quest);
		}
		String text = rawMessage.length() > 60 ? rawMessage.substring(0, 57) + "…" : rawMessage;
		builderStatus.setText("<html>requirement: " + text + "</html>");
	}

	static Color stateColor(TransportAuditPlugin.FindingState state)
	{
		switch (state)
		{
			case ARMED:
				return Color.YELLOW;
			case DOOR:
				return TransportAuditSceneOverlay.UNMAPPED_DOOR;
			case CONFIRM:
				return new Color(176, 128, 255);
			case CAPTURED_ONE_WAY:
			case DATA_ONE_WAY:
				return new Color(190, 220, 60);
			case CAPTURED_SESSION:
				return new Color(70, 220, 90);
			case CAPTURED_PRIOR:
				return new Color(0, 190, 170);
			case KNOWN:
				return new Color(80, 200, 255);
			case RESOLVED:
				return ColorScheme.MEDIUM_GRAY_COLOR;
			default:
				return TransportAuditSceneOverlay.UNMAPPED;
		}
	}

	private static String stateText(TransportAuditPlugin.FindingState state)
	{
		switch (state)
		{
			case ARMED:
				return "capture armed — traverse it now";
			case DOOR:
				return "door not in registry — re-run doorDump";
			case CONFIRM:
				return "machine-derived — traverse with the audit on to confirm";
			case CAPTURED_ONE_WAY:
				return "captured ONE-WAY ✓ — walk the reverse to complete";
			case DATA_ONE_WAY:
				return "data covers ONE direction — traverse the other way to capture it";
			case CAPTURED_SESSION:
				return "captured this session ✓ (in transport-captures.tsv)";
			case CAPTURED_PRIOR:
				return "captured in an earlier session ✓";
			case KNOWN:
				return "curated data — select to spotlight in the world";
			case RESOLVED:
				return "covered by current data ✓ (prunable)";
			default:
				return "missing — use it to auto-capture, or Copy";
		}
	}

	private static String hex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}
}
