# Trivial Commander — a two-panel file manager

<img width="1200" height="787" alt="image" src="https://github.com/user-attachments/assets/65b04047-124b-4475-968a-e69ed1192976" />


## Running

```bash
mvn package                     # builds target/tcm-1.0.0.jar (shade, all dependencies inside)
java -jar target/tcm-1.0.0.jar
```

Or in an IDE: run `tcm.Main`. The full key list is `F1` in the program.

### Native image (GraalVM)

```bash
mvn -Pnative package   # ~3 min, target/tcm + lib*.so (AWT)
target/tcm
```

Also packs `target/tcm-1.0.0-linux-amd64.tar.xz` and `target/tcm-1.0.0-linux-amd64.AppImage`
(the AppImage needs `mksquashfs` from squashfs-tools; the AppImage runtime is downloaded once into `~/.cache/appimage`).
On every launch the AppImage puts itself into the application menu: it writes `~/.local/share/applications/tcm.desktop`
and its icon, and rewrites the entry when the AppImage file has moved. It leaves the menu to Gear Lever, AppImageLauncher,
appimaged or a system package when one of them has an entry for TCM, keeps an entry hidden in a menu editor hidden,
and never touches a `tcm.desktop` it did not write.

#### Windows

Needs Visual Studio 2022 (17.6+) with the "Desktop development with C++" workload. Run it from an
"x64 Native Tools Command Prompt for VS 2022" (or after `vcvars64.bat`); the `windows` profile is activated automatically:

```bat
mvn -Pnative package
target\tcm.exe
```

Packs `target/tcm-1.0.0-windows-amd64.zip`: `tcm.exe`, the JDK DLLs it loads (AWT, fonts, JPEG, color management)
and `lib/fontconfig.bfc` (Windows has no system fontconfig, without it the UI does not start) must stay together.
The Linux archives are skipped on Windows.

## Features

- **Two panels** with a Name / Ext / Size / Date / Attr table, sorting by clicking the header or `Ctrl+F3..F7`,
  brief/full view (`Ctrl+F1`/`Ctrl+F2`), thumbnails (`Ctrl+Shift+F1`), hidden files (`Ctrl+H`),
  filters (`Ctrl+F10` all, `Ctrl+F11` programs, `Ctrl+F12` custom mask), quick filter/search (`Ctrl+S`, `Ctrl+Alt+letter`).
  A name cut off by the column width shows in full in a tooltip after the mouse rests on it for 1 s.
  The path above the table is a breadcrumb bar: every directory of the path (archives and directories inside them included)
  is underlined under the mouse and opens on a click, with the cursor on the part of the path you came from;
  a path too long for the panel loses its leading parts. Double click on the rest of the bar edits the path.
  Each tab remembers the scroll position of the directories (and archives) it leaves: coming back up restores it,
  scrolling only if the directory just left is out of view; without a remembered position that directory is put in the middle of the panel.
  File icons are the ones the system associates with the type: on Linux from the current icon theme by MIME type
  (shared-mime-info, PNG and SVG icons; another theme — `-Dtcm.iconTheme=breeze`), on Windows from the shell,
  on macOS from Launch Services (`java -jar` only: the jar manifest exports `sun.lwawt.macosx`). On Linux and macOS
  the icons of `.exe`/`.dll`, `.ico`/`.cur`/`.icns` and `.AppImage` files are read from the files (an AppImage is not run: its icon
  is taken from the squashfs image inside — gzip, xz or zstd). Directories and files without a system icon keep the built-in icons.
- **Tabs** (`Ctrl+T`/`Ctrl+Shift+T` — new/background, `Ctrl+W`/`Ctrl+Shift+W` — close/others, `Ctrl+Tab`, list `Ctrl+Shift+A`),
  double click on empty space right of the tabs duplicates the current one, middle button (wheel) on a tab closes it; tabs in one row (at most 200 px wide,
  full path in the tooltip), the strip scrolls with the mouse wheel,
  history `Alt+←/→`, `Alt+↓`, favorite directories `Ctrl+D`, directory tree `Alt+F10`, swap panels `Ctrl+U`/`Ctrl+Shift+U`,
  the other panel's directory into the active one — `Ctrl+=` (Commands menu and a toolbar button).
