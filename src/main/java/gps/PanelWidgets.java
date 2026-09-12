package gps;

import gps.transport.TransportType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Set;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The side panel's shared chrome (panel series P1): the sizes and accent colours every section
 * uses, and the small stateless builders (a centred cell, a control label, a subtle button, a
 * wrapped label, the category dots). Built on the tile-packs style: small icon controls with
 * hover states and tooltips.
 */
final class PanelWidgets
{
	static final int CONTROL_SIZE = 18;
	static final int METHOD_TEXT_WIDTH = 132;
	// Wrap width for message-banner text: the sidebar content (~192px after the panel's outer
	// padding and scrollbar) minus the banner's accent bar, paddings, icon and gap (~40px), with
	// slack for font-metric variance; wrapping a line early is invisible, clipping is not.
	static final int BANNER_TEXT_WIDTH = 138;
	static final Color BANNER_INFO_ACCENT = new Color(0x4C, 0x8B, 0xF5);   // GPS blue
	static final Color BANNER_WARN_ACCENT = new Color(0xFF, 0x98, 0x1F);   // amber
	static final Color BANNER_OK_ACCENT = new Color(0x4C, 0xAF, 0x50);     // green
	// Tallest the expanded teleport-methods box may grow before it scrolls internally.
	static final int CATALOG_MAX_HEIGHT = 240;

	// Neutral dot colour for walking legs; deliberately outside the category palette so walking
	// does not masquerade as a teleport category.
	static final Color WALK_DOT_COLOUR = new Color(0x9E, 0x9E, 0x9E);
	static final Color SAIL_DOT_COLOUR = new Color(0x2E, 0x86, 0xC1);
	// Teleport-item dots are coloured by charge model, permanent (reusable) against charged
	// (consumes a charge or the item), so the two read apart in a route card.
	static final Color PERMANENT_ITEM_DOT = new Color(0x4D, 0xB6, 0xAC); // teal
	static final Color CHARGED_ITEM_DOT = new Color(0xF2, 0xC1, 0x4E);   // amber

	// Stable, distinct-ish palette; categories without a fixed colour hash into it so the same
	// category always gets the same dot colour.
	private static final Color[] CATEGORY_PALETTE =
	{
		new Color(0x5B, 0x9B, 0xD5), // blue
		new Color(0x4C, 0xAF, 0x50), // green
		new Color(0xE9, 0x7D, 0x3B), // orange
		new Color(0xB4, 0x6F, 0xD4), // purple
		new Color(0x4D, 0xB6, 0xAC), // teal
		new Color(0xE5, 0x73, 0x99), // pink
		new Color(0xC0, 0xA8, 0x3B), // gold
		new Color(0x7E, 0x8C, 0x9A), // slate
		new Color(0x8B, 0xC3, 0x4A), // lime
		new Color(0xD1, 0x5B, 0x5B), // red
	};

	private PanelWidgets()
	{
	}

	/**
	 * Wraps a component so that, in a BorderLayout WEST/EAST cell (stretched to the row's full
	 * height), it sits vertically centred against the possibly two-line label in CENTER, while
	 * staying left-aligned horizontally.
	 */
	static JPanel verticallyCentered(Component content)
	{
		JPanel wrap = new JPanel(new GridBagLayout());
		wrap.setOpaque(false);
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.anchor = GridBagConstraints.WEST;
		wrap.add(content, gbc);
		return wrap;
	}

	/** Sizes an icon label as a small square control. */
	static JLabel control(JLabel label)
	{
		label.setPreferredSize(new Dimension(CONTROL_SIZE, CONTROL_SIZE));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		return label;
	}

