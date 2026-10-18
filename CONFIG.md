# Settings reference

Everything tuifileman reads from outside its own code, with the default of each setting. The same defaults, as
comments, are what the program writes into a new `config.ini` on its first start.

## The ini file

| | |
|---|---|
| Windows | `%APPDATA%\tuifileman\config.ini` |
| Linux, or when `APPDATA` is not set | `~/.tuifileman/config.ini` |

Plain INI: `[section]` headers, `key = value` lines, `#` or `;` comments. Keys are not case sensitive. Unknown keys
are ignored and a bad line or value only produces a warning (the first one is shown in the status line at start),
the default is used instead, so a typo never stops the program. Changes apply the next time the program starts. Deleting a line
gives back its default. A value in double quotes loses the quotes, unless it is a command line.

The program only ever edits single lines: the choices made in the Options menu and in the Run command dialog
(`theme.name`, `run.shell`, `run.keep_open`) are written back into your file with all comments kept.

The file is created once, from the defaults of that version. A file written by an older version does not get
the keys added later (they work with their defaults). To get the current commented file, rename yours and start
the program once, then move your values over.

Booleans accept `true`/`false`, `yes`/`no`, `on`/`off`, `1`/`0`.

| Key | Default | Meaning |
|---|---|---|
| **`[theme]`** | | |
| `name` | `dark` | Colour scheme: `dark`, `light`, `contrast` (high contrast) or `mc` (Midnight Commander look). An unknown name falls back to `dark` with a warning. Also switchable in the Options menu, which saves the choice. The command line takes `--theme` instead. |
| **`[run]`** | | |
| `shell` | `cmd` (Windows), `bash` (Linux) | Shell the Run command (`Ctrl+O`) starts with, as last chosen in its dialog. Built in: `cmd`, `powershell`, `pwsh`, `wsl` (the last three when found on `PATH`) on Windows; `bash`, your login shell and `sh` on Linux, plus `cmd`, `powershell` and `pwsh` through WSL interop. |
| `keep_open` | `true` | `true`: the window stays open after the command (`cmd /k`, `-NoExit`, `exec bash`); on Linux the program waits for Enter. Remembered from the dialog. |
| `shell.NAME` | none | Adds a shell called `NAME` to the dialog; the value is the command line to start. `{cmd}` is replaced by the typed command (quotes in it become `\"`), without `{cmd}` the command is appended. Example: `shell.gitbash = "C:\Program Files\Git\bin\bash.exe" -c "{cmd}; exec bash"` |
| **`[viewer]`** | | |
| `line_numbers` | `true` | Line number gutter in the F3 text view (`L` toggles it while viewing). |
| `wrap` | `true` | Wrap long lines in the F3 text view instead of panning sideways (`W` toggles it while viewing). |
| **`[image]`** | | |
| `chafa` | `chafa` | External renderer for image preview in F3 (png, jpg, gif, webp, svg, tiff, qoi): a name on `PATH` or a full path. Empty disables image preview (F3 then shows an info page). |
| `symbols` | `half+quad+space` | Character cells used for the picture: any `chafa --symbols` value, for example `block`, `half`, `ascii`. |
| `format` | `sixel` | `sixel`: real pixels, for sixel terminals (Windows Terminal 1.22+); `symbols`: coloured block characters, works in every terminal. `S` toggles it while viewing. |
| **`[pdf]`** | | |
| `pdfinfo` | `pdfinfo` | Poppler tool for the details page. A name on `PATH` or a full path. |
| `pdftoppm` | `pdftoppm` | Poppler tool for the page picture (shown through the `[image]` settings). |
| `pdftotext` | `pdftotext` | Poppler tool for the text view (`Tab`). |
| **`[video]`** | | |
| `ffprobe` | `ffprobe` | Details of video and audio files. |
| `ffmpeg` | `ffmpeg` | Preview frame (`Space`/`PgUp` step through frames). |
| `mpv` | `mpv` | Player (`P` plays the file inside the terminal). |
| `vo` | `sixel` | mpv video output: `sixel` (pixels inside the terminal; needs an mpv build with sixel, otherwise `tct` is used), `tct` (coloured half blocks, works with any build) or `gpu` (a separate window, full quality). |
| `cell_size` | `10x20` | Terminal cell size in pixels, `WIDTHxHEIGHT`, used to size the sixel picture. |
| `args` | empty | Extra mpv arguments, space separated, for example `--vo-sixel-dither=none`. |
| **`[editor]`** | | |
| `tab_size` | `4` | Width of a tab in the F4 editor; limited to 1..16. |
| `use_tabs` | `false` | `true`: the Tab key inserts a tab character, `false`: spaces. |
| `auto_indent` | `true` | Enter keeps the indentation of the current line. |
| `line_numbers` | `true` | Line number gutter in the editor (`Ctrl+L` toggles it). |
| **`[sync]`** | | |
| `time_tolerance` | `2s` | Two files whose modified times differ by at most this count as having the same time: directory sync (`Ctrl+F9`), copy/move "overwrite older" and the `diff`/`sync`/`copy`/`move` commands (`--time-tolerance` overrides it there). A whole number with a unit `s`, `m` or `h` (`2s`, `90s`, `5m`, `1h`); `0` means the times must be exactly equal. |

## Not in the ini file

Command-line options override the file for that run: `--theme`, `--color auto|always|never`,
`--progress auto|bar|lines|json|none`, `--time-tolerance`, `-t/--threads`. `tuifileman <command> --help` lists all
options of a command.

| Where | Name | Effect |
|---|---|---|
| Environment | `NO_COLOR` | Any non-empty value turns colours off in the command line modes (same as `--color never`). |
| Environment | `TFM_TRACE` | Set to anything (for example `1`) to print a startup timeline on stderr; for finding where a slow start goes. Every command then writes those lines to stderr, so unset it for scripts. |
| Java property | `-Dtfm.threads=N` | Number of threads for parallel copy, delete and scans, instead of the automatic choice per drive type (`--threads` sets the same). |
| Environment | `PATH` | Where the external tools above (`chafa`, Poppler, FFmpeg, mpv, shells) are looked up when a setting holds only a name. |

Other files the program keeps next to `config.ini`: `bookmarks.txt` (`Ctrl+D`) and `run-history.txt` (the Run
command dialog). On Windows the JNA library is unpacked to `%LOCALAPPDATA%\tuifileman\native\jnidispatch.dll` at
first start.
