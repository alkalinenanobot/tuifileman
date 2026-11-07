# Changelog

## Unreleased

Fixes from a code audit.

- `move`/`copy`: two sources that would end up with the same name in the destination no longer replace each other
  (the second one is reported, its source stays). A rename never replaces a file that appeared after planning.
- Windows junctions (and other folder links) inside a copied or moved tree are copied as links, never followed, so a
  loop link cannot fill the disk and a move no longer turns a link into a deep copy.
- A failed or crashed file copy keeps its source in a move; a move counts each item once (summary, progress, JSON).
- Archives: entry names with a backslash or a drive letter can no longer write outside the temporary folder. A nested
  archive is extracted once and its temporary copy is deleted on close; extraction checks the free space first.
- `sync --plan`: a plan's `only-left`/`only-right`/`left-newer`/`right-newer` verdicts are recomputed from the current
  files (an old plan can no longer delete or overwrite a file that is now on both sides). A path that goes through a
  link, or a path listed twice, is refused. Names that differ only in case stay two files on a case-sensitive disk.
- `sync`/`diff` with `.gitignore` rules stop with an error when a nested `.gitignore` cannot be read, instead of
  syncing as if it had no rules. The sync screen no longer runs the previous result after a failed rescan.
- The editor, `config.ini`, bookmarks, last folders and run history are written to a temporary file and moved over the
  old one, so a full disk or a kill cannot leave them empty. A file that holds only a line break is saved unchanged;
  a BOM is not written in front of text that fell back to ANSI; a BOM at the start of `config.ini` no longer
  duplicates its first section.
- File name patterns use a linear matcher: `find --name "*a*b*c*d*e*"` no longer takes minutes on long names.
- Windows: external tools (chafa, ffmpeg, pdftoppm, clipboard, shells) start by full path, so a program of the same
  name in the folder you are browsing is not run instead.

## 0.8.0

- Both panels open in the folders they were in when the program was last closed (`last-dirs.txt` next to the config,
  updated on every change, so a killed terminal loses nothing). Start folders given on the command line still win;
  a folder that is gone falls back to the current folder.
- Viewer: select text with the mouse. Dragging selects only the text, never the line numbers, and wrapped rows join
  without a line break; releasing the button copies the selection (tabs kept) to the system clipboard, `c` or `Ctrl+C`
  copies again, `Esc` clears it. Dragging past the top or bottom scrolls.
- Viewer: wrapped rows of an indented line or a list item (rendered HTML and Markdown included) line up under the text
  instead of starting at the first column.

## 0.7.1

- `sync` copies while it is still comparing: with `--yes`, no `--dry-run` or `--plan`, and no progress bar to draw
  (`--quiet`, `--progress none`, or output that is not a terminal), new folders are created and files are copied as the
  scan finds them instead of after it. Deletions of a mirror still wait for the end of the scan, so nothing is removed
  before the whole tree has been seen. Runs with a prompt, a bar, a plan or a dry run work as before. On a large tree
  with a few changed files this saves about the time of the copying (0.6 s of 3.8 s for 3,000 changed files in a tree of
  300,000). If the scan is cancelled, the files copied so far stay, as with a cancelled copy.
- `sync` writes each replaced file as before (temporary name, then rename, so a failed copy never damages the target),
  but no longer tries to delete the temporary file after a successful rename and checks the target folder with one call.

## 0.7.0

- New theme `onedark` (Atom One Dark colours: slate panels, blue accent, yellow folders, solid blue cursor bar in the
  active panel). Themes got `cursor`, `cursorText` and `directory` roles; the other four themes look as before.
- Compact main view: the panels touch each other and the screen edges, the volume and path are the panel's top
  border title (no path row, no blank rows), and the two key-hint rows plus the status row are one bottom line
  (`F2 rename` ... `Ctrl+P palette`); a message from an action replaces the hints until the next key or click. A
  36-row terminal shows 30 entries instead of 21. The less common hints (compare, sync, find, bookmarks, go to, run,
  drive) are in the menus, `F1` and the palette.

## 0.6.0

