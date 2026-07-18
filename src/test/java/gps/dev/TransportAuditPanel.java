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

	/** EDT. Rebuilds only when the content signature changed, so scrolling isn't reset per tick. */
	void update(List<TransportAuditPlugin.Row> rows, String captureLine, Color captureColor)
	{
		capture.setText(captureLine == null ? " " : captureLine);
		capture.setForeground(captureColor == null ? ColorScheme.LIGHT_GRAY_COLOR : captureColor);

		long missing = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.MISSING
			|| r.state == TransportAuditPlugin.FindingState.ARMED).count();
		long doors = rows.stream().filter(r -> r.state == TransportAuditPlugin.FindingState.DOOR).count();
		long captured = rows.size() - missing - doors;
		summary.setText(rows.isEmpty()
			? "Scene fully mapped"
			: missing + " missing, " + doors + " door(s), " + captured + " captured");

		StringBuilder signature = new StringBuilder();
		for (TransportAuditPlugin.Row row : rows)
		{
			signature.append(row.finding.object.getId()).append(':')
				.append(row.finding.packedTemplateTile).append(':')
				.append(row.state).append(':').append(row.distance / 16).append(';');
		}
		if (signature.toString().equals(lastSignature))
		{
			return;
		}
		lastSignature = signature.toString();

		list.removeAll();
		for (TransportAuditPlugin.Row row : rows)
		{
			list.add(buildRow(row));
			list.add(javax.swing.Box.createVerticalStrut(4));
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel buildRow(TransportAuditPlugin.Row row)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(4, 6, 4, 6));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);

		Color color = stateColor(row.state);
		String distance = row.distance == Integer.MAX_VALUE
			? "another floor" : row.distance + " tiles away";
		JLabel text = new JLabel("<html><b>" + row.finding.name + "</b> (" + row.finding.action
			+ ") id=" + row.finding.object.getId()
			+ "<br>@" + gps.WorldPointUtil.unpackWorldX(row.finding.packedTemplateTile)
			+ "," + gps.WorldPointUtil.unpackWorldY(row.finding.packedTemplateTile)
			+ "," + gps.WorldPointUtil.unpackWorldPlane(row.finding.packedTemplateTile)
			+ " — " + distance
			+ "<br><font color='" + hex(color) + "'>" + stateText(row.state) + "</font></html>");
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setFont(FontManager.getRunescapeSmallFont());
		panel.add(text, BorderLayout.CENTER);

		JButton copy = new JButton("Copy");
		copy.setMargin(new Insets(2, 6, 2, 6));
		copy.setFont(FontManager.getRunescapeSmallFont());
		copy.setToolTipText("Copy the dossier (tile, actions, transports.tsv template) to the clipboard");
		copy.addActionListener(e -> plugin.copyDossier(row.finding));
		JPanel east = new JPanel(new BorderLayout());
		east.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		east.add(copy, BorderLayout.NORTH);
		panel.add(east, BorderLayout.EAST);

		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
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
			default:
				return "missing — use it to auto-capture, or Copy";
		}
	}

	private static String hex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}
}
