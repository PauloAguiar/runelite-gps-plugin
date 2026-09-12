package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

import static gps.PanelWidgets.banner;
import static gps.PanelWidgets.control;

/**
 * The panel header (panel series P5, out of the panel class): the GPS title with the Report an
 * issue button, the GitHub and Discord marks and the burger of occasional actions; the copy box
 * the issue report fills; the segmented mode picker (Inventory, + Bank, All); and the
 * bank-contents warning that belongs with the mode it explains. Report, GitHub and Discord stay
 * in the header row by the owner's decision (reverted from the burger on 2026-09-07).
 */
final class PanelHeaderView extends JPanel
{
	// The header's GitHub mark points at the project home; the Discord mark at the community invite.
	private static final String GITHUB_REPO_URL = "https://github.com/PauloAguiar/runelite-gps-plugin";
	private static final String DISCORD_URL = "https://discord.gg/7VAbrPsUzT";

	private final ShortestPathPlugin plugin;
	private final JPanel reportBox = new JPanel(new BorderLayout(0, 4));
	private final JTextArea reportContext = new JTextArea();
	// The "bank contents unknown" warning, sitting directly under the mode buttons (it is about
	// the "+ Bank" mode) rather than down in the notices strip. Repopulated on each refresh.
	private final JPanel modeBankWarning = new JPanel();
	private final JButton inventoryModeButton = new JButton("Inventory");
	private final JButton bankModeButton = new JButton("+ Bank");
	private final JButton allModeButton = new JButton("All");

	PanelHeaderView(ShortestPathPlugin plugin)
	{
		super(new BorderLayout());
		this.plugin = plugin;
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(0, 0, 8, 0));
		add(titleRow(), BorderLayout.NORTH);

		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel northStack = new JPanel();
		northStack.setLayout(new BoxLayout(northStack, BoxLayout.Y_AXIS));
		northStack.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buildReportBox();
		reportBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		reportBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
		northStack.add(reportBox);
		JPanel modeRow = modeRow();
		modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		northStack.add(modeRow);
		bottom.add(northStack, BorderLayout.NORTH);