- **Auto-refresh**: panels are re-read when focus returns to the window and at most once per second on changes
  in the local directories open in them (WatchService) — e.g. you can see a downloading file grow.
  Cursor, marks, scroll position and in-panel rename are not disturbed; search results are not re-read.
- **File operations**: copy (`F5`, `Shift+F5` — into the same directory), move (`F6`), in-panel rename (`Shift+F6` or `F2` on a name),
  multi-rename (`Ctrl+M`), directory (`F7`, `Shift+F7` — in the other panel), delete to trash/permanently (`F8`/`Shift+F8`, `Del`/`Shift+Del`),
  symbolic links (`Ctrl+Shift+F5`), file clipboard (`Ctrl+C`/`Ctrl+X`/`Ctrl+V`, compatible with the system one).
  Operation queue (`F2 Queue` in the dialog), progress with Pause / Background / Cancel, conflicts Overwrite / All / Skip / Skip all
  (in the overwrite dialog `F3`/`Shift+F3` — view, `Ctrl+F3` — compare).
- **ZIP and JAR as directories**: enter with Enter, copy/move/delete/rename inside the archive (the archive is rewritten
  atomically), packing (`Alt+F5`, `Alt+Shift+F5` — with deletion), unpacking (`Alt+F9`/`Alt+F6`), CRC test (`Alt+Shift+F9`).
  Packing also creates TAR, TAR.GZ, TAR.BZ2 and TAR.XZ (packer buttons in the dialog or the extension of the name); such an archive
  cannot be appended to, so an existing one is replaced after confirmation.
  Names without the UTF-8 flag are read as DOS: CP866 if they are Russian, otherwise CP437.
- **TAR, TAR.GZ/TGZ, TAR.BZ2, TAR.XZ, RAR (RAR4/RAR5), 7z, ISO, AppImage, squashfs, Chromium/Electron `.pak`, Apple `.dmg`,
  `.deb`, `.rpm`, `.chm`, `.msi`, `.cab` and plain `.gz`, `.bz2`, `.xz`** — read only:
  browsing contents, viewing files, copying from the archive, unpacking, testing. `.pak` resources have only numeric IDs, so they are
  named by ID plus an extension guessed from the content (`1234.png`, `.gz`, `.br`, `.txt`...); aliases are shown as copies. ISO 9660 names come from Rock Ridge (long names, permissions), otherwise
  from Joliet; UDF-only images are not supported. An AppImage is browsed without running it: `Enter` on an executable `.AppImage`
  runs it, `Ctrl+PgDn` opens it as an archive. Symbolic links in ISO/squashfs/DMG are shown as files.
  A `.dmg` (UDIF; zlib, bzip2, ADC, LZMA chunks — not LZFSE) shows the files of its APFS volumes (unencrypted; files compressed by
  macOS with zlib are unpacked); an image with HFS+ or another file system lists its partitions as raw `.img` files. A compressed TAR is unpacked into a temporary `.tar` once
  (as in TC).
  A `.deb` is shown as `dpkg-deb -R` unpacks it: the files of `data.tar` at the root, the control files in `DEBIAN`
  (gzip, xz, zstd, bzip2, lzma or none); an `.rpm` shows its cpio payload (gzip, bzip2, xz, lzma, zstd). Symbolic links in them are
  empty files, as in TAR. A `.chm` lists its pages and images (LZX), hiding the internal `::` entries. An `.msi` shows the files it
  installs in the tree of its Directory table — standard folders under their names (`ProgramFiles64Folder`, `System64Folder`...) —
  unpacked from its embedded cabinets or `.cab` files next to it; any other OLE2 compound file (`.doc`, `.xls` by `Ctrl+PgDn`) lists
  its raw streams. Cabinets: stored, MSZIP and LZX folders (not Quantum), no multi-cabinet sets.
  A plain `.gz`, `.bz2` or `.xz` (not a TAR) holds one file named as the archive without the extension (`notes.txt.gz` →
  `notes.txt`); found by signature under another extension, a gzip takes the name stored in its header. The size comes from
  the gzip trailer (modulo 4 GiB) or the xz index; bzip2 does not store it, so a `.bz2` is decompressed once on opening to count it.
  `Enter` on a `.chm` or `.msi` opens it with the associated program (help viewer, installer); without one, and with `Ctrl+PgDn`,
  it is browsed as an archive.
  `Ctrl+PgDn` opens any file as an archive: the format is detected by signature (`.docx`, `.apk`, `.cbr`...).
  `Enter`/double click on a file without an associated program does the same.
  Entry names with `..` are trimmed, so unpacking cannot escape the target directory.