- Viewer (`F3`): HTML (`.html`, `.htm`, `.xhtml`) and Markdown (`.md`, `.markdown`) files open rendered as styled
  text: coloured headings, links and code, bold, italic, underline and strike-through, lists, tables as aligned
  columns (GFM tables in Markdown, `<table>` in HTML), task lists, quotes, code blocks, links as `text (url)`;
  `R` switches to the raw source. Built in with jsoup and commonmark-java, no external tool. The themes got
  `heading`, `link` and `code` colours.
- Viewer (`F3`): source files are coloured by extension (34 languages, from RSyntaxTextArea's scanners: strings,
  comments, text blocks and escapes are handled by the scanner, not by regular expressions of ours). The whole
  file is scanned once on opening, up to `viewer.highlight_max_kb` (1024); `viewer.highlight = false` turns it off.
  Themes got `keyword`, `type`, `string`, `number`, `comment` and `meta` colours.
- Bookmark list (`Ctrl+D`): typing searches it like in a panel (name prefix first, else any part of the path;
  `Up`/`Down` step through matches, `Backspace` edits, `Esc` clears).
- Editor (`F4`): source files are coloured while you edit (`editor.highlight`, default on). One scanner state per line
  is kept (`syntax.DocumentStyles`) and repaired after every edit, undo and redo: typing in an ordinary line rescans
  that line only, opening or closing a comment or text block recolours the lines it reaches (followed up to 5000 lines
  at once, the rest when drawn). Selection, current line and tabs keep the colours.
- Viewer (`F3`): `G` takes a line number in the text view: a plain number (`1234`, also `l1234` or `line 1234`) is a
  line, a byte offset needs a colon (`:1234`; `0xHEX` and `NN%` as before; in the hex view a plain number stays an
  offset). The newlines are counted from the nearer of the start of the file and the position the gutter counted to,
  and `Esc` cancels a long scan. (Until 0.5.0 a plain number was a byte offset: use `:1234` for that now.)
- Linux native executable: the window size was never re-read, so resizing the terminal (also in Windows Terminal under
  WSL) left the screen at its old size. Lanterna registers its SIGWINCH handler through reflection on
  `sun.misc.Signal`, which the native image did not know; `native/config` now lists it.

## 0.5.0

- `find -x/--exclude GLOB`, `-g/--gitignore` and `--ignore-file FILE` (the options of `diff`): excluded or ignored folders
  are not entered, e.g. `find . -N bom.ini -x .git`.
- `find` takes an archive file (zip, jar, docx ...) or a folder inside one as its root (`find pack.zip -N "BuildID*"`).
  An entry of an archive can be named `ARCHIVE!/path/in/it`, as `find` prints it, in `cat`, `diff`, `find` and as a
  source of `copy` (also with `*`/`?`); `move`, `delete` and `rename` refuse archive entries (read-only).
- New command `cat FILE...`: files, or single archive entries, to stdout unchanged (`tfm-cat` alias).
- `diff` with two files prints a unified diff (`-U N`, `-w`, `-i`), reusing the line comparison of the file manager;
  binary files only report whether they differ. Folders against a file are a usage error.
- `diff -c` on folders reports a file whose content differs (other size, or equal size and other bytes) as
  `different` instead of `left-newer`/`right-newer`; `sync -c` still names the newer side, which a merge needs.

## 0.4.2

- The panel's size column is compact and right aligned (`9.1K`, `113K`, `373B`, `12M`): one letter units keep the last
  digits in line, and the name column gets 3 more cells.

## 0.4.1

- Zip files written with backslash separators (`dir\file.txt`, as PowerShell 5.1 `Compress-Archive` does) showed as one
  flat list of names with backslashes that could not be opened or copied. They browse as normal folders now.

## 0.4.0

- F3 opened a text file as a hex dump when more than 5 % of its bytes were outside ASCII (Hungarian, Cyrillic, CJK,
  ... in UTF-8 or a single byte code page): bytes above 0x7F were counted as control characters. They are text now.
- The F4 editor wraps long lines like the F3 viewer (`editor.wrap`, default on; `Alt+W` toggles it). The line number
  shows on the first row of a line, the cursor, selection and mouse work on the wrapped rows, and `Up`/`Down`/`PgUp`/
  `PgDn`/`Home`/`End` and the mouse wheel move by screen row (`End` stops before the space a row was broken at).