		// The bank-contents warning belongs with the mode buttons it explains (+ Bank mode).
		modeBankWarning.setLayout(new BoxLayout(modeBankWarning, BoxLayout.Y_AXIS));
		modeBankWarning.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeBankWarning.setBorder(new EmptyBorder(6, 0, 0, 0));
		bottom.add(modeBankWarning, BorderLayout.SOUTH);
		add(bottom, BorderLayout.SOUTH);
		refresh();
	}

	/** The mode buttons' highlight and the bank-contents warning, from the plugin's current state. */
	void refresh()
	{
		AlternativeRoutesMode mode = plugin.getRoutesMode();
		styleModeButton(inventoryModeButton, mode == AlternativeRoutesMode.OWNED_INVENTORY);
		styleModeButton(bankModeButton, mode == AlternativeRoutesMode.OWNED_WITH_BANK);
		styleModeButton(allModeButton, mode == AlternativeRoutesMode.ALL_EVERYTHING);

		// The bank container is only populated once the bank has been opened this session; without
		// it Bank mode cannot see banked items (the same constraint as Shortest Path itself).
		modeBankWarning.removeAll();
		if (mode == AlternativeRoutesMode.OWNED_WITH_BANK && !plugin.isBankContentsKnown())
		{
			modeBankWarning.add(banner(RouteIcons.BANNER_WARNING,
				"Bank contents unknown",
				plugin.getGpsConfig().rememberBank()
					? "Open your bank once so banked items can be found. GPS will remember it for future sessions."
					: "Open your bank once so banked items can be found.",
				ColorScheme.PROGRESS_ERROR_COLOR));
		}
		modeBankWarning.setVisible(modeBankWarning.getComponentCount() > 0);
		modeBankWarning.revalidate();
		modeBankWarning.repaint();
	}

	/**
	 * Shows the routing context in the copy box under the title, pre-selected so a single Ctrl+C
	 * carries it to the GitHub issue. Stays until the player hides it. EDT only.
	 */
	void showReportContext(String context)
	{
		reportContext.setText(context);
		reportBox.setVisible(true);
		reportBox.revalidate();
		reportContext.requestFocusInWindow();
		reportContext.selectAll();
	}

	private JPanel titleRow()
	{
		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// The plugin's identity mark: blue pin plus bold white "GPS", matching the overlay header
		// and the sidebar tab.
		JLabel title = new JLabel("GPS", new ImageIcon(RouteIcons.gpsPin()), SwingConstants.LEADING);
		title.setIconTextGap(6);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		titleRow.add(title, BorderLayout.WEST);

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		actions.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// A compact red button shows the routing context in a copy box below the header and opens
		// GitHub's new-issue page (a bare link: nothing rides in the URL, no clipboard API).
		JButton reportButton = new JButton("Report an issue");
		reportButton.setFont(FontManager.getRunescapeSmallFont());
		reportButton.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		reportButton.setMargin(new Insets(2, 6, 2, 6));
		reportButton.setFocusPainted(false);
		reportButton.setToolTipText("<html>Shows your routes and settings in a box to copy, and opens<br>"
			+ "GitHub: paste the context into the issue.<br>"
			+ "First calculate the route that's misbehaving, so the report captures it.</html>");
		reportButton.addActionListener(e -> plugin.reportIssue());
		actions.add(reportButton);
		actions.add(control(new IconActionLabel(RouteIcons.GITHUB, RouteIcons.GITHUB,
			"View the project on GitHub", () -> LinkBrowser.browse(GITHUB_REPO_URL))));
		actions.add(control(new IconActionLabel(RouteIcons.DISCORD, RouteIcons.DISCORD,
			"Join the GPS Discord", () -> LinkBrowser.browse(DISCORD_URL))));
		// Occasional actions (snapshot, reset) tuck into the burger.
		JPopupMenu actionsMenu = new JPopupMenu();
		JMenuItem debugItem = new JMenuItem("Save debug snapshot", RouteIcons.DEBUG);
		debugItem.setToolTipText("Save a debug snapshot of the current routes to disk (for reproducing issues)");
		debugItem.addActionListener(e -> plugin.captureDebugSnapshot());
		actionsMenu.add(debugItem);
		JMenuItem resetItem = new JMenuItem("Reset excluded methods", RouteIcons.CLEAR);
		resetItem.setToolTipText("Re-include every method you've disabled");
		resetItem.addActionListener(e -> plugin.clearExclusions());
		actionsMenu.add(resetItem);
		IconActionLabel[] menuButton = new IconActionLabel[1];
		menuButton[0] = new IconActionLabel(RouteIcons.MENU, RouteIcons.MENU_HOVER, "More actions",
			() -> actionsMenu.show(menuButton[0], 0, menuButton[0].getHeight()));
		actions.add(control(menuButton[0]));
		titleRow.add(actions, BorderLayout.EAST);
		return titleRow;
	}

	/**
	 * The "Report an issue" copy box: the routing context appears here for the player to copy BY
	 * HAND into the GitHub issue that just opened; no clipboard API, no data in the URL, and they
	 * see exactly what they are sharing. Hidden until the button is used.
	 */
	private void buildReportBox()
	{
		reportBox.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		reportBox.setBorder(new EmptyBorder(6, 6, 6, 6));
		reportBox.setVisible(false);
		JPanel reportTitleRow = new JPanel(new BorderLayout());
		reportTitleRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel reportTitle = new JLabel("<html>Select all (Ctrl+A), copy (Ctrl+C), and paste into the GitHub issue:</html>");
		reportTitle.setFont(FontManager.getRunescapeSmallFont());
		reportTitle.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		reportTitleRow.add(reportTitle, BorderLayout.CENTER);
		JButton reportHide = new JButton("Hide");
		reportHide.setFont(FontManager.getRunescapeSmallFont());
		reportHide.setMargin(new Insets(0, 4, 0, 4));
		reportHide.setFocusable(false);
		reportHide.addActionListener(e ->
		{
			reportBox.setVisible(false);
			reportBox.revalidate();
		});
		reportTitleRow.add(reportHide, BorderLayout.EAST);
		reportBox.add(reportTitleRow, BorderLayout.NORTH);
		reportContext.setEditable(false);
		reportContext.setLineWrap(true);
		reportContext.setWrapStyleWord(true);
		reportContext.setBackground(ColorScheme.DARK_GRAY_COLOR);
		reportContext.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		reportContext.setFont(FontManager.getRunescapeSmallFont());
		JScrollPane reportScroll = new JScrollPane(reportContext);
		reportScroll.setBorder(null);
		// Fixed height, zero preferred width: a text area reports its longest line as preferred
		// width even when wrapping, which would stretch the whole sidebar (audit-panel lesson).
		reportScroll.setPreferredSize(new Dimension(0, 150));
		reportBox.add(reportScroll, BorderLayout.CENTER);
	}

	/**
	 * One segmented row, ordered by inclusiveness (each step considers strictly more methods):
	 * what you carry, plus your bank, everything in the game.
	 */
	private JPanel modeRow()
	{
		JPanel modeRow = new JPanel(new GridLayout(1, 3, 4, 0));
		modeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		modeRow.setBorder(new EmptyBorder(8, 0, 0, 0));
		inventoryModeButton.setToolTipText("<html><b>Available now</b>: only methods usable with what you carry<br>"
			+ "(inventory + equipment).</html>");
		inventoryModeButton.setFont(FontManager.getRunescapeSmallFont());
		inventoryModeButton.setFocusPainted(false);
		inventoryModeButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.OWNED_INVENTORY));
		bankModeButton.setToolTipText("<html><b>Available via your bank</b>: also counts banked items;<br>"
			+ "routes detour to a bank to withdraw them.<br>"
			+ "Open your bank once per session so its contents are known.</html>");
		bankModeButton.setFont(FontManager.getRunescapeSmallFont());
		bankModeButton.setFocusPainted(false);
		bankModeButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.OWNED_WITH_BANK));
		allModeButton.setToolTipText("<html><b>Every method in the game</b>, regardless of items or unlocks:<br>"
			+ "the planning view. Markers in the catalog show what each one is missing.</html>");
		allModeButton.setFont(FontManager.getRunescapeSmallFont());
		allModeButton.setFocusPainted(false);
		allModeButton.addActionListener(e -> plugin.setRoutesMode(AlternativeRoutesMode.ALL_EVERYTHING));
		modeRow.add(inventoryModeButton);
		modeRow.add(bankModeButton);
		modeRow.add(allModeButton);
		return modeRow;
	}

	private static void styleModeButton(JButton button, boolean active)
	{
		button.setForeground(active ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		button.setBackground(active ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(active ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(3, 0, 3, 0)));
	}
}
