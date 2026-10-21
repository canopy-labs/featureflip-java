package io.featureflip.client.internal;

import io.featureflip.client.FeatureFlagConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The {@code featureflip-java/<version>} User-Agent every request carries, so the
 * Evaluation API can count SDK usage by name and version (#4140, contract in #4138).
 */
public final class SdkIdentity {
    private static final Logger log = LoggerFactory.getLogger(SdkIdentity.class);

    public static final String NAME = "featureflip-java";

    /**
     * The release version, read from a resource that processResources stamps from the
     * {@code version} in build.gradle.kts. publish-java-sdk.yml rewrites that line from
     * the tag, so this cannot drift from what shipped the way a literal did (#2141).
     */
    public static final String VERSION = readVersion();

    // RFC 9110 product token: tchars, optionally "/" and more tchars.
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    private SdkIdentity() { }

    private static String readVersion() {
        try (InputStream in = SdkIdentity.class.getResourceAsStream("/io/featureflip/client/featureflip-sdk.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * The User-Agent value: the SDK token, then the configured wrapper's token. A wrapper
     * that is not a valid product token is dropped with a warning rather than thrown,
     * because identity metadata must never stop the client starting.
     */
    static String userAgent(FeatureFlagConfig config) {
        String sdk = NAME + "/" + VERSION;
        String name = config.getWrapperName();
        if (name == null || name.isEmpty()) return sdk;

        String version = config.getWrapperVersion();
        boolean valid = TOKEN.matcher(name).matches()
            && (version == null || TOKEN.matcher(version).matches());
        if (!valid) {
            log.warn("Ignoring wrapper '{}/{}': not a valid User-Agent product token.", name, version);
            return sdk;
        }
        return version == null ? sdk + " " + name : sdk + " " + name + "/" + version;
    }
}
