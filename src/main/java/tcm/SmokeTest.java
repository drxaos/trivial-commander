package tcm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.jetbrains.annotations.NotNull;

import tcm.core.ArchiveFormat;
import tcm.core.DirWatcher;
import tcm.core.Progress;
import tcm.core.Searcher;
import tcm.core.Trash;
import tcm.core.TreeStats;
import tcm.core.VFile;
import tcm.core.Vfs;
import tcm.core.ZipOps;
import tcm.ops.ConflictResolver;
import tcm.ops.CopyJob;
import tcm.ops.DeleteJob;
import tcm.ops.Job;
import tcm.ops.JobContext;
import tcm.ops.LinkJob;
import tcm.ops.PackJob;
import tcm.ops.TestJob;
import tcm.ops.UnpackJob;
import tcm.util.Diff;
import tcm.util.Masks;
import tcm.util.TextUtil;

/**
 * Core check without GUI: listing, copying locally/into an archive/from an archive, packing, deletion, mkdir, renaming,
 * unpacking, TAR/TAR.GZ/TAR.BZ2/TAR.XZ/RAR, masks, diff. Exit code 1 on the first failure.
 */
public final class SmokeTest {

    private static final String RAR5 = "UmFyIRoHAQDz4YLrCwEFBwAGAQGAgIAA1tMf2iUCAwufAATgErSDAvz+XuiABQEHZC94LnR4dAoDEwKO3Gr6Vtsf"
        + "wYccMwI/sy/yKy100PQkIP9QHl6rqfPfRgMy0XtdwNGdPsgRAgIAAwD9gwElfZNlgAABAWQdd1ZRAwUEAA==";

    private static int checks;

    private SmokeTest() {
    }

    public static void main(final String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        final Path root = Files.createTempDirectory("tcm-smoke");
        try {
            run(root);
            System.out.println("OK: " + checks + " checks passed");
        } catch (final Throwable e) {
            System.out.println("FAILED after " + checks + " checks");
            e.printStackTrace(System.out);
            System.exit(1);
        } finally {
            Trash.deleteTree(root, Progress.NONE);
        }
    }

