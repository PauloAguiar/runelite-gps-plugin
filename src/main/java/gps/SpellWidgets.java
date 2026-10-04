package gps;

import java.util.HashMap;
import java.util.Map;

/**
 * Which spellbook component a teleport spell's Display info names, from spell-widgets.tsv: the
 * exact text first, else its stem before ": ", " — " or " (", so every "Teleport to Boat" variant
 * and "Varrock Teleport: GE" land on their one spell.
 */
final class SpellWidgets {
    private static final String[] QUALIFIERS = {": ", " — ", " ("};
    private static Map<String, Integer> components;

    private SpellWidgets() {
    }

    /** The packed component id of the spell, or -1 when the table has no row for it. */
    static synchronized int componentFor(String spell) {
        if (spell == null)
            return -1;
        if (components == null) {
            components = new HashMap<>();
            for (String[] row : ItemVariations.rows("/spell-widgets.tsv"))
                components.put(row[0], Integer.parseInt(row[1]));
        }
        Integer id = components.get(spell);
        if (id == null)
            id = components.get(stem(spell));
        return id == null ? -1 : id;
    }

    private static String stem(String spell) {
        for (String qualifier : QUALIFIERS) {
            int at = spell.indexOf(qualifier);
            if (at > 0)
                spell = spell.substring(0, at);
        }
        return spell;
    }
}
