package io.featureflip.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileSnapshotStoreTest {
    @TempDir
    Path dir;

    private final List<String> warnings = new ArrayList<>();

    private FileSnapshotStore store(Path path) {
        return new FileSnapshotStore(path, warnings::add);
    }

    private List<String> names() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    void roundTripsUtf8Text() throws Exception {
        FileSnapshotStore store = store(dir.resolve("snap.json"));
        store.put("{\"a\":\"café ☃\"}");
        assertThat(store.get()).isEqualTo("{\"a\":\"café ☃\"}");
        assertThat(warnings).isEmpty();
        assertThat(names()).containsExactly("snap.json");
    }

    @Test
    void missingFileIsASilentMiss() {
        assertThat(store(dir.resolve("nope.json")).get()).isNull();
        assertThat(warnings).isEmpty();
    }

    @Test
    void fileModeIsOwnerOnly() throws Exception {
        Path path = dir.resolve("snap.json");
        store(path).put("x");
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void preExistingBroaderModeTargetEndsOwnerOnly() throws Exception {
        Path path = dir.resolve("snap.json");
        Files.writeString(path, "old");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw-rw-"));
        store(path).put("new");
        assertThat(Files.readString(path)).isEqualTo("new");
        Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path);
        assertThat(mode).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void leavesNoTempFileAndNeverCreatesTheParentDirectory() throws Exception {
        Path missingDir = dir.resolve("absent");
        FileSnapshotStore store = store(missingDir.resolve("snap.json"));
        store.put("x"); // must not throw
        assertThat(Files.exists(missingDir)).isFalse();
        assertThat(warnings).hasSize(1);
        assertThat(names()).isEmpty();
        assertThat(store.get()).isNull();
    }

    @Test
    void concurrentPutsLeaveOneCompleteFileAndNoTemps() throws Exception {
        Path path = dir.resolve("snap.json");
        FileSnapshotStore store = store(path);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch go = new CountDownLatch(1);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < writers; i++) texts.add("{\"writer\":" + i + ",\"pad\":\"" + "x".repeat(20_000) + "\"}");
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (String text : texts) {
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int n = 0; n < 5; n++) store.put(text);
                    return null;
                }));
            }
            go.countDown();
            for (var f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(texts).contains(Files.readString(path));
        assertThat(names()).containsExactly("snap.json");
        assertThat(warnings).isEmpty();
    }

    @Test
    void directoryAsFileIsAWarnedMiss() throws Exception {
        Path asDir = Files.createDirectory(dir.resolve("snap.json"));
        assertThat(store(asDir).get()).isNull();
        assertThat(warnings).hasSize(1);
        store(asDir).put("x"); // rename over a directory fails: warned, no temp left
        assertThat(names()).containsExactly("snap.json");
    }

    @Test
    void invalidUtf8IsReplacedNotThrown() throws Exception {
        Path path = dir.resolve("snap.json");
        Files.write(path, new byte[] {(byte) 0xff, (byte) 0xfe, 'x'});
        String text = store(path).get();
        assertThat(text).contains("x").contains("�");
    }
}
