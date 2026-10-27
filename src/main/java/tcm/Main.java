package tcm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.jetbrains.annotations.NotNull;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import tcm.util.Settings;

/**
 * Entry point: FlatLaf theme from settings and the main window.
 */
public final class Main {

    static final String[][] THEMES = {{"Light"}, {"Dark"}, {"IntelliJ"}, {"Darcula"}};

    private Main() {
    }

    public static void main(final String[] args) {
        initNativeImage();
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(() -> {
            applyTheme(Settings.get("theme", "Light"));
            new MainFrame().setVisible(true);
        });
    }

    /**
     * A native image has no java.home, and AWT reads font settings relative to it. If java.home has no lib directory,
     * FontConfiguration assumes it found a vendor fontconfig.properties and does not consult the system fontconfig
     * ("Fontconfig head is null"). So java.home is the executable's directory if lib is next to it,
     * otherwise a temporary directory with an empty lib.
     */
    private static void initNativeImage() {
        if (System.getProperty("java.home") != null) {
            return;
        }
        final Path exeDir = ProcessHandle.current()
            .info()
            .command()
            .map(c -> Path.of(c).toAbsolutePath().getParent())
            .orElse(null);
        if (exeDir != null && Files.isDirectory(exeDir.resolve("lib"))) {
            System.setProperty("java.home", exeDir.toString());

            return;
        }
        try {
            final Path home = Files.createTempDirectory("tcm-java-home");
            final Path lib = Files.createDirectory(home.resolve("lib"));
            lib.toFile().deleteOnExit();
            home.toFile().deleteOnExit();
            System.setProperty("java.home", home.toString());
        } catch (final IOException e) {
            System.setProperty("java.home", String.valueOf(exeDir != null ? exeDir : Path.of(".").toAbsolutePath()));
        }
    }

    static void applyTheme(final @NotNull String name) {
        final FlatLaf laf;
        switch (name) {
            case "Dark":
                laf = new FlatDarkLaf();
                break;
            case "IntelliJ":
                laf = new FlatIntelliJLaf();
                break;
            case "Darcula":
                laf = new FlatDarculaLaf();
                break;
            default:
                laf = new FlatLightLaf();
                break;
        }
        FlatLaf.setup(laf);
        UIManager.put("Table.showHorizontalLines", Boolean.FALSE);
        UIManager.put("Table.showVerticalLines", Boolean.FALSE);
        UIManager.put("TabbedPane.showTabSeparators", Boolean.TRUE);
        FlatLaf.updateUI();
    }
}