    private static void run(final @NotNull Path root) throws Exception {
        final Path src = Files.createDirectories(root.resolve("src"));
        final Path dst = Files.createDirectories(root.resolve("dst"));
        Files.writeString(src.resolve("a.txt"), "hello\nworld\n");
        Files.writeString(src.resolve("b.md"), "# title\n");
        Files.createDirectories(src.resolve("sub/deep"));
        Files.writeString(src.resolve("sub/deep/c.txt"), "deep file");

        // listing
        final VFile srcDir = Vfs.resolve(src.toString());
        check(Vfs.list(srcDir).size() == 3, "list local");
        check(Vfs.findChild(srcDir, "a.txt") != null, "findChild local");

        // local copy with a mask
        exec(new CopyJob(Vfs.list(srcDir), Vfs.resolve(dst.toString()), null, "*.txt", false));
        check(Files.exists(dst.resolve("a.txt")), "copy local a.txt");
        check(!Files.exists(dst.resolve("b.md")), "copy mask skips b.md");
        check(Files.readString(dst.resolve("sub/deep/c.txt")).equals("deep file"), "copy local recursive");

        // packing
        final Path zip = root.resolve("arc.zip");
        exec(new PackJob(Vfs.list(srcDir), zip, true, false));
        final VFile zipRoot = Vfs.resolve(zip.toString());
        check(zipRoot.isArchiveRoot(), "resolve zip as dir");
        check(names(zipRoot).equals(List.of("a.txt", "b.md", "sub")), "pack content: " + names(zipRoot));
        check(Vfs.resolve(zip + "!/sub/deep").isDirectory(), "archive subdir resolve");
        check(new String(Vfs.readAll(Vfs.findChild(Vfs.resolve(zip + "!/sub/deep"), "c.txt")), StandardCharsets.UTF_8)
            .equals("deep file"), "read entry");

        // copying into an archive (into a subdirectory) and out of an archive
        Files.writeString(root.resolve("extra.txt"), "extra");
        exec(new CopyJob(List.of(Vfs.stat(root.resolve("extra.txt"))), Vfs.resolve(zip + "!/sub"), null, "*", false));
        check(Vfs.findChild(Vfs.resolve(zip + "!/sub"), "extra.txt") != null, "copy into archive");
        final Path out = Files.createDirectories(root.resolve("out"));
        exec(new CopyJob(List.of(Vfs.findChild(zipRoot(zip), "sub")), Vfs.resolve(out.toString()), null, "*", false));
        check(Files.readString(out.resolve("sub/extra.txt")).equals("extra"), "copy from archive");
        check(Files.readString(out.resolve("sub/deep/c.txt")).equals("deep file"), "copy from archive recursive");

        // overwrite with a conflict
        Files.writeString(root.resolve("extra.txt"), "extra2");
        exec(new CopyJob(List.of(Vfs.stat(root.resolve("extra.txt"))), Vfs.resolve(zip + "!/sub"), null, "*", false));
        check(new String(Vfs.readAll(Vfs.findChild(Vfs.resolve(zip + "!/sub"), "extra.txt")), StandardCharsets.UTF_8).equals("extra2"),
            "overwrite in archive");

        // mkdir, rename, deletion inside an archive
        ZipOps.mkdir(zip, "newdir/inner");
        check(Vfs.resolve(zip + "!/newdir/inner").isDirectory(), "mkdir in archive");
        ZipOps.rename(zip, "sub", "renamed");
        check(Vfs.resolve(zip + "!/renamed/deep").isDirectory(), "rename dir in archive");
        check(Vfs.findChild(zipRoot(zip), "sub") == null, "old name gone");
        exec(new DeleteJob(List.of(Vfs.findChild(zipRoot(zip), "b.md")), true));
        check(Vfs.findChild(zipRoot(zip), "b.md") == null, "delete entry");

        // moving inside an archive and out of an archive
        exec(new CopyJob(List.of(Vfs.findChild(zipRoot(zip), "a.txt")), Vfs.resolve(zip + "!/newdir"), null, "*", true));
        check(Vfs.findChild(Vfs.resolve(zip + "!/newdir"), "a.txt") != null, "move inside archive: target");
        check(Vfs.findChild(zipRoot(zip), "a.txt") == null, "move inside archive: source gone");
        final Path moved = Files.createDirectories(root.resolve("moved"));
        exec(new CopyJob(List.of(Vfs.findChild(zipRoot(zip), "newdir")), Vfs.resolve(moved.toString()), null, "*", true));
        check(Files.exists(moved.resolve("newdir/a.txt")), "move from archive: target");
        check(Vfs.findChild(zipRoot(zip), "newdir") == null, "move from archive: source gone");

        // copy within the same archive
        exec(new CopyJob(List.of(Vfs.findChild(zipRoot(zip), "renamed")), zipRoot(zip), "copy", "*", false));
        check(Vfs.resolve(zip + "!/copy/deep").isDirectory() && Vfs.resolve(zip + "!/renamed/deep").isDirectory(), "copy inside archive");

        // local move and rename
        exec(new CopyJob(List.of(Vfs.stat(dst.resolve("a.txt"))), Vfs.resolve(dst.toString()), "a2.txt", "*", true));
        check(Files.exists(dst.resolve("a2.txt")) && !Files.exists(dst.resolve("a.txt")), "rename local");

        // unpacking
        final Path unpacked = Files.createDirectories(root.resolve("unpacked"));
        exec(new UnpackJob(List.of(Vfs.stat(zip)), Vfs.resolve(unpacked.toString()), "*"));
        check(Files.readString(unpacked.resolve("renamed/deep/c.txt")).equals("deep file"), "unpack");

        // jar as a directory
        final Path jar = root.resolve("lib.jar");
        exec(new PackJob(List.of(Vfs.stat(src.resolve("a.txt"))), jar, true, false));
        check(names(Vfs.resolve(jar.toString())).equals(List.of("a.txt")), "jar as dir");

        // local deletion (permanent, so as not to litter the trash)
        exec(new DeleteJob(List.of(Vfs.stat(out)), true));
        check(!Files.exists(out), "delete local tree");

        checkSearch(root, src, zip);
        checkReadOnlyArchives(root);
        checkWatcher(root);
        checkUtils();
    }

