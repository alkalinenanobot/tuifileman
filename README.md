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

## Features

- **Fast file operations.** Copy, move and delete run as background jobs with parallel scan, folder creation,
  copy and delete (32 threads on SSD/RAM disk, 4 on HDD, 6 on network shares). On Windows files go through
  native `CopyFileEx` with long-path support. Conflicts are asked per file, with "for all" policies. Errors are
  collected in a list instead of stopping the job. Benchmarks against robocopy, FastCopy, Total Commander and
  FreeFileSync: [PERF.md](PERF.md).
- **Directory sync** (`Ctrl+F9`): compares by size + time or by content, honours `.gitignore`, and merges or
  mirrors.
- **File compare** (`Ctrl+F3`): side-by-side line diff for text, hex view for binary files.
- **Viewer** (`F3`): text with encoding detection, or a hex dump; multi-GB files open instantly. Images (chafa,
  sixel), PDFs (Poppler), and video and audio (FFmpeg, mpv inside the terminal).
- **Editor** (`F4`): keeps encoding, line endings and BOM. Selection, system clipboard, undo, find/replace,
  line wrap.
- **File search** (`Alt+F7`): by name and content, in parallel, optionally inside archives.
- **Multi-rename** (`F2`): masks, counter, search/replace, case change, live preview, undo.
- **Archives as read-only folders**: zip, jar, docx and anything else the JDK zip reader accepts.
- **Everyday tools**: run command (`Ctrl+O`), bookmarks, go to path, names or paths to the clipboard, quick
  search, sortable columns, mouse, menus and a command palette, four themes (`dark`, `light`, `contrast`,
  `mc`; Options menu or `Ctrl+P` "theme").
