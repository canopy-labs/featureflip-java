package io.featureflip.client.internal;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RedactingLoggerTest {
    private static final String KEY = "sdk_server_SentinelKeyMustNeverAppearInLogs-0123456789";

    private final List<String> lines = new ArrayList<>();
    private final List<Throwable> throwables = new ArrayList<>();

    /** A Logger that records what reaches the sink, formatted like SLF4J would. */
    private Logger sink() {
        return (Logger) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Logger.class},
            (proxy, method, args) -> {
                if (method.getName().startsWith("is")) return true;
                if (args != null && args.length == 2 && args[0] instanceof String && args[1] instanceof Object[]) {
                    Object[] fa = (Object[]) args[1];
                    String text = (String) args[0];
                    for (Object a : fa) {
                        if (a instanceof Throwable) { throwables.add((Throwable) a); continue; }
                        text = text.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(a)));
                    }
                    lines.add(text);
                }
                return null;
            });
    }

    @Test
    void redactsTheKeyInsideAnExceptionMessage() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);
        Exception e = new java.io.IOException("request failed, headers: Authorization: " + KEY);

        log.warn("Initial flag fetch failed: {}", e.getMessage());
        log.warn("Poll failed", e);

        assertThat(lines).containsExactly("Initial flag fetch failed: request failed, headers: Authorization: [redacted]", "Poll failed");
        assertThat(throwables).hasSize(1);
        Throwable seen = throwables.get(0);
        assertThat(seen.getMessage()).contains("[redacted]").doesNotContain(KEY);
        assertThat(seen.toString()).startsWith("java.io.IOException: ").doesNotContain(KEY);
        assertThat(seen.getStackTrace()).isEqualTo(e.getStackTrace());
        StringWriter sw = new StringWriter();
        seen.printStackTrace(new PrintWriter(sw));
        assertThat(sw.toString()).doesNotContain(KEY);
    }

    @Test
    void redactsAKeyQuotedByTheCauseOfAnException() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);
        Exception e = new RuntimeException("outer", new IllegalStateException("Authorization=" + KEY));

        log.error("boom", e);

        StringWriter sw = new StringWriter();
        throwables.get(0).printStackTrace(new PrintWriter(sw));
        assertThat(sw.toString()).contains("outer").contains("IllegalStateException").doesNotContain(KEY);
    }

    @Test
    void leavesACleanExceptionUntouched() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);
        Exception e = new RuntimeException("fine");

        log.warn("x", e);

        assertThat(throwables).containsExactly(e);
    }

    @Test
    void redactsTheFormatAndNonStringArguments() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);

        log.info("key " + KEY + " seen in {} and {}", Arrays.asList("a", KEY), 42);

        assertThat(lines).containsExactly("key [redacted] seen in [a, [redacted]] and 42");
    }

    @Test
    void aKeyShorterThanTheMinimumIsLeftAlone() {
        RedactingLogger log = RedactingLogger.wrap(sink(), "k");

        log.warn("skipped a malformed flag {}", "k");

        assertThat(lines).containsExactly("skipped a malformed flag k");
    }

    @Test
    void aRegisteredKeyStaysRegistered() {
        RedactingLogger.register(KEY);
        assertThat(RedactingLogger.isRegistered(KEY)).isTrue();
    }

    @Test
    void aThrowableWhoseToStringThrowsIsTreatedAsLeaking() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);
        Exception e = new RuntimeException("x") {
            @Override public String toString() { throw new IllegalStateException("nope"); }
        };

        log.warn("x", e);

        assertThat(throwables).hasSize(1).doesNotContain(e);
    }

    @Test
    void redactsAKeyInsideArrays() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);

        log.info("a {} b {} c {}", new String[] {"x", KEY}, KEY.toCharArray(), new byte[] {1});

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).doesNotContain(KEY).contains("[redacted]");
    }

    @Test
    void aCauseCycleDoesNotHang() {
        RedactingLogger log = RedactingLogger.wrap(sink(), KEY);
        Exception a = new RuntimeException("a " + KEY);
        Exception b = new RuntimeException("b", a);
        a.initCause(b);

        log.warn("x", a);

        assertThat(throwables).hasSize(1);
        assertThat(throwables.get(0).toString()).doesNotContain(KEY);
    }

    @Test
    void onlyRedactingLoggerTouchesLoggerFactory() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (f.getFileName().toString().equals("RedactingLogger.java")) continue;
                assertThat(java.nio.file.Files.readString(f))
                    .as(f.toString()).doesNotContain("org.slf4j.LoggerFactory").doesNotContain("java.util.logging");
            }
        }
    }
}
