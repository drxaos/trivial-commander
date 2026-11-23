package tcm.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;

/**
 * Mount points of physical devices (USB sticks, cards, other disks) — as the file manager's "Devices" section shows them.
 * Mount points themselves are never touched: a hung mount (dead network share, stuck disk) must not freeze the drive list.
 */
public final class Devices {

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final Path MOUNTINFO = Path.of("/proc/self/mountinfo");
    /** "/dev/disk4s1 on /Volumes/NO NAME (msdos, local, nodev, nosuid, mounted by user)" */
    private static final Pattern MAC_MOUNT = Pattern.compile("^(/dev/\\S+) on (.+) \\(([^)]*)\\)$");
    private static final long MOUNT_TIMEOUT_MS = 1500;

    private Devices() {
    }

    /** Sorted mount points; empty on Windows (drive letters cover it) and when the list cannot be read. */
    public static @NotNull List<String> mountPoints() {
        try {
            if (OS.contains("win")) {
                return List.of();
            }
            if (OS.contains("mac")) {
                return macMountPoints();
            }

            return Files.isReadable(MOUNTINFO) ? linuxMountPoints(Files.readAllLines(MOUNTINFO), System.getProperty("user.home")) : List.of();
        } catch (final IOException | RuntimeException e) {
            // the list is a convenience: without it only / and ~ are offered
            return List.of();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();

            return List.of();
        }
    }

    /**
     * /proc/self/mountinfo lines: "id parent major:minor root mountpoint options [optional...] - fstype source superoptions".
     * As in GIO (g_unix_mount_guess_should_display): block devices mounted under /media, /run/media, /mnt or the home directory;
     * system mounts (/, /home, /boot/efi), snaps and bind mounts of subdirectories are not devices.
     */
    static @NotNull List<String> linuxMountPoints(final @NotNull List<String> lines, final @NotNull String home) {
        final TreeSet<String> result = new TreeSet<>();
        for (final String line : lines) {
            final String[] f = line.split(" ");
            int sep = -1;
            for (int i = 6; i < f.length; i++) {
                if ("-".equals(f[i])) {
                    sep = i;
                    break;
                }
            }
            if (f.length < 6 || sep < 0 || sep + 2 >= f.length) {
                continue;
            }
            final String root = f[3];
            final String mountPoint = unescape(f[4]);
            final String source = f[sep + 2];
            if ("/".equals(root) && source.startsWith("/dev/") && isUserMountPoint(mountPoint, home)) {
                result.add(mountPoint);
            }
        }

        return List.copyOf(result);
    }

    private static boolean isUserMountPoint(final @NotNull String mountPoint, final @NotNull String home) {
        for (final String prefix : List.of("/media/", "/run/media/", "/mnt/", home + "/")) {
            if (mountPoint.startsWith(prefix) && mountPoint.length() > prefix.length()) {
                return true;
            }
        }

        return false;
    }

    /** The kernel writes space, tab, newline and backslash in mount points as \040, \011, \012, \134. */
    private static @NotNull String unescape(final @NotNull String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\\' && i + 3 < bytes.length && isOctal(bytes[i + 1]) && isOctal(bytes[i + 2]) && isOctal(bytes[i + 3])) {
                out.write((bytes[i + 1] - '0') * 64 + (bytes[i + 2] - '0') * 8 + (bytes[i + 3] - '0'));
                i += 3;
            } else {
                out.write(bytes[i]);
            }
        }

        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isOctal(final byte b) {
        return b >= '0' && b <= '7';
    }

    /** Finder shows local volumes under /Volumes except those mounted "nobrowse" (system and hidden ones). */
    private static @NotNull List<String> macMountPoints() throws IOException, InterruptedException {
        final Process p = new ProcessBuilder("/sbin/mount")
            .redirectErrorStream(true)
            .start();
        final byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        if (!p.waitFor(MOUNT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();

            return List.of();
        }

        return macMountPoints(List.of(new String(out, StandardCharsets.UTF_8).split("\n")));
    }

    static @NotNull List<String> macMountPoints(final @NotNull List<String> lines) {
        final List<String> result = new ArrayList<>();
        for (final String line : lines) {
            final Matcher m = MAC_MOUNT.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            final List<String> options = List.of(m.group(3).split(",\\s*"));
            final String mountPoint = m.group(2);
            if (mountPoint.startsWith("/Volumes/") && options.contains("local") && !options.contains("nobrowse")) {
                result.add(mountPoint);
            }
        }

        return result.stream()
            .sorted()
            .toList();
    }
}
