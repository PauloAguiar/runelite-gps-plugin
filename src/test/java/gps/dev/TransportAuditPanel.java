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
	private final JLabel summary = new JLabel();
	private final JLabel capture = new JLabel();
	private final JPanel list = new JPanel();
	private String lastSignature = "";

	// Transport builder controls (values are the operator's; only the labels track the plugin).
	private final JLabel builderOrigin = new JLabel("origin: — (shift right-click)");
	private final JLabel builderDest = new JLabel("dest: —");
	private final JLabel builderObject = new JLabel("object: —");
	private final javax.swing.JTextField builderSkills = new javax.swing.JTextField();
	private final javax.swing.JTextField builderItems = new javax.swing.JTextField();
	private final javax.swing.JTextField builderQuests = new javax.swing.JTextField();
	private final javax.swing.JTextField builderDuration = new javax.swing.JTextField("1");
	private final javax.swing.JTextField builderDisplay = new javax.swing.JTextField();
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
	}

	/** How many backlog (not-in-scene) rows to show before truncating. */
	private static final int MAX_BACKLOG_ROWS = 30;

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
		save.setFont(FontManager.getRunescapeSmallFont());
		save.addActionListener(e -> builderStatus.setText(plugin.saveBuilderRow(
			builderSkills.getText().trim(), builderItems.getText().trim(),
			builderQuests.getText().trim(), builderDuration.getText().trim(),
			builderDisplay.getText().trim(), builderBothWays.isSelected())));
		JButton clear = new JButton("Clear");
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
		builderOrigin.setText("origin: " + (builderOriginText == null ? "— (shift right-click)" : builderOriginText));
		builderDest.setText("dest: " + (builderDestText == null ? "—" : builderDestText));
		builderObject.setText("object: " + (builderMenuText == null ? "—" : builderMenuText));

		long missing = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.MISSING
			|| r.state == TransportAuditPlugin.FindingState.ARMED).count();
		long doors = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.DOOR).count();
		long resolved = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.RESOLVED).count();
		long captured = rows.size() - missing - doors - resolved;
		summary.setText(rows.isEmpty()
			? "Nothing recorded yet"
			: missing + " missing, " + doors + " door(s), " + captured + " captured, " + resolved + " resolved");

		StringBuilder signature = new StringBuilder();
		for (TransportAuditPlugin.Row row : rows)
		{
			signature.append(row.id).append(':').append(row.packedTile).append(':')
				.append(row.state).append(':').append(row.live).append(':')
				.append(row.distance / 16).append(';');
		}
		if (signature.toString().equals(lastSignature))
		{
			return;
		}
		lastSignature = signature.toString();

		list.removeAll();
		boolean liveHeaderAdded = false;
		boolean backlogHeaderAdded = false;
		int backlogShown = 0;
		for (TransportAuditPlugin.Row row : rows)
		{
			if (row.live && !liveHeaderAdded)
			{
				list.add(groupLabel("In this scene"));
				liveHeaderAdded = true;
			}
			if (!row.live && !backlogHeaderAdded)
			{
				list.add(groupLabel("Recorded elsewhere"));
				backlogHeaderAdded = true;
			}
			if (!row.live && ++backlogShown > MAX_BACKLOG_ROWS)
			{
				JLabel more = groupLabel("… more in transport-audit.tsv (see audit_diff.py)");
				more.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				list.add(more);
				break;
			}
			list.add(buildRow(row));
			list.add(javax.swing.Box.createVerticalStrut(4));
		}
		list.revalidate();
		list.repaint();
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

	private JPanel buildRow(TransportAuditPlugin.Row row)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(4, 6, 4, 6));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		Color color = stateColor(row.state);
		String distance = row.distance == Integer.MAX_VALUE
			? "another floor" : (row.live ? row.distance + " tiles away" : "~" + row.distance + " tiles");
		JLabel text = new JLabel("<html><b>" + row.name + "</b> (" + row.action
			+ ") id=" + row.id
			+ "<br>@" + gps.WorldPointUtil.unpackWorldX(row.packedTile)
			+ "," + gps.WorldPointUtil.unpackWorldY(row.packedTile)
			+ "," + gps.WorldPointUtil.unpackWorldPlane(row.packedTile)
			+ " — " + distance
			+ "<br><font color='" + hex(color) + "'>" + stateText(row.state) + "</font></html>");
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setFont(FontManager.getRunescapeSmallFont());
		panel.add(text, BorderLayout.CENTER);

		JButton copy = new JButton("Copy");
		copy.setMargin(new Insets(2, 6, 2, 6));
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setToolTipText("Copy the dossier (tile, actions, transports.tsv template) to the clipboard");
		copy.addActionListener(e -> copyText(row.dossier));
		JPanel east = new JPanel(new BorderLayout());
		east.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		east.add(copy, BorderLayout.NORTH);
		panel.add(east, BorderLayout.EAST);

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
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

	static Color stateColor(TransportAuditPlugin.FindingState state)
	{
		switch (state)
		{
			case ARMED:
				return Color.YELLOW;
			case DOOR:
				return TransportAuditSceneOverlay.UNMAPPED_DOOR;
			case CAPTURED_SESSION:
				return new Color(70, 220, 90);
			case CAPTURED_PRIOR:
				return new Color(0, 190, 170);
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
			case CAPTURED_SESSION:
				return "captured this session ✓ (in transport-captures.tsv)";
			case CAPTURED_PRIOR:
				return "captured in an earlier session ✓";
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
