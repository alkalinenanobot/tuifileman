# TODO

In the order we want to tackle them.

1. **Packaging investigation (GraalVM first).** Check whether a GraalVM
   `native-image` build is possible: Lanterna reflection, JNA (needs JNA
   reflection/JNI config and `CopyFileEx` callbacks), startup time, and whether
   Windows Terminal input still works. If it is not viable, fall back to
   `jlink`/`jpackage` (or a fat jar plus launcher) - that fallback is delayed.
2. **Bookmarks / hot list** - DONE (Ctrl+D, stored in
   `%APPDATA%\tuifileman\bookmarks.txt`); possible extras: named bookmarks,
   reordering, digit shortcuts.
   **Quick file search by typing plain letters** - DONE.
   **File search (Alt+F7, `tuifileman find`)** - DONE (`search/FileSearch` engine, `app/FindView`,
   `cli/FindCommand`; `fs/Glob` is the wildcard compiler shared with the CLI `Wildcards`). Not done: regex
   names/text, archives inside archives, a results panel that feeds F5/F6/F8. The CLI streams matches as found
   (unsorted); `--sort` and json collect them first.
   **`<LNK>` marker and attribute column** - DONE (junctions/symlinks show
   `<LNK>` but still open like directories; attributes are `rhsa`; dates are
   `yyyy-MM-dd HH:mm`).
3. **Multi-rename (F2):** DONE (name/extension masks, counter, date/parent
   tokens, wildcard search/replace with `*` and `?`, case change, live
   preview, conflict checks, in-memory undo with Ctrl+Z). Possible extras:
   per-column colours in the preview, regex search, saved presets.
4. **File compare (Ctrl+F3):** first version DONE (read-only side-by-side text
   diff with intra-line highlight, whitespace/case options, hex compare, next/
   previous difference). F3 stays reserved for preview and F4 for the editor.
   Possible extras: merge/copy blocks between sides (with the editor), go to
   line/offset, directory arguments (-> F9).
5. **Directory sync (Ctrl+F9; was F9 before the menu bar):** first version DONE (recursive scan of the two panel
   directories by size + modified time with a 2 s tolerance, optional content
   compare, per-item direction, merge / mirror modes, runs through the job
   engine, writes via temp file and keeps modified times). Links and junctions
   are skipped. Possible extras: filters (only left/right/newer), file
   exclude masks, compare by content for large trees in the background with
   progress, parallel copy, remembering the last mode.
6. **Copy/move conflict policy:** DONE (per-file question with sizes/dates;
   overwrite / skip / keep both, and "all" variants incl. older-only and
   different-size-only). Possible extras: overwrite read-only files prompt,
   remembering the last choice, the same policy in directory sync.
7. **Tuning:** DONE. Measured cold (file cache dropped) on a 211k file / 16 GB
   tree on NVMe: plan 14.5 s -> 1.2 s, delete 65 s -> 15.5 s, copy 96 s -> 65-69 s
   (big files now overlap the small-file pool). Thread count adaptive by drive type (seek penalty
   query), `-Dtfm.threads=N` overrides. Not measured on HDD or network
   shares (4 / 6 threads are educated guesses). Comparison with robocopy,
   FastCopy and Total Commander: see PERF.md. Possible extras: parallel
   `PanelState` listing of huge folders.
8. **Run script / build:** DONE (`pom.xml` uses Lanterna 3.2.0-alpha1 and
   jna/jna-platform 5.17.0, the fat jar manifest enables native access, and
   `run-lanterna.ps1` passes `--enable-native-access=ALL-UNNAMED`).
