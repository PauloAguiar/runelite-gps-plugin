package gps.dev;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;

/**
 * The varbit heatmap sidebar: every varbit/varp that changed since tracking began, hottest
 * first, with the row's background cooling from orange to grey as ticks pass. Click a row for
 * the detail card — name, id, the varp it lives in and its bit range, the full change history
 * with the tile you stood on — and Copy it for an issue. "Mark" brackets an action: press it,
 * do the thing in game, tick "since mark" and the list is exactly what that action flipped.
 */
class VarbitWatchPanel extends PluginPanel
{
	private static final Color HOT = new Color(0xE0, 0x7A, 0x2A);
	private static final Color SELECTED_BG = new Color(0x2E, 0x3E, 0x5E);
	private static final int MAX_ROWS = 120;
	private static final int MAX_HISTORY_LINES = 12;

	private final VarbitWatch watch;
	/** Resolves a varbit's composition (varp index + bit range) on the client thread. */
	private final Consumer<Detail> compositionResolver;
	private final JLabel summary = new JLabel();
	private final JLabel status = new JLabel(" ");
	private final JPanel list = new JPanel();
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
	private final IconTextField searchBar = new IconTextField();
	private final JCheckBox sinceMark = new JCheckBox("Since mark only", false);
	private final JCheckBox hideNoisy = new JCheckBox("Hide noisy (timers)", true);
	private final JButton pause = new JButton("Pause");
	private String filterText = "";
	private int nowTick;
	private VarbitWatch.Entry selected;
	private String lastSignature = "";

	/** A selected entry's composition line, filled in asynchronously by the plugin. */
	static final class Detail
	{
		final boolean varbit;
		final int id;
		volatile String composition = "";

		Detail(boolean varbit, int id)
		{
			this.varbit = varbit;
			this.id = id;
		}
	}

	VarbitWatchPanel(VarbitWatch watch, Consumer<Detail> compositionResolver, Runnable dump)
	{
		super(false);
		this.watch = watch;
		this.compositionResolver = compositionResolver;
		setLayout(new BorderLayout(0, 6));
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel title = new JLabel("Varbit watch");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(title);
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(summary);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		buttons.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
		buttons.add(button("Mark", "Remember this tick: tick 'Since mark only' after doing"
			+ " something in game and the list is exactly what it flipped", () ->
		{
			watch.mark(nowTick);
			sinceMark.setSelected(true);
			status.setText("marked at tick " + nowTick);
			rebuild(true);
		}));
		pause.setFont(FontManager.getRunescapeSmallFont());
		pause.setFocusable(false);
		pause.setMargin(new Insets(2, 6, 2, 6));
		pause.setToolTipText("Stop recording (the list keeps what it has)");
		pause.addActionListener(e ->
		{
			watch.setPaused(!watch.isPaused());
			pause.setText(watch.isPaused() ? "Resume" : "Pause");
		});
		buttons.add(pause);
		buttons.add(button("Clear", "Forget every change and the mark", () ->
		{
			watch.clear();
			selected = null;
			rebuild(true);
		}));
		buttons.add(button("Dump", "Write everything (with histories) to gps-debug/varbit-watch-*.tsv", dump));
		top.add(buttons);

		for (JCheckBox box : new JCheckBox[]{sinceMark, hideNoisy})
		{
			box.setFocusable(false);
			box.setBackground(ColorScheme.DARK_GRAY_COLOR);
			box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			box.setFont(FontManager.getRunescapeSmallFont());
			box.setAlignmentX(Component.LEFT_ALIGNMENT);
			box.addActionListener(e -> rebuild(true));
			top.add(box);
		}
		hideNoisy.setToolTipText("Drops ids that changed in " + VarbitWatch.NOISE_HITS + "+ of the last "
			+ VarbitWatch.NOISE_WINDOW + " ticks — timers, animations, fishing spots");

		searchBar.setIcon(IconTextField.Icon.SEARCH);
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
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setFont(FontManager.getRunescapeSmallFont());
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		top.add(status);
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

		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setVisible(false);
		add(detail, BorderLayout.SOUTH);
	}

	private JButton button(String label, String tooltip, Runnable action)
	{
		JButton button = new JButton(label);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(false);
		button.setMargin(new Insets(2, 6, 2, 6));
		button.setToolTipText(tooltip);
		button.addActionListener(e -> action.run());
		return button;
	}

	private void applyFilter()
	{
		filterText = searchBar.getText();
		rebuild(true);
	}

	void setStatus(String text)
	{
		status.setText(text);
	}

	/** Called every game tick (on the EDT). Rebuilds only when the visible rows changed. */
	void refresh(int tick)
	{
		nowTick = tick;
		rebuild(false);
	}

	private void rebuild(boolean force)
	{
		List<VarbitWatch.Entry> rows = watch.rows(filterText, sinceMark.isSelected(), hideNoisy.isSelected(), nowTick);
		StringBuilder signature = new StringBuilder();
		for (VarbitWatch.Entry entry : rows)
		{
			signature.append(entry.id).append(':').append(entry.lastTick).append(':').append(entry.count).append(';');
		}
		// Heat fades every tick, so redraw at least every few ticks even when the set is stable.
		String sig = signature.toString() + (nowTick / 3);
		if (!force && sig.equals(lastSignature))
		{
			return;
		}
		lastSignature = sig;

		summary.setText(watch.size() + " id(s) changed · tick " + nowTick
			+ (watch.markTick() >= 0 ? " · mark @" + watch.markTick() : "")
			+ (watch.isPaused() ? " · PAUSED" : ""));
		list.removeAll();
		int shown = 0;
		for (VarbitWatch.Entry entry : rows)
		{
			if (shown++ >= MAX_ROWS)
			{
				JLabel more = new JLabel("… " + (rows.size() - MAX_ROWS) + " more — narrow the filter");
				more.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				more.setFont(FontManager.getRunescapeSmallFont());
				list.add(more);
				break;
			}
			list.add(row(entry));
		}
		if (rows.isEmpty())
		{
			JLabel empty = new JLabel(watch.size() == 0
				? "Waiting for changes — do something in game."
				: "Nothing matches (mark / noisy / filter).");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(FontManager.getRunescapeSmallFont());
			list.add(empty);
		}
		list.revalidate();
		list.repaint();
		renderDetail();
	}

