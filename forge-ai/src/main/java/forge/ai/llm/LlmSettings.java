package forge.ai.llm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Local-only LLM opponent settings for Ascendant. Stored in {@code llm_opponent.properties}
 * under the Forge user directory (see system property {@code forge.llm.dir}). The API key is
 * never written to a world save and must never appear in logs, {@link #toString()}, or error
 * messages shown to the player.
 */
public final class LlmSettings {
    public static final String FILE_NAME = "llm_opponent.properties";
    public static final String PROP_ENABLED = "enabled";
    public static final String PROP_BASE_URL = "baseUrl";
    public static final String PROP_API_KEY = "apiKey";
    public static final String PROP_MODEL = "model";
    public static final String PROP_TIMEOUT = "timeoutSeconds";

    public static final String DEFAULT_BASE_URL = "http://localhost:11434/v1";
    public static final String DEFAULT_MODEL = "";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MIN_TIMEOUT_SECONDS = 1;
    public static final int MAX_TIMEOUT_SECONDS = 300;

    private boolean enabled;
    private String baseUrl = DEFAULT_BASE_URL;
    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    /** Raw key for HTTP Authorization only. Never log or display this. */
    public String getApiKey() {
        return apiKey;
    }

    /** Trims leading/trailing whitespace. Never log or display the raw value. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Masked form for UI fields (empty when unset). */
    public String maskedApiKey() {
        if (!hasApiKey()) {
            return "";
        }
        int n = apiKey.length();
        if (n <= 4) {
            return "****";
        }
        return "****" + apiKey.substring(n - 4);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < MIN_TIMEOUT_SECONDS) {
            this.timeoutSeconds = MIN_TIMEOUT_SECONDS;
        } else if (timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            this.timeoutSeconds = MAX_TIMEOUT_SECONDS;
        } else {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /**
     * Ready for duel use when enabled, URL and model are set. API key is optional (local
     * OpenAI-compatible servers often ignore it).
     */
    public boolean canActivate() {
        return enabled && !baseUrl.isBlank() && !model.isBlank();
    }

    /** URL + model are enough for the settings Test button (Enable may be off). */
    public boolean canTest() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    /** Independent copy for Test snapshots; mutations do not affect the source. */
    public LlmSettings copy() {
        LlmSettings s = new LlmSettings();
        s.enabled = enabled;
        s.baseUrl = baseUrl;
        s.apiKey = apiKey;
        s.model = model;
        s.timeoutSeconds = timeoutSeconds;
        return s;
    }

    /** Directory from {@code forge.llm.dir}, or the user home as a last resort. */
    public static File settingsDir() {
        String d = System.getProperty("forge.llm.dir");
        return d == null || d.isBlank() ? new File(System.getProperty("user.home")) : new File(d);
    }

    public static File settingsFile() {
        return new File(settingsDir(), FILE_NAME);
    }

    public static LlmSettings load() {
        return load(settingsFile());
    }

    public static LlmSettings load(File file) {
        LlmSettings s = new LlmSettings();
        if (file == null || !file.isFile()) {
            return s;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return s;
        }
        s.enabled = "true".equalsIgnoreCase(p.getProperty(PROP_ENABLED, "false").trim());
        s.setBaseUrl(p.getProperty(PROP_BASE_URL, DEFAULT_BASE_URL));
        s.setApiKey(p.getProperty(PROP_API_KEY, ""));
        s.setModel(p.getProperty(PROP_MODEL, DEFAULT_MODEL));
        try {
            s.setTimeoutSeconds(Integer.parseInt(p.getProperty(PROP_TIMEOUT, String.valueOf(DEFAULT_TIMEOUT_SECONDS)).trim()));
        } catch (NumberFormatException ignored) {
            s.timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        }
        return s;
    }

    public void save() throws IOException {
        save(settingsFile());
    }

    public void save(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create settings directory: " + parent);
        }
        Properties p = new Properties();
        p.setProperty(PROP_ENABLED, Boolean.toString(enabled));
        p.setProperty(PROP_BASE_URL, baseUrl == null ? "" : baseUrl);
        p.setProperty(PROP_API_KEY, apiKey == null ? "" : apiKey);
        p.setProperty(PROP_MODEL, model == null ? "" : model);
        p.setProperty(PROP_TIMEOUT, Integer.toString(timeoutSeconds));
        try (OutputStream out = new FileOutputStream(file)) {
            p.store(out, "Forge Adventure LLM opponent (local only; not part of the save)");
        }
        restrictOwnerOnly(file);
    }

    /**
     * Best-effort owner-only access: POSIX {@code 600} when supported; otherwise
     * {@link File#setReadable}/{@link File#setWritable} owner-only and, on Windows,
     * an ACL that allows only the file owner. Failures are ignored.
     */
    public static void restrictOwnerOnly(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        Path path = file.toPath();
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
            return;
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX (e.g. Windows default FS)
        } catch (IOException ignored) {
        }
        try {
            // Clear broader access, then grant owner read/write only.
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
        } catch (SecurityException ignored) {
        }
        try {
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            UserPrincipal owner = Files.getOwner(path);
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(
                            AclEntryPermission.READ_DATA,
                            AclEntryPermission.WRITE_DATA,
                            AclEntryPermission.APPEND_DATA,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.WRITE_ATTRIBUTES,
                            AclEntryPermission.READ_NAMED_ATTRS,
                            AclEntryPermission.WRITE_NAMED_ATTRS,
                            AclEntryPermission.READ_ACL,
                            AclEntryPermission.SYNCHRONIZE)
                    .build();
            view.setAcl(List.of(entry));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
        }
    }

    /**
     * Replaces every occurrence of the API key in {@code text} so logs and UI errors stay safe.
     * Also strips common {@code Bearer &lt;key&gt;} forms when a key is set.
     */
    public String redact(String text) {
        return redact(text, apiKey);
    }

    public static String redact(String text, String key) {
        if (text == null) {
            return null;
        }
        if (key == null || key.isBlank()) {
            return text;
        }
        String out = text;
        if (out.contains(key)) {
            out = out.replace(key, "***");
        }
        String bearer = "Bearer " + key;
        if (out.contains(bearer)) {
            out = out.replace(bearer, "Bearer ***");
        }
        return out;
    }

    /** Never includes the API key. */
    @Override
    public String toString() {
        return "LlmSettings{enabled=" + enabled
                + ", baseUrl='" + baseUrl + '\''
                + ", model='" + model + '\''
                + ", timeoutSeconds=" + timeoutSeconds
                + ", apiKey=" + (hasApiKey() ? "set" : "unset")
                + '}';
    }
}
