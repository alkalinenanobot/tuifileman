package org.ds.tuifileman.view;

import com.googlecode.lanterna.screen.Screen;
import org.ds.tuifileman.config.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A video or audio file for the F3 viewer, done with optional external tools: {@code ffprobe} for the info
 * page, {@code ffmpeg} for a poster frame (shown through the image path; {@code Space}/{@code PgUp} step
 * through ten frames spread over the file) and {@code mpv} for playback ({@code P}), which gets the terminal
 * for as long as it runs.
 */
final class MediaDoc implements PreviewSource {
    private static final Set<String> VIDEO = Set.of("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg",
            "mpeg", "m2ts", "mts", "ogv", "3gp", "vob");
    private static final Set<String> AUDIO = Set.of("mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "aac", "wma",
            "aiff", "ape", "mka");
    private static final int FRAMES = 10;
    private static final int INFO_TIMEOUT = 30;
    private static final int RENDER_TIMEOUT = 60;
    private static final int MAX_STREAM_ROWS = 14;
    private static final String INSTALL = "FFmpeg installed? on PATH?";
    private static final Pattern STREAM_KEY = Pattern.compile("^streams\\.stream\\.(\\d+)\\.(.+)$");
    private static final List<String[]> TAGS = List.of(new String[] {"title", "Title"}, new String[] {"artist", "Artist"},
            new String[] {"album", "Album"}, new String[] {"date", "Date"}, new String[] {"genre", "Genre"});

    private final Path path;
    private final String ffprobe;
    private final String ffmpeg;
    private final String mpv;
    private final String vo;
    private final String extraArgs;
    private final int cellWidth;
    private final int cellHeight;
    private final Map<String, String> format = new HashMap<>();
    private final SortedMap<Integer, Map<String, String>> streams = new TreeMap<>();
    private String infoError;
    private double duration;
    private int position = 2;
    private Path tempDir;
    private int renderedPosition = -1;
    private int renderedHeight = -1;

    private MediaDoc(Path path) {
        Config config = Config.get();
        this.path = path;
        this.ffprobe = Tools.resolve(config.text("video.ffprobe", "ffprobe").strip());
        this.ffmpeg = Tools.resolve(config.text("video.ffmpeg", "ffmpeg").strip());
        this.mpv = Tools.resolveConsole(config.text("video.mpv", "mpv").strip());
        this.vo = config.text("video.vo", "sixel").strip();
        int[] cell = cellSize(config.text("video.cell_size", "10x20"));
        this.cellWidth = cell[0];
        this.cellHeight = cell[1];
        this.extraArgs = config.text("video.args", "").strip();
        readInfo();
    }

    static boolean supports(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String extension = name.substring(dot + 1);
        return VIDEO.contains(extension) || AUDIO.contains(extension);
    }

    static MediaDoc open(Path path) {
        return new MediaDoc(path);
    }

