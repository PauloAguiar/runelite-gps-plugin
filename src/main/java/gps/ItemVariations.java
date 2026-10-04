package gps;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import lombok.Getter;

/**
 * An item family: the name the transport TSVs use in their Items column ("AXE", "COINS") and
 * every item id that counts as that item, read from item-variations.tsv. For a rune family,
 * rune-sources.tsv names the staff and offhand (tome) families that stand in for it. Data rather
 * than code, so a new ornament kit is a row, not a release of logic.
 */
public final class ItemVariations {
    private static final Map<String, ItemVariations> FAMILIES = load();
    /** The one family code names directly: the fairy rings' staff, with its bank and withdraw hints. */
    public static final ItemVariations DRAMEN_STAFF = fromName("DRAMEN_STAFF");

    private final String name;
    @Getter
    private int[] ids = new int[0];
    private ItemVariations staves;
    private ItemVariations offhands;

    private ItemVariations(String name) {
        this.name = name;
    }

    /** The family of that name, or null when no family carries it (the parser then tries a raw item id). */
    public static ItemVariations fromName(String name) {
        return FAMILIES.get(name);
    }

    /** The item ids of the staff family that is an unlimited source of this rune family, or null. */
    public static int[] staves(ItemVariations family) {
        return family == null || family.staves == null ? null : family.staves.ids;
    }

    /** The item ids of the offhand family (a tome) that covers this rune family, or null. */
    public static int[] offhands(ItemVariations family) {
        return family == null || family.offhands == null ? null : family.offhands.ids;
    }

    @Override
    public String toString() {
        return name;
    }

    private static Map<String, ItemVariations> load() {
        Map<String, ItemVariations> families = new LinkedHashMap<>();
        for (String[] row : rows("/item-variations.tsv")) {
            ItemVariations family = families.computeIfAbsent(row[0], ItemVariations::new);
            family.ids = Arrays.copyOf(family.ids, family.ids.length + 1);
            family.ids[family.ids.length - 1] = Integer.parseInt(row[1]);
        }
        for (String[] row : rows("/rune-sources.tsv")) {
            ItemVariations rune = families.get(row[0]);
            rune.staves = row.length > 1 && !row[1].isEmpty() ? families.get(row[1]) : null;
            rune.offhands = row.length > 2 && !row[2].isEmpty() ? families.get(row[2]) : null;
        }
        return families;
    }

    /** The data rows of a TSV resource: comment lines and the header skipped, fields tab-split. */
    static List<String[]> rows(String resource) {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = ItemVariations.class.getResourceAsStream(resource);
            Scanner scanner = new Scanner(in, "UTF-8")) {
            boolean header = true;
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                if (header) {
                    header = false;
                    continue;
                }
                rows.add(line.split("\t"));
            }
        }
        catch (java.io.IOException e) {
            throw new IllegalStateException(resource, e);
        }
        return rows;
    }
}
