package tcm.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

/**
 * Start Menu shortcut for Windows native builds (tcm.exe).
 * On launch, it ensures that exactly one shortcut exists in the user's Start Menu
 * (%APPDATA%\Microsoft\Windows\Start Menu\Programs\), configured with AppUserModelID
 * for proper taskbar grouping, and automatically updates the shortcut target and working directory
 * if the executable was moved to a new path.
 */
public final class WindowsMenuEntry {

    public enum Status {
        NOT_WINDOWS_NATIVE,
        UP_TO_DATE,
        CREATED,
        UPDATED,
        ERROR
    }

    public static final String APP_NAME = "Trivial Commander";
    public static final String SHORTCUT_FILE_NAME = APP_NAME + ".lnk";
    public static final String APP_USER_MODEL_ID = "tcm.TrivialCommander";

    private WindowsMenuEntry() {
    }

    /**
     * Checks and updates the Start Menu shortcut in a background daemon thread.
     * Only runs if running as a Windows native build (e.g. tcm.exe).
     */
    public static void reconcileInBackground() {
        if (!isWindowsNative()) {
            return;
        }
        final Thread t = new Thread(() -> {
            try {
                reconcile();
            } catch (final RuntimeException e) {
                // shortcut update is a convenience: failure does not prevent app from running
            }
        }, "windows-menu-entry");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Reconciles the Start Menu shortcut for the current running process and default programs directory.
     */
    @NotNull
    public static Status reconcile() {
        if (!isWindowsNative()) {
            return Status.NOT_WINDOWS_NATIVE;
        }
        final Optional<Path> exePath = currentExePath();
        if (exePath.isEmpty()) {
            return Status.NOT_WINDOWS_NATIVE;
        }
        return reconcile(exePath.get(), defaultProgramsDir());
    }

    /**
     * Reconciles the shortcut at {@code programsDir/SHORTCUT_FILE_NAME} to point to {@code exePath}.
     * If the shortcut already points to {@code exePath} and has the correct AppUserModelID, no changes are made.
     * If it is missing or points to another path, it is created or updated.
     */
    @NotNull
    public static Status reconcile(final @NotNull Path exePath, final @NotNull Path programsDir) {
        final Path shortcutPath = programsDir.resolve(SHORTCUT_FILE_NAME);
        final boolean exists = Files.isRegularFile(shortcutPath);
        if (exists && isUpToDate(shortcutPath, exePath)) {
            return Status.UP_TO_DATE;
        }
        final boolean success = createOrUpdateShortcut(shortcutPath, exePath, APP_USER_MODEL_ID);
        if (!success) {
            return Status.ERROR;
        }
        return exists ? Status.UPDATED : Status.CREATED;
    }

    /**
     * Checks whether the current process is running as a Windows native build.
     * Returns false on non-Windows OSes or when running under a JVM (e.g. java.exe, in IDE or tests).
     */
    public static boolean isWindowsNative() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("windows")) {
            return false;
        }
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return true;
        }
        return currentExePath()
            .map(p -> {
                final String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.endsWith(".exe") && !name.equals("java.exe") && !name.equals("javaw.exe");
            })
            .orElse(false);
    }

    @NotNull
    public static Optional<Path> currentExePath() {
        return ProcessHandle.current().info().command().map(Path::of).map(Path::toAbsolutePath);
    }

    @NotNull
    public static Path defaultProgramsDir() {
        final String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs");
        }
        return Path.of(System.getProperty("user.home"), "AppData", "Roaming", "Microsoft", "Windows", "Start Menu", "Programs");
    }

    /**
     * Checks if the shortcut file already points to the specified executable path and contains the AppUserModelID.
     */
    public static boolean isUpToDate(final @NotNull Path shortcutPath, final @NotNull Path exePath) {
        if (!Files.isRegularFile(shortcutPath)) {
            return false;
        }
        try {
            final byte[] bytes = Files.readAllBytes(shortcutPath);
            final String exeStr = exePath.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            final String aumidStr = APP_USER_MODEL_ID.toLowerCase(Locale.ROOT);

            final String latin1 = new String(bytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            final String utf16 = new String(bytes, StandardCharsets.UTF_16LE).toLowerCase(Locale.ROOT);
            final String utf16Offset = bytes.length > 1
                ? new String(bytes, 1, bytes.length - 1, StandardCharsets.UTF_16LE).toLowerCase(Locale.ROOT)
                : "";

            final boolean hasExe = latin1.contains(exeStr) || utf16.contains(exeStr) || utf16Offset.contains(exeStr);
            final boolean hasAumid = utf16.contains(aumidStr) || utf16Offset.contains(aumidStr) || latin1.contains(aumidStr);

            return hasExe && hasAumid;
        } catch (final IOException e) {
            return false;
        }
    }

    static boolean createOrUpdateShortcut(
        final @NotNull Path shortcutPath,
        final @NotNull Path exePath,
        final @NotNull String aumid
    ) {
        try {
            final Path dir = shortcutPath.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            final Path workDir = exePath.getParent() != null ? exePath.getParent() : exePath;
            final String script = buildPowerShellScript(
                shortcutPath.toAbsolutePath().toString(),
                exePath.toAbsolutePath().toString(),
                workDir.toAbsolutePath().toString(),
                aumid
            );
            final String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

            final Process proc = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded
            ).redirectErrorStream(true).start();

            final boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (final IOException | InterruptedException e) {
            return false;
        }
    }

    @NotNull
    static String buildPowerShellScript(
        final @NotNull String lnkPath,
        final @NotNull String exePath,
        final @NotNull String workDir,
        final @NotNull String aumid
    ) {
        final String escapedLnk = lnkPath.replace("'", "''");
        final String escapedExe = exePath.replace("'", "''");
        final String escapedDir = workDir.replace("'", "''");
        final String escapedId = aumid.replace("'", "''");

        return String.join("\r\n",
            "$lnk = '" + escapedLnk + "'",
            "$exe = '" + escapedExe + "'",
            "$dir = '" + escapedDir + "'",
            "$id  = '" + escapedId + "'",
            "",
            "$wsh = New-Object -ComObject WScript.Shell",
            "$sc = $wsh.CreateShortcut($lnk)",
            "$sc.TargetPath = $exe",
            "$sc.WorkingDirectory = $dir",
            "$sc.IconLocation = \"$exe,0\"",
            "$sc.Save()",
            "",
            "$c = @'",
            "using System;",
            "using System.Runtime.InteropServices;",
            "public class TCMAppIdHelper {",
            "    [DllImport(\"shell32.dll\", CharSet = CharSet.Unicode)]",
            "    private static extern int SHGetPropertyStoreFromParsingName(string p, IntPtr b, uint f, ref Guid r, out IPropertyStore s);",
            "    [ComImport, Guid(\"886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]",
            "    private interface IPropertyStore {",
            "        int GetCount(out uint c);",
            "        int GetAt(uint i, out PK pk);",
            "        int GetValue(ref PK pk, out PV pv);",
            "        int SetValue(ref PK pk, PV pv);",
            "        int Commit();",
            "    }",
            "    [StructLayout(LayoutKind.Sequential, Pack = 4)]",
            "    private struct PK { public Guid fmtid; public uint pid; }",
            "    [StructLayout(LayoutKind.Explicit)]",
            "    private class PV : IDisposable {",
            "        [FieldOffset(0)] public ushort vt;",
            "        [FieldOffset(8)] public IntPtr ptr;",
            "        public PV(string s) { vt = 31; ptr = Marshal.StringToCoTaskMemUni(s); }",
            "        public void Dispose() { if (ptr != IntPtr.Zero) { Marshal.FreeCoTaskMem(ptr); ptr = IntPtr.Zero; } }",
            "    }",
            "    public static void Set(string p, string id) {",
            "        Guid g = new Guid(\"886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99\");",
            "        IPropertyStore s;",
            "        if (SHGetPropertyStoreFromParsingName(p, IntPtr.Zero, 2, ref g, out s) == 0) {",
            "            PK k = new PK { fmtid = new Guid(\"9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3\"), pid = 5 };",
            "            using (PV v = new PV(id)) { s.SetValue(ref k, v); s.Commit(); }",
            "            Marshal.ReleaseComObject(s);",
            "        }",
            "    }",
            "}",
            "'@",
            "Add-Type -TypeDefinition $c",
            "[TCMAppIdHelper]::Set($lnk, $id)"
        );
    }
}

