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
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
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
}