9. **F3 file preview** (quick view): text/hex viewer DONE (paged by byte
   offset, find, go to, line numbers, wrap of long lines (`W`, `viewer.wrap`, `view/Wrap`,
   tested on a virtual terminal in `FileViewWrapTest`); possible extras: follow mode, wrap in the
   F4 editor and in the Ctrl+F3 compare view).
   Images DONE: the optional external tool `chafa`
   renders the picture as coloured cells, parsed into Lanterna cells (no
   Java ImageIO/AWT; animated GIFs show the first frame). Sixel output is
   implemented and the default (`image.format`, `S` in the viewer; `symbols`
   is the fallback for terminals without sixel): the
   cells are flushed first, then the sixel data goes straight to the terminal,
   centred, on Windows Terminal's fixed 10x20 pixel cell grid; the screen is
   rewritten completely when it closes. Confirmed in Windows Terminal; an
   automatic fallback (DA1 query) is a later idea; iTerm2 and kitty graphics
   are not supported by Windows Terminal. Fallback DONE: when chafa is
   missing, disabled or fails, an info page (`ImageInfo`, header-only parsing
   of PNG/JPEG/GIF/BMP/WebP/QOI/TIFF/SVG: format, size, colour depth) is shown
   with the reason, and `Tab` still switches to hex.
   PDF DONE: Poppler tools (`pdfinfo`, `pdftoppm`, `pdftotext`; keys `pdf.*`)
   render the current page through the same image path (sixel or cells,
   about 0.3 s per page); `Space`/`PgDn`/`PgUp`/`Home`/`End`/`G` change page.
   `Tab` cycles page - text (positioned at that page, with `page N of M` separator
   lines; search works) - hex - page, and the page follows the text position.
   Without Poppler the info page shows the reason. Ideas: page prefetch, zoom
   and scroll within a page, fit-width mode.
   Video/audio DONE: `ffprobe` info page (format, duration, bitrate, tags,
   streams), an `ffmpeg` frame through the same image path (ten positions,
   `Space`/`PgUp`/`Home`/`End`/`G`), audio files show the info page only, and `P`
   hands the terminal to `mpv` (`video.vo = tct` in the terminal, or `gpu` for a
   window) and takes it back. mpv has no sixel output; `ffplay` has no terminal
   output, so it is not used. All optional. Untested here: mpv keyboard handling
   after Lanterna releases the console (needs a visual check).
10. **F4 editor:** first version DONE (`edit/TextBuffer`, `edit/EditorView`,
    `edit/SystemClipboard`). Keeps the file's encoding (UTF-8/UTF-16 BOM, ANSI
    fallback), line endings and final newline; selection, undo/redo with typing
    coalescing, find/replace (smart case), go to line, auto-indent, block
    indent, clipboard via `clip.exe`/PowerShell, "changed on disk" check, mouse
    click/drag/wheel. Shift+F4 edits a new file. Not done: syntax highlighting,
    overwrite mode, wide-character column handling, files over 32 MB, merging
    blocks from the compare view. Unverified: Shift+F4 and mouse click/drag in
    Windows Terminal.
10b. **Configuration:** `%APPDATA%\tuifileman\config.ini` (INI style, comments,
    created with commented defaults on first run, bad lines only warn). Keys so
    far: `viewer.line_numbers`, `viewer.wrap`, `image.chafa`, `image.symbols`, `image.format`,
    `pdf.pdfinfo`, `pdf.pdftoppm`, `pdf.pdftotext`, `video.ffprobe`, `video.ffmpeg`,
    `video.mpv`, `video.vo`, `video.cell_size`, `video.args`, `editor.tab_size`,
    `editor.use_tabs`, `editor.auto_indent`, `editor.line_numbers`. Next candidates:
    start folders, theme, F-key actions.
11. **Packaging rebuild:** DONE. Maven fat jar (`mvn package`) and native exe
    rebuilt after re-running `native.ps1 -Agent` (adds `fs.Parallel$Api`). The
    terminal is created directly (`WindowsTerminal`, Ctrl+C trapped) instead of
    through `DefaultTerminalFactory`. The build still emits `awt.dll`
    (JNA references `java.awt`), `java.dll` and `jvm.dll`, but the exe runs
    without them, so `native.ps1 -Build` copies only the exe to
    `target\dist\tuifileman.exe` (about 28 MB). Re-run `-Agent` when new JNA
    interfaces or reflection are added.
12. **Menu bar** - DONE (`MenuBar`: F9 or Alt+F/C/L/R/H or a click on the top row;
    arrows, Enter, Esc; drawn and driven from the main loop; menus Files,
    Commands, Left, Right, Help). Directory sync moved from F9 to Ctrl+F9.
    Not done: hover highlight (the terminal mouse mode has no motion events)
    and the mouse path was not exercised in automated tests.
