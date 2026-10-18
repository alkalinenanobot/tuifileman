package org.ds.tuifileman.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small command-line parser: {@code --name value}, {@code --name=value}, {@code -x value}, clustered
 * flags ({@code -qv}) and {@code --} for "everything after this is a positional argument". The options a
 * command accepts are declared up front, so a typo is a usage error instead of a silent surprise.
 */
final class Args {
    /** A bad command line; the message is shown to the user together with a hint to use {@code --help}. */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    private record Spec(String longName, boolean takesValue) { }

    private final Map<String, Spec> byName = new HashMap<>();
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    /** Declares {@code --longName}, with an optional one-letter alias ({@code 0} for none). */
    Args option(String longName, char shortName, boolean takesValue) {
        Spec spec = new Spec(longName, takesValue);
        byName.put("--" + longName, spec);
        if (shortName != 0) {
            byName.put("-" + shortName, spec);
        }
        return this;
    }

    Args parse(List<String> arguments) throws UsageException {
        boolean rest = false;
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            if (rest || !argument.startsWith("-") || argument.equals("-")) {
                positional.add(argument);
            } else if (argument.equals("--")) {
                rest = true;
            } else if (argument.startsWith("--")) {
                int equals = argument.indexOf('=');
                String name = equals < 0 ? argument : argument.substring(0, equals);
                Spec spec = lookup(name);
                if (spec.takesValue()) {
                    if (equals >= 0) {
                        add(spec, argument.substring(equals + 1));
                    } else if (i + 1 < arguments.size()) {
                        add(spec, arguments.get(++i));
                    } else {
                        throw new UsageException("option " + name + " needs a value");
                    }
                } else if (equals >= 0) {
                    throw new UsageException("option " + name + " does not take a value");
                } else {
                    add(spec, "");
                }
            } else {
                // -x, -x value, -xvalue is not supported; -qv clusters flags
                for (int c = 1; c < argument.length(); c++) {
                    String name = "-" + argument.charAt(c);
                    Spec spec = lookup(name);
                    if (spec.takesValue()) {
                        if (c + 1 < argument.length()) {
                            throw new UsageException("option " + name + " needs a value; write " + name + " VALUE");
                        } else if (i + 1 < arguments.size()) {
                            add(spec, arguments.get(++i));
                        } else {
                            throw new UsageException("option " + name + " needs a value");
                        }
                    } else {
                        add(spec, "");
                    }
                }
            }
        }
        return this;
    }

    private Spec lookup(String name) throws UsageException {
        Spec spec = byName.get(name);
        if (spec == null) {
            throw new UsageException("unknown option " + name);
        }
        return spec;
    }

    private void add(Spec spec, String value) {
        values.computeIfAbsent(spec.longName(), k -> new ArrayList<>()).add(value);
    }

    boolean has(String longName) {
        return values.containsKey(longName);
    }

    /** The last value given (so a later option overrides an earlier one), or the fallback. */
    String get(String longName, String fallback) {
        List<String> list = values.get(longName);
        return list == null ? fallback : list.get(list.size() - 1);
    }

    List<String> all(String longName) {
        return values.getOrDefault(longName, List.of());
    }

    List<String> positional() {
        return positional;
    }
}
