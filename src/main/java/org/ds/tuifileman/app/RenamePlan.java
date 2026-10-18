package org.ds.tuifileman.app;

import org.ds.tuifileman.model.FileEntry;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes new names for a multi-rename. Pure logic, no file access: masks are expanded per
 * entry and the result is validated against Windows naming rules and against name clashes.
 *
 * <p>Mask tokens: {@code [N]} name, {@code [E]} extension, {@code [N2-5]} / {@code [N3-]} /
 * {@code [N4]} character ranges (1-based, also for {@code E}), {@code [C]} counter, {@code [P]}
 * parent directory, {@code [Y] [M] [D] [h] [m] [s]} parts of the modified time, {@code [YMD]}
 * and {@code [hms]} combined. Unknown tokens stay as typed.
 */
public final class RenamePlan {

    public record Item(FileEntry entry, String newName, String error) {
        public boolean changed() {
            return !entry.name().equals(newName);
        }
    }

    public enum CaseMode { UNCHANGED, LOWER, UPPER, WORDS, FIRST }

    public enum CaseScope { BOTH, NAME, EXTENSION }

    /**
     * @param search wildcard pattern ({@code *} any text, {@code ?} any one character) replaced by
     *     {@code replace} in the whole new file name; empty means no search/replace
     */
    public record Settings(String nameMask, String extensionMask, long counterStart, long counterStep, int counterDigits,
            String search, String replace, boolean matchCase, CaseMode caseMode, CaseScope caseScope) {

        public Settings(String nameMask, String extensionMask, long counterStart, long counterStep, int counterDigits) {
            this(nameMask, extensionMask, counterStart, counterStep, counterDigits,
                    "", "", false, CaseMode.UNCHANGED, CaseScope.BOTH);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\[([NE])(?:(\\d{1,4})(?:(-)(\\d{0,4}))?)?]|\\[(YMD|hms|[CPYMDhms])]");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3",
            "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6",
            "LPT7", "LPT8", "LPT9");

    private final List<Item> items;

    private RenamePlan(List<Item> items) {
        this.items = items;
    }

    public List<Item> items() {
        return items;
    }

    public int changedCount() {
        return (int) items.stream().filter(Item::changed).count();
    }

    public int errorCount() {
        return (int) items.stream().filter(item -> item.error() != null).count();
    }

    /**
     * @param batch the entries to rename, in the order the counter is applied
     * @param directoryNames every name in the directory (to detect clashes with files outside the batch)
     */
    public static RenamePlan create(List<FileEntry> batch, Set<String> directoryNames, Settings settings) {
        Set<String> outside = new HashSet<>();
        for (String name : directoryNames) {
            outside.add(key(name));
        }
        for (FileEntry entry : batch) {
            outside.remove(key(entry.name()));
        }
        List<String> names = new ArrayList<>();
        Map<String, Integer> uses = new HashMap<>();
        long counter = settings.counterStart();
        for (FileEntry entry : batch) {
            String name = newName(entry, settings, counter);
            counter += settings.counterStep();
            names.add(name);
            uses.merge(key(name), 1, Integer::sum);
        }
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            String name = names.get(i);
            String error = validate(name);
            if (error == null && uses.get(key(name)) > 1) {
                error = "Same new name as another item";
            } else if (error == null && outside.contains(key(name))) {
                error = "A file with this name already exists";
            }
            items.add(new Item(batch.get(i), name, error));
        }
        return new RenamePlan(items);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static String validate(String name) {
        String error = FileOps.validateName(name);
        if (error != null) {
            return error;
        }
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
        if (RESERVED.contains(stem)) {
            return "Reserved Windows name";
        }
        return name.length() > 255 ? "Name too long" : null;
    }

    private static String newName(FileEntry entry, Settings settings, long counter) {
        String oldName = entry.name();
        int dot = oldName.lastIndexOf('.');
        boolean hasExtension = !entry.directory() && dot > 0;
        String name = hasExtension ? oldName.substring(0, dot) : oldName;
        String extension = hasExtension ? oldName.substring(dot + 1) : "";
        String newName = expand(settings.nameMask(), entry, name, extension, counter, settings);
        String newExtension = expand(settings.extensionMask(), entry, name, extension, counter, settings);
        String full = newExtension.isEmpty() ? newName : newName + "." + newExtension;
        full = replaceWildcards(full, settings.search(), settings.replace(), settings.matchCase());
        return changeCase(full, entry.directory(), settings.caseMode(), settings.caseScope());
    }

    /** Replaces every match of a wildcard pattern: {@code *} is any text (shortest match), {@code ?} one character. */
    static String replaceWildcards(String text, String search, String replace, boolean matchCase) {
        if (search.isEmpty()) {
            return text;
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < search.length(); i++) {
            char c = search.charAt(i);
            if (c == '*') {
                regex.append(i == search.length() - 1 ? ".*" : ".*?");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        Matcher matcher = Pattern.compile(regex.toString(),
                matchCase ? Pattern.DOTALL : Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                continue;
            }
            out.append(text, last, matcher.start()).append(replace);
            last = matcher.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    static String changeCase(String full, boolean directory, CaseMode mode, CaseScope scope) {
        if (mode == CaseMode.UNCHANGED) {
            return full;
        }
        int dot = full.lastIndexOf('.');
        boolean hasExtension = !directory && dot > 0;
        String name = hasExtension ? full.substring(0, dot) : full;
        String extension = hasExtension ? full.substring(dot + 1) : "";
        if (scope != CaseScope.EXTENSION) {
            name = caseOf(name, mode);
        }
        if (scope != CaseScope.NAME) {
            extension = caseOf(extension, mode);
        }
        return hasExtension ? name + "." + extension : name;
    }

    private static String caseOf(String text, CaseMode mode) {
        switch (mode) {
            case LOWER:
                return text.toLowerCase(Locale.ROOT);
            case UPPER:
                return text.toUpperCase(Locale.ROOT);
            case FIRST:
                return text.isEmpty() ? text
                        : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1).toLowerCase(Locale.ROOT);
            case WORDS:
                StringBuilder out = new StringBuilder();
                boolean wordStart = true;
                for (char c : text.toCharArray()) {
                    out.append(wordStart ? Character.toUpperCase(c) : Character.toLowerCase(c));
                    wordStart = c == ' ' || c == '_' || c == '-' || c == '.' || c == '(' || c == '[';
                }
                return out.toString();
            default:
                return text;
        }
    }

    private static String expand(String mask, FileEntry entry, String name, String extension, long counter,
            Settings settings) {
        Matcher matcher = TOKEN.matcher(mask);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            if (matcher.group(1) != null) {
                String source = "N".equals(matcher.group(1)) ? name : extension;
                replacement = range(source, matcher.group(2), matcher.group(3), matcher.group(4));
            } else {
                replacement = simple(matcher.group(5), entry, counter, settings);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String range(String source, String from, String dash, String to) {
        if (from == null) {
            return source;
        }
        int start = Math.max(1, Integer.parseInt(from));
        int end = dash == null ? start : to == null || to.isEmpty() ? source.length() : Integer.parseInt(to);
        end = Math.min(end, source.length());
        return start > end ? "" : source.substring(start - 1, end);
    }

    private static String simple(String token, FileEntry entry, long counter, Settings settings) {
        if ("C".equals(token)) {
            String digits = Long.toString(Math.abs(counter));
            String padded = "0".repeat(Math.max(0, settings.counterDigits() - digits.length())) + digits;
            return counter < 0 ? "-" + padded : padded;
        }
        if ("P".equals(token)) {
            java.nio.file.Path parent = entry.path().toAbsolutePath().getParent();
            java.nio.file.Path parentName = parent == null ? null : parent.getFileName();
            return parentName == null ? "" : parentName.toString();
        }
        LocalDateTime time = entry.modified() == null ? LocalDateTime.of(1970, 1, 1, 0, 0)
                : LocalDateTime.ofInstant(entry.modified().toInstant(), ZoneId.systemDefault());
        return switch (token) {
            case "Y" -> String.format("%04d", time.getYear());
            case "M" -> String.format("%02d", time.getMonthValue());
            case "D" -> String.format("%02d", time.getDayOfMonth());
            case "h" -> String.format("%02d", time.getHour());
            case "m" -> String.format("%02d", time.getMinute());
            case "s" -> String.format("%02d", time.getSecond());
            case "YMD" -> String.format("%04d%02d%02d", time.getYear(), time.getMonthValue(), time.getDayOfMonth());
            default -> String.format("%02d%02d%02d", time.getHour(), time.getMinute(), time.getSecond());
        };
    }
}