- **Encrypted archives**: ZIP (WinZip AES and the traditional ZipCrypto), RAR (encrypted files and encrypted headers)
  and 7z (encrypted contents and headers). The password is asked in a dialog when the archive is entered (encrypted headers)
  or a file is viewed/unpacked; a wrong one is asked again. An accepted password is remembered for the archive until exit.
  An encrypted ZIP is read-only.
- **Archive attributes**: Unix permissions (`rwxr-xr-x`) or DOS flags (`rahs`) stored in ZIP, TAR and RAR are shown in the Attr
  column and set on unpacked files (Unix permissions on Windows and DOS flags on Unix — only "read-only").
- **Archives inside archives** open with Enter/`Ctrl+PgDn` as well (read only): the nested archive is unpacked into a temporary file,
  the path is shown as `outer.zip!/dir/inner.tar.gz!/...`, and the temporary file is deleted when the tab leaves the archive or is closed.
- **Search** (`Alt+F7`): name mask or RegEx, text (substring/RegEx, case, "NOT containing"), depth, archives,
  size/date/type on the Advanced tab, search in results (`F2`), result history (`Alt+←/→`); "Feed to listbox", "Go to file".
  Flat list of subdirectories — `Ctrl+B`.
- **Viewer** (`F3`; on a directory `F3` enters it, as Enter does; a file already shown in a viewer brings that window forward): text (UTF-8/BOM, fallback ISO-8859-1; `A`/`S` — 1251/866), binary, hex, HTML;
  search `F7`/`F3`, switching between files `N`/`P`. `Ctrl+End` checks the file size first and re-reads a file that has changed
  since it was loaded (a log being written), then goes to the end. Quick view `Ctrl+Q` (in the other panel) and `Ctrl+Shift+Q` (window).
  Menus as in TC: File (Save as, Reload `F2`, Close), Edit (Copy, Select all, Find `F7`, Find next `F5`),
  Options (modes `1`–`7`, Markdown `8`, JSON `9`, ANSI `A`, ASCII `S`, other encoding, proportional font `V`, wrap `W`,
  cursor `F6`),
  Image, Help (`F1`). Edit and Options are unavailable for images, Image — for text.
  Images: PNG, JPEG, GIF, BMP, WBMP, ICO/CUR and ICNS (the largest image of the icon; JPEG 2000 elements of ICNS are skipped).
  Markdown (CommonMark and GFM tables) is shown rendered, `.md` — right away; raw HTML is shown as text,
  images from the network are not loaded, http/https links open in the browser.
  JSON `9` is shown indented (pretty-print); parsing is lenient, so truncated or broken JSON is formatted too.
  `.json` files open in JSON mode right away, `.html`/`.htm` — in HTML mode `5`.
  The mouse wheel rolled with the wheel pressed scrolls 5 times more lines.
  Reading and converting the file (decoding, hex, markup) runs in the background: the main window does not wait, and a closed viewer
  (and editor) window cancels loading.
  Line numbers on the left; a click on a number selects the whole line, dragging — several lines, `Shift`+click extends the selection.
  Lines wider than ~24000 px (about 3000 characters at the normal font size) are cut into pieces in text modes, a piece has no
  line number of its own: with XRender on Linux glyphs further than 32767 px from the line start are drawn over its beginning,
  and a single multi-megabyte line would lock up the window for a long time.