- **Command line** for scripts and AI agents: `diff`, `copy`, `move`, `delete`, `sync`, `find`, `rename`
  with dry runs, reviewable plans, JSON output and fixed exit codes (see [Command line mode](#command-line-mode)).

Planned: see [TODO.md](TODO.md).

## Install and run

Download the archive for your platform from the [releases](https://github.com/alkalinenanobot/tuifileman/releases)
page and its `.sha256` file. Each archive holds one folder with the native executable (no JDK needed), the
licences and this README.

- **Windows x64** (`tuifileman-<version>-windows-x64.zip`): unpack it anywhere, e.g. into
  `%LOCALAPPDATA%\Programs`, and add the folder containing `tuifileman.exe` to your PATH. Check the archive
  first with `(Get-FileHash tuifileman-<version>-windows-x64.zip).Hash` against the `.sha256` file.
- **Linux x64** (`tuifileman-<version>-linux-x64.tar.gz`): `sha256sum -c` the archive, unpack it and put
  `tuifileman` on your PATH, e.g. into `~/.local/bin`. The binary links dynamically against glibc; if an older
  distribution reports `GLIBC_x.y not found`, use the jar instead.
- **Other platforms**: build the fat jar with `mvn package` (see [Build](#build-maven--netbeans)) and run it on
  a JDK 17+.

Then:

```powershell
tuifileman                                   # file manager in the current folder
tuifileman D:\src D:\backup                  # optional start folders: tuifileman [left [right]]
java -jar target\tuifileman-0.5.0.jar        # the same from the jar
tuifileman diff D:\a D:\b                    # command line mode, see below
```

The file manager needs an interactive terminal: Windows Terminal, a PowerShell or cmd console, or a Linux
terminal. IDE output windows (NetBeans) are not one.

## Keys

Every action is also in the menu bar (`F9`) and the command palette (`Ctrl+P`). Help > Keyboard shortcuts
shows a short list, and the key hints above the status line can be clicked.

### Panels

The panel title shows the drive and its volume label; the path line shortens long paths in the middle with
`...`.

| Key | Action |
|---|---|
| `F9`, `Alt+F`/`C`/`L`/`R`/`O`/`H`, click on the top row | Menu bar: `Left`/`Right` switch menus, `Up`/`Down` select, `Enter` runs, `Esc` closes |
| `Ctrl+P` | Command palette: type part of an action's name, `Enter` runs it |
| `Tab`, `Left`, `Right` | Switch panel |
| `Up`, `Down`, `Home`, `End`, `PgUp`, `PgDn` | Move the cursor |
| `Enter`, double-click | Open a directory, or open a file (an archive too) with its associated program (`xdg-open` and the like on Linux) |
| `Ctrl+Enter`, `Ctrl+PgDn`, `Shift+Enter` (where the terminal reports it) | Open the file under the cursor as a read-only archive ([Archives](#archives)) |
| `Backspace` | Parent directory, or leave an archive |
| letters | Quick search: prefix match first, then "contains". `Up`/`Down` step through matches, `Backspace` edits, `Esc` clears; any other key ends the search and acts normally |
| `Space` / `Insert` | Mark and move down / mark in place |
| `*` / `Alt+*` | Invert the marks of all entries, folders included / of files only |
| `Ctrl+R` | Refresh |
| `Alt+F1` / `Alt+F2`, click on a panel title | Change the drive of the left / right panel; the last directory of each drive is remembered |
| `Ctrl+L` | Go to a typed path |
| `Ctrl+D` | Bookmarks: `Enter` go, `Insert` add the current directory, `Delete` remove |
| `Ctrl+C` / `Ctrl+K` | Copy the names / full paths of the marked entries (or of the one under the cursor) to the system clipboard, one per line (not inside an archive) |
| `F10` | Quit |
| `F12` | Problem list with demo data (debug aid for UI checks) |

### Mouse and sorting

- Click selects and activates a panel, double-click opens, the wheel scrolls.
- A click on a panel title opens the drive list, on the top row the menus, on a key hint above the status line
  (e.g. `F5 copy`, `Ctrl+D bookmarks`) runs it.
- Directories come first, then files, by name and case-insensitive. A click on a column header sorts the panel
  by that column (the header turns accent-coloured with ▲ ascending / ▼ descending); clicking it again
  reverses. `.EXT` at the right end of the name column sorts by extension (names without one first). A new
  column starts A to Z; size and modified start with the biggest / newest.
- Folders stay above files (for extension and size they keep name order), `..` stays on top, the cursor stays
  on its entry, and the order is kept for other folders until changed. Without a mouse: Left / Right menu,
  "Sort by ..." (also in the palette).

### File operations

| Key | Action |
|---|---|
| `F5` / `F6` | Copy / move the marked entries (or the one under the cursor) to the other panel |
| `F7` | Make a directory |
| `F8` | Delete |
| `F2` | Multi-rename of the marked items, or of everything in the directory: masks, counter, wildcard search/replace, case change, live preview |
| `Ctrl+Z` | Undo the last rename of the session |

- With one item the `F5`/`F6` box also takes a new name: an existing folder receives the item, any other text
  is the new name in that folder. So `F6` + `b.bat` renames and `F5` + `b.bat` copies in place; end with `\`
  or `/` to force a new folder.
- Copy, move and delete run as background jobs with a progress window (Cancel button or `Esc`). A same-volume
  move is a rename; a cross-volume move copies first and deletes a source only when everything in it was
  copied. Small files (< 32 MB) are copied in parallel, big ones one at a time alongside them; on Linux and
  for archive sources a channel copy replaces `CopyFileEx`. `-Dtfm.threads=N` overrides the thread count.
- Conflicts are asked per existing file, showing size and date of both (marked newer/older/same time):
  overwrite, skip or keep both (the new copy becomes `name (1).ext`) for this file, or for the rest of the
  job: overwrite all, skip all, keep both for all, overwrite all older (the new file is newer), overwrite all
  with different size, smaller or larger (of the file being replaced). `Esc` cancels the job.
- Errors do not stop a job. They are collected into a scrollable list (full path + reason), also saved to
  `tuifileman-errors.log` in the temp folder (`%TEMP%`).

### Archives

`Ctrl+Enter` (or `Ctrl+PgDn`, or the Files menu "Open as archive") browses the file under the cursor like a
directory, whatever its name: zip, jar, war, docx, xlsx, apk, epub, ... (read through the JDK zip support, no
external tool). `Enter` still opens it with the system application. Inside, view (`F3`), compare (`Ctrl+F3`)
and copy out (`F5`) work; anything that writes is refused. `..` or `Backspace` leaves. tar and 7z are not
supported yet. The [command line](#command-line-mode) reaches into archives too (`pack.zip!/dir/file`): `find`,
`cat`, `diff` and `copy`.

### Viewer (`F3`)

Text with UTF-8/UTF-16/ANSI detection, or a hex dump; multi-GB files open instantly.

| Key | Action |
|---|---|
| `Tab` | Switch between text and hex |
| `Space` | Page down |
| `L` | Line numbers (on by default, `viewer.line_numbers`) |
| `W` | Wrap long lines (on by default, `viewer.wrap`): breaks after the last space that fits, hard at the window width otherwise; with wrap off `Left`/`Right` pan |
| `F7`, `/` | Find (`0x..` searches bytes) |
| `F3`, `n` / `p` | Next / previous match |
| `g` | Go to an offset or `NN%` |
| `Esc`, `q` | Close |

Previews use external tools; their commands are settings ([CONFIG.md](CONFIG.md)):

- **Images** through [chafa](https://hpjansson.com/chafa/) (`image.chafa`; empty disables it).
  `image.format` is `sixel` (default) or `symbols` for terminals without sixel (`image.symbols` is the
  `chafa --symbols` set, default `half+quad+space`). `S` toggles the format while viewing, `Tab` switches
  between image and hex view. If chafa is missing, disabled or fails (also for BMP), an info page is shown
  instead (format, size, colour depth, file size, date, the reason); `Tab` still switches to hex.
- **PDFs** through the [Poppler](https://poppler.freedesktop.org/) tools (`pdf.pdfinfo`, `pdf.pdftoppm`,
  `pdf.pdftotext`): `Space`/`PgDn`/`PgUp`/`Home`/`End` change the page, `G` goes to a page, `Tab` cycles
  page, text (at that page, searchable) and hex.
- **Video and audio** through [FFmpeg](https://ffmpeg.org/) and [mpv](https://mpv.io/) (`video.ffprobe`,
  `video.ffmpeg`, `video.mpv`, `video.vo`, `video.args`): a frame (`Space`/`PgUp` step through ten frames
  over the file) or, for audio, an info page (duration, codecs, tags, streams). `P` plays the file with mpv
  inside the terminal and returns to the viewer when mpv quits. `video.vo = sixel` is the default and passes
  the terminal size to mpv, which cannot detect it under Windows Terminal; it needs an mpv build with sixel,
  otherwise it falls back to `tct` coloured half blocks; `gpu` opens a window instead.

### Editor (`F4`, `Shift+F4` for a new file)

Full-screen editor that keeps encoding (UTF-8/UTF-16/ANSI), line endings and BOM. Settings:
`editor.tab_size`, `editor.use_tabs`, `editor.auto_indent`, `editor.line_numbers`, `editor.wrap`.

| Key | Action |
|---|---|
| `Shift`+arrows | Select |
| `Ctrl`+arrows | Move by word |
| `Ctrl+Home` / `Ctrl+End` | Start / end of the file |
| `Ctrl+A` | Select all |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` (also `Ctrl+Insert`, `Shift+Delete`, `Shift+Insert`) | Copy / cut / paste, system clipboard |
| `Ctrl+Z` / `Ctrl+Y` | Undo / redo |
| `Ctrl+K` | Delete the line |
| `Tab` / `Shift+Tab` | Indent / unindent the selected lines |
| `F7`, `Ctrl+F` | Find (case matters only if the query has capitals) |
| `F3` / `Shift+F3` | Next / previous match |
| `Ctrl+R` | Replace (`Y`/`N`/`A`ll) |
| `Ctrl+G` | Go to `line[:col]` |
| `Ctrl+L` | Line numbers |
| `Alt+W` | Wrap long lines (`Up`/`Down`/`Home`/`End` then work on screen rows) |
| `F2`, `Ctrl+S` | Save |
| `Esc`, `F10`, `Ctrl+Q` | Close (asks when modified) |

### Compare files (`Ctrl+F3`)

Compares the two files under the cursors (or two marked files) in a full-screen side-by-side view: a line diff
for text, a hex view for binary files.

| Key | Action |
|---|---|
| `N` / `P` | Next / previous difference |
| `W` | Ignore whitespace |
| `C` | Ignore case |
| `Left` / `Right` | Pan |
| `B` / `T` | Hex / text view |
| `Esc` | Close |

### Synchronize directories (`Ctrl+F9`)

A full-screen list of the differences between the two panel directories, with a proposed action per item.

| Key | Action |
|---|---|
| `R` | Recursive on/off |
| `C` | Compare the contents of same-size files |
| `E` | Show equal items |
| `G` | Apply the `.gitignore` rules |
| `I` | Show the ignored items |
| `Left` / `Right` | Set the direction |
| `Space` | Reset to the default action |
| `Del` | Remove a one-sided item |
| `1` | Merge: newer and missing files go to the other side, never deletes |
| `2` / `3` | Mirror left to right / right to left (extra files on the target are deleted) |
| `0` | Clear all actions |
| `Enter`, `F5` | Run, after a confirmation |
| `Esc` | Close |

### Find files (`Alt+F7`)

A dialog asks for:

- the folder (default: the active panel's);
- the file names: wildcards `*` and `?` as in the rename dialog, several patterns separated by `,` or `;`
  (e.g. `*.txt,*.ini`); a pattern without a wildcard matches names that contain it; names never match case
  sensitively;
- optional text the files must contain: case-insensitive unless "Match case"; binary files are skipped;
  UTF-8, UTF-16 with BOM and Windows-1252 text is read;
- a maximum depth (empty = all levels, `1` = only that folder);
- "Search in archives" (off by default): zip, jar, war, ear, apk, aar, whl, nupkg and vsix files are searched as
  folders one level deeper; archives inside archives are not.

Folders are scanned in parallel (`-Dtfm.threads=N` overrides the thread count). The full-screen result list
fills while the search runs. A text search lists each matching file once with its first matching line; the
list stops at 100,000 matches.

| Key | Action |
|---|---|
| `Enter` | Show the file in the active panel (inside the archive for an archive hit) |
| `F3` | View it, also from an archive, while the search keeps running; after a text search the viewer's find prompt already holds the searched text, so `F3`/`N` jumps to the first match |
| `F7`, `N` | New search with the previous values |
| `Esc` | Stop a running search, then close the list |

### Run command (`Ctrl+O`)

A Win+R style dialog with a shell drop-down and a command box. `Alt+Up`/`Alt+Down` change the shell from
anywhere in the dialog; `Up`/`Down` in the command box recall earlier commands (`run-history.txt` next to the
INI file).

- Shells: `cmd`, `powershell`, `pwsh` and `wsl` when installed on Windows; `bash`, your login shell and `sh`
  on Linux; plus `[run] shell.NAME` entries from `config.ini`.
- On Windows the command starts in a separate terminal window in the active panel's folder, and the window
  stays open afterwards (checkbox / `[run] keep_open`). On Linux it runs in this terminal; the program waits
  for Enter, then repaints. An empty command just opens the shell there. No embedded terminal.
- When exactly one entry is marked (or none, and the cursor is on one) its name is pre-filled, double-quoted
  if it needs it, and counts as selected: a printable first key replaces it, `Home`/`End`/arrows keep it for
  editing.
- Commands are passed through unchanged (PowerShell and WSL receive them Base64-encoded), so quotes, `&` and
  `|` work; `wsl` runs them in `bash -l`.

## Configuration

`%APPDATA%\tuifileman\config.ini` (`~/.tuifileman/config.ini` on Linux) is created with commented defaults on
the first start. It is plain INI (`[section]`, `key = value`, `#` or `;` comments); a bad line only shows a
warning. Every setting with its default and values is listed in [CONFIG.md](CONFIG.md).

## Command line mode

`tuifileman <command> ...` runs without the interface (jar or exe). Available: `diff`
(read-only comparison of two folders, the scan behind Ctrl+F9, or of two files as a unified diff), `copy`, `move`,
`delete`, `sync`, `rename` (multi-rename), `find` (file search, the engine of Alt+F7), `cat` (files to stdout) and
`install-aliases`; `--version`.

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
tuifileman find D:\src -N bom.ini -x .git -x node_modules   # leave folders out (they are not entered)
tuifileman find D:\src -g -x .git -T Vmo_factor # also obey the .gitignore files; .git itself needs -x .git
tuifileman find D:\share\pack.zip -N "BuildID*" # one archive as the root: its entries are searched like a folder
tuifileman cat "D:\share\pack.zip!/info/BuildID.txt"   # one entry of an archive to stdout, no extraction
tuifileman copy "D:\share\pack.zip!/cfg/*.ini" D:\out  # entries of an archive as sources (read-only)
tuifileman diff D:\a\app.ini E:\b\app.ini        # two files: a unified diff, like diff -u
tuifileman diff -U 0 -w "D:\p.zip!/app.ini" E:\app.ini   # no context lines, ignore white space, one side inside a zip
tuifileman diff D:\a E:\b -c -t 32 --stats -f none   # timing, items/s, threads and disk type
tuifileman install-aliases                      # tfm-diff.exe, tfm-copy.exe ... (hard links) next to the exe
tuifileman help copy                            # all options
```

- `copy`/`move`/`delete` take several sources; `*` and `?` in the last path segment are expanded by
  the tool (cmd and PowerShell do not do it for exes). The destination is a folder and is created
  when missing. `-n/--dry-run` prints what would happen (including unresolved conflicts) and changes nothing.
  `copy` never writes file contents to stdout (its stdout is the plan/result report): to print a file, or one entry
  of an archive, use `cat`.
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
  only; `--limit N`, `--sort`; `-x/--exclude GLOB`, `-g/--gitignore` and `--ignore-file FILE` as in `diff`:
  an excluded or ignored folder is not entered, `.git` is only skipped with `-x .git`). FOLDER may also be an
  archive file (zip, jar, docx ...; read through the JDK zip support, so it is searched as a folder and every
  hit is `archive!/path`) or a folder inside one (`pack.zip!/dir`); `-A` is then not needed. The folder is
  required. Read-only. stdout: absolute paths, printed as they are
  found (so a long search shows results at once and `find ... | head` stops early) in no fixed order, unless
  `--sort` waits for the end and lists them in path order (json is one document and always in path order); with `--text`
  `path:line: text`; `-f text|json|jsonl|csv|none` and `-S/--summary` work as in `diff` (json has `schema`,
  `command`, `root`, `names`, `text`, `summary` with `matches`/`examined`/`unreadable`/`limited`, and `items`
  with `entry`, `path`, `archive`, `size`, `modified`, plus `line`/`snippet` for a text search). Exit 0 matches,
  1 no matches (like grep), 2 usage, 3 incomplete: folders that cannot be read are listed on stderr and nothing
  can be said about them (a search of a whole drive usually ends with 3), 4 error. Unlike the file manager the
  command has no 100,000-match cap. Quote patterns (`-N '*.c'`); PowerShell splits an unquoted `a,b`
  into two arguments, so quote it or repeat `-N`.
- `diff LEFT RIGHT` with two files prints a unified diff (like `diff -u`, `--- left`/`+++ right`, `@@` hunks) on
  stdout: `-U/--unified N` context lines (default 3), `-w/--ignore-space`, `-i/--ignore-case`, `-f none` for the
  exit code only. It is the line comparison of the file manager's Ctrl+F3, so the same text detection applies
  (UTF-8 with or without BOM, UTF-16, else the system code page; files over 32 MB, or with NUL bytes, are
  binary and only reported as differing). Text that differs only in line endings or a final newline gives no
  hunk but says so and exits 1 (unless `-w`/`-i`). Exit 0 identical, 1 different. A folder against a file is a
  usage error; `--format json|jsonl|csv` and `-c`, `-s`, `-x` ... describe folders. With `-c/--content` on two
  folders a file whose content differs is `different` whatever its time (without it a different time gives
  `left-newer`/`right-newer`, the direction a merge needs).
- `cat FILE...` writes the files to stdout byte for byte, one after the other, with nothing else on stdout (that is
  why it is a command of its own and not a mode of `copy`, whose stdout carries the report). Errors go to stderr;
  exit 0 ok, 3 some files could not be read, 4 none could, 2 usage.
- `ARCHIVE!/path/inside` (a `!` followed by `/` or `\`) names an entry of a zip, jar, docx ... wherever a command
  takes a path: `find`, `cat`, `diff` and the sources of `copy` (`*`/`?` work in the last segment; a folder inside
  the archive is a valid source or search root). The file manager already copies out of archives with `F5`; this
  is the same for the command line. It is what `find` prints for a hit. An archive inside an archive works
  (`a.zip!/b.zip!/c.txt`; the inner one is extracted to a temporary file). Archives are read-only: `move`, `delete`
  and `rename` refuse them (exit 2). In Git Bash and other shells where `!` or `\` are special, quote the path.
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
  copied, overwritten or deleted (a mirror `sync` keeps them in the target); they are not differences, so
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
- `--stats` (diff, copy, move, delete, sync, find) prints a block on stderr - wall time with the scan/work split,
  items and bytes per second, the threads used and the drive type - and adds a `"stats"` object to the
  json/jsonl result. It is printed even with `-q`.
- `install-aliases` creates `tfm-diff.exe`, `tfm-copy.exe`, `tfm-move.exe`, `tfm-delete.exe`, `tfm-sync.exe`,
  `tfm-rename.exe`, `tfm-find.exe` and `tfm-cat.exe` next to the exe as hard links (no extra disk space; `--copy` where hard links are not
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
mvn package                                  # fat jar: target\tuifileman-0.5.0.jar (runs the JUnit tests first)
mvn test                                     # tests only
java -jar target\tuifileman-0.5.0.jar
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

To use a modified Lanterna (it is LGPL), change its version (or point it at your own jar) in `pom.xml` and
rebuild as above.

## Layout

- `app/` — `App` (main loop, command registry, dialogs), `MenuBar` (pull-down menus), `CommandPalette` (Ctrl+P),
  `Launcher` (opens a file with its associated program), `PanelState`
  (directory, cursor, scroll, marks), `Key`/`Command`, `Job` with `CopyJob` /
  `DeleteJob` (background operations), `NativeCopy` (JNA `CopyFileEx`),
  `FileOps`
- `ui/` — `Theme` (color roles), `Panel` (renderer)
- `model/` — `FileEntry`, `DirectoryListing` (java.nio.file)
- `edit/` — `EditorView` (F4 editor UI), `TextBuffer` (document, undo, encodings), `SystemClipboard`
- `cli/` — `Cli` (command dispatch, `Exit` codes), one class per command: `DiffCommand`, `OpCommand`
  (copy/move/delete), `SyncCommand`, `RenameCommand`, `FindCommand`, `AliasCommand`
- `compare/` — `DirCompare` (folder scan behind `diff`, `sync` and Ctrl+F9), `CompareView` (Ctrl+F3),
  `TextCompare`/`LineDiff`/`BinaryCompare`, `TimeTolerance`
- `search/` — `FileSearch` (engine of Alt+F7 and `find`)
- `view/` — `FileView` (F3 viewer) and the image, PDF and media previews
- `fs/` — `Archives`, `Parallel` (thread counts per drive type), `Glob`, `Entries`
- `ignore/` — `.gitignore` matching (`GitIgnoreMatcher`, JGit rules)
- `config/` — `Config` (the INI settings)

## Notes

- Lanterna 3.2.0-alpha1 is an alpha; pinned deliberately. Its
  `win32.WindowsTerminal` backend (JNA) is what makes real-terminal operation
  on Windows possible. `App` creates the terminal directly (`WindowsTerminal`, or a stty-based `UnixTerminal` elsewhere), not through `DefaultTerminalFactory`, which ignores the Ctrl+C setting for the Windows console and pulls in the Swing/AWT classes.
- The app entry point is `org.ds.tuifileman.app.App`.
- The main view is drawn by our own render loop. Dialogs are Lanterna GUI2 dialogs themed via
  `ui/DialogTheme`, shown only modally on top of it (the main view is their backdrop). Do not use a GUI2
  window-less background component: Lanterna 3.2.0-alpha1 throws an NPE on mouse clicks when no window is open.
- Colours are semantic roles in `ui/Theme`; a new palette is one more factory method plus an entry in
  `Theme.all()`. The theme choice is saved to `[theme] name` in `config.ini`.
- The native exe is not code-signed, so Windows SmartScreen or an antivirus
  scanner may warn about it. Compare the SHA-256 file of a release, or build the
  exe yourself (see Build).

## License

tuifileman is released under the [MIT License](LICENSE), copyright (c) 2026 alkalinenanobot.
It bundles Lanterna (LGPL-3.0-or-later), JNA (Apache-2.0 option), Eclipse JGit (EDL, BSD-3-Clause)
and SLF4J (MIT); their notices and full license texts are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the [licenses](licenses) folder, and a release
archive contains them next to the exe.

## Credits

The code, tests and documentation of tuifileman were written by AI coding assistants, working under the
direction of the repository owner: Sonnet 5.5, Opus 5.5 and GPT-5.6-sol. The credit for the code is theirs.
