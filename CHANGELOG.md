# Changelog

## Unreleased

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