## 0.3.6

- `find`: a match found just as the progress bar was drawn no longer lands on the bar's line, and the bar also steps
  aside for the matches with `--progress bar` (it did only for `auto`).
- `find --text` stops at once when it is cancelled or reaches `--limit`: queued big files are no longer opened and the
  line number of a match deep in a big file is no longer counted to the end.

## 0.3.5

- `find --text` is about 15 times faster on big files (a 1.2 GB log took 14 s, now 0.8 s; a 6.7 GB tree 17 s, now
  about 1 s with a warm cache). The text is looked for in the raw bytes of UTF-8 files (a plain ASCII text also in
  single byte files) with every letter in its upper and lower case form, big files get a task of their own, and the
  line number and snippet are only worked out for files that match. A case form that has another length in UTF-8
  than the typed letter (the Kelvin sign for `k`, the dotless i) is no longer matched there.
- `find` shows the progress bar also while the matches stream to the terminal; it steps aside while they are written.
- Windows: characters outside the ANSI code page (French oe ligature, Cyrillic, Greek, ...) in the command line are
  no longer turned into `?` (and then expanded as a wildcard into the names of the current folder), in search texts
  as well as in file and folder names. The real command line is read with `GetCommandLineW`.

## 0.3.4

- Konsole: `Shift+F3` (and other modified `F1`-`F4`, sent as `ESC O <mod> P..S`) keeps its modifier, and
  `Shift+Enter` (sent as `ESC O M`) is no longer typed into quick search as `M`. `Ctrl+Enter` is a plain Enter in
  Konsole; use `Ctrl+PageDown` or `Shift+Enter` to open as archive.
- Sixel pictures use the terminal's real cell size (asked with `ESC [ 16 t`, fixed with `image.cell_size`) instead of
  Windows Terminal's 10x20 pixels. In Konsole on a scaled display the picture was too big, shifted down a row and
  covered the status line.

## 0.3.3

- Mouse: reports use xterm's SGR encoding, which is plain ASCII and has no column limit. Clicking in a terminal
  wider than 94 columns (right panel of a maximised window) could type `[M#` into quick search, because Windows
  Terminal split the old multi-byte coordinates and Lanterna took the first part for keys.

## 0.3.2

- Invert marks of files only is `Alt+*` (terminals do not deliver `Ctrl+*`: Windows Terminal, GNOME Terminal and
  others send a plain `*`).
- "Invert marks" and "Invert marks (files only)" are in the Commands menu, so the command palette finds them.

## 0.3.1

- `Ctrl+C` / `Ctrl+K`: copy the names / full paths of the marked entries (or the one under the cursor) to the
  system clipboard.
- Run command (`Ctrl+O`): the name of the single marked entry, or the one under the cursor, is pre-filled (quoted
  when needed) and acts as selected text.
- `*` inverts the marks of all entries (folders included), `Ctrl+*` only of files. `*` no longer starts quick search.

## 0.3.0 - first public release

- Two-panel terminal file manager (Lanterna 3.2.0-alpha1): menus, command palette, mouse, themes, bookmarks,
  quick search by typing, file search (Alt+F7), column sorting.
- Copy / move / delete as parallel background jobs with progress, conflict policy and an error list;
  native `CopyFileEx` for big files on Windows.
- Directory sync and compare (merge and mirror modes, content compare, `.gitignore` aware), file compare
  (text and hex), multi-rename (F2) with undo, viewer (text, hex, image, PDF, video/audio previews through
  external tools) and a text editor (F4), read-only archive browsing.
- Scriptable command line (`diff`, `sync`, `copy`, `move`, `delete`, `rename`, `find`) with JSON/CSV output,
  dry runs, reviewable plans and defined exit codes; `install-aliases` for `tfm-*` command names.
- Release builds: Windows x64 zip and Linux x64 tar.gz, each a single GraalVM native executable with the licence
  files and a SHA-256 file. Both are built by CI from the same fat jar. The Linux binary needs glibc 2.34 or newer
  (the highest versioned symbol it imports; checked with `objdump -T`).
- Package and Maven `groupId` are `org.ds.tuifileman`.