12b. **Command palette** - DONE (`CommandPalette`, Ctrl+P: type-to-filter list of
    every menu action with its shortcut; words match in any order, also as
    subsequences; Up/Down/PgUp/PgDn, Enter runs, Esc closes, click runs).
    Built from the menu definition, so new menu items show up automatically.
    Possible later: recently used first, entries that are not in a menu.
12c. **Theme variants** - DONE (`dark`, `light`, `contrast`, `mc`; Options menu
    or Ctrl+P "theme"; applied immediately incl. dialogs; saved to `[theme] name`
    in the INI via `Config.save`). Diff/search colours are theme roles too.
    Not checked by eye: light and contrast palettes in the compare/sync views.
12d. **Run command** - DONE (`RunCommand`, `RunHistory`; Ctrl+O or Commands menu).
    Shell drop-down cmd / powershell / pwsh / wsl (only when found on PATH) plus
    `[run] shell.NAME = command line with {cmd}` from the INI; command box with
    Up/Down history (`run-history.txt`); starts a new console window through
    `cmd /c start` in the active panel's folder; the window stays open
    (`/k`, `-NoExit`, `exec bash`) unless "Keep the window open" is unchecked
    (`[run] keep_open`; last shell and checkbox are remembered). Verified for
    cmd, powershell and wsl with quotes, `&` and `|` in the command.
    Linux/WSL: runs in the same terminal like mc (stop screen, `inheritIO`, optional
    "Wait for Enter", repaint). Shells: bash (default, `-lc`), `$SHELL`, sh, and cmd /
    powershell / pwsh through WSL interop when the `.exe` is on PATH. Interop re-quotes
    arguments, so the cmd line travels in env `TFM_LINE` (via `WSLENV`) and powershell
    gets `-EncodedCommand`; the folder is mapped with `wslpath -w` (`pushd`). Verified
    for bash, sh, cmd and powershell in a pty (`RunCommandLaunchTest` covers the command lines).
    Caveat: after a distro restart the `WSLInterop` binfmt entry can be missing (`.exe`
    then fails with "Exec format error"); fix: `sudo sh -c 'echo :WSLInterop:M::MZ::/init:PF > /proc/sys/fs/binfmt_misc/register'`.
    Verified by the user in WSL (Run dialog works). Not done: a drop-down list of the history (Up/Down only), interactive (empty command) shells on Linux untested.
13. **Automated tests** DONE: 159 JUnit 5 tests (`mvn package` runs them, about 20 s; the two child-JVM
    contract classes take most of it). In-process unit tests with `@TempDir` for `CopyJob` (every conflict answer,
    dry run, move, errors), `DeleteJob`, `SyncJob`, `DirCompare`, `LineDiff` (against a brute-force LCS),
    `RenamePlan`, `Renamer`, `PanelState`, `Wildcards`, `Args`, `Json`/`JsonReader`; `Fixtures` builds the trees.
    Not covered: the Lanterna UI, `TextBuffer`, the viewers, `BinaryCompare`/`TextCompare`, `NativeCopy`.
