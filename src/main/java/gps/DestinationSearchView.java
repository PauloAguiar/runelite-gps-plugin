package gps;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

import static gps.PanelWidgets.BANNER_INFO_ACCENT;
import static gps.PanelWidgets.control;
import static gps.PanelWidgets.fullWidth;
import static gps.PanelWidgets.innerTextField;
import static gps.PanelWidgets.sectionLabel;
import static gps.PanelWidgets.subtleButton;
import static gps.PanelWidgets.verticallyCentered;

/**
 * The "Go to" destination search (panel series P1, out of the panel class): type a place or
 * amenity ("Falador bank", "nearest altar") or coordinates and pick a result to set it as the
 * GPS destination; an empty box offers the saved favourites and the recent selections. The
 * results float in a non-focusable popup under the field (inline results pushed the whole panel
 * down while typing), navigable by keyboard and mouse alike. Above the search sits an inline
 * favourite editor (the heart), below it the "Find nearest" row.
 */
final class DestinationSearchView extends JPanel
{
	private static final int MAX_DESTINATION_RESULTS = 12;
	// "x, y" or "x y", with an optional plane (0-3): x is 4 digits (the playable range is roughly
	// 1000-4600), y 4-5 digits (surface ~3000-4200; dungeon/instance planes reach past 10000).
	private static final Pattern COORDINATE_QUERY = Pattern.compile("(\\d{4})[,;\\s]+(\\d{4,5})(?:[,;\\s]+([0-3]))?");
	// Selected search-result row: a blue-tinted background plus a GPS-blue accent bar; the two
	// near-identical dark greys the highlight used before were invisible when arrowing through.
	private static final Color RESULT_SELECTED_BG = new Color(0x2E, 0x3E, 0x5E);

	private final ShortestPathPlugin plugin;
	private final IconTextField searchField = new IconTextField();
	private final JPanel results = new JPanel();
	// The search results float over the panel in a non-focusable popup anchored under the search
	// field (autocomplete-style).
	private final JPopupMenu popup = new JPopupMenu();
	// The name-search index (places + dungeons + minigames), built once the transport data is
	// available: it is session-static, so caching avoids rescanning transports on every keystroke.
	private List<Destinations.Entry> index;
	// The currently-shown search result rows and their entries (parallel), plus the keyboard-
	// selected index into them (-1 = none). Up/Down move it, Enter picks it; mouse hover keeps it
	// in sync so both input methods share one highlight.
	private final List<JPanel> resultRows = new ArrayList<>();
	private final List<Destinations.Entry> resultEntries = new ArrayList<>();
	private int selectedResult = -1;
	// The inline favourite editor.
	private final JPanel favoriteEditor = new JPanel();
	private final JTextField favoriteLabelInput = new JTextField();
	private final JTextField favoritePositionInput = new JTextField();
	private final JLabel favoriteError = new JLabel();

	DestinationSearchView(ShortestPathPlugin plugin)
	{
		this.plugin = plugin;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(10, 0, 0, 0));

		// Header: the section label plus the favourite-saving heart on the right.
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.add(sectionLabel("Go to a place"), BorderLayout.CENTER);
		IconActionLabel saveFavorite = new IconActionLabel(RouteIcons.FAVORITE, RouteIcons.FAVORITE_HOVER,
			"Save a favourite position with a label (your current tile, or any coordinates)",
			this::toggleFavoriteEditor);
		header.add(verticallyCentered(control(saveFavorite)), BorderLayout.EAST);

		buildFavoriteEditor();
		// Above the "Go to a place" header: saving a favourite is its own little task, not part
		// of the search flow below it.
		add(fullWidth(favoriteEditor));
		add(fullWidth(header));
		buildSearchField();
		add(searchField);

		results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
		results.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		// Hosted in a floating popup under the search field, NOT in the panel flow. Non-focusable
		// so typing stays in the search field while the popup is showing.
		popup.setFocusable(false);
		popup.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		popup.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		popup.setLayout(new BorderLayout());
		popup.add(results, BorderLayout.CENTER);

