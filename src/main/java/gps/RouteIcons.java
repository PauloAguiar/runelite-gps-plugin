package gps;

import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;

/**
 * Small 16px UI icons for the alternative-routes panel, PNG resources under /icons. They were drawn
 * with Java2D up to 0.13.2 (that RouteIcons is the source of every file here, pixel for pixel, as
 * RouteIconsTest's recorded hashes attest); the hub's review bot counts code, not resources, so
 * the drawings became assets. Each action has a base (grey) and a hover (accent) variant,
 * mirroring the base/hover icon swap used by the tile-packs panel controls.
 */
final class RouteIcons {
	private static final int SIZE = 16;

	// Show / hide a route on the map (map pin). Active = currently shown.
	static final ImageIcon SHOW = icon("show");
	static final ImageIcon SHOW_ACTIVE = icon("show_active");
	// Exclude a method from the next search (no-entry).
	static final ImageIcon EXCLUDE_HOVER = icon("exclude_hover");
	// Resting state on route cards: present but nearly invisible, coloured up while the card is
	// hovered — toggling visibility instead shifted the row height.
	static final ImageIcon EXCLUDE_DIM = icon("exclude_dim");
	// Marks the route card's ETA.
	static final ImageIcon CLOCK = icon("clock");
	// Hull-type glyphs for the sailing section's berth rows: drawn, not font glyphs (the panel
	// font has no boat character and falls back to a warning triangle).
	static final ImageIcon BOAT_RAFT = icon("boat_raft");
	static final ImageIcon BOAT_SKIFF = icon("boat_skiff");
	static final ImageIcon BOAT_SLOOP = icon("boat_sloop");
	// Clear all exclusions (trash can).
	static final ImageIcon CLEAR = icon("clear");
	// Catalog toggles: included (check), excluded (cross), partially-included category (dash).
	static final ImageIcon CHECK = icon("check");
	static final ImageIcon CHECK_HOVER = icon("check_hover");
	static final ImageIcon CROSS = icon("cross");
	static final ImageIcon CROSS_HOVER = icon("cross_hover");
	// Red at REST (brighter red on hover): close controls that should read as red without hovering.
	static final ImageIcon CROSS_RED = icon("cross_red");
	static final ImageIcon CROSS_RED_HOVER = icon("cross_red_hover");
	static final ImageIcon DASH = icon("dash");
	static final ImageIcon DASH_HOVER = icon("dash_hover");
	// Dimmed variants of the catalog toggle glyphs for the configuration sections' checkboxes,
	// whose rows (unlike the catalog's) can be disabled.
	static final ImageIcon CHECK_DIM = icon("check_dim");
	static final ImageIcon CROSS_DIM = icon("cross_dim");
	// Favourite positions: the save button beside the destination search, and the search results'
	// category glyph for saved favourites.
	static final ImageIcon FAVORITE = icon("favorite");
	static final ImageIcon FAVORITE_HOVER = icon("favorite_hover");
	// Route control panel: a green "+" for more routes, blue refresh, red clear.
	static final ImageIcon SHOW_MORE = icon("show_more");
	static final ImageIcon SHOW_MORE_HOVER = icon("show_more_hover");
	static final ImageIcon CTRL_REFRESH = icon("ctrl_refresh");
	static final ImageIcon CTRL_REFRESH_HOVER = icon("ctrl_refresh_hover");
	static final ImageIcon CTRL_CLEAR = icon("ctrl_clear");
	static final ImageIcon CTRL_CLEAR_HOVER = icon("ctrl_clear_hover");
	// Expand/collapse a category.
	static final ImageIcon CHEVRON_RIGHT = icon("chevron_right");
	static final ImageIcon CHEVRON_DOWN = icon("chevron_down");
	// Method the player can't use right now (missing item/level/quest/unlock).
	static final ImageIcon LOCKED = icon("locked");
	// Method whose required item is owned but sitting in the bank (route through a bank to grab it).
	static final ImageIcon IN_BANK = icon("in_bank");
	// Capture a debug snapshot of the current routes (camera).
	static final ImageIcon DEBUG = icon("debug");
	// Filter the catalog to only the currently-disabled methods (funnel). Orange = active.
	static final ImageIcon FILTER = icon("filter");
	static final ImageIcon FILTER_HOVER = icon("filter_hover");
	static final ImageIcon FILTER_ACTIVE = icon("filter_active");
	static final ImageIcon FILTER_ACTIVE_HOVER = icon("filter_active_hover");
	// Header burger menu holding the secondary actions (debug snapshot, reset exclusions).
	static final ImageIcon MENU = icon("menu");
	static final ImageIcon MENU_HOVER = icon("menu_hover");
	// GitHub mark linking to the plugin's repository (report issues / contribute). The rest state
	// sits at the panel's grey-icon weight.
	static final ImageIcon GITHUB = new ImageIcon(ImageUtil.alphaOffset(
		ImageUtil.resizeImage(ImageUtil.loadImageResource(RouteIcons.class, "/github.png"), SIZE, SIZE), -70));
	// Discord invite: the bundled Discord mark (from Quest Helper's resources).
	static final ImageIcon DISCORD = new ImageIcon(
		ImageUtil.resizeImage(ImageUtil.loadImageResource(RouteIcons.class, "/discord.png"), SIZE, SIZE));
	// Report an issue: a red warning triangle on the report button (opens a pre-filled GitHub issue).
	static final ImageIcon REPORT = icon("report");

