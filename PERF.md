# Performance

Cold-cache comparison of tuifileman with robocopy, FastCopy and Total Commander
(2026-10-11). The numbers are a snapshot on one machine, not a general benchmark.

## Setup

- Machine: Windows, 22 logical CPUs, 32 GB RAM, two NVMe SSDs (C:, D:).
- Test data: a large real-world source repository, a little over 200 thousand
  files in about 27 thousand folders, roughly 16 GB (mostly small files). It was
  only ever read, never written to.
- Tools: tuifileman `CopyJob` / `DeleteJob` / `DirCompare` (the same code the UI
  runs), robocopy, FastCopy (`fcp.exe`, the user's own settings: no verify, no
  ACL, full speed), Total Commander 11.03 with default settings.

## Results (D: -> C:, cold cache, run + disk flush)

Copy to an empty target (seconds, round 1 / round 2):

| Tool | Round 1 | Round 2 |
|---|---|---|
| **tuifileman** | 264.9 | **244.4** |
| robocopy /MT:32 | 239.5 | 284.6 |
| robocopy /MT:8 | 267.4 | 308.3 |
| FastCopy (Diff mode) | 494.3 | 508.0 |
| Total Commander | 464.0 (single run) | |

Delete of the copied tree (seconds, round 1 / round 2):

| Tool | Round 1 | Round 2 |
|---|---|---|
| **tuifileman** | **36.1** | 52.0 |
| FastCopy (Delete) | 124.8 | 61.9 |
| Total Commander | 96.7 (single run) | |
| cmd `rd /s /q` | 179.4 | 153.7 |
| robocopy /MIR from an empty folder, /MT:32 | 193.8 | 354.3 |

Directory sync preparation: scan of the repo against a copy where about 10% of
the files were changed (content flips with the same size and time, time-only
changes, size changes, deleted and extra files). Run time only, seconds,
round 1 / round 2:

| Tool | Round 1 | Round 2 |
|---|---|---|
| tuifileman, size + time | 4.2 | 2.7 |
| robocopy `/L /MIR /MT:32` (size + time) | 6.9 | 10.0 |
| **tuifileman, by content** | 46.0 | 39.2 |
| Total Commander, by content | 269.2 (single run) | |

Copy, same SSD to a different folder (D: -> D:\temp, a second tree of about
150 thousand files, 13.5 GB, no elevation, no flush): tuifileman 45.4 / 45.9 s, robocopy /MT:32
58.1 / 56.6 s. The Windows Search indexer used 0.0 s of CPU during these runs, so
it is not what makes C: slow.

Before / after of the parallel engine itself (first repo, D: -> D:, measured
during development): planning 14.5 s -> 1.1 s, delete 65 s -> 15.5 s, copy
96 s -> 65-69 s (big files copied alongside the small-file pool).

## Method

- Every run starts with the target wiped, the volumes flushed and the cache
  dropped with RAMMap (`RAMMap64 -Ew -Es -Em -Et`: working sets, system working
  sets, modified and standby lists). The measured time includes a final
  `Write-VolumeCache` flush of C:.
- After each copy the result is verified: file count, folder count and total
  bytes must equal the source.
- Variants run in random order, two rounds.
- The harness (elevated PowerShell script plus a small Java class that calls
  the real jobs) was throwaway and is not in the repository. The raw logs were
  kept with the session notes.
- Without elevation (the D: -> D: test) the cache cannot be dropped. Instead
  about 35 GB of unrelated files were read before each run to push the source
  out of the file cache.

### Measuring Total Commander automatically

Total Commander has no command line for copy, delete or sync, so it cannot be
timed like the others. The controller script does everything except the
operation itself:

1. It wipes the target, then asks the user to open the F5 (or F8, or the
   Synchronize dirs) dialog in Total Commander without confirming.
2. On Enter it drops the cache and beeps. The user confirms in Total Commander.
3. It polls (every 50 ms) the top-level windows of the Total Commander process
   (`EnumWindows` by process id). While a copy or delete runs, the progress
   dialog is a window of class `TDLG2FILEACTIONMIN`. Start is the moment it
   appears, end is when it has been gone for 0.5 s. Then it flushes C: and
   verifies the file count.
4. For the sync compare no such window was identified. The end is 4 s without
   I/O activity of the process (`Win32_Process` operation counters), plus an
   optional keypress by the user when the result list shows. Both agreed within
   0.7 s (269.2 s vs 269.9 s).

The first attempt used only the I/O counters for copy as well. It ended the
timing too early (after 10.9 s of a 464 s copy) because the counters go quiet
during big-file copies. Window detection is both exact and free of overhead; a
Total Commander operation log would only give whole-second stamps.

## FreeFileSync 14.12 (2026-10-14)

Cold cache, D: -> D:\tmp, same machine as above. Native `tuifileman.exe` (GraalVM CE 25, process start
included) against FreeFileSync 14.12 free edition run as a batch job from the command line. Seconds, one value
per round, mean in bold:

| Scenario | tuifileman | FreeFileSync | Ratio |
|---|---|---|---|
| Compare two repositories, size + time (2.2 / 1.7 / 1.9) | **1.9** | **12.2** (12.7 / 12.0 / 12.0) | 6.4x |
| Copy 16.5 GB, 210,945 files, 27,418 folders (69.5 / 73.6) | **71.6** | **681** (686.1 / 675.8) | 9.5x |
| Delete that copy (16.0 / 15.8) | **15.9** | **91.7** (91.2 / 92.2) | 5.8x |

