# Changelog

## Unreleased

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
