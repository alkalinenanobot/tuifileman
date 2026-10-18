# tuifileman

A two-panel terminal file manager in Java 17+, built on
[Lanterna](https://github.com/mabe02/lanterna) 3.2.0-alpha1 (no Swing window). Runs in
Windows Terminal / PowerShell (native Windows console backend) and in Linux terminals, with RGB colors,
mouse support and real filesystem browsing. Release builds (Windows and Linux x64, single native
executable, no JDK needed): <https://github.com/alkalinenanobot/tuifileman/releases>.

It is also a tool for AI agents: a fast command line (`copy`, `move`, `delete`, `diff`, `sync`, `find`, `rename`)
for large directory trees, with dry runs, reviewable plans, JSON output and fixed exit codes. On a tree of 200
thousand files it copied as fast as `robocopy /MT:32` and faster than FastCopy and Total Commander (see
[PERF.md](PERF.md)). To have an AI agent install it, point the agent at [install.md](install.md).

## Current state

Working:
- Two panels with real directory listings (directories first, then files,
  case-insensitive by name; sortable by name, extension, size, modified time or attributes),
  navigation, mouse, refresh, free-space display, command registry, theme.
- Panel title shows the drive and its volume label; the path line shortens
  long paths in the middle with `...`.
- Drive selection per panel (Alt+F1 left, Alt+F2 right, or click the panel
  title); the last directory of each drive is remembered.
- mkdir (F7).
- Archives as read-only folders: `Ctrl+Enter` (or `Ctrl+PageDown`, or the Files
  menu "Open as archive") browses the file under the cursor like a directory,
  whatever its name (zip, jar, war, docx, xlsx, apk, epub, ...; read through the JDK zip
  support, no external tool). `Enter` still opens it with the system application. Inside:
  view (F3), compare (Ctrl+F3) and copy out (F5) work, anything that writes is refused;
  `..` or `Backspace` leaves. tar/7z are not supported yet.
- Copy/move with one item accept a new name: `F6` + `b.bat` renames, `F5` + `b.bat`
  copies in place (a trailing `\` or `/` means "a new folder").
- Multi-select (Space marks and moves down, Insert marks in place); double-click
  opens.
- Copy (F5) / move (F6) / delete (F8) run as background jobs with a progress
  window (Cancel button or Esc).
  - Copy engine: small files (< 32 MB) in parallel; big files one at a time
    (alongside the small ones) through native Windows `CopyFileEx` (progress,
    cancel, long-path support). The scan, folder creation, copy and delete are
    parallel: 32 threads on SSD/RAM disk, 4 on HDD, 6 on network shares (override
    with `-Dtfm.threads=N`). Directory sync / compare scans are parallel too, and so are the copies and deletes of a sync (same engine; each file still goes through a temp file and a rename, and only planned items are deleted). Same-volume move is a rename; cross-volume move copies first and
    deletes a source only when everything in it was copied. Cold-cache benchmarks against robocopy, FastCopy and Total Commander: see [PERF.md](PERF.md).
  - Conflicts: asked per existing file, showing size and date of both
    (marked newer/older/same time). Overwrite, skip or keep both (new copy
    becomes `name (1).ext`) for this file, or for the rest of the job:
    overwrite all, skip all, keep both for all, overwrite all older (the new file is newer), overwrite
    overwrite all with different size, smaller or larger (of the file being
    replaced). Esc cancels the job.
  - Errors do not stop a job. They are collected into a scrollable list (full
    path + reason), also saved to `%TEMP%\tuifileman-errors.log`.
  - F12 opens that list with demo data (debug aid for UI checks).
- Dialogs are Lanterna GUI2 dialogs themed via `ui/DialogTheme`.
- Themes: `dark` (default), `light`, `contrast` (high contrast) and `mc`
  (Midnight Commander look). Switch in the Options menu (Alt+O) or with
  Ctrl+P and "theme"; the change applies at once and is saved to
  `[theme] name` in `config.ini`. Colours are semantic roles in `ui/Theme`;
  a new palette is one more factory method plus an entry in `Theme.all()`.

Dialogs: the main view is drawn by our own render loop; GUI2 dialogs are shown
only modally on top of it (the main view is their backdrop). Do not use a GUI2
window-less background component: Lanterna 3.2.0-alpha1 throws an NPE on mouse
clicks when no window is open.

Planned: see [TODO.md](TODO.md).

Configuration: `%APPDATA%\tuifileman\config.ini` is created with commented
defaults on the first start. It is plain INI (`[section]`, `key = value`, `#`
comments); a bad line only shows a warning. Every setting with its default and
values is listed in [CONFIG.md](CONFIG.md). Notes on some: `viewer.line_numbers`
(default `true`), `viewer.wrap` (default `true`; long lines wrap in the F3 text view, `false` pans
sideways instead), `image.chafa` (the [chafa](https://hpjansson.com/chafa/) command
used for image preview in F3; empty disables it) and `image.symbols` (the
`chafa --symbols` set, default `half+quad+space`) and `image.format` (`sixel`, the
default, or `symbols` for terminals without sixel; `S` toggles it while viewing an
image, `Tab` switches between the
image and the hex view). If chafa is missing, disabled or fails (also for BMP),
F3 shows an info page instead (format, size, colour depth, file size, date, the
reason); `Tab` still switches between it and the hex view. PDFs are shown the same
way through the [Poppler](https://poppler.freedesktop.org/) tools (`pdf.pdfinfo`,
`pdf.pdftoppm`, `pdf.pdftotext`): `Space`/`PgDn`/`PgUp`/`Home`/`End` change the page,
`G` goes to a page, `Tab` cycles page, text (at that page, searchable) and hex.
Video and audio files use [FFmpeg](https://ffmpeg.org/) and [mpv](https://mpv.io/)
(`video.ffprobe`, `video.ffmpeg`, `video.mpv`, `video.vo`, `video.args`): F3 shows
a frame (`Space`/`PgUp` step through ten frames over the file) or, for audio, an
info page (duration, codecs, tags, streams); `P` plays the file with mpv inside the
terminal (`video.vo = sixel` by default, passing the terminal size to mpv because it cannot
detect it under Windows Terminal; needs an mpv build with sixel, otherwise it falls back to
`tct` coloured half blocks; `gpu` opens a window instead) and
returns to the viewer when mpv quits.

## Run

```powershell
.\run-lanterna.ps1          # compile + run (needs interactive terminal)
.\run-lanterna.ps1 -Check   # compile only
# the jar / exe take optional start folders: tuifileman [left [right]]
```

The script downloads pinned dependencies (lanterna, jna, jna-platform, jgit, slf4j-api) from
Maven Central on first use. JDK 17+ with `java`/`javac` on PATH required.
Licences of the bundled libraries: see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Command line mode

`tuifileman <command> ...` runs without the interface (jar or exe). Available: `diff`
(read-only folder comparison, the engine of Ctrl+F3), `copy`, `move`, `delete`, `sync`, `rename`
(multi-rename), `find` (file search, the engine of Alt+F7) and `install-aliases`; `--version`.

```powershell
tuifileman diff D:\a D:\b                       # coloured list on a terminal
tuifileman diff D:\a D:\b --content -x node_modules -f json > diff.json
tuifileman diff D:\a D:\b -S                    # counts only
tuifileman diff D:\a E:\b --time-tolerance 2m   # times up to 2 minutes apart count as equal (default 2s; 0 = exact)
tuifileman diff D:\a D:\b --gitignore           # leave out what the .gitignore files in both folders ignore
tuifileman sync -n D:\a E:\b --ignore-file D:\my.ignore   # extra patterns, same syntax as .gitignore
tuifileman copy D:\a\*.txt D:\a\sub E:\backup   # copies INTO the destination folder
tuifileman copy -n -f json D:\a E:\backup       # dry run: the plan as JSON, nothing is touched
tuifileman move -c newer D:\a\* E:\archive      # overwrite only when the source is newer
tuifileman delete -y E:\old\*.tmp               # no prompt
tuifileman sync -n D:\a E:\backup               # merge dry run: newer and missing files go to the other side
tuifileman sync -m mirror-right -y D:\a E:\b    # make E:\b identical to D:\a (deletes extras)
tuifileman sync -n -f jsonl D:\a E:\b > plan.jsonl   # reviewable plan ...
tuifileman sync -p plan.jsonl -y D:\a E:\b           # ... applied later
tuifileman diff -f jsonl D:\a E:\b | tuifileman sync -p - -m mirror-right -y D:\a E:\b
tuifileman rename -n -N "holiday_[C]" --digits 3 --case lower 'D:\pics\*.JPG'   # plan only
tuifileman rename -y -N "holiday_[C]" --digits 3 'D:\pics\*.JPG' -u undo.jsonl  # ... for real, with an undo log
tuifileman rename --undo undo.jsonl -y                                          # revert it
tuifileman find D:\src -N "*.c,*.h" -T "TODO"   # names and content: path:line: text of the first match
tuifileman find D:\src -N "*.c" -N "*.h" -d 3 -f json > hits.json   # the option may repeat; three levels deep
tuifileman find D:\backup -A -N "*.log" -f csv  # also inside zip/jar/... archives
tuifileman diff D:\a E:\b -c -t 32 --stats -f none   # timing, items/s, threads and disk type
tuifileman install-aliases                      # tfm-diff.exe, tfm-copy.exe ... (hard links) next to the exe
tuifileman help copy                            # all options
```

- `copy`/`move`/`delete` take several sources; `*` and `?` in the last path segment are expanded by
  the tool (cmd and PowerShell do not do it for exes). The destination is a folder and is created
  when missing. `-n/--dry-run` prints what would happen (including unresolved conflicts) and changes nothing.
- `-c/--on-conflict ask|overwrite|skip|keep-both|newer|larger|smaller|different` (copy/move).
  `ask` (default) prompts on a terminal (`o`/`s`/`k` for this file, `a`/`n`/`b`/`u` for all, `c` cancel);
  without a terminal an unresolved conflict aborts before anything is copied (exit 2).
  `move` is not prompted beyond that: use an explicit policy in scripts.
- `delete` asks for confirmation on a terminal; without one it needs `-y/--yes` (exit 2 otherwise).
  Drive roots are refused. `-t/--threads N` tunes parallel delete/copy.
- `sync LEFT RIGHT` (the engine of Ctrl+F9; same size + time rules, `-c/--content` compares bytes, `-s`
  shallow, `-x GLOB` excludes (matched against the whole relative path or any single name; an excluded
  folder is not even scanned) and excluded items are never copied or deleted). `-m/--mode`:
  `merge` (default) copies newer and missing files to the other side and never deletes;
  `mirror-right` / `mirror-left` make one side identical to the other, deleting extras. Items with the same
  time but different content are conflicts: merge leaves them alone and lists them as `skip` / `conflict`
  in the plan (no error, exit code unchanged). Files are written through a temp name and keep their times.
  The folders must be different and neither inside the other; links and junctions are skipped.
  A folder below the roots that cannot be read (e.g. access denied) is left out on both sides, never treated
  as empty: `diff` and `sync` list it as an error on stderr, count it as `unreadable` in the summaries and
  exit 3. An unreadable root is a fatal error (exit 4).
- `find FOLDER` (the engine of Alt+F7, same rules: `-N/--name` wildcards `*` `?`, several patterns separated
  by `,`/`;` or by repeating the option, a pattern without a wildcard matches names that contain it, names
  never case sensitive; `-T/--text` content, `--match-case`; `-A/--archives`; `-d/--depth N`, 1 = the folder
  only; `--limit N`, `--sort`). The folder is required. Read-only. stdout: absolute paths, printed as they are
  found (so a long search shows results at once and `find ... | head` stops early) in no fixed order, unless
  `--sort` waits for the end and lists them in path order (json is one document and always in path order); with `--text`
  `path:line: text`; `-f text|json|jsonl|csv|none` and `-S/--summary` work as in `diff` (json has `schema`,
  `command`, `root`, `names`, `text`, `summary` with `matches`/`examined`/`unreadable`/`limited`, and `items`
  with `entry`, `path`, `archive`, `size`, `modified`, plus `line`/`snippet` for a text search). Exit 0 matches,
  1 no matches (like grep), 2 usage, 3 incomplete: folders that cannot be read are listed on stderr and nothing
  can be said about them (a search of a whole drive usually ends with 3), 4 error. Unlike the file manager the
  command has no 100,000-match cap. Quote patterns (`-N '*.c'`); PowerShell splits an unquoted `a,b`
  into two arguments, so quote it or repeat `-N`.
- `diff`, `sync`, `copy` and `move` take `--time-tolerance T` (for `copy`/`move` it applies to
  `--on-conflict newer`): two files whose modified times differ by at most T have the
  same time. T is a whole number with an optional unit `s`, `m` or `h` (`2`, `90s`, `5m`, `1h`; no unit means
  seconds, `0` means exactly equal, default `2s` for FAT's resolution). Use it for network shares, other
  file systems or a daylight-saving shift (`1h`). Not given in milliseconds on purpose. The default can be
  set once in the settings file (`[sync]` section, `time_tolerance = 2s`); the TUI (sync view and the
  copy/move conflict dialog's "newer" choices) uses that setting too, and the option wins over it.
- `diff` and `sync` take `-g/--gitignore` and `--ignore-file FILE` (repeatable). `--gitignore` reads the
  `.gitignore` in every folder of both trees (not the parents of the two roots); `--ignore-file` patterns
  are relative to the compared folders, have lower priority than `.gitignore` and later files override
  earlier ones. Matching is git's (JGit's implementation, checked against `git check-ignore` in the
  tests), case-insensitive on Windows. Like git, an ignored folder is never entered, so nothing inside
  can be re-included. An entry ignored on either side is ignored on both (when the two `.gitignore` files
  disagree at the same level, ignored wins). Ignored entries get the status `ignored` and are never
  copied, overwritten or deleted (`sync --mirror` keeps them in the target); they are not differences, so
  they do not change the exit code. A folder is one row and is not entered. Text output hides the rows
  unless `--show-ignored` (or `-o ignored`) is given and shows which side's rules matched
  (`(by left rules)`); `json`/`jsonl`/`csv` always list them (`"ignoredBy":"left|right|both"`) and the
  summaries count them. A plan fed to `sync --plan` may list ignored entries only as `skip`; any other
  action is an error. `--gitignore` does not skip `.git` (it is compared like any folder); use
  `-g -x .git` to leave it out. The `.gitignore` files are compared like any other file. A malformed
  pattern line is skipped. `--stats` reports an `ignored` count. The ignore options cannot be combined
  with `sync --plan`. In the TUI sync view `G` toggles the `.gitignore` rules and
  `I` shows or hides the ignored rows.
- Every real `sync` asks first on a terminal (it lists the deletions) and needs `-y/--yes` without one.
  `-n` prints the plan; as `-f jsonl` each line is `{"action":"to-right|to-left|delete-left|delete-right|skip",
  "path":..., "entry":..., "status":..., "size":...}`. `-p/--plan FILE|-` applies such lines: the `action`
  wins (edit a `skip` into `to-left` to resolve a conflict), a line without one (e.g. from `diff -f jsonl`)
  follows `--mode` and its `status`. A plan is checked against the current state first: unsafe paths
  (`..`, drives) and entries that no longer exist stop the run before anything is changed (exit 2).
  A plan read from stdin cannot be confirmed, so it needs `--yes`.
- `rename ITEM...` is the F2 multi-rename: `-N/--name MASK` and `-e/--ext MASK` (`[N]` name, `[E]` extension,
  `[N2-5]` characters, `[C]` counter with `--start/--step/--digits`, `[P]` parent folder, `[Y][M][D][h][m][s]`
  modified time), `-s/--search` + `-r/--replace` (`*` and `?` wildcards, `--match-case`), `--case
  unchanged|lower|upper|words|first` with `--case-scope both|name|ext`. All items must be in one folder; the
  counter follows the order given (a wildcard is sorted by name). Invalid or clashing names change nothing
  (exit 2, also with `-n`, which lists them). A real run asks on a terminal and needs `-y/--yes` without one.
  `-u/--undo-log FILE` records the renames (JSON lines; an existing file is refused) and `rename --undo FILE`
  reverts them (supports `-n` and `-y`).
- `--stats` (diff, copy, move, delete, sync) prints a block on stderr - wall time with the scan/work split,
  items and bytes per second, the threads used and the drive type - and adds a `"stats"` object to the
  json/jsonl result. It is printed even with `-q`.
- `install-aliases` creates `tfm-diff.exe`, `tfm-copy.exe`, `tfm-move.exe`, `tfm-delete.exe`, `tfm-sync.exe`
  and `tfm-rename.exe` next to the exe as hard links (no extra disk space; `--copy` where hard links are not
  possible). A different file of that name is kept unless `--force`; `--remove` deletes only links to, or
  copies of, the program. A link keeps the build it was made from: run it again with `--force` after a rebuild.

- stdout carries only the result (`--format text|json|jsonl|csv|none`, JSON has `"schema":1`);
  progress, the summary and errors go to stderr, so redirecting stdout keeps the progress bar.
- Progress `--progress auto|bar|lines|json|none`: `auto` is a live bar when stderr is a terminal
  and silent otherwise; `-q` silences everything but errors.
- Colours come from the active theme (`--theme`, `[theme]` in the INI file); `NO_COLOR` and
  `--color never` turn them off. Data formats are never coloured.
- Exit codes: 0 ok/identical, 1 differences (`diff`; from the whole comparison, `--only` filters the listing only),
  2 usage error, 3 finished with file errors (for `diff`
  and `sync` also: a folder could not be read, so the result is incomplete; this wins over 1),
  4 fatal error (e.g. missing source), 130 cancelled (Ctrl+C on the jar; also answering "no" to the delete prompt).
- A copy of the exe named `tfm-diff.exe` (or `diff.exe`) runs `diff` directly. A folder called
  like a command is opened with `.\diff`.

## Build (Maven / NetBeans)

Needs a JDK 17+ and Maven. The console backend is JNA with the Win32 API on Windows and Lanterna's stty-based
terminal on Linux.

```powershell
mvn package                                  # fat jar: target\tuifileman-0.3.0.jar (runs the JUnit tests first)
mvn test                                     # tests only
java -jar target\tuifileman-0.3.0.jar
.\run-lanterna.ps1 -Check                    # dev run without Maven: compile only (-Check) or compile and start
.\native\native.ps1 -Build                   # GraalVM native exe from the fat jar: target\dist\tuifileman.exe (single file)
.\native\package.ps1                         # release zip + SHA-256 in target\release
```

```bash
mvn package
native/native.sh                             # GraalVM native binary from the fat jar: target/dist/linux/tuifileman
native/package.sh                            # release tar.gz + SHA-256 in target/release
```

Both native builds use the fat jar of `mvn package` as their class path (run it first; the scripts refuse a missing
or outdated jar) and share the reflection/resource config in `native/config`; `native.ps1 -Agent` and
`native/native.sh --agent` re-trace it. The project is a plain Maven project: in NetBeans use *File > Open Project*.
NetBeans' output window is not a real terminal, so build in the IDE but run
from Windows Terminal / a console. The Windows native build needs GraalVM (set
`GRAALVM_HOME` or pass `-GraalHome`) and the Visual Studio C++ tools (found
with vswhere, or pass `-Vcvars`); see `native\native.ps1`. The Linux one needs GraalVM, gcc and zlib-devel.

To use a modified Lanterna (it is LGPL), change its version in `pom.xml` and in
`run-lanterna.ps1` (or drop your own jar into `.cache` under the same file
name) and rebuild as above.

Keys: `F9` (or `Alt+F`/`C`/`L`/`R`/`H`, or a click on the top row) opens the
menu bar (`Left`/`Right` switch menus, `Up`/`Down`, `Enter` runs, `Esc` closes);
`Ctrl+P` opens the command palette (type part of an action's name, `Enter` runs it);
`Tab`/`Left`/`Right` switch panel, `Up`/`Down`/`Home`/`End`/`PgUp`/`PgDn`
navigate, `Enter` (or double-click) opens a directory, or a file (an archive too) with its Windows-associated program, `Ctrl+Enter` (or `Ctrl+PageDown`, or `Shift+Enter` where the terminal reports it) opens the file under the cursor as a read-only archive whatever its name (zip, jar, docx, ... anything the JDK zip reader accepts; `F5` copies out of it, writing is refused; also Files menu "Open as archive"), `Backspace` parent directory (or leaves an archive), `Ctrl+R` refresh,
`Space`/`Insert` mark, `F5` copy, `F6` move (with one item the box also takes a new name: an existing folder receives the item, any other
text is the new name in that folder, so `F6` + `b.bat` renames and `F5` + `b.bat` copies in place; end with `\` or `/` to force a new folder), `F7` mkdir, `F8` delete,
`Alt+F1`/`Alt+F2` change drive (left/right), `Ctrl+L` go to a typed path,
`Ctrl+D` bookmarks (`Enter` go, `Insert` add current dir, `Delete` remove),
`Ctrl+O` run command: a Win+R style dialog with a shell drop-down (`cmd`,
`powershell`, `pwsh` and `wsl` when installed, plus `[run] shell.NAME` entries
from `config.ini`) and a command box; `Alt+Up`/`Alt+Down` change the shell
from anywhere in the dialog, `Up`/`Down` in the command box recall earlier commands
(`run-history.txt` next to the INI file). The command starts in a separate
terminal window in the active panel's folder, and the window stays open
afterwards (checkbox / `[run] keep_open`); an empty command just opens the
shell there. No embedded terminal. Commands are passed through unchanged
(PowerShell and WSL receive them Base64-encoded), so quotes, `&` and `|` work;
`wsl` runs them in `bash -l`,
`F2` multi-rename (marked items, or everything in the directory; masks,
counter, wildcard search/replace, case change, live preview, `Ctrl+Z` undoes
the last rename of the session),
`Ctrl+F3` compares the two files under the cursors (or two marked files) in a
full-screen side-by-side view: line diff for text (`N`/`P` next/previous
difference, `W` ignore whitespace, `C` ignore case, `Left`/`Right` pan) and
hex view for binary files (`B`/`T` switch between them), `Esc` closes,
`F3` views the file under the cursor (images via `chafa`, PDFs via Poppler; text with UTF-8/UTF-16/ANSI detection, or
hex dump; opens multi-GB files instantly; `Tab` text/hex, `Space` page down, `L` toggles line numbers (on by default),
`W` toggles wrapping of long lines (on by default; breaks after the last space that fits, hard at the window
width otherwise; with wrap off `Left`/`Right` pan), `F7` or `/` find
(`0x..` searches bytes), `F3`/`n` and `p` next/previous match, `g` go to offset
or `NN%`, `Esc`/`q` closes),
`F4` edits the file under the cursor, `Shift+F4` a new file: full-screen editor that
keeps encoding (UTF-8/UTF-16/ANSI), line endings and BOM. `Shift`+arrows select,
`Ctrl`+arrows move by word, `Ctrl+A` select all, `Ctrl+C/X/V` clipboard (the Windows
clipboard; `Ctrl+Insert`, `Shift+Delete`, `Shift+Insert` too), `Ctrl+Z`/`Ctrl+Y`
undo/redo, `Ctrl+K` delete line, `Tab`/`Shift+Tab` indent/unindent the selected lines,
`F7`/`Ctrl+F` find (case matters only if the query has capitals), `F3`/`Shift+F3`
next/previous, `Ctrl+R` replace (`Y`/`N`/`A`ll), `Ctrl+G` go to `line[:col]`,
`Ctrl+L` line numbers, `F2`/`Ctrl+S` save, `Esc` close (asks when modified).
Settings: `editor.tab_size`, `editor.use_tabs`, `editor.auto_indent`, `editor.line_numbers`.
`Ctrl+F9` synchronizes the two panel directories: a full-screen list of differences
(recursive; `R` toggles, `C` compares contents of same-size files, `E` shows equal
items, `G` applies the `.gitignore` rules, `I` shows the ignored items) with a proposed action per item. `Left`/`Right` set the direction,
`Space` resets to the default, `Del` removes a one-sided item, `1` merge
(newer and missing files go to the other side, never deletes), `2`/`3` mirror
left to right / right to left (extra files on the target are deleted), `0` clears,
`Enter`/`F5` runs after a confirmation,
`F10` quit. Typing plain letters starts a quick search in the active panel
(prefix match first, then "contains"; `Up`/`Down` step through matches,
`Backspace` edits, `Esc` clears; any other key ends the search and acts normally).
`Alt+F7` (also Commands > Find files, `Ctrl+P`) searches for files: a dialog asks for the folder (default: the
active panel's), the file names (wildcards `*` and `?` as in the rename dialog, several patterns in one
round separated by `,` or `;`, e.g. `*.txt,*.ini`; a pattern without a wildcard matches names that contain it;
names never match case sensitively), optional text the files must contain (case-insensitive unless
"Match case"; binary files are skipped; UTF-8, UTF-16 with BOM and Windows-1252 text is read), a
maximum depth (empty = all levels, `1` = only that folder) and "Search in archives" (off by default; zip, jar, war,
ear, apk, aar, whl, nupkg and vsix files are searched as folders one level deeper; archives inside archives are not).
Folders are scanned in parallel like the compare and delete scans (`-Dtfm.threads=N` overrides the thread count).
The full-screen result list fills while the search runs: `Enter` shows the file in the active panel (inside the
archive for an archive hit), `F3` views it (also from an archive; the search keeps running; after a text search the
viewer's find prompt already holds the searched text, so `F3`/`N` jumps to the first match),
`F7`/`N` starts a new search with the previous values, `Esc` stops a running search and then closes the list.
A text search lists each matching file once with its first matching line; the list stops at 100,000 matches.
Mouse: click selects and activates a panel, double-click opens, wheel scrolls,
click on a panel title opens the drive list, click on the top row opens the menus,
click on a key hint above the status line (e.g. `F5 copy`, `Ctrl+D bookmarks`) runs it.
Click on a column header sorts the panel by that column (the header turns accent-coloured with ▲ ascending /
▼ descending); clicking it again reverses. `.EXT` at the right end of the name column sorts by extension
(names without one first). A new column starts A to Z, size and modified start with the biggest / newest.
Folders stay above files (for extension and size they keep name order), `..` stays on top, the cursor stays
on its entry and the order is kept for other folders until changed. Without a mouse: Left / Right menu,
"Sort by ..." (also in the palette).

## Layout

- `app/` — `App` (main loop, command registry, dialogs), `MenuBar` (pull-down menus), `CommandPalette` (Ctrl+P),
  `Launcher` (opens a file with its associated program), `PanelState`
  (directory, cursor, scroll, marks), `Key`/`Command`, `Job` with `CopyJob` /
  `DeleteJob` (background operations), `NativeCopy` (JNA `CopyFileEx`),
  `FileOps`
- `ui/` — `Theme` (color roles), `Panel` (renderer)
- `model/` — `FileEntry`, `DirectoryListing` (java.nio.file)
- `edit/` — `EditorView` (F4 editor UI), `TextBuffer` (document, undo, encodings), `SystemClipboard`

## Notes

- Lanterna 3.2.0-alpha1 is an alpha; pinned deliberately. Its
  `win32.WindowsTerminal` backend (JNA) is what makes real-terminal operation
  on Windows possible; `DefaultTerminalFactory` is forced to text mode.
- The app entry point is `org.ds.tuifileman.app.App`.
- The native exe is not code-signed, so Windows SmartScreen or an antivirus
  scanner may warn about it. Compare the SHA-256 file of a release, or build the
  exe yourself (see Build).

## License

tuifileman is released under the [MIT License](LICENSE), copyright (c) 2026 alkalinenanobot.
It bundles Lanterna (LGPL-3.0-or-later), JNA (Apache-2.0 option), Eclipse JGit (EDL, BSD-3-Clause)
and SLF4J (MIT); their notices and full license texts are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the [licenses](licenses) folder, and a release
archive contains them next to the exe.

## JLine experiment (archived)

An earlier prototype used JLine 4.4.7. It proved viable for rendering and
input but provides no widget layer (layouts, dialogs, tables, focus), so
Lanterna was chosen instead. Details: JLine supplies native terminal access,
resize signals, decoded keys, styled Unicode text, alternate-screen handling
and buffered differential redraw via `Display`; everything above that would
have been ours to build.

