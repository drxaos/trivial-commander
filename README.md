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
GRAALVM_HOME=../graalvm-25.4.4.1.1 mvn -Pnative package   # ~3 min, target/tcm + lib*.so (AWT)
target/tcm
```

## Features

- **Two panels** with a Name / Ext / Size / Date / Attr table, sorting by clicking the header or `Ctrl+F3..F7`,
  brief/full view (`Ctrl+F1`/`Ctrl+F2`), thumbnails (`Ctrl+Shift+F1`), hidden files (`Ctrl+H`),
  filters (`Ctrl+F10` all, `Ctrl+F11` programs, `Ctrl+F12` custom mask), quick filter/search (`Ctrl+S`, `Ctrl+Alt+letter`).
- **Tabs** (`Ctrl+T`/`Ctrl+Shift+T` — new/background, `Ctrl+W`/`Ctrl+Shift+W` — close/others, `Ctrl+Tab`, list `Ctrl+Shift+A`),
  double click on empty space right of the tabs duplicates the current one, middle button (wheel) on a tab closes it; tabs in one row (at most 200 px wide,
  full path in the tooltip), the strip scrolls with the mouse wheel,
  history `Alt+←/→`, `Alt+↓`, favorite directories `Ctrl+D`, directory tree `Alt+F10`, swap panels `Ctrl+U`/`Ctrl+Shift+U`,
  the other panel's directory into the active one — `Ctrl+=` (Commands menu and a toolbar button).
- **Auto-refresh**: panels are re-read when focus returns to the window and at most once per second on changes
  in the local directories open in them (WatchService) — e.g. you can see a downloading file grow.
  Cursor, marks, scroll position and in-panel rename are not disturbed; search results are not re-read.
- **File operations**: copy (`F5`, `Shift+F5` — into the same directory), move (`F6`), in-panel rename (`Shift+F6`),
  multi-rename (`Ctrl+M`), directory (`F7`, `Shift+F7` — in the other panel), delete to trash/permanently (`F8`/`Shift+F8`, `Del`/`Shift+Del`),
  symbolic links (`Ctrl+Shift+F5`), file clipboard (`Ctrl+C`/`Ctrl+X`/`Ctrl+V`, compatible with the system one).
  Operation queue (`F2 Queue` in the dialog), progress with Pause / Background / Cancel, conflicts Overwrite / All / Skip / Skip all
  (in the overwrite dialog `F3`/`Shift+F3` — view, `Ctrl+F3` — compare).
- **ZIP and JAR as directories**: enter with Enter, copy/move/delete/rename inside the archive (the archive is rewritten
  atomically), packing (`Alt+F5`, `Alt+Shift+F5` — with deletion), unpacking (`Alt+F9`/`Alt+F6`), CRC test (`Alt+Shift+F9`).
  Names without the UTF-8 flag are read as DOS: CP866 if they are Russian, otherwise CP437.
- **TAR, TAR.GZ/TGZ, TAR.BZ2, TAR.XZ, RAR (RAR4/RAR5)** — read only: browsing contents, viewing files,
  copying from the archive, unpacking, testing. A compressed TAR is unpacked into a temporary `.tar` once
  (as in TC). `Ctrl+PgDn` opens any file as an archive: the format is detected by signature (`.docx`, `.apk`, `.cbr`...).
  `Enter`/double click on a file without an associated program does the same.
  Entry names with `..` are trimmed, so unpacking cannot escape the target directory.
- **Search** (`Alt+F7`): name mask or RegEx, text (substring/RegEx, case, "NOT containing"), depth, archives,
  size/date/type on the Advanced tab, search in results (`F2`), result history (`Alt+←/→`); "Feed to listbox", "Go to file".
  Flat list of subdirectories — `Ctrl+B`.
- **Viewer** (`F3`): text (UTF-8/BOM, fallback ISO-8859-1; `A`/`S` — 1251/866), binary, hex, HTML;
  search `F7`/`F3`, switching between files `N`/`P`. Quick view `Ctrl+Q` (in the other panel) and `Ctrl+Shift+Q` (window).
  Menus as in TC: File (Save as, Reload `F2`, Close), Edit (Copy, Select all, Find `F7`, Find next `F5`),
  Options (modes `1`–`7`, Markdown `8`, JSON `9`, ANSI `A`, ASCII `S`, other encoding, proportional font `V`, wrap `W`,
  cursor `F6`),
  Image, Help (`F1`). Edit and Options are unavailable for images, Image — for text.
  Markdown (CommonMark and GFM tables) is shown rendered, `.md` — right away; raw HTML is shown as text,
  images from the network are not loaded, http/https links open in the browser.
  JSON `9` is shown indented (pretty-print); parsing is lenient, so truncated or broken JSON is formatted too.
  The mouse wheel rolled with the wheel pressed scrolls 5 times more lines.
  Reading and converting the file (decoding, hex, markup) runs in the background: the main window does not wait, and a closed viewer
  (and editor) window cancels loading.
  Lines longer than 10000 characters are wrapped in text modes: otherwise a single multi-megabyte line would lock up the window for a long time.
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
  in the original encoding, including into an archive; undo, search, `Ctrl+G`.
  Menus File (Save, Save as, Reload, Close) and Edit (Copy, Select all, Find, Find next).
- **Content comparison** (`Ctrl+Shift+F3`): two files side by side, LCS diff, differences in red, `Alt+↑/↓`, synchronized scrolling,
  two-line panel `F9`. Comparing panel file lists — `Shift+F2`.
- **Command line**: `cd`, `md`, `rd` are built in, everything else runs in a console window (stdout/stderr, stdin, Stop) in the active panel's directory;
  letters typed in the panel go to the line, the line is a history drop-down (arrow on the right, `Alt+F8`, `Ctrl+↓`), `Ctrl+E` — previous command,
  `Ctrl+Enter`/`Ctrl+P` — name/path into the line.
- **Themes** FlatLaf (Configuration): Light, Dark, IntelliJ, Darcula. Window, tab and history state is saved (`java.util.prefs`) on exit (and on `Cmd+Q` on macOS) and every 30 s.