	/** Shared subtle-button chrome: small font, outline, tight padding, hand cursor, hover lift. */
	static JButton subtleButton(JButton button)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(Color.WHITE);
		button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		button.setFocusPainted(false);
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 8, 3, 8)));
		button.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				button.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				button.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}
		});
		return button;
	}

	/** The first JTextField inside a composite component (IconTextField hides its own). */
	static JTextField innerTextField(Container root)
	{
		for (Component component : root.getComponents())
		{
			if (component instanceof JTextField)
			{
				return (JTextField) component;
			}
			if (component instanceof Container)
			{
				JTextField inner = innerTextField((Container) component);
				if (inner != null)
				{
					return inner;
				}
			}
		}
		return null;
	}

	/** A small white section title with the sections' vertical rhythm. */
	static JLabel sectionLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(Color.WHITE);
		label.setBorder(new EmptyBorder(6, 0, 4, 0));
		return label;
	}

	/** Left-aligns a component and lets it stretch to the full width at its preferred height. */
	static JComponent fullWidth(JComponent component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
		return component;
	}

	/** A small grey label wrapped at the method text width, top-aligned for two-line rows. */
	static JLabel wrappedLabel(String innerHtml)
	{
		JLabel label = new JLabel("<html><body style='width:" + METHOD_TEXT_WIDTH + "px'>" + innerHtml + "</body></html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setVerticalAlignment(SwingConstants.TOP);
		return label;
	}

	/**
	 * A full-width, left-aligned note row for a route card. Bare JLabels must not be added straight
	 * into the vertical BoxLayout: they do not stretch and default to centred alignment, which floats
	 * them into odd positions and clips them at the card edge.
	 */
	static JPanel noteRow(String innerHtml, String tooltip)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		JLabel text = wrappedLabel(innerHtml);
		if (tooltip != null)
		{
			text.setToolTipText(tooltip);
		}
		row.add(text, BorderLayout.WEST);
		return row;
	}

	static String escapeHtml(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** A fixed-height spacer in the panel's background colour. */
	static Component verticalGap(int height)
	{
		JPanel gap = new JPanel();
		gap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		gap.setPreferredSize(new Dimension(1, height));
		return gap;
	}

	/** A 9px rounded dot icon in {@code colour}. */
	static Icon dot(Color colour)
	{
		final int s = 9;
		BufferedImage image = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(colour);
		g.fillRoundRect(0, 1, s - 1, s - 2, 4, 4);
		g.dispose();
		return new ImageIcon(image);
	}

	static Icon categoryDot(String category)
	{
		return dot(categoryColour(category));
	}

	/** The category dot for a route-card method, splitting teleport items by charge model. */
	static Icon methodDot(TeleportMethod method)
	{
		if (method.getType() == TransportType.TELEPORTATION_ITEM)
		{
			return dot(method.isConsumable() ? CHARGED_ITEM_DOT : PERMANENT_ITEM_DOT);
		}
		return categoryDot(method.category());
	}

	/**
	 * Fixed, deliberately-distinct colour per known category (the old hash assignment gave
	 * "Boats & ships" the exact same teal as the permanent-item dot). Items are teal to match the
	 * permanent-item dot; charged items get the amber dot via {@link #methodDot}. The hashed
	 * palette remains only as a fallback for categories added later.
	 */
	static Color categoryColour(String category)
	{
		switch (category)
		{
			case "Spells": return new Color(0x5B, 0x9B, 0xD5);          // blue
			case "Items": return PERMANENT_ITEM_DOT;                     // teal (charged = amber)
			case "Jewellery box": return new Color(0xB4, 0x6F, 0xD4);   // purple
			case "Levers": return new Color(0xD1, 0x5B, 0x5B);          // red
			case "Minigame teleports": return new Color(0xE5, 0x73, 0x99); // pink
			case "Portals": return new Color(0x9C, 0x7B, 0xE8);         // violet
			case "Fairy rings": return new Color(0x4C, 0xAF, 0x50);     // green
			case "Spirit trees": return new Color(0x8B, 0xC3, 0x4A);    // lime
			case "Gnome gliders": return new Color(0xC9, 0x69, 0xC9);   // magenta
			case "Hot air balloons": return new Color(0xE9, 0x7D, 0x3B); // orange
			case "Magic carpets": return new Color(0xB0, 0x3A, 0x5B);   // wine
			case "Mushtrees": return new Color(0xE0, 0x60, 0x60);       // light red
			case "Minecarts": return new Color(0x60, 0x7D, 0x8B);       // slate
			case "Mountain guides": return new Color(0x8D, 0x6E, 0x63); // mountain brown
			case "Quetzals": return new Color(0x4A, 0xC6, 0xE0);        // cyan
			case "Obelisks": return new Color(0x9A, 0xA5, 0xB1);        // steel
			case "Boats & ships": return new Color(0x5C, 0x6B, 0xC0);   // indigo, NOT the item teal
			case "Canoes": return new Color(0xB5, 0x79, 0x3B);          // wood brown
			case "Seasonal": return new Color(0x94, 0xB4, 0x4A);        // olive
			default: return CATEGORY_PALETTE[Math.floorMod(category.hashCode(), CATEGORY_PALETTE.length)];
		}
	}

	/**
	 * A message banner: a coloured left accent bar, an icon, and wrapped text; used for status
	 * and warnings instead of loose labels.
	 */
	static JPanel banner(Icon icon, String innerHtml, Color accent)
	{
		JPanel banner = new JPanel(new BorderLayout(7, 0));
		banner.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		banner.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			new EmptyBorder(5, 7, 5, 6)));
		banner.setAlignmentX(Component.LEFT_ALIGNMENT);

		// The icon sits vertically centred against the (possibly multi-line) text.
		banner.add(verticallyCentered(new JLabel(icon)), BorderLayout.WEST);

		JLabel text = new JLabel("<html><body style='width:" + BANNER_TEXT_WIDTH + "px'>" + innerHtml + "</body></html>");
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		banner.add(text, BorderLayout.CENTER);

		banner.setMaximumSize(new Dimension(Integer.MAX_VALUE, banner.getPreferredSize().height));
		return banner;
	}

	/**
	 * A titled banner: a bold white title on the first line, the description beneath it. For
	 * warnings and notices that read better as heading plus body than one run.
	 */
	static JPanel banner(Icon icon, String title, String body, Color accent)
	{
		String html = "<font color='#FFFFFF'><b>" + escapeHtml(title) + "</b></font>";
		if (body != null && !body.isEmpty())
		{
			html += "<br>" + body;
		}
		return banner(icon, html, accent);
	}

	/**
	 * Collapsible shell shared by the configuration sections: a clickable header row (chevron,
	 * title, coloured state text) that runs {@code toggle} to flip the caller's expanded flag,
	 * then {@code afterToggle} to rebuild. The caller adds the body when expanded.
	 * {@code headline} styles the title like the panel's top-level section headers (bold, brand
	 * orange), used by the "Travel options" section that groups the others.
	 */
	static JPanel sectionShell(String title, String tooltip, boolean expanded, Runnable toggle,
		String stateText, Color stateColor, boolean headline, Runnable afterToggle)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setBackground(ColorScheme.DARK_GRAY_COLOR);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel titleRow = new JPanel(new BorderLayout(5, 0));
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setBorder(new EmptyBorder(0, 0, 4, 0));
		titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		titleRow.add(control(new JLabel(expanded ? RouteIcons.CHEVRON_DOWN : RouteIcons.CHEVRON_RIGHT)),
			BorderLayout.WEST);
		JLabel titleLabel = new JLabel(title);
		if (headline)
		{
			titleLabel.setFont(FontManager.getRunescapeBoldFont());
			titleLabel.setForeground(ColorScheme.BRAND_ORANGE);
		}
		else
		{
			titleLabel.setForeground(Color.WHITE);
		}
		titleRow.add(titleLabel, BorderLayout.CENTER);
		JLabel state = new JLabel(stateText);
		state.setForeground(stateColor);
		titleRow.add(state, BorderLayout.EAST);
		titleRow.setToolTipText(tooltip);
		titleRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		titleRow.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				toggle.run();
				afterToggle.run();
			}
		});
		section.add(titleRow);
		return section;
	}

	/** The rest icon of a priority tier: stacked arrows for prefer/avoid, a cross when excluded. */
	static ImageIcon priorityRestIcon(MethodPriority tier)
	{
		switch (tier)
		{
			case PREFER_1:
				return RouteIcons.PRIORITY_UP_ICONS[0];
			case PREFER_2:
				return RouteIcons.PRIORITY_UP_ICONS[1];
			case PREFER_3:
				return RouteIcons.PRIORITY_UP_ICONS[2];
			case AVOID_1:
				return RouteIcons.PRIORITY_DOWN_ICONS[0];
			case AVOID_2:
				return RouteIcons.PRIORITY_DOWN_ICONS[1];
			case AVOID_3:
				return RouteIcons.PRIORITY_DOWN_ICONS[2];
			case EXCLUDED:
				return RouteIcons.CROSS;
			default:
				return RouteIcons.PRIORITY_NEUTRAL;
		}
	}

	static ImageIcon priorityHoverIcon(MethodPriority tier)
	{
		switch (tier)
		{
			case PREFER_1:
				return RouteIcons.PRIORITY_UP_HOVER_ICONS[0];
			case PREFER_2:
				return RouteIcons.PRIORITY_UP_HOVER_ICONS[1];
			case PREFER_3:
				return RouteIcons.PRIORITY_UP_HOVER_ICONS[2];
			case AVOID_1:
				return RouteIcons.PRIORITY_DOWN_HOVER_ICONS[0];
			case AVOID_2:
				return RouteIcons.PRIORITY_DOWN_HOVER_ICONS[1];
			case AVOID_3:
				return RouteIcons.PRIORITY_DOWN_HOVER_ICONS[2];
			case EXCLUDED:
				return RouteIcons.CROSS_HOVER;
			default:
				return RouteIcons.PRIORITY_NEUTRAL_HOVER;
		}
	}

	static String priorityTooltip(String label, MethodPriority tier)
	{
		String state = tier == MethodPriority.NORMAL
			? "Normal priority"
			: tier.label + (tier.chipText().isEmpty() ? "" : " (" + tier.chipText() + " on ranking)");
		return "<html><b>" + escapeHtml(label) + "</b>: " + state
			+ "<br>Click for priority options: prefer/avoid shift the ranking, exclude removes it.</html>";
	}

	/** The hover text of a method: its category, label and arrival tile. */
	static String methodTooltip(TeleportMethod method)
	{
		return "<html>" + methodTooltipBody(method) + "</html>";
	}

	/** The tooltip's inner HTML, for callers that prepend their own line (the route cards). */
	static String methodTooltipBody(TeleportMethod method)
	{
		int destination = method.getDestination();
		int x = WorldPointUtil.unpackWorldX(destination);
		int y = WorldPointUtil.unpackWorldY(destination);
		int plane = WorldPointUtil.unpackWorldPlane(destination);
		return "<b>" + escapeHtml(method.category()) + "</b><br>"
			+ escapeHtml(method.label()) + "<br>"
			+ "Arrives at " + x + ", " + y + (plane > 0 ? " (plane " + plane + ")" : "");
	}

	/** Human list of method labels: "Fairy ring", or "Fairy ring and Cowbell amulet". */
	static String joinLabels(Set<TeleportMethod> methods)
	{
		StringBuilder joined = new StringBuilder();
		int i = 0;
		for (TeleportMethod method : methods)
		{
			if (i > 0)
			{
				joined.append(i == methods.size() - 1 ? " and " : ", ");
			}
			joined.append(method.label());
			i++;
		}
		return joined.toString();
	}

	/**
	 * Attaches a click listener to a component and its descendants, skipping {@link IconActionLabel}s
	 * so the icon controls keep their own action. Swing only delivers a click to the deepest component
	 * under the cursor, hence the recursion.
	 */
	static void addClickRecursively(Component component, MouseListener listener)
	{
		if (component instanceof IconActionLabel)
		{
			return;
		}
		component.addMouseListener(listener);
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				addClickRecursively(child, listener);
			}
		}
	}

	/**
	 * Marker for a method the player cannot use in the current mode: a bank glyph for an item
	 * that is only in the bank, a padlock for everything else, each with a reason tooltip. The
	 * classification's own detail ("Requires 60 Mining", "Missing item: Willow logs") names
	 * exactly what is missing when it recorded one; the per-status wording is the fallback.
	 */
	static JLabel statusMarker(ShortestPathPlugin plugin, MethodAvailability status, TeleportMethod method)
	{
		JLabel label = new JLabel(status == MethodAvailability.IN_BANK ? RouteIcons.IN_BANK : RouteIcons.LOCKED);
		String detail = plugin.methodUnavailabilityDetail(method);
		if (detail == null)
		{
			label.setToolTipText(statusReason(status));
		}
		else
		{
			label.setToolTipText(status == MethodAvailability.IN_BANK
				? detail + ": switch to + Bank or withdraw it"
				: detail);
		}
		return label;
	}

	/** The generic reason wording per unavailability kind. */
	static String statusReason(MethodAvailability status)
	{
		switch (status)
		{
			case IN_BANK:
				return "In your bank: switch to + Bank or withdraw it";
			case MISSING_ITEM:
				return "You don't have the required item";
			case MISSING_LEVEL:
				return "Your skill level is too low";
			case MISSING_QUEST:
				return "Requires an unfinished quest";
			case LOCKED:
			default:
				return "Not unlocked yet (diary, minigame, purchase or setting)";
		}
	}
}