- **Images** in the Viewer:
  - mouse wheel — zoom around the point under the cursor; left-button drag — scroll;
  - `←`/`→` — neighboring image in the directory;
  - `Ctrl+C` — copy the image to the clipboard;
  - **Image** menu (in the menu bar and on right click): Zoom (1:1, Fit, In, Out), Flip (Horizontal `H`, Vertical `Shift+H`),
    Rotate (CW `R`, CCW `Shift+R`).
- **Mouse in the panel**:
  - right-button press marks a file, right-button drag marks several files; holding the right button for 1 s — context menu;
  - holding the right button on empty space — directory menu: New (Folder, File), Terminal, Explore (file manager), Properties;
  - dragging files outside the window — into another program (copy; only files from disk, not from an archive);
  - clicking the `F8 Delete` button with `Shift` — delete bypassing the trash;
- **Change attributes** (file context menu, Files menu): rwx permissions for owner/group/others (DOS attributes on Windows),
  modification date, processing directory contents. An untouched checkbox does not change the attribute; a gray one means the selected files have different values.
- The **Terminal** toolbar button opens a terminal in the active panel's directory.
  - left-button drag to the other panel (or onto a directory) opens the copy dialog; the directory where the files will land
    is outlined with a frame; while the right button is pressed during the drag — move, released — copy again;
  - a marked file drags all marked ones along, an unmarked one — only itself;
  - files dropped from another program (file manager, desktop) — copy dialog into the directory under the mouse
    or into the panel's current directory (copy only);
  - double click on the panel divider puts it exactly in the middle; in a window up to 1200 px wide it always stays in the middle;
  - the `\` `..` `~` buttons above the panel — root, up, home.
- **Directory sizes**: `Space` on a directory, `Alt+Shift+Enter` — all directories of the current panel in turn (also a Files menu item).
  Alt+Shift (Ctrl+Shift) shortcuts work even when the layout is switched with these keys (XKB `grp:alt_shift_toggle`):
  the modifier eaten by the switch is restored from the presses of the keys themselves (`LayoutSwitchKeys`).
- **Editor** (`F4`, `Shift+F4` — new file, the name under the cursor is prefilled in the prompt): saving with `F2`/`Ctrl+S`
  in the original encoding, including into an archive; undo, search, `Ctrl+G`. Line numbers as in the viewer;
  a file with too wide lines (see the viewer) is opened with word wrap. `End` on a line with trailing whitespace
  alternates between the end of the line and the end of its text. `F4` on a file that is already being edited brings that editor forward.
  Menus File (Save, Save as, Reload, Close) and Edit (Copy, Select all, Find, Find next).
- **Content comparison** (`Ctrl+Shift+F3`): two files side by side, LCS diff, differences in red, `Alt+↑/↓`, synchronized scrolling,
  two-line panel `F9`. Comparing panel file lists — `Shift+F2`.
- **Command line**: `cd`, `md`, `rd` are built in, everything else runs in a console window (stdout/stderr, stdin, Stop) in the active panel's directory;
  letters typed in the panel go to the line, the line is a history drop-down (arrow on the right, `Alt+F8`, `Ctrl+↓`), `Ctrl+E` — previous command,
  `Ctrl+Enter`/`Ctrl+P` — name/path into the line.
- **Themes** FlatLaf (Configuration): Light, Dark, IntelliJ, Darcula. Window, tab and history state is saved (`java.util.prefs`) on exit (and on `Cmd+Q` on macOS) and every 30 s.