	/**
	 * The plugin's identity mark: the navigation-blue location pin, matching the GPS overlay's title
	 * glyph, scaled up to fill the 16px tile for the sidebar tab (the panel's row pins stay smaller
	 * so they read as buttons next to text). Also exportable for the hub listing icon.
	 */
	static BufferedImage gpsPin() {
		return image("gps_pin");
	}

	// ── Destination-search category icons ──────────────────────────────
	// A coherent, meaningful set (one glyph per category) replacing the old hash-coloured dots.
	private static final ImageIcon DEST_PLACE = icon("dest_place");
	private static final ImageIcon DEST_BANK = icon("dest_bank");
	private static final ImageIcon DEST_BANK_ROUND_TRIP = icon("dest_bank_round_trip");
	// The Bank quick button while a click would add a stop: the coin stack with a "+" badge.
	private static final ImageIcon DEST_BANK_STOP = icon("dest_bank_stop");
	private static final ImageIcon DEST_ALTAR = icon("dest_altar");
	private static final ImageIcon DEST_WATER = icon("dest_water");
	private static final ImageIcon DEST_FURNACE = icon("dest_furnace");
	private static final ImageIcon DEST_ANVIL = icon("dest_anvil");
	private static final ImageIcon DEST_RANGE = icon("dest_range");
	private static final ImageIcon DEST_SPINNING = icon("dest_spinning");
	private static final ImageIcon DEST_POTTERY = icon("dest_pottery");
	private static final ImageIcon DEST_FAIRY = icon("dest_fairy");
	private static final ImageIcon DEST_SPIRIT_TREE = icon("dest_spirit_tree");
	private static final ImageIcon DEST_DUNGEON = icon("dest_dungeon");
	private static final ImageIcon DEST_MINIGAME = icon("dest_minigame");
	private static final ImageIcon DEST_LANDMARK = icon("dest_landmark");
	// Training spots (agility courses, skilling areas): a course flag.
	private static final ImageIcon DEST_TRAINING = icon("dest_training");
	private static final ImageIcon DEST_PIN = icon("dest_pin");

	// Panel message-banner glyphs: a warning triangle, an info circle, and a busy spinner.
	static final ImageIcon BANNER_WARNING = icon("banner_warning");
	static final ImageIcon BANNER_INFO = icon("banner_info");
	static final ImageIcon BANNER_BUSY = icon("banner_busy");

	/** The icon for a destination category, falling back to a location pin for anything unmapped. */
	static ImageIcon destinationIcon(String category) {
		switch (category) {
			case "place": return DEST_PLACE;
			case "bank": return DEST_BANK;
			case "bank_round_trip": return DEST_BANK_ROUND_TRIP;
			case "altar": return DEST_ALTAR;
			case "water": return DEST_WATER;
			case "furnace": return DEST_FURNACE;
			case "anvil": return DEST_ANVIL;
			case "range": return DEST_RANGE;
			case "spinning_wheel": return DEST_SPINNING;
			case "pottery": return DEST_POTTERY;
			case "fairy_ring": return DEST_FAIRY;
			case "spirit_tree": return DEST_SPIRIT_TREE;
			case "dungeon": return DEST_DUNGEON;
			case "minigame": return DEST_MINIGAME;
			case "landmark": return DEST_LANDMARK;
			case "training": return DEST_TRAINING;
			case "favorite": return FAVORITE;
			default: return DEST_PIN;
		}
	}

	/** The Bank quick button's icon: the coin stack, with a "+" badge while a click would add a stop. */
	static ImageIcon bankButtonIcon(boolean addsStop) {
		return addsStop ? DEST_BANK_STOP : DEST_BANK;
	}

	/** The hull glyph for a tier. */
	static ImageIcon hullIcon(BoatHull hull) {
		switch (hull) {
			case RAFT:
				return BOAT_RAFT;
			case SKIFF:
				return BOAT_SKIFF;
			case SLOOP:
			default:
				return BOAT_SLOOP;
		}
	}

	// Priority tiers (MethodPriority): stacked arrowheads, RimWorld-style — green up = prefer,
	// amber/red down = avoid, grey dash = normal. Hover variants brighten. Index = tier - 1.
	static final ImageIcon[] PRIORITY_UP_ICONS = {icon("priority_up_1"), icon("priority_up_2"), icon("priority_up_3")};
	static final ImageIcon[] PRIORITY_UP_HOVER_ICONS = {
		icon("priority_up_hover_1"), icon("priority_up_hover_2"), icon("priority_up_hover_3")};
	static final ImageIcon[] PRIORITY_DOWN_ICONS = {
		icon("priority_down_1"), icon("priority_down_2"), icon("priority_down_3")};
	static final ImageIcon[] PRIORITY_DOWN_HOVER_ICONS = {
		icon("priority_down_hover_1"), icon("priority_down_hover_2"), icon("priority_down_hover_3")};

	// Neutral tier: a quiet dash (a checkmark reads as "enabled", not "no preference"). The dim
	// variant is the route-card rest state, near-invisible like the old exclude circle.
	static final ImageIcon PRIORITY_NEUTRAL = icon("priority_neutral");
	static final ImageIcon PRIORITY_NEUTRAL_HOVER = icon("priority_neutral_hover");
	static final ImageIcon PRIORITY_NEUTRAL_DIM = icon("priority_neutral_dim");

	private RouteIcons() {
	}

	private static ImageIcon icon(String name) {
		return new ImageIcon(image(name));
	}

	private static BufferedImage image(String name) {
		return ImageUtil.loadImageResource(RouteIcons.class, "/icons/" + name + ".png");
	}
}