    private static void checkWatcher(final @NotNull Path root) throws Exception {
        final Path dir = Files.createDirectories(root.resolve("watched"));
        final DirWatcher watcher = new DirWatcher();
        check(watcher.watch(Set.of(dir)).isEmpty(), "watcher: no changes yet");
        Files.writeString(dir.resolve("new.txt"), "x");
        check(waitChanged(watcher, dir), "watcher: file created");
        Files.writeString(dir.resolve("new.txt"), "xx");
        check(waitChanged(watcher, dir), "watcher: file modified");
        check(watcher.watch(Set.of()).isEmpty() && watcher.watch(Set.of(dir)).isEmpty(), "watcher: events consumed");
    }

    private static boolean waitChanged(final @NotNull DirWatcher watcher, final @NotNull Path dir) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (watcher.watch(Set.of(dir)).contains(dir)) {
                return true;
            }
            Thread.sleep(100);
        }

        return false;
    }

    /** TAR and compressed TAR are created right here, RAR5 (rar 7 no longer writes RAR4) is embedded, with a compressed entry d/x.txt. */
    private static void checkReadOnlyArchives(final @NotNull Path root) throws Exception {
        final String text = "tar content\n".repeat(100);
        for (final String name : List.of("t.tar", "t.tar.gz", "t.tgz", "t.tar.bz2", "t.tar.xz")) {
            final Path tar = root.resolve(name);
            writeTar(tar, text);
            check(ArchiveFormat.of(name) != null && Vfs.stat(tar).isArchiveFile(), "archive by extension " + name);
            check(new String(Vfs.readAll(Vfs.file(tar + "!/d/x.txt")), StandardCharsets.UTF_8).equals(text), "read entry " + name);
            final Path out = Files.createDirectories(root.resolve("untar-" + name));
            exec(new UnpackJob(List.of(Vfs.stat(tar)), Vfs.resolve(out.toString()), "*"));
            check(Files.readString(out.resolve("d/x.txt")).equals(text) && Files.isDirectory(out.resolve("d/empty")), "unpack " + name);
            final TestJob test = new TestJob(List.of(Vfs.stat(tar)));
            exec(test);
            check(test.getReport().get(0).startsWith("OK"), "test " + name);
            check(failsWithIo(() -> exec(new CopyJob(List.of(Vfs.stat(out.resolve("d/x.txt"))), Vfs.resolve(tar + "!/d"), "y.txt", "*", false))),
                "tar is read-only " + name);
        }
        final Path renamed = Files.copy(root.resolve("t.tar.gz"), root.resolve("t.bin"));
        check(names(Vfs.resolve(renamed + VFile.ARCHIVE_SEPARATOR)).equals(List.of("d")), "archive by signature");
        final byte[] gz = Files.readAllBytes(root.resolve("t.tar.gz"));
        gz[gz.length - 6] ^= 0x55;
        final Path broken = Files.write(root.resolve("broken.tar.gz"), gz);
        final TestJob brokenTest = new TestJob(List.of(Vfs.stat(broken)));
        exec(brokenTest);
        check(brokenTest.getReport().get(0).startsWith("ERROR"), "test detects broken tar.gz");

        final Path rar = Files.write(root.resolve("r.rar"), Base64.getDecoder().decode(RAR5));
        check(names(Vfs.resolve(rar.toString())).equals(List.of("d")), "rar listing");
        check(Vfs.readAll(Vfs.file(rar + "!/d/x.txt")).length == 2400, "rar read compressed entry");
        final Path unrar = Files.createDirectories(root.resolve("unrar"));
        exec(new UnpackJob(List.of(Vfs.stat(rar)), Vfs.resolve(unrar.toString()), "*"));
        check(Files.readString(unrar.resolve("d/x.txt")).equals("rar content\n".repeat(200)), "unpack rar");
        final TestJob rarTest = new TestJob(List.of(Vfs.stat(rar)));
        exec(rarTest);
        check(rarTest.getReport().get(0).startsWith("OK"), "test rar");

        final Path evil = root.resolve("evil.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new ZipEntry("../../evil.txt"));
            z.write('x');
            z.closeEntry();
        }
        final Path into = Files.createDirectories(root.resolve("x/y/into"));
        exec(new UnpackJob(List.of(Vfs.stat(evil)), Vfs.resolve(into.toString()), "*"));
        check(Files.exists(into.resolve("evil.txt")) && !Files.exists(root.resolve("x/evil.txt")), "unpack strips ../ from entry names");

        // names without the UTF-8 flag: Russian DOS/Windows archives write them in CP866, Western ones in CP437
        checkDosNames(root, "dos866.zip", "IBM866", "Документы/Отчёт.txt");
        checkDosNames(root, "dos437.zip", "IBM437", "Müller/Straße.txt");
    }

    private static void checkDosNames(
        final @NotNull Path root,
        final @NotNull String file,
        final @NotNull String charset,
        final @NotNull String name
    ) throws Exception {
        final Path zip = root.resolve(file);
        // ZipOutputStream sets the UTF-8 flag only for UTF-8
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip), Charset.forName(charset))) {
            z.putNextEntry(new ZipEntry(name));
            z.write('x');
            z.closeEntry();
        }
        final Path into = Files.createDirectories(root.resolve(file + ".out"));
        exec(new UnpackJob(List.of(Vfs.stat(zip)), Vfs.resolve(into.toString()), "*"));
        check(Files.exists(into.resolve(name)), "unpack " + charset + " names");
    }

    private static void writeTar(final @NotNull Path file, final @NotNull String text) throws IOException {
        final String name = file.getFileName().toString();
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream packed = name.endsWith("gz") ? new GzipCompressorOutputStream(raw)
                 : name.endsWith("bz2") ? new BZip2CompressorOutputStream(raw)
                 : name.endsWith("xz") ? new XZCompressorOutputStream(raw) : raw;
             TarArchiveOutputStream tar = new TarArchiveOutputStream(packed)) {
            tar.putArchiveEntry(new TarArchiveEntry("./d/empty/"));
            tar.closeArchiveEntry();
            final byte[] data = text.getBytes(StandardCharsets.UTF_8);
            final TarArchiveEntry e = new TarArchiveEntry("./d/x.txt");
            e.setSize(data.length);
            tar.putArchiveEntry(e);
            tar.write(data);
            tar.closeArchiveEntry();
        }
    }

    private static boolean failsWithIo(final @NotNull Action action) throws Exception {
        try {
            action.run();

            return false;
        } catch (final IOException e) {
            return true;
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void checkSearch(final @NotNull Path root, final @NotNull Path src, final @NotNull Path zip) throws Exception {
        final Searcher.Criteria byMask = new Searcher.Criteria();
        byMask.setName("*.txt");
        check(search(byMask, src).size() == 2, "search by mask (recursive)");
        byMask.setDepth(0);
        check(search(byMask, src).size() == 1, "search depth 0");
        final Searcher.Criteria byText = new Searcher.Criteria();
        byText.setText("HELLO");
        check(search(byText, src).size() == 1, "search by text, case-insensitive");
        byText.setCaseSensitive(true);
        check(search(byText, src).isEmpty(), "search by text, case-sensitive");
        byText.setCaseSensitive(false);
        byText.setNotContaining(true);
        byText.setName("*.txt");
        check(search(byText, src).size() == 1, "search NOT containing");
        final Searcher.Criteria inArchives = new Searcher.Criteria();
        inArchives.setName("c.txt");
        inArchives.setSearchArchives(true);
        final List<VFile> found = search(inArchives, root);
        check(found.stream().anyMatch(VFile::isInArchive), "search inside archives");
        final Searcher.Criteria regex = new Searcher.Criteria();
        regex.setName("^[ab]\\.(txt|md)$");
        regex.setNameRegex(true);
        regex.setDepth(0);
        check(search(regex, src).size() == 2, "search name regex");
        final Searcher.Criteria big = new Searcher.Criteria();
        big.setMinSize(1000);
        big.setFilesOnly(true);
        check(search(big, src).isEmpty(), "search size filter");

        final TreeStats stats = TreeStats.of(List.of(Vfs.stat(src)), () -> false);
        check(stats.getFiles() == 3 && stats.getDirs() == 3, "tree stats: " + stats.getFiles() + " files, " + stats.getDirs() + " dirs");
        check(Vfs.file(zip + "!/copy/deep/c.txt").getSize() == 9, "Vfs.file inside archive");

        final TestJob test = new TestJob(List.of(Vfs.stat(zip)));
        exec(test);
        check(test.getReport().get(0).startsWith("OK"), "test archive");
        // one large incompressible entry: the byte at offset 200 is guaranteed to be inside its data
        final byte[] noise = new byte[64 * 1024];
        new Random(42).nextBytes(noise);
        final Path bigText = Files.write(root.resolve("big.bin"), noise);
        final Path broken = root.resolve("broken.zip");
        exec(new PackJob(List.of(Vfs.stat(bigText)), broken, true, false));
        final byte[] bytes = Files.readAllBytes(broken);
        bytes[200] ^= 0x55;
        Files.write(broken, bytes);
        final TestJob testBroken = new TestJob(List.of(Vfs.stat(broken)));
        exec(testBroken);
        check(testBroken.getReport().get(0).startsWith("ERROR"), "test broken archive");

        final Path links = Files.createDirectories(root.resolve("links"));
        exec(new LinkJob(List.of(Vfs.stat(src.resolve("a.txt"))), links));
        check(Files.isSymbolicLink(links.resolve("a.txt")), "create symlink");
        try {
            ZipOps.safeResolve(root, "../evil.txt");
            check(false, "zip slip rejected");
        } catch (final IOException e) {
            check(true, "zip slip rejected");
        }
    }

    private static @NotNull List<VFile> search(final @NotNull Searcher.Criteria c, final @NotNull Path dir) throws Exception {
        final List<VFile> result = new ArrayList<>();
        new Searcher(c, result::add, d -> { }, () -> false).search(List.of(Vfs.resolve(dir.toString())));

        return result;
    }

    private static void checkUtils() {
        check(Masks.of("*.txt").matches("A.TXT"), "mask wildcard");
        check(!Masks.of("*.txt").matches("a.md"), "mask wildcard negative");
        check(Masks.of("read").matches("README.md"), "mask substring");
        check(Masks.of("*.*").matches("Makefile"), "mask *.* matches no-ext");
        check(Masks.of("*.txt;*.md").matches("x.md"), "mask list");
        check(!Masks.of("*|*.md").matches("x.md"), "mask exclude");
        final Diff.Result diff = Diff.compare(List.of("a", "b", "c", "d"), List.of("a", "x", "c", "d", "e"), true);
        check(diff.getBlocks().size() == 2, "diff blocks: " + diff.getBlocks().size());
        check(diff.getRows().size() == 5, "diff rows: " + diff.getRows().size());
        check(Diff.compare(List.of("A"), List.of("a"), false).getBlocks().isEmpty(), "diff ignore case");
        final byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        check(TextUtil.decode(bom).getText().equals("hi"), "decode utf-8 bom");
        check(TextUtil.decode(new byte[] {(byte) 0xE9}).getCharset().equals(StandardCharsets.ISO_8859_1), "decode fallback latin1");
    }

    private static @NotNull VFile zipRoot(final @NotNull Path zip) throws Exception {
        return Vfs.archiveDir(zip, "");
    }

    private static @NotNull List<String> names(final @NotNull VFile dir) throws Exception {
        return Vfs.list(dir)
            .stream()
            .map(VFile::getName)
            .sorted()
            .toList();
    }

    private static void exec(final @NotNull Job job) throws Exception {
        job.run(new JobContext(ConflictResolver.OVERWRITE_ALL));
    }

    private static void check(final boolean condition, final @NotNull String what) {
        if (!condition) {
            throw new AssertionError("check failed: " + what);
        }
        checks++;
        System.out.println("  ok  " + what);
    }
}
