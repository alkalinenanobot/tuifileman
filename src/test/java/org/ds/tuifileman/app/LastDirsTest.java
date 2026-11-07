package org.ds.tuifileman.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LastDirsTest {
    @TempDir
    Path dir;

    @Test
    void bothFoldersComeBackInANewInstance() throws Exception {
        Path left = Files.createDirectory(dir.resolve("left"));
        Path right = Files.createDirectory(dir.resolve("right"));
        Path file = dir.resolve("state").resolve("last-dirs.txt");
        new LastDirs(file).save(left, right);
        assertArrayEquals(new Path[] {left, right}, new LastDirs(file).load());
    }

    @Test
    void missingFoldersMissingFilesAndDamagedLinesComeBackAsNullWithoutLosingTheOtherSide() throws Exception {
        Path left = Files.createDirectory(dir.resolve("left"));
        Path right = Files.createDirectory(dir.resolve("right"));
        Path file = dir.resolve("last-dirs.txt");
        assertArrayEquals(new Path[2], new LastDirs(file).load(), "no file");
        Files.writeString(file, "bad\u0000path\n\n");
        assertArrayEquals(new Path[2], new LastDirs(file).load(), "damaged lines");

        new LastDirs(file).save(left, right);
        Files.delete(left);
        assertArrayEquals(new Path[] {null, right}, new LastDirs(file).load());

        new LastDirs(file).save(null, right);
        assertEquals(List.of("", right.toString()), Files.readAllLines(file));
    }
}