		// "Find nearest": a single button opening a menu of amenity types; picking one routes to the
		// closest of that type using available teleports.
		add(buildNearestRow());
	}

	/**
	 * Focuses the search box and selects any existing text, so the focus-search hotkey lands the
	 * caret ready to type. Marshalled to the EDT and deferred so the panel (just opened by the
	 * hotkey) is laid out and focusable first.
	 */
	void focusSearch()
	{
		SwingUtilities.invokeLater(() ->
		{
			searchField.requestFocusInWindow();
			JTextField inner = innerTextField(searchField);
			if (inner != null)
			{
				inner.selectAll();
			}
			// Surface the recent-searches list (the focus listener does this too, but requesting
			// focus on an already-focused field will not re-fire it).
			renderResults();
		});
	}

	/** Closes the results popup (the panel is being hidden). */
	void hidePopup()
	{
		popup.setVisible(false);
	}

	/**
	 * Parses a typed coordinate query into a packed world point, or {@link WorldPointUtil#UNDEFINED}
	 * when the text is not a plausible in-world coordinate pair.
	 */
	static int parseCoordinateQuery(String query)
	{
		Matcher matcher = COORDINATE_QUERY.matcher(query);
		if (!matcher.matches())
		{
			return WorldPointUtil.UNDEFINED;
		}
		int x = Integer.parseInt(matcher.group(1));
		int y = Integer.parseInt(matcher.group(2));
		int plane = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
		if (x > 4600 || y > 12900)
		{
			return WorldPointUtil.UNDEFINED;
		}
		return WorldPointUtil.packWorldPoint(x, y, plane);
	}

	/**
	 * The name matches for {@code query} in {@code pool}, best first: the score tiers (exact,
	 * prefix, word prefixes, substring, subsequence) rank the list; proximity to {@code player}
	 * breaks ties within a tier, or the name when the player's position is unknown; at most
	 * {@link #MAX_DESTINATION_RESULTS}.
	 */
	static List<Destinations.Entry> rank(List<Destinations.Entry> pool, String query, int player)
	{
		List<Destinations.Entry> matches = new ArrayList<>();
		Map<Destinations.Entry, Integer> scores = new HashMap<>();
		for (Destinations.Entry entry : pool)
		{
			int score = SearchMatcher.score(entry.name, query);
			if (score > 0)
			{
				matches.add(entry);
				scores.put(entry, score);
			}
		}
		Comparator<Destinations.Entry> byScore = Comparator.comparingInt(e -> -scores.getOrDefault(e, 0));
		if (player != WorldPointUtil.UNDEFINED)
		{
			matches.sort(byScore.thenComparingInt(e -> WorldPointUtil.distanceBetween(player, e.packedPosition)));
		}
		else
		{
			matches.sort(byScore.thenComparing(e -> e.name));
		}
		return matches.size() > MAX_DESTINATION_RESULTS ? matches.subList(0, MAX_DESTINATION_RESULTS) : matches;
	}

	private void buildFavoriteEditor()
	{
		// Two labelled fields: Name and At (coordinates, prefilled with the current tile;
		// "3221 3218", "3221,3218,1" and "3221, 3218 0" all parse; empty = current tile). Tab
		// moves between them, Enter saves from either, Esc closes.
		KeyAdapter escapeCloses = new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE)
				{
					favoriteEditor.setVisible(false);
				}
			}
		};
		for (JTextField field : new JTextField[]{favoriteLabelInput, favoritePositionInput})
		{
			field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			field.setForeground(Color.WHITE);
			field.setCaretColor(Color.WHITE);
			field.setFont(FontManager.getRunescapeSmallFont());
			field.addActionListener(e -> attemptSaveFavorite());
			field.addKeyListener(escapeCloses);
		}
		favoriteLabelInput.setToolTipText("The favourite's name, shown in search results");
		favoritePositionInput.setToolTipText("<html>Where it is: \"3221 3218\", \"3221,3218,1\" or"
			+ " \"3221, 3218 0\".<br>Empty = your current tile.</html>");
		JButton favoriteSave = new JButton("Save");
		favoriteSave.setMargin(new Insets(2, 8, 2, 8));
		favoriteSave.setFont(FontManager.getRunescapeSmallFont());
		favoriteSave.addActionListener(e -> attemptSaveFavorite());
		JPanel positionRow = new JPanel(new BorderLayout(4, 0));
		positionRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		positionRow.add(favoritePositionInput, BorderLayout.CENTER);
		positionRow.add(favoriteSave, BorderLayout.EAST);
		favoriteError.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		favoriteError.setFont(FontManager.getRunescapeSmallFont());
		favoriteError.setVisible(false);
		favoriteEditor.setLayout(new BoxLayout(favoriteEditor, BoxLayout.Y_AXIS));
		favoriteEditor.setBackground(ColorScheme.DARK_GRAY_COLOR);
		favoriteEditor.setBorder(new EmptyBorder(0, 0, 6, 0));
		favoriteEditor.setAlignmentX(LEFT_ALIGNMENT);
		favoriteError.setAlignmentX(LEFT_ALIGNMENT);
		// Title row: the section label plus a red cross to close (the heart also toggles, Esc too).
		JPanel titleRow = new JPanel(new BorderLayout());
		titleRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		titleRow.setAlignmentX(LEFT_ALIGNMENT);
		titleRow.add(sectionLabel("Save a favourite"), BorderLayout.CENTER);
		IconActionLabel close = new IconActionLabel(RouteIcons.CROSS_RED, RouteIcons.CROSS_RED_HOVER,
			"Close without saving", () -> favoriteEditor.setVisible(false));
		titleRow.add(verticallyCentered(control(close)), BorderLayout.EAST);
		titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleRow.getPreferredSize().height));
		favoriteEditor.add(titleRow);
		favoriteEditor.add(favoriteFieldRow("Name", favoriteLabelInput));
		favoriteEditor.add(Box.createVerticalStrut(3));
		favoriteEditor.add(favoriteFieldRow("At", positionRow));
		favoriteEditor.add(favoriteError);
		favoriteEditor.setVisible(false);
	}

	private void buildSearchField()
	{
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.setToolTipText("Search places, dungeons and minigames by name,"
			+ " or type coordinates (\"3221, 3218\", optional plane: \"3221 3218 1\")");
		// Taller than the default field height for an easier click target and more presence: it
		// is the section's primary control.
		final int searchHeight = 32;
		searchField.setPreferredSize(new Dimension(searchField.getPreferredSize().width, searchHeight));
		searchField.setMinimumSize(new Dimension(0, searchHeight));
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchHeight));
		searchField.setAlignmentX(LEFT_ALIGNMENT);
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				renderResults();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				renderResults();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				renderResults();
			}
		});
		// Clicking into the empty box offers the recent searches; IconTextField does not expose its
		// inner text field, so find it in the component tree to hear focus.
		JTextField inner = innerTextField(searchField);
		if (inner == null)
		{
			return;
		}
		inner.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				renderResults();
			}
		});
		// Up/Down move the highlighted result, Enter picks it, Escape closes the popup, so a
		// destination can be chosen without leaving the keyboard.
		inner.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				if (!popup.isVisible())
				{
					return;
				}
				switch (e.getKeyCode())
				{
					case KeyEvent.VK_DOWN:
						moveSelection(1);
						e.consume();
						break;
					case KeyEvent.VK_UP:
						moveSelection(-1);
						e.consume();
						break;
					case KeyEvent.VK_ENTER:
						if (selectedResult >= 0 && selectedResult < resultEntries.size())
						{
							selectEntry(resultEntries.get(selectedResult));
							e.consume();
						}
						break;
					case KeyEvent.VK_ESCAPE:
						popup.setVisible(false);
						e.consume();
						break;
					default:
						break;
				}
			}
		});
	}

	/**
	 * The nearest-X row: a compact "Find nearest" opener plus icon-only quick buttons for the most
	 * common targets (bank, bank-and-back); the full-width button pulled attention away from the
	 * search box above, the section's primary control.
	 */
	private JPanel buildNearestRow()
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(4, 0, 0, 0));
		row.setAlignmentX(LEFT_ALIGNMENT);

		JButton menuButton = subtleButton(new JButton("Find nearest…"));
		menuButton.setHorizontalAlignment(SwingConstants.CENTER);
		menuButton.setToolTipText("Route to the nearest altar / water source / furnace / … using available teleports");
		menuButton.addActionListener(e -> showNearestMenu(menuButton));
		row.add(menuButton, BorderLayout.CENTER);

		JPanel quick = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		quick.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JButton bank = nearestQuickButton("bank");
		if (bank != null)
		{
			quick.add(bank);
		}
		JButton bankAndBack = nearestQuickButton("bank_round_trip");
		if (bankAndBack != null)
		{
			quick.add(bankAndBack);
		}
		row.add(quick, BorderLayout.EAST);

		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** An icon-only quick button running one nearest-X option directly (tooltip names it). */
	private JButton nearestQuickButton(String optionId)
	{
		for (Destinations.NearestOption option : Destinations.NEAREST_OPTIONS)
		{
			if (option.id.equals(optionId))
			{
				JButton button = subtleButton(new JButton(RouteIcons.destinationIcon(option.id)));
				button.setToolTipText("Nearest " + option.label.toLowerCase(Locale.ROOT));
				button.addActionListener(e -> runNearestOption(option));
				return button;
			}
		}
		return null;
	}

	/** Runs one nearest-X option, shared by the menu items and the quick buttons. */
	private void runNearestOption(Destinations.NearestOption option)
	{
		Set<Integer> tiles = Destinations.tilesForCategory(option.id, plugin.getTransports());
		boolean roundTrip = "bank_round_trip".equals(option.id);
		if ("bank".equals(option.id) || roundTrip)
		{
			// Union in the engine's accessible-bank tiles: the amenity dump misses oddly-named
			// bank objects (Slepe's "Bank Chest-wreck"), and "nearest bank" must never disagree
			// with where the engine itself can bank.
			tiles.addAll(plugin.getEngineBankTiles());
		}
		plugin.setNearestCategory(tiles, "nearest " + option.label.toLowerCase(Locale.ROOT), roundTrip);
		searchField.setText("");
	}

	private void showNearestMenu(JComponent anchor)
	{
		JPopupMenu menu = new JPopupMenu();
		menu.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		menu.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		for (Destinations.NearestOption option : Destinations.NEAREST_OPTIONS)
		{
			JMenuItem item = new JMenuItem(option.label, RouteIcons.destinationIcon(option.id));
			item.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			item.setForeground(Color.WHITE);
			item.setFont(FontManager.getRunescapeSmallFont());
			item.setIconTextGap(6);
			item.addActionListener(e -> runNearestOption(option));
			menu.add(item);
		}
		menu.show(anchor, 0, anchor.getHeight());
	}

	/** The cached name-search index; (re)built only once the transport data is available. */
	private List<Destinations.Entry> index()
	{
		List<Destinations.Entry> cached = index;
		if (cached != null)
		{
			return cached;
		}
		List<Destinations.Entry> built = Destinations.searchable(plugin.getTransports());
		if (plugin.getTransports() != null)
		{
			index = built;
		}
		return built;
	}

	private void renderResults()
	{
		results.removeAll();
		resultRows.clear();
		resultEntries.clear();
		selectedResult = -1;
		String query = searchField.getText().trim();
		final int player = plugin.getLastKnownPlayerLocation();
		if (query.isEmpty())
		{
			// An empty box offers the saved favourites and the recent selections instead of hiding:
			// reopening a frequent destination without retyping it.
			List<Destinations.Entry> favorites = plugin.getFavoriteDestinations();
			List<Destinations.Entry> history = plugin.getSearchHistory();
			if (favorites.isEmpty() && history.isEmpty())
			{
				popup.setVisible(false);
				return;
			}
			if (!favorites.isEmpty())
			{
				results.add(resultsHeader("Favourites"));
				for (Destinations.Entry entry : favorites)
				{
					addResultRow(entry, player);
				}
			}
			if (!history.isEmpty())
			{
				results.add(resultsHeader("Recent searches"));
				for (Destinations.Entry entry : history)
				{
					addResultRow(entry, player);
				}
			}
			preselectFirstResult();
			showPopup();
			return;
		}

		// A typed coordinate pair ("3221, 3218", "3221 3218", optional plane "3221 3218 1") becomes
		// a direct route-to-tile result ahead of the name matches.
		int coordinate = parseCoordinateQuery(query);
		if (coordinate != WorldPointUtil.UNDEFINED)
		{
			int plane = WorldPointUtil.unpackWorldPlane(coordinate);
			addResultRow(new Destinations.Entry("coordinates",
				"Tile (" + WorldPointUtil.unpackWorldX(coordinate) + ", " + WorldPointUtil.unpackWorldY(coordinate)
					+ (plane > 0 ? ", plane " + plane : "") + ")",
				coordinate), player);
		}

		// "nearest altar", or a bare category word: the category's nearest-of row comes first.
		Destinations.NearestOption nearest = Destinations.parseNearest(query);
		if (nearest != null)
		{
			Set<Integer> tiles = Destinations.tilesForCategory(nearest.id, plugin.getTransports());
			if (!tiles.isEmpty())
			{
				String label = nearest.label;
				addResultRow(new Destinations.Entry("bank_round_trip".equals(nearest.id) ? "bank" : nearest.id,
					"Nearest " + Character.toLowerCase(label.charAt(0)) + label.substring(1),
					WorldPointUtil.UNDEFINED, tiles, nearest), player);
			}
		}

		// Fuzzy match, best first (see rank). Saved favourites are part of the pool, matched by
		// their label.
		List<Destinations.Entry> pool = new ArrayList<>(plugin.getFavoriteDestinations());
		pool.addAll(index());
		for (Destinations.Entry entry : rank(pool, query, player))
		{
			addResultRow(entry, player);
		}
		if (resultEntries.isEmpty())
		{
			JLabel none = new JLabel("No matching destination");
			none.setForeground(Color.GRAY);
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setBorder(new EmptyBorder(2, 4, 2, 4));
			results.add(none);
		}
		preselectFirstResult();
		showPopup();
	}

	/** Builds a result row, adds it to the popup and tracks it for keyboard navigation. */
	private void addResultRow(Destinations.Entry entry, int player)
	{
		JPanel row = destinationRow(entry, player);
		resultEntries.add(entry);
		resultRows.add(row);
		results.add(row);
	}

	/** A small grey group header inside the results popup ("Favourites", "Recent searches"). */
	private static JLabel resultsHeader(String text)
	{
		JLabel header = new JLabel(text);
		header.setForeground(Color.GRAY);
		header.setFont(FontManager.getRunescapeSmallFont());
		header.setBorder(new EmptyBorder(2, 4, 2, 4));
		return header;
	}

	/** A small captioned row for the favourite editor (caption west, component center). */
	private static JPanel favoriteFieldRow(String caption, Component component)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);
		JLabel label = new JLabel(caption);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setPreferredSize(new Dimension(34, label.getPreferredSize().height));
		row.add(label, BorderLayout.WEST);
		row.add(component, BorderLayout.CENTER);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height + 4));
		return row;
	}

	/** The heart button: shows (or hides) the inline favourite editor, prefilled with the current tile. */
	private void toggleFavoriteEditor()
	{
		if (favoriteEditor.isVisible())
		{
			favoriteEditor.setVisible(false);
			return;
		}
		int player = plugin.getLastKnownPlayerLocation();
		String prefill = "";
		if (player != WorldPointUtil.UNDEFINED)
		{
			int plane = WorldPointUtil.unpackWorldPlane(player);
			prefill = WorldPointUtil.unpackWorldX(player) + " " + WorldPointUtil.unpackWorldY(player)
				+ (plane > 0 ? " " + plane : "");
		}
		favoriteLabelInput.setText("");
		favoritePositionInput.setText(prefill);
		favoriteError.setVisible(false);
		favoriteEditor.setVisible(true);
		favoriteEditor.revalidate();
		// Name first: type the label, Tab to adjust the (prefilled) position, Enter to save.
		favoriteLabelInput.requestFocusInWindow();
	}

	/** Enter/Save in the inline editor: label + position (empty position = current tile). */
	private void attemptSaveFavorite()
	{
		String label = favoriteLabelInput.getText().trim();
		if (label.isEmpty())
		{
			favoriteInputError("Name the favourite: it is what search results show.");
			favoriteLabelInput.requestFocusInWindow();
			return;
		}
		String positionText = favoritePositionInput.getText().trim();
		int position = positionText.isEmpty()
			? plugin.getLastKnownPlayerLocation() : parseCoordinateQuery(positionText);
		if (position == WorldPointUtil.UNDEFINED)
		{
			favoriteInputError(positionText.isEmpty()
				? "No position: log in, or type coordinates (\"3221 3218\", optional plane)."
				: "Not a coordinate: \"3221 3218\", \"3221,3218,1\" and \"3221, 3218 0\" all work.");
			favoritePositionInput.requestFocusInWindow();
			return;
		}
		plugin.addFavoriteDestination(label, position);
		favoriteEditor.setVisible(false);
		if (popup.isVisible())
		{
			renderResults();
		}
	}

	private void favoriteInputError(String message)
	{
		favoriteError.setText(message);
		favoriteError.setVisible(true);
		favoriteEditor.revalidate();
	}

	/** Preselects the top result so Enter works immediately; -1 when there are none. */
	private void preselectFirstResult()
	{
		selectedResult = resultRows.isEmpty() ? -1 : 0;
		applySelectionHighlight();
	}

	/** Highlights the selected row (shared by keyboard and mouse) and resets the rest. */
	private void applySelectionHighlight()
	{
		for (int i = 0; i < resultRows.size(); i++)
		{
			boolean selected = i == selectedResult;
			JPanel row = resultRows.get(i);
			row.setBackground(selected ? RESULT_SELECTED_BG : ColorScheme.DARKER_GRAY_COLOR);
			// The accent bar replaces 3px of the left padding, so the row text does not shift.
			row.setBorder(selected
				? BorderFactory.createCompoundBorder(
					BorderFactory.createMatteBorder(0, 3, 0, 0, BANNER_INFO_ACCENT),
					new EmptyBorder(3, 1, 3, 4))
				: new EmptyBorder(3, 4, 3, 4));
		}
	}

	/** Moves the keyboard selection by {@code delta}, wrapping around the result list. */
	private void moveSelection(int delta)
	{
		if (resultRows.isEmpty())
		{
			return;
		}
		selectedResult = ((selectedResult + delta) % resultRows.size() + resultRows.size()) % resultRows.size();
		applySelectionHighlight();
	}

	/** Commits a destination selection (from a click or Enter): route to it, remember it, close. */
	private void selectEntry(Destinations.Entry entry)
	{
		Destinations.Entry resolved = withTiles(entry);
		if (resolved.nearest != null)
		{
			plugin.setNearestCategory(resolved.tiles, resolved.name, "bank_round_trip".equals(resolved.nearest.id));
		}
		else if (resolved.tiles.size() > 1)
		{
			// A named amenity (Falador Bank: every booth): the nearest of its tiles, like "nearest X".
			plugin.setNearestCategory(resolved.tiles, resolved.name);
		}
		else
		{
			plugin.setDestination(entry.packedPosition, "search");
		}
		if (entry.nearest == null)
		{
			plugin.recordSearchSelection(entry);
		}
		// Clearing the text re-renders the popup with the recent list; a selection should end the
		// interaction instead.
		searchField.setText("");
		popup.setVisible(false);
	}

	/**
	 * History and favourite entries persist one tile; a named amenity's full tile set comes back
	 * from the index by category and name.
	 */
	private Destinations.Entry withTiles(Destinations.Entry entry)
	{
		if (entry.tiles.size() > 1 || entry.nearest != null)
		{
			return entry;
		}
		for (Destinations.Entry indexed : index())
		{
			if (indexed.tiles.size() > 1 && indexed.category.equals(entry.category) && indexed.name.equals(entry.name))
			{
				return indexed;
			}
		}
		return entry;
	}

	/**
	 * Floats the results over the panel, matching the search field's width. Re-showing on every
	 * keystroke would flicker and can steal the caret, so a visible popup is resized in place.
	 */
	private void showPopup()
	{
		int width = Math.max(searchField.getWidth(), 180);
		popup.setPreferredSize(new Dimension(width, results.getPreferredSize().height + 2));
		if (popup.isVisible())
		{
			popup.revalidate();
			popup.repaint();
			popup.pack();
		}
		else
		{
			popup.show(searchField, 0, searchField.getHeight());
		}
	}

	private JPanel destinationRow(Destinations.Entry entry, int player)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(3, 4, 3, 4));
		row.setCursor(new Cursor(Cursor.HAND_CURSOR));

		JLabel name = new JLabel(entry.name, RouteIcons.destinationIcon(entry.category), SwingConstants.LEADING);
		name.setIconTextGap(3);
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());
		row.add(name, BorderLayout.CENTER);

		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		east.setOpaque(false);
		if (entry.nearest == null && entry.tiles.size() > 1)
		{
			// A named amenity: say which kind, since "Falador Bank" and "Falador" sit side by side.
			JLabel chip = new JLabel(Destinations.categoryLabel(entry.category));
			chip.setForeground(Color.GRAY);
			chip.setFont(FontManager.getRunescapeSmallFont());
			east.add(chip);
		}
		if (player != WorldPointUtil.UNDEFINED && entry.packedPosition != WorldPointUtil.UNDEFINED)
		{
			int distance = WorldPointUtil.distanceBetween(player, entry.packedPosition);
			if (distance != Integer.MAX_VALUE)
			{
				JLabel dist = new JLabel(distance + " tiles");
				dist.setForeground(Color.GRAY);
				dist.setFont(FontManager.getRunescapeSmallFont());
				east.add(dist);
			}
		}
		if ("favorite".equals(entry.category))
		{
			east.add(new IconActionLabel(RouteIcons.CROSS, RouteIcons.CROSS_HOVER,
				"Remove this favourite", () ->
			{
				plugin.removeFavoriteDestination(entry);
				renderResults();
			}));
		}
		if (east.getComponentCount() > 0)
		{
			row.add(verticallyCentered(east), BorderLayout.EAST);
		}

		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				selectEntry(entry);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				// Move the shared selection to the hovered row so keyboard and mouse agree.
				selectedResult = resultRows.indexOf(row);
				applySelectionHighlight();
			}
		});
		return row;
	}
}
