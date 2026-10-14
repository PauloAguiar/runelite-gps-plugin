package gps.transport.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.util.function.IntUnaryOperator;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Represents a variable-based requirement for a transport.
 * This can be either a varbit or a varplayer requirement.
 *
 * <p>
 * Consolidates the previously separate TransportVarbit and TransportVarPlayer
 * classes which had identical logic.
 * </p>
 */
@Getter
@EqualsAndHashCode
public class VarRequirement {

    private final VarType varType;
    private final int id;
    private final int value;
    private final VarCheckType checkType;
    /** The clauses of an "a||b" group, any one of which satisfies it; null for a single clause. */
    private final List<VarRequirement> alternatives;

    public VarRequirement(VarType varType, int id, int value, VarCheckType checkType) {
        this(varType, id, value, checkType, null);
    }

    private VarRequirement(VarType varType, int id, int value, VarCheckType checkType, List<VarRequirement> alternatives) {
        this.varType = varType;
        this.id = id;
        this.value = value;
        this.checkType = checkType;
        this.alternatives = alternatives;
    }

    /** One requirement holding when any of the clauses does (they share the var type). */
    public static VarRequirement anyOf(List<VarRequirement> clauses) {
        VarRequirement first = clauses.get(0);
        return clauses.size() == 1 ? first
            : new VarRequirement(first.varType, first.id, first.value, first.checkType, List.copyOf(clauses));
    }

    /** Every variable id this requirement reads. */
    public List<Integer> ids() {
        if (alternatives == null)
            return List.of(id);
        List<Integer> out = new ArrayList<>(alternatives.size());
        for (VarRequirement clause : alternatives)
            out.add(clause.id);
        return out;
    }

    /** Whether the requirement holds with the variables read through {@code lookup}. */
    public boolean checkWith(IntUnaryOperator lookup) {
        if (alternatives == null)
            return checkValue(lookup.applyAsInt(id));
        for (VarRequirement clause : alternatives)
            if (clause.checkValue(lookup.applyAsInt(clause.id)))
                return true;
        return false;
    }

    /**
     * Creates a varbit requirement.
     */
    public static VarRequirement varbit(int id, int value, VarCheckType checkType) {
        return new VarRequirement(VarType.VARBIT, id, value, checkType);
    }

    /**
     * Creates a varplayer requirement.
     */
    public static VarRequirement varPlayer(int id, int value, VarCheckType checkType) {
        return new VarRequirement(VarType.VARPLAYER, id, value, checkType);
    }

    /**
     * Checks if this requirement is satisfied given the current variable values.
     *
     * @param values A map of variable IDs to their current values
     * @return true if the requirement is satisfied
     */
    public boolean check(Map<Integer, Integer> values) {
        if (alternatives != null) {
            for (VarRequirement clause : alternatives)
                if (clause.check(values))
                    return true;
            return false;
        }
        Integer currentValue = values.get(id);
        if (currentValue == null)
            return false;
        return checkValue(currentValue);
    }

    /**
     * Same logic as {@link #check(Map)} but with the variable value already resolved (e.g. from the client).
     */
    public boolean checkValue(int currentValue) {
        switch (checkType) {
            case EQUAL:
                return currentValue == value;
            case GREATER:
                return currentValue > value;
            case SMALLER:
                return currentValue < value;
            case BIT_SET:
                return (currentValue & value) > 0;
            case COOLDOWN_MINUTES:
                return ((System.currentTimeMillis() / 60000) - currentValue) > value;
            default:
                return false;
        }
    }

    public boolean isVarbit() {
        return varType == VarType.VARBIT;
    }

    public boolean isVarPlayer() {
        return varType == VarType.VARPLAYER;
    }

    @Override
    public String toString() {
        if (alternatives != null)
            return varType + "[any of " + alternatives + "]";
        return varType + "[" + id + " " + checkType.getCode() + " " + value + "]";
    }

    /**
     * The type of variable this requirement checks.
     */
    public enum VarType {
        VARBIT,
        VARPLAYER
    }
}
