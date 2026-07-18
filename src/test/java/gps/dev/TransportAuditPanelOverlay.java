package gps.dev;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Panel half of the dev transport audit: the current scene's unmapped traversal objects with
 * one line of operator guidance each (the full transports.tsv template is printed to the log).
 * Dev client only.
 */
class TransportAuditPanelOverlay extends OverlayPanel
{
	private static final int MAX_PANEL_ROWS = 8;

	private final Client client;
	private final TransportAuditPlugin plugin;

	@Inject
	TransportAuditPanelOverlay(Client client, TransportAuditPlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		int count = plugin.findingCount();
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("GPS transport audit")
			.color(count == 0 ? Color.GREEN : TransportAuditSceneOverlay.UNMAPPED)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(count == 0 ? "Scene fully mapped" : count + " unmapped object(s) in scene")
			.build());
		if (count > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("Right-click one → \"Copy GPS audit\"")
				.leftColor(Color.LIGHT_GRAY)
				.build());
		}

		// Nearest first: the operator maps what's in front of them.
		List<TransportAuditPlugin.Finding> sorted = new ArrayList<>();
		plugin.findings().forEach(sorted::add);
		Player local = client.getLocalPlayer();
		if (local != null)
		{
			LocalPoint here = local.getLocalLocation();
			sorted.sort(Comparator.comparingInt(f ->
			{
				LocalPoint p = f.object.getLocalLocation();
				return p == null ? Integer.MAX_VALUE
					: Math.abs(p.getX() - here.getX()) + Math.abs(p.getY() - here.getY());
			}));
		}
		int rows = 0;
		for (TransportAuditPlugin.Finding finding : sorted)
		{
			if (++rows > MAX_PANEL_ROWS)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left("… and " + (count - MAX_PANEL_ROWS) + " more (see log)").build());
				break;
			}
			panelComponent.getChildren().add(LineComponent.builder()
				.left(finding.describe())
				.leftColor(finding.door
					? TransportAuditSceneOverlay.UNMAPPED_DOOR
					: TransportAuditSceneOverlay.UNMAPPED)
				.build());
			panelComponent.getChildren().add(LineComponent.builder()
				.left("  → " + finding.instruction())
				.leftColor(Color.LIGHT_GRAY)
				.build());
		}
		return super.render(graphics);
	}
}