	private JPanel row(VarbitWatch.Entry entry)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		float heat = VarbitWatch.heat(entry, nowTick);
		boolean isSelected = selected != null && selected == entry;
		row.setBackground(isSelected ? SELECTED_BG : blend(ColorScheme.DARKER_GRAY_COLOR, HOT, heat * 0.75f));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, blend(ColorScheme.DARK_GRAY_COLOR, HOT, heat)),
			new EmptyBorder(3, 6, 3, 6)));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		String name = entry.name();
		JLabel head = new JLabel("<html><b>" + (entry.varbit ? "" : "varp ") + entry.id + "</b>"
			+ (name != null ? " <font color='#B0B8C0'>" + name + "</font>" : "") + "</html>");
		head.setFont(FontManager.getRunescapeSmallFont());
		head.setForeground(Color.WHITE);
		JLabel sub = new JLabel(VarbitWatch.value(entry.lastFrom) + " → " + entry.lastTo
			+ "   ×" + entry.count + "   " + age(entry)
			+ (watch.isNoisy(entry, nowTick) ? "   noisy" : ""));
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		text.add(head);
		text.add(sub);
		row.add(text, BorderLayout.CENTER);
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				selected = entry;
				compositionResolver.accept(new Detail(entry.varbit, entry.id));
				rebuild(true);
			}
		});
		return row;
	}

	private String age(VarbitWatch.Entry entry)
	{
		int ticks = Math.max(0, nowTick - entry.lastTick);
		return ticks == 0 ? "now" : ticks + " tick" + (ticks == 1 ? "" : "s") + " ago";
	}

	private volatile String compositionLine = "";

	/** The plugin hands back the selected id's varp index and bit range. */
	void showComposition(Detail resolved)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (selected != null && selected.varbit == resolved.varbit && selected.id == resolved.id)
			{
				compositionLine = resolved.composition;
				rebuild(true);
			}
		});
	}

	private void renderDetail()
	{
		detail.removeAll();
		if (selected == null)
		{
			detail.setVisible(false);
			return;
		}
		VarbitWatch.Entry entry = selected;
		detail.setBorder(new EmptyBorder(6, 8, 6, 8));
		String name = entry.name();
		JLabel title = new JLabel("<html><b>" + entry.kind() + " " + entry.id + "</b>"
			+ (name != null ? "<br>" + name : "") + "</html>");
		title.setForeground(Color.WHITE);
		title.setFont(FontManager.getRunescapeSmallFont());
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(title);
		if (!compositionLine.isEmpty())
		{
			JLabel composition = new JLabel(compositionLine);
			composition.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			composition.setFont(FontManager.getRunescapeSmallFont());
			composition.setAlignmentX(Component.LEFT_ALIGNMENT);
			detail.add(composition);
		}
		JLabel stats = new JLabel(entry.count + " change(s) · first tick " + entry.firstTick
			+ " · last tick " + entry.lastTick);
		stats.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		stats.setFont(FontManager.getRunescapeSmallFont());
		stats.setAlignmentX(Component.LEFT_ALIGNMENT);
		detail.add(stats);
		int lines = 0;
		for (VarbitWatch.Change change : entry.history)
		{
			if (lines++ >= MAX_HISTORY_LINES)
			{
				JLabel more = new JLabel("… " + (entry.history.size() - MAX_HISTORY_LINES) + " more in the dump");
				more.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				more.setFont(FontManager.getRunescapeSmallFont());
				more.setAlignmentX(Component.LEFT_ALIGNMENT);
				detail.add(more);
				break;
			}
			JLabel line = new JLabel("tick " + change.tick + ": " + VarbitWatch.value(change.from) + " → "
				+ change.to + "  @ " + change.x + "," + change.y + "," + change.plane);
			line.setForeground(lines == 1 ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
			line.setFont(FontManager.getRunescapeSmallFont());
			line.setAlignmentX(Component.LEFT_ALIGNMENT);
			detail.add(line);
		}
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.add(button("Copy", "Copy this id's name, composition and history to the clipboard", () ->
		{
			StringBuilder sb = new StringBuilder();
			sb.append(entry.kind()).append(' ').append(entry.id);
			if (name != null)
			{
				sb.append(' ').append(name);
			}
			if (!compositionLine.isEmpty())
			{
				sb.append('\n').append(compositionLine);
			}
			for (VarbitWatch.Change change : entry.history)
			{
				sb.append("\ntick ").append(change.tick).append(": ").append(VarbitWatch.value(change.from))
					.append(" -> ").append(change.to).append(" @ ").append(change.x).append(',')
					.append(change.y).append(',').append(change.plane);
			}
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
			status.setText("copied " + entry.kind() + " " + entry.id);
		}));
		actions.add(button("Close", "Hide the detail card", () ->
		{
			selected = null;
			rebuild(true);
		}));
		detail.add(actions);
		detail.setVisible(true);
		detail.revalidate();
		detail.repaint();
	}

	private static Color blend(Color from, Color to, float t)
	{
		t = Math.max(0f, Math.min(1f, t));
		return new Color(
			Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
			Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
			Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
	}
}
