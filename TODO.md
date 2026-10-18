# TODO

Open items only. What is finished is described in [README.md](README.md), [CONFIG.md](CONFIG.md),
[PERF.md](PERF.md) and [CHANGELOG.md](CHANGELOG.md). The list was carried over from the earlier development
notes; items marked "unverified" were never checked on a real setup.

## Release and platforms

- macOS: no native build, no terminal detection. arm64 builds for Windows and Linux.
- Linux binary reach: it needs a glibc at least as new as the CI runner's. Build on an older baseline or try
  `--static-nolibc` / musl for older distros.
- Linux config path: it falls back to `~/.tuifileman`; follow XDG.
- Code signing for the Windows exe (SignPath Foundation), then winget / scoop manifests.
- When JNA interfaces or reflection change, re-run `native\native.ps1 -Agent` or `native/native.sh --agent` and
  commit the merged `native/config`.

## Features

- Bookmarks: named entries, reordering, digit shortcuts.
- Find (Alt+F7, `find`): regex for names and text, archives inside archives, a results panel that feeds
  F5/F6/F8. The CLI streams matches unsorted; `--sort` and json collect them first.
- Multi-rename: per-column colours in the preview, regex search, saved presets.
- File compare: merge/copy blocks between the sides (with the editor), go to line/offset, folder arguments.
- Directory sync: filters (only left/right/newer), compare by content in the background with progress,
  remembering the last mode, conflict policies for merge (newer wins on equal time).
- Copy/move: prompt before overwriting read-only files, remembering the last conflict choice, the same policy in
  sync.
- Panels: parallel listing of huge folders.
- Viewer: follow mode; long-line wrap in the F4 editor and in the compare view; terminal capability query (DA1) to
  choose sixel automatically; PDF page prefetch, zoom/scroll within a page, fit-width.
- Editor: syntax highlighting, overwrite mode, wide-character column handling, files over 32 MB.
- Configuration: start folders, F-key actions.
- Command palette: recently used first, entries that are not in a menu.
- Run command: a drop-down list of the history (Up/Down only today).
- Archives: tar / tar.gz / 7z (Apache Commons Compress, Apache-2.0, needs a `THIRD-PARTY-NOTICES.md` entry),
  writing into archives, free space inside an archive, the Unix exec bit on copy-out when the zip has none.

## Command line

- `diff --open`: continue in the TUI sync view.
- `diff`: symlinks and unreadable entries are skipped silently.
- `diff`/`sync` ignore rules: `.gitignore` of the parent folders of the roots, a `--skip-vcs` shorthand for
  `-x .git`, global and `info/exclude` files, `[[:UPPER:]]` classes in case-insensitive mode.
- Summary lines use the default locale (`0,0 s`); the dry-run JSON `skipped` count includes conflicts.
- No `older` conflict policy (the engine has none).
- Ctrl+C on the native exe ends the process at once (the progress line is not erased); Ctrl+C during a copy on
  the native exe is untested.

## Tests and verification

- Not covered by automated tests: the Lanterna UI, `TextBuffer`, the viewers, `BinaryCompare`/`TextCompare`,
  `NativeCopy`, and the mouse path of the menu bar.
- Unverified: Shift+Enter on the Windows console; Shift+F4 and mouse click/drag in the editor in Windows Terminal;
  mpv keyboard handling after Lanterna releases the console; the light and contrast themes in the compare/sync
  views; empty-command (interactive) shells on Linux; the 4 (HDD) and 6 (network share) thread counts, which are
  educated guesses.

## Known risks

- Windows junctions inside copy trees are untested for copy (delete and sync skip them).
- The Cancel button has focus in the progress window, so an accidental Enter cancels the job.
- The menu bar has no hover highlight (the terminal mouse mode reports no motion events).
- Lanterna 3.2.0-alpha1 is an alpha release.
