package com.example.target;

/** Each javac lowering of a switch over an enum, a string and a pattern, for ADR 0038. */
public class SwitchJavaTarget {

    public int enumStatement(SwitchColor color) {
        switch (color) {
            case RED:
                return 1;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }

    public int enumNoDefault(SwitchColor color) {
        int result = 0;
        switch (color) {
            case GREEN:
                result = 2;
                break;
            case RED:
                result = 1;
                break;
        }
        return result;
    }

    public int enumExpression(SwitchColor color) {
        return switch (color) {
            case RED -> 1;
            case GREEN -> 2;
            case BLUE -> 3;
        };
    }

    public int stringStatement(String status) {
        switch (status) {
            case "open":
                return 1;
            case "closed":
            case "done":
                return 2;
            case "Aa":
                return 3;
            case "BB":
                return 4;
            default:
                return 0;
        }
    }

    public int patternSwitch(Object value) {
        return switch (value) {
            case String s -> s.length();
            case Integer i -> i;
            default -> 0;
        };
    }

    public int guardedPattern(Object value) {
        return switch (value) {
            case String s when s.length() > 3 -> 1;
            case String s -> 2;
            default -> 0;
        };
    }

    public int enumPattern(SwitchColor color) {
        return switch (color) {
            case RED -> 1;
            case SwitchColor c when c.ordinal() > 1 -> 2;
            default -> 0;
        };
    }

    /** The sealed hierarchy {@link #sealedPattern} matches exhaustively. */
    public sealed interface Shape permits Circle, Square {}

    /** One {@link Shape}. */
    public record Circle(int radius) implements Shape {}

    /** The other {@link Shape}. */
    public record Square(int side) implements Shape {}

    public int sealedPattern(Shape shape) {
        return switch (shape) {
            case Circle c -> 1;
            case Square s -> 2;
        };
    }

    public int nullCase(Object value) {
        return switch (value) {
            case null -> -1;
            case String s -> 1;
            default -> 0;
        };
    }
}
