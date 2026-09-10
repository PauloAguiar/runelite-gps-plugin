package gps;

import java.awt.Color;

/**
 * The display settings the overlays read every frame (plan step L24, out of the plugin
 * class): one immutable snapshot, taken from the config (with any plugin-message override
 * applied) at startup and again on every config change, so a render never sees a half-updated
 * set and never touches the config proxy.
 */
final class OverlaySettings
{
	final boolean drawMap;
	final boolean drawMinimap;
	final boolean drawTiles;
	final boolean drawRecalculationRanges;
	final boolean showTransportInfo;
	final boolean showBankPickupInfo;
	final Color colourPath;
	final Color colourPathSailing;
	final Color colourPathBlocked;
	final Color colourPathCalculating;
	final Color colourPathUnreachable;
	final Color colourText;
	final Color colourTeleportPulse;
	final Color colourOverlayAccent;
	final boolean showTeleportPulse;
	final boolean showDirections;
	final boolean overrideOverlayTransparency;
	final int overlayTransparency;
	// Display-only preference; not part of the capture-replay override set.
	final OverlayFontSize overlayFontSize;
	final boolean arrivalAutoDismiss;
	final int arrivalDismissSeconds;
	final int unreachableTargetDistance;
	final String unreachableText;

	private OverlaySettings(ShortestPathConfig config)
	{
		drawMap = ConfigOverrides.override("drawMap", config.drawMap());
		drawMinimap = ConfigOverrides.override("drawMinimap", config.drawMinimap());
		drawTiles = ConfigOverrides.override("drawTiles", config.drawTiles());
		drawRecalculationRanges = ConfigOverrides.override("drawRecalculationRanges", config.drawRecalculationRanges());
		showTransportInfo = ConfigOverrides.override("showTransportInfo", config.showTransportInfo());
		showBankPickupInfo = ConfigOverrides.override("showBankPickupInfo", config.showBankPickupInfo());

		colourPath = ConfigOverrides.override("colourPath", config.colourPath());
		colourPathSailing = ConfigOverrides.override("colourPathSailing", config.colourPathSailing());
		colourPathBlocked = ConfigOverrides.override("colourPathBlocked", config.colourPathBlocked());
		colourPathCalculating = ConfigOverrides.override("colourPathCalculating", config.colourPathCalculating());
		colourPathUnreachable = ConfigOverrides.override("colourPathUnreachable", config.colourPathUnreachable());
		colourText = ConfigOverrides.override("colourText", config.colourText());
		colourTeleportPulse = ConfigOverrides.override("colourTeleportPulse", config.colourTeleportPulse());
		colourOverlayAccent = ConfigOverrides.override("colourOverlayAccent", config.colourOverlayAccent());

		unreachableTargetDistance = ConfigOverrides.override("unreachableTargetDistanceThreshold", config.unreachableTargetDistance());
		unreachableText = config.unreachableText();

		showTeleportPulse = ConfigOverrides.override("showTeleportPulse", config.showTeleportPulse());
		showDirections = ConfigOverrides.override("showDirections", config.showDirections());
		overrideOverlayTransparency = ConfigOverrides.override("overrideOverlayTransparency", config.overrideOverlayTransparency());
		overlayTransparency = ConfigOverrides.override("overlayTransparency", config.overlayTransparency());
		overlayFontSize = config.overlayFontSize();
		arrivalAutoDismiss = ConfigOverrides.override("arrivalAutoDismiss", config.arrivalAutoDismiss());
		arrivalDismissSeconds = ConfigOverrides.override("arrivalDismissSeconds", config.arrivalDismissSeconds());
	}

	/** A snapshot of the config as it stands, overrides applied. */
	static OverlaySettings from(ShortestPathConfig config)
	{
		return new OverlaySettings(config);
	}
}
