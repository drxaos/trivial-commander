package tcm.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.core.SquashFs;

/**
 * The icon of an AppImage (type 2): the ELF runtime is followed by a squashfs image, which starts right after the ELF section
 * header table. The icon is ".DirIcon" in its root (PNG or SVG, usually a symbolic link), otherwise the Icon= of the root
 * .desktop file. Nothing from the AppImage is executed.
 */
final class AppImageIcons {

    private static final int MAX_ICON = 4 * 1024 * 1024;
    private static final int MAX_DESKTOP = 64 * 1024;

    private AppImageIcons() {
    }

    static @Nullable BufferedImage read(final @NotNull Path file, final int size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            final SquashFs fs = SquashFs.open(ch, SquashFs.imageOffset(ch));
            if (fs == null) {
                return null;
            }
            byte[] data = fs.readFile(".DirIcon", MAX_ICON);
            if (data == null) {
                data = desktopIcon(fs);
            }

            return data == null ? null : decode(data, file, size);
        }
    }

    /** No .DirIcon: the icon named by the root .desktop file, as a PNG or SVG next to it. */
    private static byte @Nullable [] desktopIcon(final @NotNull SquashFs fs) throws IOException {
        final List<String> root = fs.list("/");
        if (root == null) {
            return null;
        }
        for (final String name : root) {
            if (!name.endsWith(".desktop")) {
                continue;
            }
            final byte[] desktop = fs.readFile(name, MAX_DESKTOP);
            final String icon = desktop == null ? null : iconName(new String(desktop, StandardCharsets.UTF_8));
            if (icon == null) {
                continue;
            }
            for (final String candidate : List.of(icon + ".png", icon + ".svg", icon)) {
                final byte[] data = fs.readFile(candidate, MAX_ICON);
                if (data != null) {
                    return data;
                }
            }
        }

        return null;
    }

    private static @Nullable String iconName(final @NotNull String desktop) {
        final List<String> names = new ArrayList<>();
        for (final String line : desktop.split("\n")) {
            final String l = line.trim();
            if (l.startsWith("Icon=")) {
                names.add(l.substring(5).trim());
            }
        }
        // the icon may be an absolute path of the build machine: only its name is looked up in the image root
        final String icon = names.isEmpty() ? "" : names.get(0);
        final String name = icon.substring(icon.lastIndexOf('/') + 1);

        return name.isEmpty() || name.contains("..") ? null : name;
    }

    private static @Nullable BufferedImage decode(final byte @NotNull [] data, final @NotNull Path file, final int size) throws IOException {
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return ImageIO.read(new ByteArrayInputStream(data));
        }
        final String head = new String(data, 0, Math.min(data.length, 1024), StandardCharsets.UTF_8);
        if (head.contains("<svg") || head.trim().startsWith("<?xml")) {
            return SvgImages.render(new ByteArrayInputStream(data), file.toUri(), size);
        }

        // other formats (XPM, ...) — ImageIO may know them
        return ImageIO.read(new ByteArrayInputStream(data));
    }
}
