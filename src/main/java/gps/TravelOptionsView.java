package gps;

import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

import static gps.PanelWidgets.sectionShell;

/**
 * The "Travel options" slot (panel series P3, out of the panel class): one collapsible home for
 * everything routing may use, the player-stated configuration sections and the teleport-methods
 * catalog, all sharing the same header style. Fixed below the panel header (the catalog scrolls
 * inside its own bounded box instead of pushing the route list down). Rebuilt only when its
 * inputs change: routes stream several updates per generation, and rebuilding a thousand
 * catalog rows on the EDT for each of them made the toggles unresponsive (the row under the
 * cursor kept being replaced).
 */
final class TravelOptionsView extends JPanel
{
	private final ConfigSectionsView sections;
	private final MethodCatalogView catalog;
	private boolean expanded;
	// Snapshot of the inputs the slot was last built from (see needsRebuild).
	private List<TeleportMethod> builtCatalog;
	private Set<TeleportMethod> builtExclusions;
	private Map<TeleportMethod, MethodAvailability> builtUnavailable;
	private boolean builtCatalogExpanded;

	TravelOptionsView(ShortestPathPlugin plugin, Runnable refreshAll)
	{
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		sections = new ConfigSectionsView(plugin, this::rebuild, refreshAll);
		catalog = new MethodCatalogView(plugin, this::rebuild, () ->
		{
			revalidate();
			repaint();
		});
	}

	/** Whether the inputs changed since the slot was last built. */
	boolean needsRebuild(List<TeleportMethod> newCatalog, Set<TeleportMethod> exclusions,
		Map<TeleportMethod, MethodAvailability> unavailable)
	{
		return !newCatalog.equals(builtCatalog) || !exclusions.equals(builtExclusions)
			|| !unavailable.equals(builtUnavailable) || catalog.isExpanded() != builtCatalogExpanded;
	}

	/** Rebuilds the slot for the given inputs, carrying the catalog rows' scroll position over. */
	void rebuild(List<TeleportMethod> newCatalog, Set<TeleportMethod> exclusions,
		Map<TeleportMethod, MethodAvailability> unavailable)
	{
		catalog.update(newCatalog, exclusions, unavailable);
		rebuild();
	}

	/** The Log-storage-low banner for the notes strip (see ConfigSectionsView). */
	JPanel balloonLowBanner(List<String> lowTypes)
	{
		return sections.balloonLowBanner(lowTypes);
	}

	/** The tier menu for a method, shared by the route cards and the catalog rows. */
	void showPriorityMenu(Component anchor, TeleportMethod method)
	{
		catalog.showPriorityMenu(anchor, method);
	}

	/** Rebuilds with the current inputs (a header toggle, a tier or funnel change). */
	private void rebuild()
	{
		// The rebuild replaces the method-rows scroll pane; carry its position over so toggling a
		// method or category (which regenerates routes and re-renders) does not jump the list back
		// to the top.
		int rowsScrollPosition = catalog.scrollPosition();
		removeAll();
		add(section());
		revalidate();
		repaint();
		catalog.restoreScroll(rowsScrollPosition);
		builtCatalog = catalog.catalog();
		builtExclusions = catalog.exclusions();
		builtUnavailable = catalog.unavailable();
		builtCatalogExpanded = catalog.isExpanded();
	}

	private JPanel section()
	{
		List<TeleportMethod> methods = catalog.catalog();
		JPanel section = sectionShell("Travel options",
			"Everything routing may use: your house, wilderness policy, bank, balloons and the travel methods",
			expanded, () -> expanded = !expanded,
			methods.isEmpty() ? "" : catalog.enabledCount() + "/" + methods.size(),
			ColorScheme.LIGHT_GRAY_COLOR, true, this::rebuild);
		if (!expanded)
		{
			catalog.slotCollapsed();
			return section;
		}

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.setBorder(new EmptyBorder(0, 8, 0, 0));
		for (JPanel part : sections.sections())
		{
			body.add(part);
		}
		if (!methods.isEmpty())
		{
			body.add(catalog.section());
		}
		section.add(body);
		return section;
	}
}