14. **Command line mode** (the engine is fast enough to be worth scripting; also
    meant as a standalone dir-compare tool once published). Phases 1-4 DONE
    (`diff`, plumbing, styling layer, exe-name alias, `copy`/`move`/`delete`, `sync`,
    `rename`, `--stats`, `install-aliases`, `--version`, contract tests). Decisions:
    - Invocation: no args or folders = TUI as today; `tuifileman <command> ...` =
      headless, dispatched before any terminal setup. Commands: `copy`, `move`,
      `delete`, `diff` (alias `compare`), `sync`; later `rename` (multi-rename).
    - Exe-name dispatch: the last `-`/`_` token of the exe name (or the whole
      name) selects the command (`tf-sync.exe`, `tuifileman-diff.exe`, `sync.exe`),
      read via `ProcessHandle`; `install-aliases` makes hardlinks. The explicit
      subcommand always works. Wildcards are expanded by the tool (cmd/PowerShell do not).
    - Streams: stdout = result data only (`--format text|json|jsonl|csv`, JSON
      carries `"schema":1`); stderr = progress, warnings, errors. Prompts go to
      the console, never stdout.
    - Progress `--progress auto|bar|lines|json|none`: `auto` = live bar when stderr
      is a terminal, otherwise silent (`none`); `lines` = status line every few
      seconds; `json` = one NDJSON event per line on stderr; `-q` = errors only.
    - Safety (delete, move, sync with deletes): prompt on a terminal, require
      `--yes` when not interactive (exit 2 otherwise).
    - Exit codes: 0 ok/identical, 1 differences (diff), 2 usage, 3 finished with
      file errors, 4 fatal I/O, 130 Ctrl+C (cancels the job, prints the summary).
    - Options: `--on-conflict ask|overwrite|skip|keep-both|newer|older|larger|smaller|different`,
      `--threads N`, `--dry-run`; diff/sync: `--content`, `--mode merge|mirror-right|mirror-left`,
      `--only`, `--exclude GLOB`, `--time-tolerance`.
    - Architecture: `cli` package with a hand-written arg parser (native-image
      friendly), a runner that polls a `Job` like the TUI does and feeds a
      progress sink (bar/lines/json/none). Jobs/DirCompare need to be reachable
      from `cli` (package visibility or a small facade).
    - Phases: (1) parser, dispatch, sinks, exit codes and `diff` (read-only, safest);
      (2) copy/move/delete with conflict flags, dry-run, globs, prompts;
      (3) sync with plan + apply (`diff --format jsonl | sync --plan -`);
      (4) CLI contract tests, `--help`/`--version`, README, replace the benchmark
      helpers with `--stats`.
    - Dream list: `diff --open` to continue in the TUI sync view. Dropped after measuring: size -> partial
      hash / sampled early-out for `--content` (size mismatches are already never read; on two real
      checkouts 55k same-size pairs: 98% are one 512 KB chunk or less, 54.6k identical (must be read in
      full anyway), only 3 of 980 differing pairs differ after the first chunk, so sampling saves 0 bytes;
      warm `--content` costs +1.7 s over size+time, cold it is disk bound).
    - Publishing prep (prepared): MIT `LICENSE`, `THIRD-PARTY-NOTICES.md` + `licenses/` (LGPL-3, GPL-3,
      Apache-2.0), README license/build sections, `native\package.ps1` (release zip + SHA-256),
      `.github/workflows/build.yml` (untested until the first push), no hard-coded tool paths in
      `native.ps1`. GraalVM: releases use Community Edition (measured against Oracle GraalVM 25.3: same speed on
      every CLI workload, exe 4.6 MB smaller; CE lacks only PGO/GraalNN, not needed here). The open
      publishing items are in `publishing_prereq.md`.
    - Ignore rules (done, instead of a own `.tfignore`): `diff`/`sync` `-g/--gitignore` and
      `--ignore-file`, in `org.ds.tuifileman.ignore` behind `IgnoreMatcher`, backed by the JGit
      ignore classes (BSD-3, jar + slf4j-api; `slf4j.internal.verbosity=ERROR` keeps stderr clean;
      no reflection config needed in the native exe; a bad pattern throws an `Error` in native,
      caught per line). Tested against real `git check-ignore` (0 mismatches). The `nl.basjes`
      gitignore-reader was tried and dropped (45 mismatches). Ignored entries are `ignored` rows
      (`--show-ignored`, `ignoredBy` in data formats), never touched by sync; TUI `SyncView` has
      `G`/`I`. `--exclude` prunes during the scan (an excluded folder is neither listed nor
      entered, and counts no `ignored` entry; a glob matching only a folder's full path now drops
      the folder's content too). Not done: `.gitignore` of the parent folders of the roots,
      optional `--skip-vcs` shorthand for `-x .git`, global/`info/exclude` files,
      `[[:UPPER:]]` classes in case-insensitive mode (matching lower-cases pattern and path).
    - Styling (DONE, was design-first): all human output (progress bar,
      summaries, diff text, prompts, errors) goes through one small `Ansi`/`Console`
      layer instead of raw `println`, so it can be stylish like the TUI: coloured
      progress bar, accent/bold/dim text, status colours (success/warning/error,
      added/removed/changed for diff). Colours come from the same `ui.Theme` roles
      as the TUI (so `[theme] name` applies; truecolor RGB, 256/16-colour fallback).
      Colour and live redraw are used only when the stream is a terminal with VT
      enabled (Windows needs ENABLE_VIRTUAL_TERMINAL_PROCESSING); `NO_COLOR`,
      `--color auto|always|never` and redirection give plain text. Stdout data
      formats (json/jsonl/csv) are never coloured; `--format text` is coloured only
      on a terminal. Sinks take semantic roles (e.g. `accent("sync")`), never escape codes.
    - Phase 1 notes (in `org.ds.tuifileman.cli`): `Cli` dispatch, `Args`, `Tty` (JNA
      terminal detection, VT + UTF-8 switch-on), `Console` (theme-based styles),
      `Progress` (bar/lines/json/none), `DiffCommand`, `Json`. Not done yet in `diff`:
      `--time-tolerance` is done (see below). Not done yet in `diff`: symlinks/unreadable entries are skipped silently. Native exe: a
      Ctrl+C ends the process at once (Windows status, bar line not erased) because
      the shutdown hook does not get to run; on the JVM it exits 130 after cleanup.
      `--version` prints a constant (no Implementation-Version in the manifest yet).
    - Phase 2 notes (`OpCommand`, `Wildcards`, `Prompt`; `CopyJob`/`DeleteJob` got a
      dry-run mode and `Planned` records): copy always goes INTO the destination folder
      (no rename-on-copy); conflicts are resolved in `plan()` so an abort copies nothing.
      No `older` policy (the engine has none; `larger`/`smaller` compare the source).
      Only `delete` requires a prompt/`--yes`; `move` is not prompted (conflicts need
      an explicit `--on-conflict` without a terminal). Cosmetic: the summary uses the
      default locale ("0,0 s"); the dry-run JSON `skipped` count includes conflicts.
      Ctrl+C during copy on the native exe is untested (same limitation as `diff`).
    - Phase 3 notes (`SyncCommand`, `JsonReader`; `SyncJob` public, `DirCompare.itemFor` rebuilds an
      item from the current files for plans): modes merge / mirror-right / mirror-left as in the TUI;
      a same-time-different-content item is a silent skip listed as `conflict` in the plan; every real
      run is confirmed (stricter than the phase plan, which only asked for deletes); `--plan` reads
      JSON lines only (not the pretty `-f json`), re-validates against the disk, rejects unsafe paths;
      `rename` (multi-rename) is the remaining planned command. Not done:
      conflict policies for merge (newer-wins on equal time etc.), Ctrl+C on the native exe untested.
      `--time-tolerance T` (diff, sync, copy, move, DONE): a whole number with an optional unit s/m/h
      (`2`, `90s`, `5m`, `1h`; no unit = seconds; `0` = exact; default 2 s; no milliseconds on purpose, for
      humans and agents alike). Precedence: option, then INI `[sync] time_tolerance`, then 2 s; the TUI
      (sync view, copy/move overwrite policies) reads the INI value (`TimeTolerance.configured()`).
    - Phase 4 notes (DONE): `rename` (`RenameCommand`; `RenamePlan`/`Renamer` public): same masks, counter
      (`--start/--step/--digits`, separate options because PowerShell splits `1,1,3`), search/replace and case as F2;
      items in ONE folder, a wildcard sorts by name; an invalid or clashing name changes nothing (exit 2); real runs
      are confirmed like `sync`; `--undo-log FILE` (jsonl, refuses an existing file) and `rename --undo FILE`.
      The `Renamer` bug fix: chains (1->2, 2->3, 3->4) failed, a move now takes a temp name when its target is
      another move's source or its own source is wanted by another move (also fixes F2). `--stats` (`Stats`):
      stderr block and a `"stats"` object in the json/jsonl result (startup/wall/phase ms, items and bytes per
      second, effective threads, drive types); not for `rename`/`install-aliases`; CSV is unchanged.
      `install-aliases` (`AliasCommand`): hard links `tfm-diff.exe` ... next to the exe (`--copy`, `--force`,
      `--remove`, `--dir`, `--exe`); a link keeps the build it was made from, so re-run with `--force` after a
      rebuild. `Cli.VERSION` is a constant checked against the pom by a test. `TFM_TRACE=1` prints a startup
      timeline (found the cold-start JNA stall: an 8 s first load of the per-run unpacked `jnidispatch.dll`, probably
      the virus scanner; fixed by `fs.JnaBoot`, which keeps one copy in `%LOCALAPPDATA%\tuifileman\native` and sets
      `jna.boot.library.path`; the very first run ever can still be slow once). Tests: JUnit 5 through Maven (`mvn package` runs them),
      `CliContractTest` and `RenameContractTest` start the program as a child JVM and check exit codes, stdout vs
      stderr, JSON fields, safety refusals, plans, undo and aliases. The engines have in-process unit tests (item 13).
    The existing start folder arguments stay as they are.
14b. **Linux port, scope A (CLI for agents in WSL):** DONE for the jar. The Windows fat jar runs unchanged on
    Fedora WSL (JDK 25). Changes: relative paths are `/`-joined internally (the old `\` broke `sync` on Linux);
    `app.ChannelCopy` = portable chunked `FileChannel.transferTo` copy (preserves mtime, mode, uid/gid and
    `user.*` xattrs on POSIX; replaces read-only targets; cancel/progress) used by `CopyJob` (when `NativeCopy`
    is unavailable) and `SyncJob` (now also on Windows; it used to lose the exec bit on Linux); `fs.Parallel`
    classifies Linux storage by filesystem type (tmpfs = RAM, nfs/cifs/sshfs = network, 9p = unknown) and
    ignores `rotational` on virtual disks, because every WSL disk reports `rotational=1`; `cli.Tty` detects a
    terminal through `/proc/self/fd` and reads the width with `stty` (colour, progress bar and plain piped
    output verified through a pty). Measured on ext4 in WSL2 (VHDX, ~31k files): the jar matches or beats
    `diff`/`rsync`/`cp` (cold 4-6x faster); only `rm -rf` and `find` are faster natively. JNA
    `copy_file_range`/reflink would gain nothing on ext4 (the kernel already moves the data), so NIO is enough;
    a pure-NIO copy is ~25% faster than `Files.copy` on small files. `/mnt/c` and `/mnt/d` over 9p are very slow
    (200 s for a 405k item diff); `wslpassfs` (FUSE, `D:\work\socat_test`) is ~100x faster but unverified here.
    Not done: CI job on Linux, macOS (no terminal detection), XDG config path (falls back to `~/.tuifileman`).
    (The TUI itself, attributes, clipboard, Run command and the Enter launcher work on Linux, see below.)
    TUI Linux fixes: Run command restores the cooked tty (echo, line editing) while the command runs and
    raw mode afterwards (`UnixTty` in `App`); Enter on a file (`Launcher.unixCommand`): `.exe` with the
    execute bit runs itself through WSL interop, others go to xdg-open, else wslview, else explorer.exe.
    Attribute column on Unix shows the mode (`rw-r--r--`, header MODE; `DirectoryListing` picks POSIX when the
    file system supports it, the column is as wide as the longest value); Windows keeps `rhsa`.
    Editor clipboard on Linux (`SystemClipboard.choose`): wl-copy/wl-paste (needs WAYLAND_DISPLAY), xclip, xsel
    (need DISPLAY), else clip.exe + powershell.exe through WSL interop; verified copy and paste with Windows' clipboard.
    Bug found on the way: the tty kept XON/XOFF on, so Ctrl+S (save) and Ctrl+Q (quit) in the editor were swallowed;
    `stty -ixon -isig` now runs at start and after every Run command (`-isig` also stops Ctrl+Z from
    suspending the TUI into the shell's background).
    Cancelling a copy no longer logs `InterruptedException`/`ClosedByInterruptException` errors: `CopyJob.copyOne`
    ignores IOExceptions that come from the cancel interrupt (visible only on slow targets such as 9p).
    **Linux native image: DONE for the CLI** (`native/native.sh`, GraalVM CE 25.4.4.1.1 in Fedora WSL, same
    `native/config` as Windows, no extra reachability config needed; 34 s build, 23.75 MiB, links only
    libc/libm/libz; startup ~3-10 ms against ~120 ms for the jar). Verified as user and root: copy/sync
    keep mode, uid/gid, mtime and `user.*` xattrs, read-only target replaced, symlinks, plain piped output,
    colour on a pty, storage detection. Built against Fedora 43's glibc, so it needs a glibc at least as new
    (build on an older distro, or try `--static-nolibc`/musl, for wider reach). The TUI in the native Linux
    binary works too (scope B, user-tested in WSL). `.gitattributes` forces LF for `*.sh`.
    TUI paint on Linux/WSL: `System.out` autoflushes, so a full repaint was ~3,400 tiny writes (very slow through
    the WSL pty relay); `App.createTerminal` now buffers stdout (64 KB) and flushes on every input read/poll, so
    a repaint is 1-2 writes (measured with a pty harness: startup 4,253 -> 8 chunks, same bytes). Windows is
    unchanged (not measured whether it would benefit).
14c. **Archives as read-only folders (zip, jar, war, ear, apk, docx, xlsx, pptx, odt, ods, odp, epub): DONE.**
    JDK zipfs (`jdk.zipfs`, no native code; works in both native images) through `fs/Archives` (ref-counted
    open per archive file, nested archives are extracted to a session temp dir first, temp root removed at exit).
    `Enter` on a file (archives included) opens it with the system application as before (user's decision: Enter
    stays "run/open"); `Ctrl+Enter`, `Ctrl+PageDown` (Total Commander's key) and `Shift+Enter` open ANY file as an
    archive by content, not by name (same as menu "Open as archive"; e.g. a renamed jar). `..` at the archive root and `Backspace` leave and select the
    archive file. Inside: F3 view and Ctrl+F3 compare (entry extracted to temp, since `FileChannel` does not
    work on zipfs paths), F5 copy OUT (`ChannelCopy.streamCopy`, no JNA); everything that writes (F4, Shift+F4,
    F6, F7, F8, rename, sync, F5 into an archive) is refused with a message. Panel title shows
    `ARCHIVE name  archive!/inner`. Tests: `ArchiveBrowseTest`; pty-tested on Linux (jar and native).
    Key note: Lanterna sees Enter without modifiers. `App.createTerminal` adds a decoding profile (LF = Ctrl+Enter,
    kitty `CSI 13;5u`/`13;2u`, xterm `CSI 27;5;13~`/`27;2;13~`); on Unix the tty runs `-icrnl` so CR and LF stay
    apart. Windows console: Ctrl+Enter arrives as LF, Shift+Enter is indistinguishable from Enter (untested by
    the user yet). Many terminals send plain CR for Ctrl+Enter: use Ctrl+PageDown or the menu item there.
    Not done: tar/tar.gz/7z (Apache Commons Compress, Apache-2.0, needs a THIRD-PARTY-NOTICES entry), writing
    into archives, free space inside an archive, Unix exec bit on copy-out when the zip has no mode.
14d. **Copy/move under a new name; scroll after going up: DONE.** One item in the F5/F6 box: an existing folder
    receives it, any other text is the new name inside the parent of that path (relative text is relative to the
    source folder), a trailing `\` or `/` forces "new folder" (`CopyJob.setTargetName`; existing file as the name
    goes through the conflict dialog; case-only renames work as a move). Several items always mean a folder.
    `PanelState.selectPath` centres the folder just left (`goUp`, `..`) and `Panel` calls `ensureVisible` on
    every render. Tests in `CopyJobTest`/`PanelStateTest`; pty-tested on Linux.
15. Known risks: Windows junctions inside copy trees are untested for copy
    (delete and sync skip them); the Cancel button has focus in the progress
    window (an accidental Enter cancels); Lanterna is an alpha release.