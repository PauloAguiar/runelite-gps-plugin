package gps.transport.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

/**
 * Parses variable requirement strings into VarRequirement objects.
 * Supports both varbit and varplayer requirements.
 *
 * <p>
 * Format: {@code <id><check><value>} where check is one of: = > < & @
 * </p>
 * <p>
 * Multiple requirements are separated by semicolons.
 * </p>
 * <p>
 * Example: {@code 1234=1;5678>10}
 * </p>
 */
@Slf4j
public class VarRequirementParser implements FieldParser<Set<VarRequirement>> {
    private static final String DELIM_MULTI = ";";
    /** Alternatives within a clause: any one holding satisfies it. */
    private static final String DELIM_OR = "||";

    private final VarRequirement.VarType varType;

    /**
     * Creates a parser for the specified variable type.
     */
    public VarRequirementParser(VarRequirement.VarType varType) {
        this.varType = varType;
    }

    /**
     * Creates a parser for varbit requirements.
     */
    public static VarRequirementParser forVarbits() {
        return new VarRequirementParser(VarRequirement.VarType.VARBIT);
    }

    /**
     * Creates a parser for varplayer requirements.
     */
    public static VarRequirementParser forVarPlayers() {
        return new VarRequirementParser(VarRequirement.VarType.VARPLAYER);
    }

    @Override
    public Set<VarRequirement> parse(String value) {
        Set<VarRequirement> result = new HashSet<>();
        if (value == null || value.isEmpty())
            return result;

        try {
            for (String requirement : value.split(DELIM_MULTI)) {
                if (requirement.isEmpty())
                    continue;

                List<VarRequirement> clauses = new ArrayList<>();
                for (String clause : requirement.split(Pattern.quote(DELIM_OR))) {
                    VarRequirement parsed = parseRequirement(clause);
                    if (parsed == null) {
                        clauses.clear();
                        break;
                    }
                    clauses.add(parsed);
                }
                if (!clauses.isEmpty())
                    result.add(VarRequirement.anyOf(clauses));
            }
        }
        catch (NumberFormatException e) {
            log.error("Invalid var requirement: {}", value);
            ParseErrors.record("var", value);
        }
        return result;
    }

    private VarRequirement parseRequirement(String requirement) {
        for (VarCheckType checkType : VarCheckType.values()) {
            String[] parts = requirement.split(Pattern.quote(checkType.getCode()));
            if (parts.length == 2) {
                int id = Integer.parseInt(parts[0]);
                int val = Integer.parseInt(parts[1]);
                return new VarRequirement(varType, id, val, checkType);
            }
        }
        log.error("Invalid var requirement: '{}'", requirement);
        ParseErrors.record("var", requirement);
        return null;
    }
}
