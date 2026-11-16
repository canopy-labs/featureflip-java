package io.featureflip.client.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way the SDK writes a log line. Strips every live SDK key out of the format,
 * each argument and any {@link Throwable} before the SLF4J binding sees it.
 *
 * <p>Failure paths report the underlying exception verbatim, and that exception can come
 * from the HTTP stack, whose message may quote the {@code Authorization} header the SDK
 * sets to the key on every request. The host ships SLF4J output to an aggregator, so a key
 * in a log line is a published credential (#5167). Wrapping the seam once, instead of
 * auditing each call site, covers every line that exists today and any added later.
 *
 * <p>Loggers in this SDK are static, so keys are tracked in a process-wide registry:
 * {@link FlagHttpClient} registers its key on construction. Keys are never unregistered.
 *
 * <p>Only a key of at least {@value #MIN_REDACTABLE_LENGTH} characters is redacted. A real
 * key is always 54; redacting a value as short as {@code k} would corrupt every line it
 * appears in, and a key that short cannot authenticate anyway.
 *
 * <p>Internal: not part of the public API.
 */
public final class RedactingLogger {
    static final String REPLACEMENT = "[redacted]";
    static final int MIN_REDACTABLE_LENGTH = 20;
    private static final int MAX_CAUSE_DEPTH = 16;

    private static final Object LOCK = new Object();
    private static final Set<String> REGISTERED = new LinkedHashSet<>();
    private static volatile String[] secrets = new String[0];

    private final Logger delegate;
    private final String[] fixedSecrets;

    private RedactingLogger(Logger delegate, String[] fixedSecrets) {
        this.delegate = delegate;
        this.fixedSecrets = fixedSecrets;
    }

    public static RedactingLogger get(Class<?> clazz) {
        return new RedactingLogger(LoggerFactory.getLogger(clazz), null);
    }

    /** Wraps an arbitrary logger against one fixed secret; for tests. */
    static RedactingLogger wrap(Logger delegate, String secret) {
        String[] s = secret != null && secret.length() >= MIN_REDACTABLE_LENGTH
            ? new String[] {secret} : new String[0];
        return new RedactingLogger(delegate, s);
    }

    /**
     * Starts redacting {@code key} from every SDK log line, for the life of the process.
     *
     * <p>Keys are deliberately never unregistered. The set is bounded by the number of
     * distinct keys a process ever uses, redacting a closed client's key is harmless, and
     * releasing on close would open a window where a background thread's late log line
     * (a poll or SSE callback finishing after shutdown) goes out unredacted.
     */
    public static void register(String key) {
        if (key == null || key.length() < MIN_REDACTABLE_LENGTH) return;
        synchronized (LOCK) {
            if (REGISTERED.add(key)) secrets = REGISTERED.toArray(new String[0]);
        }
    }

    static boolean isRegistered(String key) {
        synchronized (LOCK) {
            return REGISTERED.contains(key);
        }
    }

    // -------------------------------------------------------------------------

    public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }

    public void debug(String format, Object... args) {
        if (delegate.isDebugEnabled()) delegate.debug(redact(format), redactArgs(args));
    }

    public void info(String format, Object... args) {
        if (delegate.isInfoEnabled()) delegate.info(redact(format), redactArgs(args));
    }

    public void warn(String format, Object... args) {
        if (delegate.isWarnEnabled()) delegate.warn(redact(format), redactArgs(args));
    }

    public void error(String format, Object... args) {
        if (delegate.isErrorEnabled()) delegate.error(redact(format), redactArgs(args));
    }

    // -------------------------------------------------------------------------

    private String[] active() {
        return fixedSecrets != null ? fixedSecrets : secrets;
    }

    private String redact(String text) {
        if (text == null) return null;
        for (String s : active()) {
            if (text.contains(s)) text = text.replace(s, REPLACEMENT);
        }
        return text;
    }

    private boolean contains(String text) {
        if (text == null) return false;
        for (String s : active()) {
            if (text.contains(s)) return true;
        }
        return false;
    }

    private Object[] redactArgs(Object[] args) {
        if (args == null || args.length == 0 || active().length == 0) return args;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = redactArg(args[i]);
        return out;
    }

    private Object redactArg(Object arg) {
        if (arg == null || arg instanceof Number || arg instanceof Boolean || arg instanceof Character) {
            return arg;
        }
        if (arg instanceof Throwable) return redactThrowable((Throwable) arg);
        if (arg.getClass().isArray()) {
            String rendered = renderArray(arg);
            return contains(rendered) ? redact(rendered) : arg;
        }
        String text;
        try {
            text = String.valueOf(arg);
        } catch (RuntimeException e) {
            return arg;
        }
        return contains(text) ? redact(text) : arg;
    }

    private static String renderArray(Object array) {
        if (array instanceof Object[]) return java.util.Arrays.deepToString((Object[]) array);
        if (array instanceof byte[]) return java.util.Arrays.toString((byte[]) array);
        if (array instanceof char[]) return new String((char[]) array) + java.util.Arrays.toString((char[]) array);
        if (array instanceof int[]) return java.util.Arrays.toString((int[]) array);
        if (array instanceof long[]) return java.util.Arrays.toString((long[]) array);
        if (array instanceof short[]) return java.util.Arrays.toString((short[]) array);
        if (array instanceof double[]) return java.util.Arrays.toString((double[]) array);
        if (array instanceof float[]) return java.util.Arrays.toString((float[]) array);
        if (array instanceof boolean[]) return java.util.Arrays.toString((boolean[]) array);
        return String.valueOf(array);
    }

    private Throwable redactThrowable(Throwable t) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        if (!leaks(t, seen, 0)) return t;
        return rebuild(t, new IdentityHashMap<>(), 0);
    }

    private boolean leaks(Throwable t, Map<Throwable, Boolean> seen, int depth) {
        if (t == null || depth > MAX_CAUSE_DEPTH || seen.put(t, Boolean.TRUE) != null) return false;
        String text;
        try {
            text = t.toString();
        } catch (RuntimeException e) {
            return true; // cannot prove it clean, so fail closed
        }
        if (contains(text)) return true;
        if (leaks(t.getCause(), seen, depth + 1)) return true;
        for (Throwable s : t.getSuppressed()) {
            if (leaks(s, seen, depth + 1)) return true;
        }
        return false;
    }

    private Throwable rebuild(Throwable t, Map<Throwable, Throwable> done, int depth) {
        if (t == null) return null;
        Throwable existing = done.get(t);
        if (existing != null) return existing;
        String text;
        try {
            text = t.toString();
        } catch (RuntimeException e) {
            text = t.getClass().getName();
        }
        Redacted r = new Redacted(redact(text));
        done.put(t, r);
        if (depth < MAX_CAUSE_DEPTH) {
            Throwable cause = rebuild(t.getCause(), done, depth + 1);
            if (cause != null) r.initCause(cause);
        }
        r.setStackTrace(t.getStackTrace());
        if (depth < MAX_CAUSE_DEPTH) {
            List<Throwable> suppressed = new ArrayList<>();
            for (Throwable s : t.getSuppressed()) suppressed.add(rebuild(s, done, depth + 1));
            for (Throwable s : suppressed) r.addSuppressed(s);
        }
        return r;
    }

    /** Stands in for an exception whose text quoted the key; keeps its class name and stack. */
    private static final class Redacted extends Throwable {
        private static final long serialVersionUID = 1L;
        private final String rendered;

        Redacted(String rendered) {
            super(rendered);
            this.rendered = rendered;
        }

        @Override
        public String toString() { return rendered; }
    }
}
