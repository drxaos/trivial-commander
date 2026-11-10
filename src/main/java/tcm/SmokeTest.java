package tcm;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream;
import org.apache.commons.compress.archivers.cpio.CpioConstants;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.jetbrains.annotations.NotNull;

import tcm.apps.ConsoleWindow;
import tcm.core.AppImageDesktopEntry;
import tcm.core.WindowsMenuEntry;
import tcm.core.ArchiveFormat;
import tcm.core.DirWatcher;
import tcm.core.EmbeddedArchives;
import tcm.core.FileAttrs;
import tcm.core.PasswordCancelledException;
import tcm.core.Passwords;
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
import tcm.ui.FileTable;
import tcm.ui.FileTableModel;
import tcm.ui.IconImages;
import tcm.ui.SvgImages;
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

    /** 7-Zip, WinZip AES-256, password 123. */
    private static final String AES_ZIP = "UEsDBDMAAQBjAAO7Y10AAAAAHwAAAAMAAAA/AAsAYSB2ZXJ5IGxvbmcgZmlsZSBuYW1lIHRoYXQgZG9lcyBub3QgZml0IGludG8gdGhlIDguMyBmb3JtYXQu"
        + "dHh0AZkHAAIAQUUDAADyL7AacK3ZetQ7wo7klCVFm0xMJC4iz7l4KR48UUUzUEsDBBQAAAAAAAO7Y10AAAAAAAAAAAAAAAACAAAAZC9QSwMEFAAAAAAAA7tj"
        + "XQAAAAAAAAAAAAAAAAgAAABkL2VtcHR5L1BLAwQzAAEAYwADu2NdAAAAAEUAAABgCQAABwALAGQveC50eHQBmQcAAgBBRQMIAH72KWhi0d691t1yzpEzX3Lc"
        + "/nuvNDMes5allwyg21fXXed2nCC3ggNT1GpiSb/F2uftMEjiSogZvOwJ6S+FjYZudbqarFBLAQI/AzMAAQBjAAO7Y10AAAAAHwAAAAMAAAA/AC8AAAAAAAAA"
        + "IIC0gQAAAABhIHZlcnkgbG9uZyBmaWxlIG5hbWUgdGhhdCBkb2VzIG5vdCBmaXQgaW50byB0aGUgOC4zIGZvcm1hdC50eHQKACAAAAAAAAEAGAAro/lE0Wvd"
        + "AQAAAAAAAAAAAAAAAAAAAAABmQcAAgBBRQMAAFBLAQI/AxQAAAAAAAO7Y10AAAAAAAAAAAAAAAACACQAAAAAAAAAEID9QYcAAABkLwoAIAAAAAAAAQAYAAtV"
        + "+UTRa90BAAAAAAAAAAAAAAAAAAAAAFBLAQI/AxQAAAAAAAO7Y10AAAAAAAAAAAAAAAAIACQAAAAAAAAAEID9QacAAABkL2VtcHR5LwoAIAAAAAAAAQAYAJpD"
        + "+ETRa90BAAAAAAAAAAAAAAAAAAAAAFBLAQI/AzMAAQBjAAO7Y10AAAAARQAAAGAJAAAHAC8AAAAAAAAAIIDtgc0AAABkL3gudHh0CgAgAAAAAAABABgAC1X5"
        + "RNFr3QEAAAAAAAAAAAAAAAAAAAAAAZkHAAIAQUUDCABQSwUGAAAAAAQABACuAQAAQgEAAAAA";

    /** Info-ZIP, traditional encryption, password 123. */
    private static final String ZIPCRYPTO_ZIP = "UEsDBAoACQAAAAK7Y116em/tDwAAAAMAAAA/ABwAYSB2ZXJ5IGxvbmcgZmlsZSBuYW1lIHRoYXQgZG9lcyBub3QgZml0IGludG8gdGhlIDguMyBmb3JtYXQu"
        + "dHh0VVQJAAPkQupq5ELqanV4CwABBOgDAAAE6AMAAOzLGKIRJAV306FPuZrcbVBLBwh6em/tDwAAAAMAAABQSwMECgAAAAAAArtjXQAAAAAAAAAAAAAAAAIA"
        + "HABkL1VUCQAD5ELqauRC6mp1eAsAAQToAwAABOgDAABQSwMECgAAAAAAArtjXQAAAAAAAAAAAAAAAAgAHABkL2VtcHR5L1VUCQAD5ELqauRC6mp1eAsAAQTo"
        + "AwAABOgDAABQSwMEFAAJAAgAArtjXdoxzkcyAAAAYAkAAAcAHABkL3gudHh0VVQJAAPkQupq5ELqanV4CwABBOgDAAAE6AMAAELAxurz+lmemqDPikw4GOeX"
        + "BKswETWDcQM6S4W7p6zLKuVhx8UGwvZIufM7wPR0jSkmUEsHCNoxzkcyAAAAYAkAAFBLAQIeAwoACQAAAAK7Y116em/tDwAAAAMAAAA/ABgAAAAAAAEAAAC0"
        + "gQAAAABhIHZlcnkgbG9uZyBmaWxlIG5hbWUgdGhhdCBkb2VzIG5vdCBmaXQgaW50byB0aGUgOC4zIGZvcm1hdC50eHRVVAUAA+RC6mp1eAsAAQToAwAABOgD"
        + "AABQSwECHgMKAAAAAAACu2NdAAAAAAAAAAAAAAAAAgAYAAAAAAAAABAA/UGYAAAAZC9VVAUAA+RC6mp1eAsAAQToAwAABOgDAABQSwECHgMKAAAAAAACu2Nd"
        + "AAAAAAAAAAAAAAAACAAYAAAAAAAAABAA/UHUAAAAZC9lbXB0eS9VVAUAA+RC6mp1eAsAAQToAwAABOgDAABQSwECHgMUAAkACAACu2Nd2jHORzIAAABgCQAA"
        + "BwAYAAAAAAABAAAA7YEWAQAAZC94LnR4dFVUBQAD5ELqanV4CwABBOgDAAAE6AMAAFBLBQYAAAAABAAEAGgBAACZAQAAAAA=";

    /** Info-ZIP from stdin (data descriptor: the check byte is the DOS time), entry "-", password 123. */
    private static final String ZIPCRYPTO_STREAMED_ZIP = "UEsDBC0ACQAIAAK7Y13aMc5H//////////8BABQALQEAEABgCQAAAAAAADIAAAAAAAAAGwT1aKnOK566EB+ow5XWUt087VKIILwEVWaPXIjHc3R4iBNVdy7l"
        + "QdUERD/t9BJbCa5QSwcI2jHORzIAAAAAAAAAYAkAAAAAAABQSwECHgMtAAkACAACu2Nd2jHORzIAAABgCQAAAQAAAAAAAAABAAAAgBEAAAAALVBLBgYsAAAA"
        + "AAAAAB4DLQAAAAAAAAAAAAEAAAAAAAAAAQAAAAAAAAAvAAAAAAAAAH0AAAAAAAAAUEsGBwAAAACsAAAAAAAAAAEAAABQSwUGAAAAAAEAAQAvAAAAfQAAAAAA";

    /** 7z with encrypted content (listing without a password), password 123. */
    private static final String SEVEN_ZIP_CONTENT = "N3q8ryccAASUXiVGBAEAAAAAAAAiAAAAAAAAAPGrNzluImEnQjD1yHS3MLapALOdPWMedvFhb1vk1ab8BJOEPZfLAUsHYzVsiJf3FFJhaJEAAIEzB64P0QDU"
        + "PKCKabDjxAD69FhUvg7E6eVyvyQAoGVHFhRpLbz7ohkTVfXVwka4S/kBlqak3W2sY1bonf9Vn+d+l0Ydahf+o3PsrKu8e8X57RyCE5TyfmVENUMY04Y0X7F8"
        + "a4G9b4WsCsAFiSKz9OlGxlwyPQMpHhyMkppSytuh9dpdkX/1h3PfQ86+UnPPHcsez7E9tVuPoYXYnkIQcduLmH6Ud4hI7I7Ttadcve06MYIouUMqwWL5fOD8"
        + "4T7GPtMxqHemmO2f/ObhV0m3SDYAABcGMAEJgNQABwsBAAEjAwEBBV0AEAAADIEuCgE4DKMKAAA=";

    /** 7z with encrypted headers (the listing needs the password), password 123. */
    private static final String SEVEN_ZIP_HEADERS = "N3q8ryccAAQcwfsaEAEAAAAAAAA+AAAAAAAAAJ03nng3xiylW2usNzaTPILvDUyp7kXbJXTUkTIWSbH68IhXrW20/1F40Hq2x1PnxEpYpp1xr0Pm2mwh4NLJ"
        + "6XXZqNjHCg9WsnkB9p2+R1vMLrb4EGvruO+4VL3sRv3nsH/ikD6iKqZaZfuS3syAy3gqeAFgqrINdpUUQv3Wxx3ktODzJD7yp5IEJ6Ik6ertwzQ3vMTejZye"
        + "PqB2+bVps7E7ZDXhYej3SSxmR9IAJUT/BD8ab5A8nKdKpodTQU9QdvmYSpvjRoY/aAE+hBUBNPmH8T/DlFoWQtrRWkqfx0KVAhw6h+GHyw7nPSkR7oyLG2B/"
        + "BjMglXtIp7sf+MmxhYmh5kRvX2CH7RCpR1ciragmut3cWRcGMAEJgOAABwsBAAIkBvEHARJTD+bYUUl75/E/aXWesHBykHcjAwEBBV0AEAAAAQAMgNSBLgoB"
        + "hRHJTgAA";

    /** genisoimage -R -J, gzip. */
    private static final String ISO_ROCK_RIDGE = "H4sIAPRC6moC/+3d328cxR0A8FmnKcFFPFUFoUAGSBFI9HK2kcHq0/VubS/cL+2dI/spWORMLMU2CleUvCDlqe1/0T+Chz5U/Xv6EvUvQO3snq9JwIlDEmhJ"
        + "Pp/NemdmZ3fmzhN9dy7ZvRAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgKzdaTaXstAt+lvb8cHanXLQe8j++fn+ft/mIe2mP9Uazp0Lb82K3vr1"
        + "3d2/qQvC+VnufDhXbc6Fr3915pVXf/HSwvz4yP/URt4vRoOi19rIY0rEtdXV5qXN9VFcL7r5aGc0znuxXeat8aCM77bfi0traysxb+wMtvobnVY3nxd++Lvl"
        + "ZnM1ftwY5q1yNOhf+rgxam8W3TQqN+o61e6qzofVQPykGMdx3ur95C93ubm8urTUXFleWf6g+UGz+dL3CprfEb5Xw6B93gk6/N9YOI7/oRuK0A9bYTvEE5d2"
        + "6IQyDELvAfu/E/9/eyl/5Pj/zqzonVfv7n5tFv8vzHIXHhT/H9AXy0+1bIQ8jZoijNLIKNLYaNUl8bgkhrWwmpZmuBI2w3oqi+lnkcZantI7aR2nVK8eXWVK"
        + "tVJ+kFIxvJtK3kvbpXSGtbCSUnlopCMGaYT2UxudVLdbt3T68vQqif+I/zxD/j2P/wAAAMAzK6v/jT2r19frn51wNryRUgsh7w3HO94iAAAAeAbm/6H6//XZ"
        + "cer1kFXz/+SNsGD+DwAAAM/K/P/C8acAC/VNfCl1dTG8WefDJByEL8I03PI+AQAAwM97/h+q5+tk9Xy/egpKVs3/kzdTgfk/AAAAPBP+fOoz9kfDF7J//CuU"
        + "5dns9nD7YvZtq6rX+vbM7Lgz/zwz28y34/XXspePT3L/pp2fz+bP+p+3f+d4s3daP7L/duBg1oGDhdkBC4/egfC38PbsoLfnvZ/vmbXycuvK5bzcudJtjLfH"
        + "v1+qX/Rf+r1OFnbjV5Mbt+L1o8PP497+9Uk83D2YxOm13Wm8ejT5Mh4eTVP5NO4fTo9S8SR+1FiJe0c3DnanjenNadXvb25XLd7+5vhhi9kP6Pf1+tkMobo/"
        + "4+R3p3Pc019m4eqT/ZL8lQB4LuydFlmeRuAPPyC6P0Er0/q5RaG6d/HEVs7WNzQeR8rFLEwOvpjeurfNx7ii+CpcnB108dMXq82Ln95/RfHC9uxS4m6rN+fX"
        + "A3dm1wN3HuN6AACeNP4/PGaeEP8fI0qeepXxVOI/APBoTv+OvVNrZH895VP1r8NuiGmmPAk3wq2Uuh6OwmH4PKX2wn7KTVLqMNU5qFPTcC2lpyl1NdWbhC/r"
        + "vUd1SVW/2u6nkmkqm9WujvooNOqvidtLpTfSmaozNNJ6M60X519PGE9+BQupJQB4vuL/w2Pjo8T/9+vnBYTqnoETayzecyPB+6d8Xr6YIvY8bgMAP1b8f3js"
        + "zk6/QvDlwQDwM5OXd7LF8Z+ysiyGV5bW1pZa4808loP2J7EsOht5LPrjvGxvtvopPSwH40F70K0Sl4tOPoqjreFwUI7j+qCMw8Go2I7rRTePo53ROO/FUd5r"
        + "9cdFezTs5q1RHtuD/rjVHsdOMWrH4dYfusVoMy/rg0fDvF2sF+3WuBj042iwVbbzRkxnyO+pmJpMp1svqmQ/daLotcqdeHnQ3erlMXWnnV7EeDA74bytop9y"
        + "vfq0Db9uAKhd21/0JgDAc2Zv/+b0jzcm8bOjw+nkcLooLy8vLy8vLy//ZHlXmAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAT8d/AD+HHhUA4AUA";

    /** genisoimage -J, gzip. */
    private static final String ISO_JOLIET = "H4sIAPRC6moC/+3d0W7TVhgA4N/t0EqHdjVtaKJgYEMgseC2qKzaVdSYEpY0KAlTewXTKKPSaCeWTXDDs+w99nDbsdOOAW2DNGCjfJ/r+JzjP06THun3cWo7"
        + "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADIVlpFMZ9Fp712ez0/2Eqr3+sesn5ve388tzjkddNPNcfMTJwbN5375NnqT+uGODWunYqZajETTz+a"
        + "/uzkByem9p6f859aLdfag16721wt81TIl5eWiis3rg/y6+1OOdgYDMtuvtIvm8NeP7+4cimfX15ezMvGRu/22mqr2Sn3Gq99tVAUS/nNxq2y2R/01q7cbAxW"
        + "brQ7qVeu1jHV6irmWtURv20P82HZ7L71t7tQLCzNzxeLC4sLV4urRXHipYbiBfFShE77vpN0+N+Y2s3/0Yl2rMXtWI9832klWtGPXnQPWP9C/v/ySvnK+f/C"
        + "uOnCyWerPx/n/zPj2pmD8v8Bv4vpbU2rUaZe045B6hnt1DeadUu+25LHciylqYg7cSOup7Y8PbZTXytTeSPNw1Tq1r2rn0rNVO+lUh4XU8ultJxPW1iOxVQq"
        + "o5Ge0Us9dC29RivFdupXmjy9viD5H/mfI+TPvfwPAAAAHFlZ/R17Vs9z9WMrjsXpVJqKsntruOEjAgAAgCMw/o/q/+uz3dJcZNX4PzkdU8b/AAAAcFTG/2d2"
        + "jwJM1SfxpdK92Thb12MzHsbPMYonPicAAAB4t8f/UV1fJ6vH+9VVULJq/J+cTQ3G/wAAAHAkTL7G/sSIrPj7Cv7T4+X0bsT4+EL2cfPOd2V/406nMVwffjOf"
        + "oufGcXMHbLHl7wIAbzb/T8jFr5D/L9TXC4rqnMF9I47VJxJejPPjqPN3j1eL43ef30v4cH139wAAeOP5//DcnU3eQ3DzAAB45/L/pHvsTYzIfp9w/P9pfB95"
        + "/Bab8SiepNJPsRPb8WMq3Y+tVNtMpe0U87AujeJBKo9S6V6K24xf6rU7dUsVXy23UssotY2jq2d9HY36NnH3U+ujtKVqC400P07zF3u3J8z3fwdT6ZUA4P3K"
        + "/4fnxlfJ/5fr6wVEdc7AvhGz/ziR4PKEbwFmU8bey9sAwJvK/4fn7mzyHoLj/wDwjnmwNetDAID3zP2tx6NfH23mP+xsjza3R7Pq6urq6urq6ur/rm4PEwAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADg9fgLYqmLFgDYBQA=";

    /** mksquashfs -comp gzip. */
    private static final String SQUASHFS = "aHNxcwUAAADkQupqAAACAAEAAAABABEAwAABAAQAAACAAAAAAAAAAIIBAAAAAAAAegEAAAAAAAD//////////40AAAAAAAAA0QAAAAAAAABMAQAAAAAAAGwB"
        + "AAAAAAAAeNrLyORKy6woKS1KVUjOzytJzSsZ5Y/yR/mj/FH+KH+UP8of5VPIBwAVp57RQgB42mNi2MLIAARPnF5lgRlIgBmIGRn+wuWZoeJMUDkmMH4Ll2dB"
        + "05vAiaqfCUlOE4hZ0cxnRZKPZ1BjYAPSAAUiEktpAHjaNYxdCoRADIM76kFyAl988Uk8igPWH3CmomHR22/F3UJoEsIX5L3SBVeQSjTtvHu3hYer5sXwW/1/"
        + "IZ1EfPS4sVmeMa2bIsek4BKJ0fRENnpPrJnmtaKtG0x2pMgHOTjswY1fg9cb4Q4AeNpLYIAAXSgNAAd4AI48AQAAAAAAABYAeNpjYICABCitAKUdoHQDlAYA"
        + "FygBQVQBAAAAAAAABIDoAwAAdAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    /** mkfs.fat -F 12 -n TINY, 64 KiB, gzip: d/Длинное имя файла.txt (VFAT name) and short.txt. */
    private static final String FAT12_IMAGE = "H4sIAAAAAAACA+3cP2sUQRgH4EkcMETypxDRysHOZkkCQsQYUIJoY0RPUTTqxuzpJfEu3K6QgEg+gp9DLMVGEL9AWmsLuzSWqdRNDiUHCoJYSJ5nl51hf8wW"
        + "M7BTzbs98/LpSrPMmnkVBuNAGAyDm2FnIIzXvR82w+m39z59bFy5eif1XLrQmJyq29GT7+8+f33qQ3Xk1pvRd4fD1vj97S9Tn7eObZ3Y/tp40ipTfbc7VcrT"
        + "YqdT5YurRVpqlStZStdWi7wsUqtdFt2+vLnaWVvbSHl7aWR4rVuUZd3dSCvFRqo6qerWyeO81U5ZlqWR4cDfuPlq59ueMXNxMFn/g23fT30ohMmHywvLC722"
        + "l8+ln8aH+vLe/nDj8vz1Rh02bjfS8b48hmh6AQAAAAAAAAAAAAAAAAAA4I9k+873h1+c78+y3+W98Rcn4tl4Lk7ELIyFiSqsh6p+u1tSovccOFqn0/F8fdX5"
        + "bDwTU5iOM3E+hpDCXHyw58Xkbv2A/u8fCiOWBwD+iWZrvXrWLVJVrFeqqQHAwZAvPrLtAwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        + "AAAA/5XvDB1KZAAAAQA=";

    /** mke2fs -t ext2 -b 1024 -L tiny -d, 100 KiB, gzip: the same files and "link" — a symbolic link to the long-named one. */
    private static final String EXT2_IMAGE = "H4sIAAAAAAACA+3bv2sTYRgH8MslIv2lVavWLUPpIkSd7NpNxc0/QNS0WC0ptKlUHLTQXUGhs6uDdFBQrCJIRzH9A9zjIII4dNH4JpeG/hKkWNv0Pp/wpHnJ"
        + "Ffpe+n3uvRwXRUBa9YYqhjoQ6lKoTLNa8kn1Noelz99vRVGtduVbprFdMk6s/l5XczAUfsShsqGeXf50/t7Qxa7nj/rj3LkPc+Wx0t3/PdeVwY9Lj+cvXJ0f"
        + "+PLrzlJ3qf73dq+b17+VD49MSv6POkSpLdWzmWvlP9MYA+lQq9Vah6iVGpAq9+0CSKtozfnvaqVp/fN1ODkB2jz/XOO7i50+v81vGKdt/wO758FseDqby23u"
        + "f5l1/W87Tuh/sKe9r69/zm61/omj/jXbdUbJdZ369ZKeUIdCHY6S60JHQh0NdSxUX6jjbbT++TlczW61/qtm43X97+Q+Xf+9mK1me6Kt5r++//fv0/nXnlaz"
        + "g3+Y/1rFM5UnlTeVV5W34fGu8jIfXi4uP8wvz1UWKq/DOwuF8ky5PT//3F98/qec/+xLcaOnZ+JC63UcFwpJr++LOuPxiany6dGJ6VIx6f31bYvN/t8d5Q6O"
        + "j5Vu148Di9mOzNTNicny9mIA7IKuDfn/kU3yD6REzi4A+QfkH5B/QP4B+QfkH5B/QP4B+QfkHwDYy1bv6dt4/1/9nuBqdiCzM3e9AnvB6NhMeXpyJF8emSl3"
        + "2h2QKteu3xB7AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIA28hvcmUNC"
        + "AJABAA==";

    private static final String FIXTURE = "fixture content\n".repeat(150);
    private static final String LONG_NAME = "a very long file name that does not fit into the 8.3 format.txt";

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

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
        checkMoreArchives(root, src);
        checkPakArchive(root);
        checkDmgImage(root);
        checkDiskImages(root);
        checkPackages(root);
        checkCabinet(root);
        checkIconImages();
        checkSvgImages();
        checkEncryptedArchives(root);
        checkArchiveAttributes(root);
        checkNestedArchives(root);
        checkEmbeddedArchives(root);
        checkWatcher(root);
        checkUtils();
        checkPlatform(root);
        checkDesktopEntry(root);
        checkWindowsMenuEntry(root);
        checkRenameSelection(root);
    }

    /** The menu entry an AppImage writes for itself, and when it leaves the menu to another integration. */
    private static void checkDesktopEntry(final @NotNull Path root) throws Exception {
        final Path home = Files.createDirectories(root.resolve("home"));
        final Path appDir = Files.createDirectories(root.resolve("AppDir"));
        Files.writeString(appDir.resolve("tcm.svg"), "<svg/>");
        final Path apps = home.resolve(".local/share/applications");
        final Path entry = apps.resolve("tcm.desktop");
        final Path icon = home.resolve(".local/share/icons/hicolor/scalable/apps/tcm.svg");
        final Path appImage = root.resolve("Down loads/tcm-1.0.0-linux-amd64.AppImage");
        final Map<String, String> env = new HashMap<>();
        env.put("APPIMAGE", appImage.toString());
        env.put("APPDIR", appDir.toString());
        env.put("XDG_DATA_DIRS", root.resolve("system").toString());

        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.INTEGRATED, "desktop entry: first launch");
        final String written = Files.readString(entry);
        final String appImageEsc = appImage.toString().replace("\\", "\\\\\\\\");
        final String iconEsc = icon.toString().replace("\\", "\\\\");
        check(written.contains("\nExec=\"" + appImageEsc + "\" %F\n"), "desktop entry: quoted Exec: " + written);
        check(written.contains("\nTryExec=" + iconEsc + "\n") || written.contains("\nTryExec=" + appImage.toString().replace("\\", "\\\\") + "\n"), "desktop entry: keys");
        check(written.contains("\nStartupWMClass=tcm-Main\n"), "desktop entry: startup wm class");
        check(written.contains("\nIcon=" + iconEsc + "\n") && Files.readString(icon).equals("<svg/>"), "desktop entry: icon copied");
        final FileTime mtime = Files.getLastModifiedTime(entry);
        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.INTEGRATED, "desktop entry: second launch");
        check(Files.getLastModifiedTime(entry).equals(mtime), "desktop entry: unchanged entry not rewritten");

        final Path moved = root.resolve("Apps/100%.AppImage");
        env.put("APPIMAGE", moved.toString());
        AppImageDesktopEntry.reconcile(env, home);
        final String movedEsc = root.resolve("Apps/100%%.AppImage").toString().replace("\\", "\\\\\\\\");
        check(Files.readString(entry).contains("\nExec=\"" + movedEsc + "\" %F\n"), "desktop entry: moved AppImage");

        Files.writeString(entry, Files.readString(entry) + "NoDisplay=true\n");
        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.HIDDEN_BY_USER, "desktop entry: hidden by user");
        check(Files.readString(entry).contains("NoDisplay=true"), "desktop entry: hidden entry kept");
        Files.delete(entry);

        AppImageDesktopEntry.reconcile(env, home);
        final Path gearLever = apps.resolve("tcm-gearlever.desktop");
        Files.writeString(gearLever, "[Desktop Entry]\nName=TCM\nExec=env DESKTOPINTEGRATION=1 " + moved + " %F\n");
        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.EXTERNAL, "desktop entry: foreign entry");
        check(!Files.exists(entry), "desktop entry: withdrawn for a foreign entry");
        Files.delete(gearLever);

        AppImageDesktopEntry.reconcile(env, home);
        env.put("DESKTOPINTEGRATION", "1");
        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.EXTERNAL, "desktop entry: DESKTOPINTEGRATION");
        check(!Files.exists(entry) && !Files.exists(icon), "desktop entry: withdrawn with its icon");
        env.remove("DESKTOPINTEGRATION");

        Files.writeString(entry, "[Desktop Entry]\nName=Mine\n");
        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.EXTERNAL, "desktop entry: user's own file");
        check(Files.readString(entry).equals("[Desktop Entry]\nName=Mine\n"), "desktop entry: user's own file untouched");
        Files.delete(entry);

        AppImageDesktopEntry.reconcile(env, home);
        Files.createDirectories(root.resolve("system/applications"));
        Files.writeString(root.resolve("system/applications/tcm.desktop"), "[Desktop Entry]\nName=TCM\n");
        env.remove("APPIMAGE");
        check(AppImageDesktopEntry.reconcile(env, home) == AppImageDesktopEntry.Status.NOT_APPIMAGE, "desktop entry: package run");
        check(!Files.exists(entry), "desktop entry: withdrawn for an installed package");
    }

    private static void checkWindowsMenuEntry(final @NotNull Path root) throws Exception {
        final Path winDir = Files.createDirectories(root.resolve("win-programs"));
        final Path exe1 = root.resolve("bin/tcm.exe");
        final Path exe2 = root.resolve("other-bin/tcm.exe");
        Files.createDirectories(exe1.getParent());
        Files.createDirectories(exe2.getParent());
        Files.writeString(exe1, "exe1");
        Files.writeString(exe2, "exe2");

        final Path shortcut = winDir.resolve(WindowsMenuEntry.SHORTCUT_FILE_NAME);

        // In test mode on non-native environment, zero-arg reconcile() returns NOT_WINDOWS_NATIVE
        check(WindowsMenuEntry.reconcile() == WindowsMenuEntry.Status.NOT_WINDOWS_NATIVE, "windows menu: jvm mode");

        if (!POSIX) {
            // First launch: creates shortcut
            check(WindowsMenuEntry.reconcile(exe1, winDir) == WindowsMenuEntry.Status.CREATED, "windows menu: first launch created");
            check(Files.exists(shortcut), "windows menu: shortcut file exists");
            check(WindowsMenuEntry.isUpToDate(shortcut, exe1), "windows menu: shortcut is up to date");

            final FileTime mtime = Files.getLastModifiedTime(shortcut);
            // Second launch: already up to date
            check(WindowsMenuEntry.reconcile(exe1, winDir) == WindowsMenuEntry.Status.UP_TO_DATE, "windows menu: second launch up to date");
            check(Files.getLastModifiedTime(shortcut).equals(mtime), "windows menu: up to date shortcut not rewritten");

            // Moved executable: updates existing shortcut
            check(WindowsMenuEntry.reconcile(exe2, winDir) == WindowsMenuEntry.Status.UPDATED, "windows menu: moved exe updated");
            check(WindowsMenuEntry.isUpToDate(shortcut, exe2), "windows menu: updated shortcut matches exe2");
            check(!WindowsMenuEntry.isUpToDate(shortcut, exe1), "windows menu: updated shortcut no longer matches exe1");

            // Third launch: up to date with new path
            check(WindowsMenuEntry.reconcile(exe2, winDir) == WindowsMenuEntry.Status.UP_TO_DATE, "windows menu: third launch up to date");
        }
    }

    /** In-place rename in panel: F2 / Shift+F6 toggles between base name and full name. */
    private static void checkRenameSelection(final @NotNull Path root) throws Exception {
        final FileTableModel model = new FileTableModel();
        final Path testFile = Files.writeString(root.resolve("document.long.name.txt"), "hello");
        final Path testDir = Files.createDirectories(root.resolve("sub.dir.name"));
        final Path noExtFile = Files.writeString(root.resolve("Makefile"), "all:");
        final Path dotFile = Files.writeString(root.resolve(".gitignore"), "*.class");

        final VFile vfFile = Vfs.stat(testFile);
        final VFile vfDir = Vfs.stat(testDir);
        final VFile vfNoExt = Vfs.stat(noExtFile);
        final VFile vfDot = Vfs.stat(dotFile);

        model.setFiles(List.of(vfFile, vfDir, vfNoExt, vfDot), false);
        final FileTable table = new FileTable(model);

        // 1. Regular file with extension
        final int fileRow = model.indexOf(vfFile.getName());
        table.setCursorRow(fileRow);
        check(table.getValueAt(fileRow, FileTableModel.COL_EXT).equals("txt"), "initially Ext column is txt");
        table.startRename(fileRow, (name, move) -> { });
        final JTextField field = table.getRenameField();
        check(field != null, "rename field created");
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "document.long.name".length(), "initially base name selected");
        check(table.getValueAt(fileRow, FileTableModel.COL_EXT).equals(""), "Ext column empty during rename");
        final Component extComp = table.prepareRenderer(table.getCellRenderer(fileRow, FileTableModel.COL_EXT), fileRow, FileTableModel.COL_EXT);
        check(extComp instanceof JLabel && ((JLabel) extComp).getText().isEmpty(), "Ext renderer empty during rename");

        final javax.swing.Action f2Action = field.getActionMap().get(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("F2")));
        check(f2Action != null, "F2 action bound in rename field");
        final javax.swing.Action shiftF6Action = field.getActionMap().get(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("shift F6")));
        check(shiftF6Action != null, "Shift+F6 action bound in rename field");

        // F2 when base name selected -> selects full name
        f2Action.actionPerformed(null);
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "document.long.name.txt".length(), "F2 selects whole name when part selected");

        // F2 when full name selected -> selects base name
        f2Action.actionPerformed(null);
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "document.long.name".length(), "F2 selects base name when whole selected");

        // F2 from arbitrary partial selection -> selects full name
        field.select(2, 6);
        f2Action.actionPerformed(null);
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "document.long.name.txt".length(), "F2 selects whole name from arbitrary selection");

        // F2 from no selection (caret at offset) -> selects full name
        field.setCaretPosition(5);
        f2Action.actionPerformed(null);
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "document.long.name.txt".length(), "F2 selects whole name from caret position");

        // Shift+F6 also toggles: currently whole name is selected -> selects base name
        shiftF6Action.actionPerformed(null);
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "document.long.name".length(), "Shift+F6 selects base name when whole selected");

        // After editing text in the field: derives base name from current text
        field.setText("new_archive.tar.gz");
        field.select(0, "new_archive.tar.gz".length());
        f2Action.actionPerformed(null);
        check(field.getSelectionStart() == 0 && field.getSelectionEnd() == "new_archive.tar".length(), "F2 selects base name of edited text");

        table.stopRename();
        check(table.getValueAt(fileRow, FileTableModel.COL_EXT).equals("txt"), "Ext column restored after stopRename");
        final Component extCompRestored = table.prepareRenderer(table.getCellRenderer(fileRow, FileTableModel.COL_EXT), fileRow, FileTableModel.COL_EXT);
        check(extCompRestored instanceof JLabel && ((JLabel) extCompRestored).getText().equals("txt"), "Ext renderer restored after stopRename");

        // 2. Directory
        final int dirRow = model.indexOf(vfDir.getName());
        table.setCursorRow(dirRow);
        table.startRename(dirRow, (name, move) -> { });
        final JTextField dirField = table.getRenameField();
        check(dirField != null && dirField.getSelectionStart() == 0 && dirField.getSelectionEnd() == "sub.dir.name".length(),
            "directory initially whole name selected");
        final javax.swing.Action dirF2 = dirField.getActionMap().get(dirField.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("F2")));
        dirField.select(1, 4);
        dirF2.actionPerformed(null);
        check(dirField.getSelectionStart() == 0 && dirField.getSelectionEnd() == "sub.dir.name".length(),
            "directory F2 selects whole name when part selected");
        table.stopRename();

        // 3. File without extension
        final int noExtRow = model.indexOf(vfNoExt.getName());
        table.setCursorRow(noExtRow);
        table.startRename(noExtRow, (name, move) -> { });
        final JTextField noExtField = table.getRenameField();
        check(noExtField != null && noExtField.getSelectionStart() == 0 && noExtField.getSelectionEnd() == "Makefile".length(),
            "no-ext initially whole name selected");
        final javax.swing.Action noExtF2 = noExtField.getActionMap().get(noExtField.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("F2")));
        noExtField.select(0, 4);
        noExtF2.actionPerformed(null);
        check(noExtField.getSelectionStart() == 0 && noExtField.getSelectionEnd() == "Makefile".length(),
            "no-ext F2 selects whole name when part selected");
        table.stopRename();

        // 4. Dotfile
        final int dotRow = model.indexOf(vfDot.getName());
        table.setCursorRow(dotRow);
        table.startRename(dotRow, (name, move) -> { });
        final JTextField dotField = table.getRenameField();
        check(dotField != null && dotField.getSelectionStart() == 0 && dotField.getSelectionEnd() == ".gitignore".length(),
            "dotfile initially whole name selected");
        final javax.swing.Action dotF2 = dotField.getActionMap().get(dotField.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("F2")));
        dotField.select(1, 5);
        dotF2.actionPerformed(null);
        check(dotField.getSelectionStart() == 0 && dotField.getSelectionEnd() == ".gitignore".length(),
            "dotfile F2 selects whole name when part selected");
        table.stopRename();
    }

    /** Behavior that differs between OSes: deleting read-only files, the command line (spaces, console encoding), attributes. */
    private static void checkPlatform(final @NotNull Path root) throws Exception {
        final Path ro = Files.writeString(root.resolve("ro.txt"), "ro");
        check(ro.toFile().setWritable(false), "make file read-only");
        Trash.delete(ro);
        check(!Files.exists(ro), "delete read-only file");
        final Path ro2 = Files.writeString(root.resolve("ro2.txt"), "ro");
        check(ro2.toFile().setWritable(false), "make file read-only again");
        check(Trash.deleteIfExists(ro2) && !Files.exists(ro2), "deleteIfExists read-only file");
        check(!Trash.deleteIfExists(ro2), "deleteIfExists missing file");

        final Path dosOut = Files.createDirectories(root.resolve("dosattr"));
        final Path hidden = Files.writeString(dosOut.resolve("h.txt"), "h");
        FileAttrs.apply(hidden, "r-h-");
        final VFile hiddenFile = Vfs.stat(hidden);
        if (POSIX) {
            check(!Files.isWritable(hidden), "dos read-only flag on Unix removes write permission");
        } else {
            check(hiddenFile.isHidden() && hiddenFile.getAttrs().equals("r-h-"), "dos attributes on Windows: " + hiddenFile.getAttrs());
        }
        Trash.delete(hidden);

        final Path spaced = Files.createDirectories(root.resolve("dir with space"));
        final boolean windows = !POSIX;
        final Path script = spaced.resolve(windows ? "echo args.cmd" : "echo args.sh");
        Files.writeString(script, windows ? "@echo args:%*\r\n" : "#!/bin/sh\necho args:\"$@\"\n");
        script.toFile().setExecutable(true);
        final String quoted = "\"" + script + "\"";
        check(runShell(quoted + " \"x y\"", spaced).equals("args:" + (windows ? "\"x y\"" : "x y")), "command line: quoted program with arguments");
        final Charset cs = ConsoleWindow.consoleCharset();
        final String text = cs.newEncoder().canEncode("Привет") ? "Привет" : "hello";
        check(runShell("echo " + text, spaced).equals(text), "console output encoding " + cs + ": " + text);
        check(runShell("cd", spaced).contains("dir with space") || !windows, "console working directory");

        final Path target = Files.writeString(root.resolve("link-target.txt"), "t");
        final Path link = root.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, target);
            check(Files.isSymbolicLink(link) && Files.readString(link).equals("t"), "symbolic link");
            Trash.delete(link);
            check(Files.exists(target) && !Files.exists(link, LinkOption.NOFOLLOW_LINKS), "deleting a link keeps its target");
        } catch (final FileSystemException | UnsupportedOperationException e) {
            System.out.println("  skip symbolic links: " + e);
        }
    }

    private static @NotNull String runShell(final @NotNull String command, final @NotNull Path dir) throws Exception {
        final Process p = new ProcessBuilder(ConsoleWindow.shellCommand(command)).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        final String out = new String(p.getInputStream().readAllBytes(), ConsoleWindow.consoleCharset()).trim();
        p.waitFor();

        return out;
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
            check(Vfs.file(tar + "!/d/x.txt").getAttrs().equals("rwxr-x---"), "tar attributes " + name);
            check(!POSIX || Files.getPosixFilePermissions(out.resolve("d/x.txt")).equals(PosixFilePermissions.fromString("rwxr-x---")),
                "unpack keeps tar permissions " + name);
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

        checkCompressedFiles(root);

        final Path rar = Files.write(root.resolve("r.rar"), Base64.getDecoder().decode(RAR5));
        check(names(Vfs.resolve(rar.toString())).equals(List.of("d")), "rar listing");
        check(Vfs.readAll(Vfs.file(rar + "!/d/x.txt")).length == 2400, "rar read compressed entry");
        final Path unrar = Files.createDirectories(root.resolve("unrar"));
        exec(new UnpackJob(List.of(Vfs.stat(rar)), Vfs.resolve(unrar.toString()), "*"));
        check(Files.readString(unrar.resolve("d/x.txt")).equals("rar content\n".repeat(200)), "unpack rar");
        check(!Vfs.file(rar + "!/d/x.txt").getAttrs().isEmpty(), "rar attributes");
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

    /** Unix permissions and DOS flags of ZIP entries: shown in the Attr column and set on unpacking. */
    private static void checkArchiveAttributes(final @NotNull Path root) throws Exception {
        final Path zip = root.resolve("attrs.zip");
        try (ZipArchiveOutputStream z = new ZipArchiveOutputStream(zip)) {
            final ZipArchiveEntry unix = new ZipArchiveEntry("unix.sh");
            unix.setUnixMode(0100640);
            z.putArchiveEntry(unix);
            z.write('u');
            z.closeArchiveEntry();
            final ZipArchiveEntry dos = new ZipArchiveEntry("dos.txt");
            // read-only + archive
            dos.setExternalAttributes(0x21);
            z.putArchiveEntry(dos);
            z.write('d');
            z.closeArchiveEntry();
        }
        check(Vfs.file(zip + "!/unix.sh").getAttrs().equals("rw-r-----"), "zip unix attributes: " + Vfs.file(zip + "!/unix.sh").getAttrs());
        check(Vfs.file(zip + "!/dos.txt").getAttrs().equals("ra--"), "zip dos attributes: " + Vfs.file(zip + "!/dos.txt").getAttrs());
        final Path out = Files.createDirectories(root.resolve("attrs.out"));
        exec(new UnpackJob(List.of(Vfs.stat(zip)), Vfs.resolve(out.toString()), "*"));
        if (POSIX) {
            check(Files.getPosixFilePermissions(out.resolve("unix.sh")).equals(PosixFilePermissions.fromString("rw-r-----")),
                "unpack keeps zip permissions");
            check(!Files.isWritable(out.resolve("dos.txt")), "unpack keeps zip read-only flag");
            // otherwise the temporary directory is not deleted on Windows; on Unix the directory permissions decide
            Files.setPosixFilePermissions(out.resolve("dos.txt"), PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    /** An archive inside an archive is unpacked into a temporary file, opened read-only and deleted with the last release. */
    private static void checkNestedArchives(final @NotNull Path root) throws Exception {
        final Path inner = root.resolve("inner.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(inner))) {
            z.putNextEntry(new ZipEntry("d/n.txt"));
            z.write("nested".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        final Path outer = root.resolve("outer.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(outer))) {
            z.putNextEntry(new ZipEntry("sub/inner.zip"));
            z.write(Files.readAllBytes(inner));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("plain.txt"));
            z.write('p');
            z.closeEntry();
        }
        final Path tmp = Vfs.openNested(Vfs.file(outer + "!/sub/inner.zip"));
        check(tmp != null && names(Vfs.archiveDir(tmp, "")).equals(List.of("d")), "nested archive listing");
        check(new String(Vfs.readAll(Vfs.file(tmp + "!/d/n.txt")), StandardCharsets.UTF_8).equals("nested"), "nested archive read");
        check(Vfs.displayLocation(Vfs.archiveDir(tmp, "d")).equals(outer + "!/sub/inner.zip!/d"), "nested display location");
        check(Vfs.archiveDir(outer, "sub").equals(Vfs.parent(Vfs.archiveDir(tmp, ""))), "nested parent is the outer directory");
        check(Vfs.diskPath(Vfs.archiveDir(tmp, "d")).equals(outer), "nested disk path");
        check(failsWithIo(() -> exec(new CopyJob(List.of(Vfs.stat(inner)), Vfs.archiveDir(tmp, "d"), null, "*", false))), "nested is read-only");
        Vfs.retainNested(tmp);
        Vfs.retainNested(tmp);
        Vfs.releaseNested(tmp);
        check(Files.exists(tmp), "nested kept while retained");
        Vfs.releaseNested(tmp);
        check(!Files.exists(tmp) && !Files.exists(tmp.getParent()), "nested temporary file deleted");
        check(Vfs.openNested(Vfs.file(outer + "!/plain.txt")) == null, "non-archive entry is not opened as nested");
    }

    /**
     * Ctrl+PgDn on a file that is not an archive: an archive starting in its first 10 MB is found past program code with fake
     * signatures in it — a ZIP is read in place, other formats are cut out into a temporary copy; both are read-only.
     */
    private static void checkEmbeddedArchives(final @NotNull Path root) throws Exception {
        final ByteArrayOutputStream stub = new ByteArrayOutputStream();
        final byte[] noise = new byte[5000];
        new Random(7).nextBytes(noise);
        stub.write(noise);
        stub.write("PK\3\4 Rar!\u001A\u0007\0 7z\u00BC\u00AF\u0027\u001C MSCF\0\0\0\0 BZh9 ".getBytes(StandardCharsets.ISO_8859_1));
        stub.write(new byte[] {0x1F, (byte) 0x8B, 8, 0, (byte) 0xFD, '7', 'z', 'X', 'Z', 0});
        check(Files.write(root.resolve("program.exe"), stub.toByteArray()) != null
            && Vfs.openEmbedded(Vfs.stat(root.resolve("program.exe")), Progress.NONE) == null, "fake signatures are not archives");

        final ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("z/a.txt"));
            z.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        final Path zipSfx = Files.write(root.resolve("zipsfx.exe"), concat(stub.toByteArray(), zip.toByteArray()));
        check(failsWithIo(() -> Vfs.archiveDir(zipSfx, "")), "zip sfx is not an archive by itself");
        final Path inPlace = Vfs.openEmbedded(Vfs.stat(zipSfx), Progress.NONE);
        check(zipSfx.equals(inPlace) && read(zipSfx + "!/z/a.txt").equals(FIXTURE), "zip sfx read in place");
        check(failsWithIo(() -> Vfs.checkWritable(zipSfx)), "zip sfx is read-only");
        check(Vfs.parent(Vfs.archiveDir(zipSfx, "")).equals(Vfs.stat(root)), "zip sfx parent");

        final ByteArrayOutputStream tgz = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(tgz))) {
            final TarArchiveEntry e = new TarArchiveEntry("t/b.txt");
            e.setSize(FIXTURE.length());
            tar.putArchiveEntry(e);
            tar.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
            tar.closeArchiveEntry();
        }
        final byte[] script = "#!/bin/sh\ntail -c +123 \"$0\" | tar xzf -\nexit 0\n".getBytes(StandardCharsets.US_ASCII);
        final Path run = Files.write(root.resolve("setup.run"), concat(script, tgz.toByteArray()));
        final Path cut = Vfs.openEmbedded(Vfs.stat(run), Progress.NONE);
        check(cut != null && !cut.equals(run) && Vfs.index(cut).getFormat() == ArchiveFormat.TAR_GZ, "makeself tar.gz cut out");
        check(read(cut + "!/t/b.txt").equals(FIXTURE), "makeself tar.gz read");
        check(Vfs.displayLocation(Vfs.archiveDir(cut, "t")).equals(run + "!/t"), "embedded display location");
        check(Vfs.parent(Vfs.archiveDir(cut, "")).equals(Vfs.stat(root)), "embedded parent is the file's directory");
        check(Vfs.diskPath(Vfs.archiveDir(cut, "t")).equals(run), "embedded disk path");
        check(failsWithIo(() -> Vfs.checkWritable(cut)), "embedded copy is read-only");
        Vfs.retainNested(cut);
        Vfs.releaseNested(cut);
        check(!Files.exists(cut), "embedded copy deleted on release");

        final Path sevenZip = root.resolve("payload.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(sevenZip.toFile())) {
            final SevenZArchiveEntry file = new SevenZArchiveEntry();
            file.setName("s/c.txt");
            file.setSize(FIXTURE.length());
            out.putArchiveEntry(file);
            out.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        // a signed installer has its certificate after the archive: only the archive part is copied
        final byte[] sfx = concat(concat(stub.toByteArray(), Files.readAllBytes(sevenZip)), noise);
        final Path outer = root.resolve("installers.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(outer))) {
            z.putNextEntry(new ZipEntry("setup.exe"));
            z.write(sfx);
            z.closeEntry();
        }
        check(Vfs.openNested(Vfs.file(outer + "!/setup.exe")) == null, "7z sfx in a zip is not an archive by itself");
        final Path nested = Vfs.openNested(Vfs.file(outer + "!/setup.exe"), Progress.NONE, true);
        check(nested != null && Files.size(nested) == Files.size(sevenZip) && read(nested + "!/s/c.txt").equals(FIXTURE), "7z sfx in a zip");
        check(Vfs.displayLocation(Vfs.archiveDir(nested, "s")).equals(outer + "!/setup.exe!/s"), "7z sfx in a zip display location");
        check(Vfs.parent(Vfs.archiveDir(nested, "")).equals(Vfs.archiveDir(outer, "")), "7z sfx in a zip parent");
        Vfs.retainNested(nested);
        Vfs.releaseNested(nested);
        check(!Files.exists(nested), "7z sfx in a zip copy deleted");

        final byte[] far = new byte[EmbeddedArchives.SEARCH_LIMIT + 1];
        check(Vfs.openEmbedded(Vfs.stat(Files.write(root.resolve("far.run"), concat(far, tgz.toByteArray()))), Progress.NONE) == null,
            "archive after 10 MB is not searched");
    }

    private static byte @NotNull [] concat(final byte @NotNull [] a, final byte @NotNull [] b) {
        final byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);

        return result;
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
            e.setMode(0100750);
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

    /** Creating TAR, TAR.GZ, TAR.BZ2, TAR.XZ; reading 7z, ISO (Rock Ridge, Joliet) and squashfs. */
    private static void checkMoreArchives(final @NotNull Path root, final @NotNull Path src) throws Exception {
        for (final String name : List.of("p.tar", "p.tar.gz", "p.tar.bz2", "p.tar.xz")) {
            final Path tar = root.resolve(name);
            exec(new PackJob(Vfs.list(Vfs.resolve(src.toString())), tar, true, false));
            check(names(Vfs.resolve(tar.toString())).equals(List.of("a.txt", "b.md", "sub")), "pack " + name + ": " + names(Vfs.resolve(tar.toString())));
            check(read(tar + "!/sub/deep/c.txt").equals("deep file"), "read packed " + name);
        }
        final Path toMove = Files.createDirectories(root.resolve("to-move/d"));
        Files.writeString(toMove.resolve("f.txt"), "moved");
        exec(new PackJob(List.of(Vfs.stat(toMove)), root.resolve("m.tgz"), true, true));
        check(!Files.exists(toMove) && read(root.resolve("m.tgz") + "!/d/f.txt").equals("moved"), "pack tgz with move");

        final Path sevenZip = root.resolve("plain.7z");
        try (SevenZOutputFile out = new SevenZOutputFile(sevenZip.toFile())) {
            final SevenZArchiveEntry dir = new SevenZArchiveEntry();
            dir.setName("d");
            dir.setDirectory(true);
            out.putArchiveEntry(dir);
            out.closeArchiveEntry();
            final SevenZArchiveEntry file = new SevenZArchiveEntry();
            file.setName("d/x.txt");
            file.setSize(FIXTURE.length());
            out.putArchiveEntry(file);
            out.write(FIXTURE.getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        check(names(Vfs.resolve(sevenZip.toString())).equals(List.of("d")) && read(sevenZip + "!/d/x.txt").equals(FIXTURE), "7z read");
        final Path un7z = Files.createDirectories(root.resolve("un7z"));
        exec(new UnpackJob(List.of(Vfs.stat(sevenZip)), Vfs.resolve(un7z.toString()), "*"));
        check(Files.readString(un7z.resolve("d/x.txt")).equals(FIXTURE), "7z unpack");
        final TestJob test7z = new TestJob(List.of(Vfs.stat(sevenZip)));
        exec(test7z);
        check(test7z.getReport().get(0).startsWith("OK"), "7z test");

        for (final String[] iso : new String[][] {{"rr.iso", ISO_ROCK_RIDGE}, {"joliet.bin", ISO_JOLIET}}) {
            final Path image = Files.write(root.resolve(iso[0]), gunzip(iso[1]));
            check(ArchiveFormat.detect(image) == ArchiveFormat.ISO, "iso signature " + iso[0]);
            final VFile isoRoot = Vfs.archiveDir(image, "");
            check(names(isoRoot).equals(List.of(LONG_NAME, "d")), "iso names " + iso[0] + ": " + names(isoRoot));
            check(Vfs.resolve(image + "!/d/empty").isDirectory() && read(image + "!/d/x.txt").equals(FIXTURE), "iso read " + iso[0]);
        }
        check(Vfs.file(root.resolve("rr.iso") + "!/d/x.txt").getAttrs().equals("rwxr-xr-x"), "iso rock ridge mode");
        final Path unIso = Files.createDirectories(root.resolve("uniso"));
        exec(new UnpackJob(List.of(Vfs.stat(root.resolve("rr.iso"))), Vfs.resolve(unIso.toString()), "*"));
        check(Files.readString(unIso.resolve("d/x.txt")).equals(FIXTURE) && Files.exists(unIso.resolve(LONG_NAME)), "iso unpack");

        final Path squash = Files.write(root.resolve("image.sfs"), Base64.getDecoder().decode(SQUASHFS));
        check(names(Vfs.resolve(squash.toString())).equals(List.of(LONG_NAME, "d")), "squashfs names");
        check(read(squash + "!/d/x.txt").equals(FIXTURE) && Vfs.file(squash + "!/d/x.txt").getAttrs().equals("rwxr-xr-x"), "squashfs read");
        final Path disguised = Files.write(root.resolve("image.bin"), Base64.getDecoder().decode(SQUASHFS));
        check(ArchiveFormat.detect(disguised) == ArchiveFormat.SQUASHFS, "squashfs signature");
    }

    /** Chromium resource pack v5: names from IDs and content, an alias reads its target, detection by signature. */
    private static void checkPakArchive(final @NotNull Path root) throws Exception {
        final byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        final byte[] text = FIXTURE.getBytes(StandardCharsets.UTF_8);
        final int dataStart = 12 + 3 * 6 + 4;
        final ByteBuffer pak = ByteBuffer.allocate(dataStart + png.length + text.length).order(ByteOrder.LITTLE_ENDIAN);
        pak.putInt(5).put((byte) 1).put(new byte[3]).putShort((short) 2).putShort((short) 1);
        pak.putShort((short) 100).putInt(dataStart);
        pak.putShort((short) 101).putInt(dataStart + png.length);
        pak.putShort((short) 0).putInt(dataStart + png.length + text.length);
        pak.putShort((short) 200).putShort((short) 1);
        pak.put(png).put(text);
        final Path file = Files.write(root.resolve("res.pak"), pak.array());
        check(names(Vfs.resolve(file.toString())).equals(List.of("100.png", "101.txt", "200.txt")), "pak names");
        check(read(file + "!/101.txt").equals(FIXTURE) && read(file + "!/200.txt").equals(FIXTURE), "pak read with alias");
        check(Vfs.file(file + "!/100.png").getSize() == png.length, "pak size");
        final Path disguised = Files.write(root.resolve("res.bin"), pak.array());
        check(ArchiveFormat.detect(disguised) == ArchiveFormat.PAK, "pak signature");
        final TestJob test = new TestJob(List.of(Vfs.stat(file)));
        exec(test);
        check(test.getReport().get(0).startsWith("OK"), "pak test");
    }

    /**
     * A synthetic UDIF image whose partition has no supported file system: listed as a raw .img assembled from zlib, zero,
     * raw and ADC chunks; the free-space partition is hidden; detected by the trailer under another extension.
     */
    private static void checkDmgImage(final @NotNull Path root) throws Exception {
        final byte[] disk = new byte[6 * 512];
        final byte[] text = FIXTURE.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text, 0, disk, 0, 1024);
        Arrays.fill(disk, 4 * 512, 5 * 512, (byte) 'r');
        Arrays.fill(disk, 5 * 512, 6 * 512, (byte) 'a');
        final ByteArrayOutputStream file = new ByteArrayOutputStream();
        final Deflater deflater = new Deflater();
        deflater.setInput(Arrays.copyOf(disk, 1024));
        deflater.finish();
        final byte[] zlib = new byte[2048];
        final int zlibLength = deflater.deflate(zlib);
        deflater.end();
        file.write(zlib, 0, zlibLength);
        file.write(disk, 4 * 512, 512);
        // ADC: a one-byte literal run, then 3-byte back references (distance 1, up to 67 bytes each) repeating it
        final ByteArrayOutputStream adc = new ByteArrayOutputStream();
        adc.write(0x80);
        adc.write('a');
        for (int left = 511; left > 0; left -= 67) {
            adc.write(0x40 | Math.min(67, left) - 4);
            adc.write(0);
            adc.write(0);
        }
        adc.writeTo(file);
        final ByteBuffer mish = ByteBuffer.allocate(204 + 5 * 40);
        mish.put("mish".getBytes(StandardCharsets.ISO_8859_1)).putInt(1).putLong(1).putLong(6);
        mish.position(200);
        mish.putInt(5);
        mish.putInt(0x80000005).putInt(0).putLong(0).putLong(2).putLong(0).putLong(zlibLength);
        mish.putInt(0).putInt(0).putLong(2).putLong(2).putLong(0).putLong(0);
        mish.putInt(1).putInt(0).putLong(4).putLong(1).putLong(zlibLength).putLong(512);
        mish.putInt(0x80000004).putInt(0).putLong(5).putLong(1).putLong(zlibLength + 512).putLong(adc.size());
        mish.putInt(0xFFFFFFFF).putInt(0).putLong(6).putLong(0).putLong(0).putLong(0);
        final ByteBuffer free = ByteBuffer.allocate(204 + 40);
        free.put("mish".getBytes(StandardCharsets.ISO_8859_1)).putInt(1).putLong(0).putLong(1);
        free.position(200);
        free.putInt(1).putInt(2).putInt(0).putLong(0).putLong(1).putLong(0).putLong(0);
        final String xml = "<?xml version=\"1.0\"?><plist version=\"1.0\"><dict><key>resource-fork</key><dict><key>blkx</key><array>"
            + "<dict><key>Data</key><data>" + Base64.getMimeEncoder().encodeToString(free.array()) + "</data>"
            + "<key>Name</key><string> (Apple_Free : 0)</string></dict>"
            + "<dict><key>Data</key><data>" + Base64.getEncoder().encodeToString(mish.array()) + "</data>"
            + "<key>Name</key><string>disk image (Apple_HFS : 1)</string></dict></array></dict></dict></plist>";
        final byte[] plist = xml.getBytes(StandardCharsets.UTF_8);
        final int xmlOffset = file.size();
        file.write(plist);
        final ByteBuffer koly = ByteBuffer.allocate(512);
        koly.put("koly".getBytes(StandardCharsets.ISO_8859_1)).putInt(4).putInt(512);
        koly.putLong(216, xmlOffset).putLong(224, plist.length);
        file.write(koly.array());
        final Path image = Files.write(root.resolve("disk.dmg"), file.toByteArray());
        final String entry = "1 disk image (Apple_HFS 1).img";
        check(names(Vfs.resolve(image.toString())).equals(List.of(entry)), "dmg raw partition names");
        try (InputStream in = Vfs.openInput(Vfs.file(image + "!/" + entry))) {
            check(Arrays.equals(in.readAllBytes(), disk), "dmg partition data (zlib, zero, raw, adc)");
        }
        check(ArchiveFormat.detect(Files.write(root.resolve("disk.img"), file.toByteArray())) == ArchiveFormat.DMG, "dmg signature");
    }

    /**
     * Raw disk images: a bare FAT12 (VFAT names) and ext2 (a symbolic link shown as its target, Unix permissions), then both
     * as partitions of an MBR disk — raw .img files named after the file system labels (unique ignoring case), which open as nested
     * archives; recognized by signature under another extension. Then the same disk in every virtual machine format.
     */
    private static void checkDiskImages(final @NotNull Path root) throws Exception {
        final byte[] fat = gunzip(FAT12_IMAGE);
        final byte[] ext = gunzip(EXT2_IMAGE);
        final String longName = "d/Длинное имя файла.txt";
        final Path floppy = Files.write(root.resolve("floppy.img"), fat);
        check(names(Vfs.resolve(floppy.toString())).equals(List.of("d", "short.txt")), "fat12 image names");
        check(read(floppy + "!/" + longName).equals("fixture text\n") && read(floppy + "!/short.txt").equals("abc\n"), "fat12 image read");
        final Path linux = Files.write(root.resolve("linux.img"), ext);
        check(names(Vfs.resolve(linux.toString())).equals(List.of("d", "link", "lost+found", "short.txt")), "ext2 image names");
        check(read(linux + "!/link").equals("fixture text\n"), "ext2 symbolic link read as its target");
        check(Vfs.file(linux + "!/short.txt").getAttrs().equals("rw-rw-r--"), "ext2 permissions");
        final int start = 2048;
        final int fatSectors = fat.length / 512;
        final int extSectors = ext.length / 512;
        final int swapSectors = 256;
        final byte[] disk = new byte[(start + fatSectors + extSectors + swapSectors) * 512];
        final ByteBuffer mbr = ByteBuffer.wrap(disk).order(ByteOrder.LITTLE_ENDIAN);
        mbr.put(446 + 4, (byte) 0x01).putInt(446 + 8, start).putInt(446 + 12, fatSectors);
        mbr.put(462 + 4, (byte) 0x83).putInt(462 + 8, start + fatSectors).putInt(462 + 12, extSectors);
        mbr.put(478 + 4, (byte) 0x82).putInt(478 + 8, start + fatSectors + extSectors).putInt(478 + 12, swapSectors);
        mbr.putShort(510, (short) 0xAA55);
        System.arraycopy(fat, 0, disk, start * 512, fat.length);
        System.arraycopy(ext, 0, disk, (start + fatSectors) * 512, ext.length);
        Arrays.fill(disk, (start + fatSectors + extSectors) * 512 + 4096 - 10, (start + fatSectors + extSectors) * 512 + 4096, (byte) 'S');
        check(ArchiveFormat.detect(Files.write(root.resolve("disk.bin"), disk)) == ArchiveFormat.DISK_IMAGE, "disk image signature");
        final Path partitioned = Files.write(root.resolve("hdd.dd"), disk);
        checkPartitions(partitioned, fat, ext, longName, "mbr");
        final Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("vdi", DiskFixtures.vdi(disk));
        images.put("vhd", DiskFixtures.vhd(disk));
        images.put("vhdx", DiskFixtures.vhdx(disk));
        images.put("vmdk", DiskFixtures.vmdk(disk));
        images.put("qcow2", DiskFixtures.qcow2(disk));
        images.put("qed", DiskFixtures.qed(disk));
        images.put("hdd", DiskFixtures.parallels(disk));
        for (final Map.Entry<String, byte[]> e : images.entrySet()) {
            final Path image = Files.write(root.resolve("vm." + e.getKey()), e.getValue());
            check(e.getValue().length < disk.length || "vhdx".equals(e.getKey()), e.getKey() + " stores only the allocated blocks");
            check(ArchiveFormat.detect(image) == ArchiveFormat.VIRTUAL_DISK, e.getKey() + " signature");
            checkPartitions(image, fat, ext, longName, e.getKey());
        }
    }

    private static void checkPartitions(
        final @NotNull Path image,
        final byte @NotNull [] fat,
        final byte @NotNull [] ext,
        final @NotNull String longName,
        final @NotNull String what
    ) throws Exception {
        check(names(Vfs.resolve(image.toString())).equals(List.of("TINY.img", "partition3.img", "tiny (2).img")),
            what + " partitions as .img files: " + names(Vfs.resolve(image.toString())));
        check(Arrays.equals(Vfs.readAll(Vfs.file(image + "!/TINY.img")), fat) && Arrays.equals(Vfs.readAll(Vfs.file(image + "!/tiny (2).img")), ext),
            what + " partition data");
        check(!Vfs.mayBeNestedArchive(Vfs.file(image + "!/partition3.img")), what + " partition with no file system is not an archive");
        check(Vfs.openNested(Vfs.file(image + "!/partition3.img")) == null, what + " no file system: not unpacked");
        final Path tmp = Vfs.openNested(Vfs.file(image + "!/tiny (2).img"));
        check(tmp != null && Files.size(tmp) == ext.length && Arrays.equals(Files.readAllBytes(tmp), ext), what + " partition unpacked");
        check(names(Vfs.archiveDir(tmp, "")).equals(List.of("d", "link", "lost+found", "short.txt")), what + " nested partition listing");
        check(read(tmp + "!/link").equals(read(floppyOf(image, fat) + "!/" + longName)), what + " nested partition read");
        Vfs.retainNested(tmp);
        Vfs.releaseNested(tmp);
        check(!Files.exists(tmp), what + " nested partition deleted");
        final Path out = Files.createDirectories(image.resolveSibling("parts-" + what));
        exec(new CopyJob(List.of(Vfs.file(image + "!/TINY.img")), Vfs.resolve(out.toString()), null, "*", false));
        check(Arrays.equals(Files.readAllBytes(out.resolve("TINY.img")), fat), what + " partition copied out (sparse)");
    }

    private static @NotNull Path floppyOf(final @NotNull Path image, final byte @NotNull [] fat) throws IOException {
        final Path floppy = image.resolveSibling("floppy.img");
        if (!Files.exists(floppy)) {
            Files.write(floppy, fat);
        }

        return floppy;
    }

    /**
     * Plain .gz, .bz2, .xz: one entry named as the archive without the extension, with its real size; by signature (a gzip
     * takes the name from its header); a compressed TAR under another name is still a TAR; checksums checked by the test.
     */
    private static void checkCompressedFiles(final @NotNull Path root) throws Exception {
        final byte[] text = FIXTURE.getBytes(StandardCharsets.UTF_8);
        for (final String ext : List.of("gz", "bz2", "xz")) {
            final ByteArrayOutputStream packed = new ByteArrayOutputStream();
            final GzipParameters params = new GzipParameters();
            params.setFileName("inner.log");
            try (OutputStream out = "gz".equals(ext) ? new GzipCompressorOutputStream(packed, params)
                : "bz2".equals(ext) ? new BZip2CompressorOutputStream(packed) : new XZCompressorOutputStream(packed)) {
                out.write(text);
            }
            final ArchiveFormat format = ArchiveFormat.valueOf(ext.toUpperCase(Locale.ROOT));
            final Path file = Files.write(root.resolve("Report.TXT." + ext), packed.toByteArray());
            check(ArchiveFormat.of(file.getFileName().toString()) == format && Vfs.stat(file).isArchiveFile(), ext + " by extension");
            check(names(Vfs.resolve(file.toString())).equals(List.of("Report.TXT")), ext + " entry name");
            check(read(file + "!/Report.TXT").equals(FIXTURE) && Vfs.file(file + "!/Report.TXT").getSize() == text.length, ext + " read and size");
            final Path disguised = Files.write(root.resolve(ext + "-data.bin"), packed.toByteArray());
            check(ArchiveFormat.detect(disguised) == format, ext + " signature");
            check(names(Vfs.archiveDir(disguised, "")).equals(List.of("gz".equals(ext) ? "inner.log" : ext + "-data")), ext + " name by signature");
            final Path tar = Files.copy(root.resolve("t.tar." + ext), root.resolve("tar-" + ext + ".bin"));
            check(ArchiveFormat.detect(tar).isTar(), "tar." + ext + " signature");
            final Path unpacked = Files.createDirectories(root.resolve("un-" + ext));
            exec(new UnpackJob(List.of(Vfs.stat(file)), Vfs.resolve(unpacked.toString()), "*"));
            check(Files.readString(unpacked.resolve("Report.TXT")).equals(FIXTURE), ext + " unpack");
            final byte[] bad = packed.toByteArray();
            bad[bad.length / 2] ^= 0x55;
            final TestJob test = new TestJob(List.of(Vfs.stat(Files.write(root.resolve("bad." + ext), bad))));
            exec(test);
            check(test.getReport().get(0).startsWith("ERROR"), "test detects broken " + ext);
        }
    }

    /** A .deb (gzip control, xz data) shown as data + DEBIAN; an .rpm with a gzip cpio payload and a hard link pair. */
    private static void checkPackages(final @NotNull Path root) throws Exception {
        final ByteArrayOutputStream control = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(control))) {
            tarFile(tar, "./control", "Package: x\n".getBytes(StandardCharsets.UTF_8), 0644);
        }
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new XZCompressorOutputStream(data))) {
            tarFile(tar, "./usr/bin/tool", FIXTURE.getBytes(StandardCharsets.UTF_8), 0755);
        }
        final ByteArrayOutputStream deb = new ByteArrayOutputStream();
        deb.write("!<arch>\n".getBytes(StandardCharsets.ISO_8859_1));
        arMember(deb, "debian-binary", "2.0\n".getBytes(StandardCharsets.ISO_8859_1));
        arMember(deb, "control.tar.gz", control.toByteArray());
        arMember(deb, "data.tar.xz", data.toByteArray());
        final Path debFile = Files.write(root.resolve("p.deb"), deb.toByteArray());
        check(names(Vfs.resolve(debFile.toString())).equals(List.of("DEBIAN", "usr")), "deb names");
        check(read(debFile + "!/usr/bin/tool").equals(FIXTURE) && Vfs.file(debFile + "!/usr/bin/tool").getAttrs().equals("rwxr-xr-x"), "deb data");
        check(read(debFile + "!/DEBIAN/control").equals("Package: x\n"), "deb control");
        check(ArchiveFormat.detect(Files.write(root.resolve("deb.bin"), deb.toByteArray())) == ArchiveFormat.DEB, "deb signature");

        final ByteArrayOutputStream cpio = new ByteArrayOutputStream();
        try (CpioArchiveOutputStream out = new CpioArchiveOutputStream(new GzipCompressorOutputStream(cpio), CpioConstants.FORMAT_NEW)) {
            final byte[] text = FIXTURE.getBytes(StandardCharsets.UTF_8);
            for (final String name : List.of("./usr/share/link", "./usr/share/data")) {
                final boolean last = name.endsWith("data");
                final CpioArchiveEntry e = new CpioArchiveEntry(CpioConstants.FORMAT_NEW, name, last ? text.length : 0);
                e.setMode(CpioConstants.C_ISREG | 0644);
                e.setInode(7);
                e.setNumberOfLinks(2);
                out.putArchiveEntry(e);
                if (last) {
                    out.write(text);
                }
                out.closeArchiveEntry();
            }
        }
        final ByteBuffer rpm = ByteBuffer.allocate(96 + 16 + 16 + cpio.size());
        rpm.putInt(0xEDABEEDB).position(96);
        rpm.putInt(0x8EADE801).putInt(0).putInt(0).putInt(0);
        rpm.putInt(0x8EADE801).putInt(0).putInt(0).putInt(0);
        rpm.put(cpio.toByteArray());
        final Path rpmFile = Files.write(root.resolve("p.rpm"), rpm.array());
        check(names(Vfs.resolve(rpmFile + "!/usr/share")).equals(List.of("data", "link")), "rpm names");
        check(read(rpmFile + "!/usr/share/data").equals(FIXTURE) && read(rpmFile + "!/usr/share/link").equals(FIXTURE), "rpm read with hard link");
        check(Vfs.file(rpmFile + "!/usr/share/link").getSize() == FIXTURE.length(), "rpm hard link size");
    }

    private static void tarFile(final @NotNull TarArchiveOutputStream tar, final @NotNull String name, final byte @NotNull [] content, final int mode)
        throws IOException {
        final TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(content.length);
        e.setMode(mode);
        tar.putArchiveEntry(e);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private static void arMember(final @NotNull ByteArrayOutputStream ar, final @NotNull String name, final byte @NotNull [] content)
        throws IOException {
        final String header = String.format("%-16s%-12s%-6s%-6s%-8s%-10s`\n", name, "0", "0", "0", "100644", content.length);
        ar.write(header.getBytes(StandardCharsets.ISO_8859_1));
        ar.write(content);
        if (content.length % 2 != 0) {
            ar.write('\n');
        }
    }

    /**
     * A cabinet with two folders: MSZIP of two blocks (the second one compressed against the first as its dictionary) and LZX
     * holding a stored block; detected by signature under another extension.
     */
    private static void checkCabinet(final @NotNull Path root) throws Exception {
        final byte[] text = FIXTURE.getBytes(StandardCharsets.UTF_8);
        final int half = text.length / 2;
        final byte[] block1 = mszipBlock(text, 0, half, null);
        final byte[] block2 = mszipBlock(text, half, text.length - half, Arrays.copyOf(text, half));
        final byte[] stored = "stored lzx data\n".repeat(9).getBytes(StandardCharsets.UTF_8);
        // LZX: no E8 header bit, block type 3, 24-bit length, padding to 16 bits, R0..R2, the raw bytes
        final int bits = 3 << 28 | stored.length << 4;
        final ByteBuffer lzx = ByteBuffer.allocate(4 + 12 + stored.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        lzx.putShort((short) (bits >>> 16)).putShort((short) bits).putInt(1).putInt(1).putInt(1).put(stored);
        final byte[][] names = {"dir\\a.txt".getBytes(StandardCharsets.US_ASCII), "b.txt".getBytes(StandardCharsets.US_ASCII)};
        final int filesStart = 36 + 2 * 8;
        final int dataStart = filesStart + 16 * 2 + names[0].length + names[1].length + 2;
        final int folder2 = dataStart + 8 * 2 + block1.length + block2.length;
        final ByteBuffer cab = ByteBuffer.allocate(folder2 + 8 + lzx.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        cab.putInt(0x4643534D).putInt(0).putInt(cab.capacity()).putInt(0).putInt(filesStart).putInt(0);
        cab.put((byte) 3).put((byte) 1).putShort((short) 2).putShort((short) 2).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        cab.putInt(dataStart).putShort((short) 2).putShort((short) 1);
        cab.putInt(folder2).putShort((short) 1).putShort((short) (3 | 15 << 8));
        final short date = (short) ((2024 - 1980) << 9 | 5 << 5 | 17);
        cab.putInt(text.length).putInt(0).putShort((short) 0).putShort(date).putShort((short) (12 << 11)).putShort((short) 0x21);
        cab.put(names[0]).put((byte) 0);
        cab.putInt(stored.length).putInt(0).putShort((short) 1).putShort(date).putShort((short) (12 << 11)).putShort((short) 0x20);
        cab.put(names[1]).put((byte) 0);
        cab.putInt(0).putShort((short) block1.length).putShort((short) half).put(block1);
        cab.putInt(0).putShort((short) block2.length).putShort((short) (text.length - half)).put(block2);
        cab.putInt(0).putShort((short) lzx.capacity()).putShort((short) stored.length).put(lzx.array());
        final Path file = Files.write(root.resolve("c.cab"), cab.array());
        check(names(Vfs.resolve(file.toString())).equals(List.of("b.txt", "dir")), "cab names");
        check(read(file + "!/dir/a.txt").equals(FIXTURE) && Vfs.file(file + "!/dir/a.txt").getAttrs().equals("ra--"), "cab mszip");
        check(read(file + "!/b.txt").equals(new String(stored, StandardCharsets.UTF_8)), "cab lzx stored block");
        check(ArchiveFormat.detect(Files.write(root.resolve("cab.bin"), cab.array())) == ArchiveFormat.CAB, "cab signature");
        final TestJob test = new TestJob(List.of(Vfs.stat(file)));
        exec(test);
        check(test.getReport().get(0).startsWith("OK"), "cab test");
    }

    private static byte @NotNull [] mszipBlock(final byte @NotNull [] data, final int off, final int len, final byte[] dictionary) {
        final Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        if (dictionary != null) {
            deflater.setDictionary(dictionary);
        }
        deflater.setInput(data, off, len);
        deflater.finish();
        final byte[] out = new byte[len + 64];
        out[0] = 'C';
        out[1] = 'K';
        final int n = deflater.deflate(out, 2, out.length - 2);
        deflater.end();

        return Arrays.copyOf(out, n + 2);
    }

    /** SVG for the viewer: its own size, viewBox only, gzip (.svgz), a thumbnail enlarged to the box, recognized as an image. */
    private static void checkSvgImages() throws Exception {
        final byte[] svg = ("<svg xmlns='http://www.w3.org/2000/svg' width='30' height='20'>"
            + "<rect width='30' height='20' fill='#ff0000'/></svg>").getBytes(StandardCharsets.UTF_8);
        final BufferedImage img = SvgImages.render(svg);
        check(img != null && img.getWidth() == 30 && img.getHeight() == 20 && img.getRGB(15, 10) == 0xFFFF0000, "svg render");
        final BufferedImage box = SvgImages.render(("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'>"
            + "<circle cx='50' cy='25' r='20' fill='blue'/></svg>").getBytes(StandardCharsets.UTF_8));
        check(box != null && box.getWidth() == 100 && box.getHeight() == 50 && box.getRGB(50, 25) == 0xFF0000FF, "svg viewBox size");
        final ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(gz)) {
            out.write(svg);
        }
        final BufferedImage svgz = SvgImages.render(gz.toByteArray());
        check(svgz != null && svgz.getRGB(1, 1) == 0xFFFF0000, "svgz render");
        final BufferedImage thumb = SvgImages.thumbnail(svg, 40);
        check(thumb != null && thumb.getWidth() == 40 && thumb.getHeight() == 27, "svg thumbnail");
        check(SvgImages.isSvg("A.SVG") && SvgImages.isSvg("b.svgz") && !SvgImages.isSvg("c.svg.txt"), "svg names");
    }

    /** ICO with a 32-bit DIB and ICNS with an RLE-packed RGB element plus its mask decode into ARGB images. */
    private static void checkIconImages() throws Exception {
        final ByteBuffer ico = ByteBuffer.allocate(6 + 16 + 40 + 2 * 2 * 4 + 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        ico.put((byte) 2).put((byte) 2).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32).putInt(40 + 16 + 8).putInt(22);
        ico.putInt(40).putInt(2).putInt(4).putShort((short) 1).putShort((short) 32).putInt(0).putInt(0).putLong(0).putLong(0);
        // bottom-up rows: the bottom row red, the top row half-transparent blue
        ico.putInt(0xFFFF0000).putInt(0xFFFF0000).putInt(0x800000FF).putInt(0x800000FF);
        final BufferedImage icon = IconImages.decode(ico.array(), 16);
        check(icon != null && icon.getWidth() == 2 && icon.getRGB(0, 0) == 0x800000FF && icon.getRGB(1, 1) == 0xFFFF0000, "ico decode");
        final ByteBuffer icns = ByteBuffer.allocate(8 + 8 + 3 * 4 + 8 + 256);
        icns.put("icns".getBytes(StandardCharsets.ISO_8859_1)).putInt(icns.capacity());
        icns.put("is32".getBytes(StandardCharsets.ISO_8859_1)).putInt(8 + 3 * 4);
        // each 256-byte plane: two runs of 128 equal bytes (0xFD = 0x80 + 128 - 3)
        for (final byte c : new byte[] {0x11, 0x22, 0x33}) {
            icns.put((byte) 0xFD).put(c).put((byte) 0xFD).put(c);
        }
        icns.put("s8mk".getBytes(StandardCharsets.ISO_8859_1)).putInt(8 + 256);
        for (int i = 0; i < 256; i++) {
            icns.put((byte) 0xCC);
        }
        final BufferedImage mac = IconImages.decode(icns.array(), IconImages.LARGEST);
        check(mac != null && mac.getWidth() == 16 && mac.getRGB(5, 9) == 0xCC112233, "icns decode");
    }

    /** Encrypted ZIP (AES, ZipCrypto) and 7z: the prompt answers a wrong password first, then the right one, which is remembered. */
    private static void checkEncryptedArchives(final @NotNull Path root) throws Exception {
        final List<String> prompts = new ArrayList<>();
        Passwords.setPrompt((name, retry) -> {
            prompts.add(name);

            return retry ? "123" : "bad";
        });
        try {
            for (final String[] a : new String[][] {{"aes.zip", AES_ZIP}, {"zipcrypto.zip", ZIPCRYPTO_ZIP}, {"content.7z", SEVEN_ZIP_CONTENT},
                {"headers.7z", SEVEN_ZIP_HEADERS}}) {
                final Path archive = Files.write(root.resolve(a[0]), Base64.getDecoder().decode(a[1]));
                prompts.clear();
                check(names(Vfs.resolve(archive.toString())).equals(List.of(LONG_NAME, "d")), "encrypted listing " + a[0]);
                check(read(archive + "!/d/x.txt").equals(FIXTURE), "encrypted read " + a[0]);
                check(prompts.size() == 2, "wrong password asked again " + a[0] + ": " + prompts);
                final Path out = Files.createDirectories(root.resolve("un-" + a[0]));
                exec(new UnpackJob(List.of(Vfs.stat(archive)), Vfs.resolve(out.toString()), "*"));
                check(Files.readString(out.resolve("d/x.txt")).equals(FIXTURE) && Files.readString(out.resolve(LONG_NAME)).equals("hi\n"),
                    "encrypted unpack " + a[0]);
                check(prompts.size() == 2, "password remembered " + a[0]);
                final TestJob test = new TestJob(List.of(Vfs.stat(archive)));
                exec(test);
                check(test.getReport().get(0).startsWith("OK"), "encrypted test " + a[0] + ": " + test.getReport());
            }
            final Path streamed = Files.write(root.resolve("streamed.zip"), Base64.getDecoder().decode(ZIPCRYPTO_STREAMED_ZIP));
            check(read(streamed + "!/-").equals(FIXTURE), "zipcrypto with data descriptor");
            boolean readOnly = false;
            try {
                Vfs.checkWritable(root.resolve("aes.zip"));
            } catch (final IOException e) {
                readOnly = true;
            }
            check(readOnly, "encrypted zip is read-only");
            Passwords.setPrompt((name, retry) -> null);
            final Path cancelled = Files.write(root.resolve("cancel.zip"), Base64.getDecoder().decode(AES_ZIP));
            boolean thrown = false;
            try {
                read(cancelled + "!/d/x.txt");
            } catch (final PasswordCancelledException e) {
                thrown = true;
            }
            check(thrown, "password prompt cancelled");
        } finally {
            Passwords.setPrompt((name, retry) -> null);
        }
    }

    private static @NotNull String read(final @NotNull String location) throws IOException {
        return new String(Vfs.readAll(Vfs.file(location)), StandardCharsets.UTF_8);
    }

    private static byte @NotNull [] gunzip(final @NotNull String base64) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return in.readAllBytes();
        }
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