    private static int[] cellSize(String text) {
        Matcher m = Pattern.compile("(\\d{1,3})\\s*x\\s*(\\d{1,3})").matcher(text);
        return m.matches() ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}
                : new int[] {10, 20};
    }

    private void readInfo() {
        try {
            Tools.Output out = Tools.run(List.of(ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "flat",
                    path.toString()), INFO_TIMEOUT, 4 << 20, "ffprobe", INSTALL);
            parseFlat(new String(out.data(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            infoError = e.getMessage();
        }
    }

    /** Reads ffprobe's flat output ({@code streams.stream.0.codec_name="h264"}) into format and stream maps. */
    void parseFlat(String text) {
        for (String line : text.split("\\R")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq);
            String value = unquote(line.substring(eq + 1));
            Matcher m = STREAM_KEY.matcher(key);
            if (m.matches()) {
                streams.computeIfAbsent(Integer.parseInt(m.group(1)), i -> new HashMap<>())
                        .put(m.group(2).toLowerCase(Locale.ROOT), value);
            } else if (key.startsWith("format.")) {
                format.put(key.substring(7).toLowerCase(Locale.ROOT), value);
            }
        }
        duration = number(format.get("duration"));
    }

    private static String unquote(String value) {
        String v = value.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static double number(String text) {
        try {
            return text == null ? 0 : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean infoKnown() {
        return !streams.isEmpty();
    }

    private static boolean attachedPicture(Map<String, String> stream) {
        return "1".equals(stream.get("disposition.attached_pic"));
    }

    /** A real video stream (not cover art). */
    private boolean hasVideo() {
        for (Map<String, String> stream : streams.values()) {
            if ("video".equals(stream.get("codec_type")) && !attachedPicture(stream)) {
                return true;
            }
        }
        return !infoKnown() && VIDEO.contains(extension());
    }

    private boolean hasCover() {
        for (Map<String, String> stream : streams.values()) {
            if ("video".equals(stream.get("codec_type")) && attachedPicture(stream)) {
                return true;
            }
        }
        return false;
    }

    private String extension() {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private Map<String, String> firstVideo() {
        for (Map<String, String> stream : streams.values()) {
            if ("video".equals(stream.get("codec_type")) && !attachedPicture(stream)) {
                return stream;
            }
        }
        return Map.of();
    }

    @Override
    public String kind() {
        return hasVideo() ? "Video" : "Audio";
    }

    @Override
    public int position() {
        return position;
    }

    @Override
    public void setPosition(int position) {
        this.position = position;
    }

    @Override
    public int count() {
        return hasVideo() && duration > 0 ? FRAMES : 1;
    }

    private double seconds() {
        return count() > 1 ? duration * (position - 0.5) / FRAMES : 0;
    }

    @Override
    public String positionLabel() {
        return count() > 1 ? "frame " + position + "/" + count() + " at " + clock(seconds()) : "";
    }

    @Override
    public String describe() {
        List<String> parts = new ArrayList<>();
        Map<String, String> video = firstVideo();
        if (!video.isEmpty() && video.get("width") != null) {
            parts.add(video.get("width") + "x" + video.get("height"));
        }
        if (duration > 0) {
            parts.add(clock(duration));
        }
        String codec = video.isEmpty() ? firstCodec("audio") : video.get("codec_name");
        if (codec != null && !codec.isEmpty()) {
            parts.add(codec);
        }
        return String.join("  ", parts);
    }

    private String firstCodec(String type) {
        for (Map<String, String> stream : streams.values()) {
            if (type.equals(stream.get("codec_type"))) {
                return stream.get("codec_name");
            }
        }
        return null;
    }

    @Override
    public String navigationHint() {
        return count() > 1 ? "Space/PgDn next frame   PgUp prev   Home/End" : "";
    }

    @Override
    public String tabHint() {
        return "Tab hex view";
    }

    @Override
    public String installHint() {
        return "Install FFmpeg (ffprobe, ffmpeg) or set video.ffmpeg in";
    }

    @Override
    public String infoError() {
        return infoError;
    }

    @Override
    public boolean canPlay() {
        return true;
    }

    @Override
    public List<String[]> infoRows() {
        List<String[]> rows = new ArrayList<>();
        String longName = format.get("format_long_name");
        rows.add(new String[] {"Format", longName != null ? longName : extension().toUpperCase(Locale.ROOT)});
        if (duration > 0) {
            rows.add(new String[] {"Duration", clock(duration)});
        }
        double bitrate = number(format.get("bit_rate"));
        if (bitrate > 0) {
            rows.add(new String[] {"Bitrate", rate(bitrate)});
        }
        for (String[] tag : TAGS) {
            String value = format.get("tags." + tag[0]);
            if (value != null && !value.isBlank()) {
                rows.add(new String[] {tag[1], value});
            }
        }
        int shown = 0;
        int[] counters = new int[3];
        for (Map<String, String> stream : streams.values()) {
            String type = stream.getOrDefault("codec_type", "");
            String label;
            String text;
            switch (type) {
                case "video" -> {
                    boolean cover = attachedPicture(stream);
                    label = cover ? "Cover art" : "Video " + (++counters[0]);
                    text = videoText(stream);
                }
                case "audio" -> {
                    label = "Audio " + (++counters[1]);
                    text = audioText(stream);
                }
                case "subtitle" -> {
                    label = "Subtitle " + (++counters[2]);
                    text = join(stream.get("codec_name"), language(stream));
                }
                default -> {
                    continue;
                }
            }
            if (shown++ >= MAX_STREAM_ROWS) {
                rows.add(new String[] {"", "... more streams"});
                break;
            }
            rows.add(new String[] {label, text});
        }
        return rows;
    }

    private static String language(Map<String, String> stream) {
        return stream.get("tags.language");
    }

    private static String videoText(Map<String, String> s) {
        String codec = s.getOrDefault("codec_name", "?");
        String profile = s.get("profile");
        List<String> parts = new ArrayList<>();
        parts.add(profile == null || profile.isEmpty() ? codec : codec + " (" + profile + ")");
        if (s.get("width") != null) {
            parts.add(s.get("width") + "x" + s.get("height"));
        }
        double fps = fps(s.get("avg_frame_rate"));
        if (fps <= 0) {
            fps = fps(s.get("r_frame_rate"));
        }
        if (fps > 0 && !attachedPicture(s)) {
            parts.add(String.format(Locale.ROOT, "%.2f fps", fps));
        }
        double bitrate = number(s.get("bit_rate"));
        if (bitrate > 0) {
            parts.add(rate(bitrate));
        }
        String language = language(s);
        if (language != null && !language.isEmpty()) {
            parts.add(language);
        }
        return String.join(", ", parts);
    }

    private static String audioText(Map<String, String> s) {
        List<String> parts = new ArrayList<>();
        String codec = s.getOrDefault("codec_name", "?");
        String profile = s.get("profile");
        parts.add(profile == null || profile.isEmpty() ? codec : codec + " (" + profile + ")");
        if (s.get("sample_rate") != null) {
            parts.add(s.get("sample_rate") + " Hz");
        }
        String layout = s.get("channel_layout");
        if (layout != null && !layout.isEmpty()) {
            parts.add(layout);
        } else if (s.get("channels") != null) {
            parts.add(s.get("channels") + " ch");
        }
        double bitrate = number(s.get("bit_rate"));
        if (bitrate > 0) {
            parts.add(rate(bitrate));
        }
        String language = language(s);
        if (language != null && !language.isEmpty()) {
            parts.add(language);
        }
        return String.join(", ", parts);
    }

    private static String join(String... items) {
        List<String> parts = new ArrayList<>();
        for (String item : items) {
            if (item != null && !item.isEmpty()) {
                parts.add(item);
            }
        }
        return String.join(", ", parts);
    }

    private static double fps(String ratio) {
        if (ratio == null) {
            return 0;
        }
        int slash = ratio.indexOf('/');
        if (slash < 0) {
            return number(ratio);
        }
        double denominator = number(ratio.substring(slash + 1));
        return denominator == 0 ? 0 : number(ratio.substring(0, slash)) / denominator;
    }

    static String rate(double bitsPerSecond) {
        return bitsPerSecond >= 1e6 ? String.format(Locale.ROOT, "%.1f Mb/s", bitsPerSecond / 1e6)
                : String.format(Locale.ROOT, "%.0f kb/s", bitsPerSecond / 1e3);
    }

    static String clock(double seconds) {
        long total = Math.round(seconds);
        long h = total / 3600;
        long m = total / 60 % 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    @Override
    public Path render(int pixelHeight) {
        if (infoKnown() && !hasVideo() && !hasCover()) {
            throw new NoPicture("audio file without cover art");
        }
        if (!infoKnown() && AUDIO.contains(extension())) {
            throw new NoPicture("audio file");
        }
        try {
            if (tempDir == null) {
                tempDir = Files.createTempDirectory("tuifileman-media");
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot create a temporary folder: " + e.getMessage());
        }
        Path png = tempDir.resolve("frame.png");
        if (position == renderedPosition && pixelHeight == renderedHeight && Files.exists(png)) {
            return png;
        }
        double at = seconds();
        if (!extractFrame(png, at, pixelHeight) && at > 0) {
            extractFrame(png, 0, pixelHeight);
        }
        if (!Files.exists(png)) {
            throw new IllegalStateException("ffmpeg produced no picture at " + clock(at));
        }
        renderedPosition = position;
        renderedHeight = pixelHeight;
        return png;
    }

    private boolean extractFrame(Path png, double seconds, int pixelHeight) {
        try {
            Files.deleteIfExists(png);
        } catch (IOException ignored) { }
        Tools.run(List.of(ffmpeg, "-v", "error", "-y", "-ss", String.format(Locale.ROOT, "%.3f", seconds), "-i",
                path.toString(), "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-vf",
                "scale=-2:min(ih\\," + pixelHeight + ")", png.toString()), RENDER_TIMEOUT, 1 << 20, "ffmpeg", INSTALL);
        try {
            return Files.exists(png) && Files.size(png) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether this mpv build lists the video output (the stock Windows builds have no sixel). */
    private boolean supportsOutput(String output) {
        if (output.equals("tct") || output.equals("gpu") || output.equals("gpu-next")) {
            return true;
        }
        try {
            Tools.Output out = Tools.run(List.of(mpv, "--vo=help"), 10, 1 << 16, "mpv", INSTALL);
            return Pattern.compile("(?m)^\\s*" + Pattern.quote(output) + "\\s").matcher(
                    new String(out.data(), StandardCharsets.UTF_8)).find();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Gives the terminal to mpv until it exits, then takes it back; returns a message when it failed. */
    @Override
    public String play(Screen screen) {
        if (mpv.isEmpty()) {
            return "video.mpv is empty in the configuration";
        }
        List<String> command = new ArrayList<>();
        command.add(mpv);
        command.add("--really-quiet");
        command.add("--profile=sw-fast");
        String output = vo;
        String result = null;
        if (!output.isEmpty() && !supportsOutput(output)) {
            result = "mpv has no '" + output + "' output, used tct (set video.mpv to a build with " + output + ")";
            output = "tct";
        }
        if (!output.isEmpty()) {
            command.add("--vo=" + output);
        }
        if (output.equals("sixel")) {
            // mpv cannot detect the terminal pixel size under Windows Terminal, so it is passed explicitly
            int cols = screen.getTerminalSize().getColumns();
            int rows = Math.max(1, screen.getTerminalSize().getRows() - 1);
            command.add("--vo-sixel-width=" + cols * cellWidth);
            command.add("--vo-sixel-height=" + rows * cellHeight);
        }
        if (!extraArgs.isEmpty()) {
            command.addAll(List.of(extraArgs.split("\\s+")));
        }
        command.add(path.toString());
        try {
            screen.stopScreen();
        } catch (IOException ignored) { }
        System.out.print("\u001B[?1049h\u001B[2J\u001B[H");
        System.out.flush();
        try {
            int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
            if (exit != 0) {
                result = "mpv exited with code " + exit;
            }
        } catch (IOException e) {
            result = "cannot start '" + mpv + "' (mpv installed? set video.mpv in " + Config.defaultFile() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            System.out.print("\u001B[0m\u001B[?25h\u001B[?1049l");
            System.out.flush();
            try {
                screen.startScreen();
            } catch (IOException e) {
                result = "cannot restore the screen: " + e.getMessage();
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (tempDir != null) {
            try (var files = Files.list(tempDir)) {
                files.forEach(f -> f.toFile().delete());
            } catch (IOException ignored) { }
            tempDir.toFile().delete();
            tempDir = null;
        }
    }
}