- Compare: two large real-world source repositories, 230,336 files, 43,733 folders, 29.4 GB against
  210,945 files, 27,418 folders, 15.4 GB; both only read. Copy and delete: the second repository to `D:\tmp`.
- FreeFileSync has no compare-only command, so the batch job compares with every sync direction set to "none".
  Copy is a Mirror job into an empty folder, delete a Mirror job from an empty folder. Symbolic links excluded
  (as in tuifileman), file time tolerance 2 s (the default of both).
- FreeFileSync runs with its defaults except a GlobalSettings.xml that switches off the lock file in the
  folders (so the repositories are never written to) and the confirmation/warning dialogs. Its default
  fail-safe copy (temp file, then rename) stays on; copy verification stays off. It copies with a single file
  operation at a time: parallel file copy is a Donation/Business Edition feature and was not tested.
- FreeFileSync's delete run ends with exit code 1: the log carries the "folders differ significantly" warning
  (source folder empty); everything was deleted and verified.
- Method as in the Method section above: RAMMap (`-Ew -Es -Em -Et`) before every timed step, and the measured copy and
  delete times include flushing the C: and D: volumes (`FlushFileBuffers`). Every copy was verified (file,
  folder and byte totals equal the source) and every delete was verified. Variants ran in random order.
- A first attempt used `Write-VolumeCache`, which failed in the elevated session without being noticed by the
  harness; that run was discarded and the whole benchmark repeated with the flush done directly and any failure
  stopping the run (the discarded run gave the same picture: FreeFileSync copy 639 s, tuifileman 68 s).

## Sync copies and deletes (2026-10-15)

`SyncJob` copied the planned files one at a time; it now uses the copy engine's scheduler (`CopyScheduler`:
folders by depth level, small files on the pool, big ones alongside). Warm cache, no flush, D: -> D:, 20,000
files of 4 KB in 210 folders plus four 48 MB files. Seconds, three rounds each:

| Scenario | Sequential (before) | Parallel (after) | `CopyJob`, same tree |
|---|---|---|---|
| Mirror into an empty folder | 44.4 / 45.7 / 45.1 | 10.9 / 11.2 / 10.7 | 4.6 / 4.2 / 4.7 |
| 2,000 changed files (time only) | 5.0 / 4.6 / 5.4 | 0.8 / 0.8 / 1.3 | |

The rest of the gap to `CopyJob` is the fail-safe temp file + rename per file: writing straight to the
target took 4.2 / 4.0 s. `CopyFileEx` instead of the channel copy, skipping the per-file parent check and
skipping the cleanup `deleteIfExists` changed nothing measurable (10.6-11.6 s).

Deletes of a sync ran one item at a time as well; they now run by depth level, deepest first, each level in
parallel (not `TreeDeleter`: a sync deletes only its planned items, so ignored entries inside a folder stay).
Mirror from an empty folder onto the 20,000 small files and 210 folders, same machine and conditions:

| Scenario | Sequential (before) | Parallel (after) | `DeleteJob`, same tree |
|---|---|---|---|
| Sync delete of 20,210 items | 4.72 / 4.77 / 4.26 | 0.89 / 0.86 / 0.89 | 0.86 / 0.81 / 0.90 |

## Directory listing

The panel listing (`DirectoryListing`) takes each entry's attributes from where
they are cheapest, which differs by platform (20,001 entries, warm cache,
2026-10-14):

| Platform | `Files.list` + one attribute read per entry | depth-1 `walkFileTree` |
|---|---|---|
| Windows NTFS | ~500 ms | ~15 ms |
| WSL ext4 (`/tmp`) | ~22 ms | ~36 ms |
| WSL drvfs (`/mnt/c`) | ~6.5 s | ~14 s |

On Windows the walk hands over the attributes the directory enumeration already
returned (`WindowsFileAttributes`, which are `DosFileAttributes`), so there is
no call per entry. On Linux the walk does its own `lstat` and yields basic
attributes only, so the permission column would need a second read; there one
`PosixFileAttributes` read per entry is the cheaper path. A symbolic link as the
listed directory itself is not opened by the walk; that case uses the
`Files.list` path on every platform. Sizes from the Windows enumeration can lag
for a file another process is still writing, as in Explorer.

## Caveats

- C: is about 4x slower than D: for this workload for every tool, probably the
  filesystem filter stack or the write path of that volume. Absolute numbers
  from C: say little; the ranking is the interesting part.
- Rounds vary a lot for some tools (robocopy delete 194 -> 354 s, FastCopy
  delete 125 -> 62 s). Two rounds are not a statistic.
- Total Commander ran once per scenario. Its copy and delete include a
  confirmation dialog (long path names, delete confirmation), about 2 s.
- The window timing ends when the dialog closes; Total Commander may do a little
  work after that.
- The sync times compare effort, not results: the status counts of Total
  Commander were not checked against ours. robocopy and FastCopy have no
  content compare, so robocopy `/L` (size + time only) is only a reference.
- The flush time in the sync runs mostly reflects dirty data left by the
  preparation step, so only the run time is shown.
- FastCopy uses the user's own settings; other settings may change its result.
- Thread counts (32 on SSD, 4 on HDD, 6 on network shares) were tuned on this
  NVMe machine only. HDD and network values are untested guesses.
