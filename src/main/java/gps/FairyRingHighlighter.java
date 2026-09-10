package gps;

import gps.pathfinder.PathStep;
import gps.transport.Transport;
import gps.transport.TransportType;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * The fairy-ring log helper (plan step L17, out of the plugin class): while the log is open and
 * a destination is set, the code the displayed route uses is marked "(GPS)" in green and the
 * list is scrolled to it, every client tick (the log rebuilds its rows).
 */
final class FairyRingHighlighter
{
	private final Client client;
	private final Supplier<List<PathStep>> displayPath;
	private final BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge;

	private boolean logOpen;

	FairyRingHighlighter(Client client, Supplier<List<PathStep>> displayPath,
		BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge)
	{
		this.client = client;
		this.displayPath = displayPath;
		this.transportsForEdge = transportsForEdge;
	}

	/** A widget opened: the log counts as open only while a destination is set. */
	void widgetLoaded(int groupId, boolean hasTargets)
	{
		if (hasTargets && groupId == InterfaceID.FAIRYRINGS_LOG)
		{
			logOpen = true;
		}
	}

	void widgetClosed(int groupId)
	{
		if (groupId == InterfaceID.FAIRYRINGS_LOG)
		{
			logOpen = false;
		}
	}

	/** After each client tick: re-mark and re-scroll while the log is open. */
	void onPostClientTick(boolean hasTargets)
	{
		if (logOpen && hasTargets)
		{
			highlight();
		}
	}

	/** The fairy-ring code the route uses (the last one on the path), or null. */
	static String routeCode(List<PathStep> path, BiFunction<PathStep, PathStep, Set<Transport>> transportsForEdge)
	{
		String code = null;
		for (int i = 1; i < path.size(); i++)
		{
			for (Transport transport : transportsForEdge.apply(path.get(i - 1), path.get(i)))
			{
				if (TransportType.FAIRY_RING.equals(transport.getType()))
				{
					code = transport.getDisplayInfo();
				}
			}
		}
		return code;
	}

	private void highlight()
	{
		List<PathStep> path = displayPath.get();
		if (path.isEmpty())
		{
			return;
		}
		String fairyRingCode = routeCode(path, transportsForEdge);
		if (fairyRingCode == null)
		{
			return;
		}

		Widget codeWidget = null;
		Widget favesPanel = client.getWidget(InterfaceID.FairyringsLog.FAVES);
		if (favesPanel != null)
		{
			codeWidget = rowWithCode(favesPanel.getStaticChildren(), fairyRingCode);
		}
		Widget contentsList = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
		if (contentsList != null && codeWidget == null)
		{
			codeWidget = rowWithCode(contentsList.getDynamicChildren(), fairyRingCode);
		}
		if (codeWidget == null)
		{
			return;
		}

		codeWidget.setTextColor(0x00FF00);
		String codeWidgetText = codeWidget.getText();
		if (codeWidgetText != null && !codeWidgetText.contains("(GPS)"))
		{
			codeWidget.setText("(GPS) " + codeWidgetText);
		}

		if (contentsList == null)
		{
			return;
		}
		int panelScrollY = Math.min(codeWidget.getRelativeY(), contentsList.getScrollHeight() - contentsList.getHeight());
		contentsList.setScrollY(panelScrollY);
		contentsList.revalidateScroll();
		client.runScript(ScriptID.UPDATE_SCROLLBAR, InterfaceID.FairyringsLog.SCROLLBAR,
			InterfaceID.FairyringsLog.CONTENTS, panelScrollY);
	}

	private static Widget rowWithCode(Widget[] rows, String code)
	{
		if (rows == null)
		{
			return null;
		}
		for (Widget widget : rows)
		{
			if (widget != null)
			{
				String text = widget.getText();
				if (code.equals(text) || ("(GPS) " + code).equals(text))
				{
					return widget;
				}
			}
		}
		return null;
	}
}
