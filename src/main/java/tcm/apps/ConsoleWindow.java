package tcm.apps;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import tcm.util.Keys;

/**
 * Console window for an external command from the command line: stdout/stderr output, stdin input, stopping the process.
 */
public final class ConsoleWindow {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final @NotNull Window window;
    private final JTextArea output = new JTextArea();
    private final JTextField input = new JTextField();
    private final JLabel state = new JLabel("Running");
    private @Nullable Process process;

    private ConsoleWindow(final @Nullable Window owner, final @NotNull String command, final @NotNull Path dir) {
        window = AppWindows.create(owner, command + " - Console");
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        final JPanel bottom = new JPanel(new BorderLayout(4, 0));
        bottom.add(new JLabel(" stdin: "), BorderLayout.WEST);
        bottom.add(input, BorderLayout.CENTER);
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(state);
        final JButton stop = new JButton("Stop");
        stop.addActionListener(e -> stop());
        buttons.add(stop);
        bottom.add(buttons, BorderLayout.EAST);
        AppWindows.container(window).getContentPane().add(new JScrollPane(output), BorderLayout.CENTER);
        AppWindows.container(window).getContentPane().add(bottom, BorderLayout.SOUTH);
        input.addActionListener(e -> send());
        final JComponent root = AppWindows.container(window).getRootPane();
        Keys.bindWindow(root, "ESCAPE", window::dispose);
        // Ctrl+Break: in AWT this is VK_CANCEL (Windows) or VK_PAUSE (X11)
        Keys.bindWindow(root, "ctrl CANCEL", this::stop);
        Keys.bindWindow(root, "ctrl PAUSE", this::stop);
        window.setVisible(true);
        input.requestFocusInWindow();
        start(command, dir);
    }

    public static void run(final @Nullable Window owner, final @NotNull String command, final @NotNull Path dir) {
        new ConsoleWindow(owner, command, dir);
    }

    public static @NotNull List<String> shellCommand(final @NotNull String command) {
        // cmd strips the outer quotes of /c, so a quoted program path followed by arguments needs one more pair
        return WINDOWS ? List.of("cmd.exe", "/c", "\"" + command + "\"") : List.of("/bin/sh", "-c", command);
    }

    /** Encoding of the child's output and input: the OEM code page of the console on Windows (chcp), UTF-8 elsewhere. */
    public static @NotNull Charset consoleCharset() {
        return WINDOWS ? ConsoleCharset.WINDOWS : StandardCharsets.UTF_8;
    }

    private static final class ConsoleCharset {

        static final Charset WINDOWS = detect();

        private static @NotNull Charset detect() {
            try {
                final Process p = new ProcessBuilder("cmd.exe", "/c", "chcp").redirectErrorStream(true).start();
                final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1).trim();
                p.waitFor();
                final Matcher m = Pattern.compile("(\\d+)$").matcher(out);
                if (m.find()) {
                    final int page = Integer.parseInt(m.group(1));

                    return page == 65001 ? StandardCharsets.UTF_8 : Charset.forName("cp" + page);
                }
            } catch (final IOException | RuntimeException e) {
                // no chcp or an unknown code page — the system encoding below
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return Charset.defaultCharset();
        }
    }

    private void start(final @NotNull String command, final @NotNull Path dir) {
        append("$ " + command + "\n");
        try {
            final ProcessBuilder pb = new ProcessBuilder(shellCommand(command)).directory(dir.toFile()).redirectErrorStream(true);
            final Process p = pb.start();
            process = p;
            final Thread reader = new Thread(() -> pump(p), "console-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (final IOException e) {
            append("Cannot start: " + e.getMessage() + "\n");
            state.setText("Failed");
        }
    }

    private void pump(final @NotNull Process p) {
        final Charset cs = consoleCharset();
        try (InputStream in = p.getInputStream(); Reader r = new InputStreamReader(in, cs)) {
            final char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                final String chunk = new String(buf, 0, n);
                SwingUtilities.invokeLater(() -> append(chunk));
            }
            final int code = p.waitFor();
            SwingUtilities.invokeLater(() -> {
                append("\n[exit code " + code + "]\n");
                state.setText("Exit code " + code);
            });
        } catch (final IOException e) {
            SwingUtilities.invokeLater(() -> append("\n[" + e.getMessage() + "]\n"));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send() {
        final Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        final String line = input.getText();
        input.setText("");
        append(line + "\n");
        try {
            final OutputStream os = p.getOutputStream();
            os.write((line + System.lineSeparator()).getBytes(consoleCharset()));
            os.flush();
        } catch (final IOException e) {
            append("[stdin closed]\n");
        }
    }

    private void stop() {
        final Process p = process;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            state.setText("Stopped");
        }
    }

    private void append(final @NotNull String s) {
        output.append(s);
        output.setCaretPosition(output.getDocument().getLength());
    }
}
