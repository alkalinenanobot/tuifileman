package org.ds.tuifileman.cli;

import org.ds.tuifileman.cli.Console.Style;
import org.ds.tuifileman.fs.ArchivePaths;
import org.ds.tuifileman.fs.Archives;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code tuifileman cat FILE...}: the bytes of files to stdout, unchanged, one after the other. A file inside an
 * archive is named like {@code find} prints it ({@code C:\a.zip!/dir/file.txt}), so one entry can be read without
 * extracting the archive. Read-only; errors go to stderr.
 */
final class CatCommand implements Cli.Command {
    private static final String COMMAND = "cat";
    private static final int BUFFER = 1 << 16;

    @Override
    public int run(List<String> arguments) {
        Args args = new Args()
                .option("color", (char) 0, true)
                .option("theme", (char) 0, true)
                .option("help", 'h', false);
        Console.ColorMode color;
        try {
            args.parse(arguments);
            if (args.has("help")) {
                return help(args.get("theme", null));
            }
            if (args.positional().isEmpty()) {
                throw new Args.UsageException("expected at least one file");
            }
            color = Cli.colorMode(args.get("color", "auto"));
        } catch (Args.UsageException e) {
            return Cli.usageError(COMMAND, e.getMessage());
        }

        Console console = Console.open(color, Cli.theme(args.get("theme", null)));
        try {
            return cat(console.err(), args.positional());
        } finally {
            Archives.closeAll();
            console.close();
        }
    }

    private static int cat(Console.Out err, List<String> files) {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), BUFFER);
        byte[] buffer = new byte[BUFFER];
        int failed = 0;
        int copied = 0;
        for (String text : files) {
            if (Cli.CANCEL.get()) {
                break;
            }
            Path path;
            try {
                path = ArchivePaths.resolve(text);
            } catch (InvalidPathException e) {
                failed += fail(err, text, "invalid path");
                continue;
            } catch (IOException e) {
                failed += fail(err, text, e.getMessage());
                continue;
            }
            if (!Files.exists(path)) {
                failed += fail(err, Archives.display(path), "no such file");
                continue;
            }
            if (!Files.isRegularFile(path)) {
                failed += fail(err, Archives.display(path), "not a file");
                continue;
            }
            try (InputStream in = Files.newInputStream(path)) {
                for (int read; (read = in.read(buffer)) > 0; ) {
                    try {
                        out.write(buffer, 0, read);
                    } catch (IOException gone) {
                        return Exit.OK; // the reader went away (cat ... | head): nothing more to say
                    }
                    if (Cli.CANCEL.get()) {
                        return Exit.CANCELLED;
                    }
                }
                copied++;
            } catch (IOException e) {
                failed += fail(err, Archives.display(path), e.getMessage() == null ? "cannot read" : e.getMessage());
            }
        }
        try {
            out.flush();
        } catch (IOException ignored) {
            // the reader went away
        }
        if (failed == 0) {
            return Exit.OK;
        }
        return copied > 0 ? Exit.PARTIAL : Exit.FATAL;
    }

    private static int fail(Console.Out err, String what, String reason) {
        err.println(err.style(Style.ERROR, "error:") + " " + what + err.style(Style.MUTED, " - " + reason));
        return 1;
    }

    private int help(String themeName) {
        Console console = Console.open(Console.ColorMode.AUTO, Cli.theme(themeName));
        Console.Out out = console.out();
        out.println(out.style(Style.TITLE, Cli.NAME + " cat") + " FILE...  "
                + out.style(Style.MUTED, "write files to stdout, unchanged (read-only)"));
        out.println();
        out.println(out.style(Style.BOLD, "Options"));
        out.println("  " + out.style(Style.ACCENT, String.format("%-22s", "    --color WHEN")) + " auto, always or never (colours the error messages)");
        out.println("  " + out.style(Style.ACCENT, String.format("%-22s", "    --theme NAME")) + " dark, light, contrast or mc (default: from the INI file)");
        out.println("  " + out.style(Style.ACCENT, String.format("%-22s", "-h, --help")) + " this help");
        out.println();
        out.println(out.style(Style.BOLD, "Archives") + "  a file inside a zip, jar, docx ... is named ARCHIVE!/path/in/it, "
                + "e.g. C:\\data\\a.zip!/dir/BuildID.txt (quote it in a shell where ! is special)");
        out.println(out.style(Style.BOLD, "Streams ") + "  the bytes on stdout, errors on stderr");
        out.println(out.style(Style.BOLD, "Exit    ") + "  0 ok, 2 usage, 3 some files could not be read, 4 none could, 130 cancelled");
        console.close();
        return Exit.OK;
    }
}
