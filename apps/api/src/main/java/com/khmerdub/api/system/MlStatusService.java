package com.khmerdub.api.system;

import com.khmerdub.api.power.PowerKeeper;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Speech-stack status for the System page: verified models (from the local manifest, plus a cheap presence check),
 * the ML worker's own start-up self-report (versions, offline switches, network isolation), and keep-awake.
 *
 * <p>Only allow-listed fields are copied out, as booleans, numbers or short strings from a safe character set, so a
 * token, a private path or anything unexpected in those files can never reach the response.
 */
@Service
public class MlStatusService {

    static final String MANIFEST = "models/manifest.verified.json";
    static final String WORKER_STATUS = "system/worker-status.json";
    static final long MAX_FILE = 256 * 1024;
    static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9 ._:()+-]{0,60}");
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]{1,60}/[A-Za-z0-9._-]{1,80}");
    private static final Pattern DIR = Pattern.compile("[A-Za-z0-9._-]{1,140}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{7,64}");

    private final StorageService storage;
    private final ObjectMapper json;
    private final PowerKeeper power;
    private final Clock clock;

    public MlStatusService(StorageService storage, ObjectMapper json, PowerKeeper power, Clock clock) {
        this.storage = storage;
        this.json = json;
        this.power = power;
        this.clock = clock;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkedAt", OffsetDateTime.now(clock).toString());
        out.put("models", models());
        out.put("worker", worker());
        out.put("keepAwake", keepAwake());
        return out;
    }

    private Map<String, Object> models() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> manifest = readJson(MANIFEST);
        if (manifest == null || !(manifest.get("models") instanceof Map<?, ?> models)) {
            out.put("manifest", "missing");
            return out;
        }
        out.put("manifest", "present");
        Map<String, Object> list = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : models.entrySet()) {
            String repo = String.valueOf(e.getKey());
            if (!REPO.matcher(repo).matches() || !(e.getValue() instanceof Map<?, ?> info)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revision", match(info.get("revision"), HEX));
            m.put("files", number(info.get("files")));
            m.put("bytes", number(info.get("bytes")));
            m.put("license", safe(info.get("license")));
            m.put("verifiedAt", safe(info.get("verifiedAt")));
            m.put("filesPresent", presentFiles(info.get("dir")));
            list.put(repo, m);
        }
        out.put("list", list);
        return out;
    }

    /** Number of files in the model folder (a cheap presence check; the worker verifies sizes and hashes). */
    private Long presentFiles(Object dir) {
        if (!(dir instanceof String d) || !DIR.matcher(d).matches()) {
            return null;
        }
        try {
            Path p = storage.resolve("models/" + d);
            if (!Files.isDirectory(p)) {
                return 0L;
            }
            try (Stream<Path> files = Files.walk(p, 8)) {
                return files.filter(Files::isRegularFile).filter(f -> !f.getFileName().toString().startsWith("."))
                        .count();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> worker() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> s = readJson(WORKER_STATUS);
        if (s == null) {
            out.put("reported", false);
            return out;
        }
        out.put("reported", true);
        String reportedAt = safe(s.get("reportedAt"));
        out.put("reportedAt", reportedAt);
        try {
            OffsetDateTime at = OffsetDateTime.parse(String.valueOf(reportedAt));
            out.put("stale", Duration.between(at, OffsetDateTime.now(clock)).compareTo(STALE_AFTER) > 0);
        } catch (DateTimeParseException e) {
            out.put("stale", true);
        }
        out.put("platform", safe(s.get("platform")));
        if (s.get("ml") instanceof Map<?, ?> ml) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", bool(ml.get("ok")));
            m.put("versionsOk", bool(ml.get("versionsOk")));
            m.put("sileroVadOk", bool(ml.get("sileroVadOk")));
            if (ml.get("models") instanceof Map<?, ?> models) {
                Map<String, Object> ok = new LinkedHashMap<>();
                models.forEach((k, v) -> {
                    if (REPO.matcher(String.valueOf(k)).matches()) {
                        ok.put(String.valueOf(k), bool(v));
                    }
                });
                m.put("models", ok);
            }
            out.put("ml", m);
        }
        if (s.get("offline") instanceof Map<?, ?> offline) {
            Map<String, Object> o = new LinkedHashMap<>();
            for (String k : new String[] {"HF_HUB_OFFLINE", "HF_HUB_DISABLE_TELEMETRY", "PYANNOTE_METRICS_ENABLED"}) {
                o.put(k, match(offline.get(k), Pattern.compile("[A-Za-z0-9]{0,8}")));
            }
            out.put("offline", o);
        }
        if (s.get("network") instanceof Map<?, ?> net) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("internetBlocked", bool(net.get("internetBlocked")));
            n.put("redis", safe(net.get("redis")));
            n.put("spring", safe(net.get("spring")));
            n.put("isolatedAndConnected", bool(net.get("isolatedAndConnected")));
            out.put("network", n);
        }
        return out;
    }

    private Map<String, Object> keepAwake() {
        Map<String, Object> st = power.status();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", st.get("enabled"));
        out.put("holding", st.get("holding"));
        Object reason = st.get("reason");
        out.put("reason", reason == null ? null : reason.toString().length() > 200
                ? reason.toString().substring(0, 200) : reason.toString());
        out.put("lastChange", st.get("lastChange") == null ? null : st.get("lastChange").toString());
        return out;
    }

    private Map<String, Object> readJson(String relative) {
        try {
            Path p = storage.resolve(relative);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE) {
                return null;
            }
            return json.readValue(p.toFile(), new TypeReference<Map<String, Object>>() { });
        } catch (IOException | RuntimeException e) { // JacksonException is a RuntimeException
            return null;
        }
    }

    private static Boolean bool(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    private static Long number(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static String safe(Object v) {
        return match(v, SAFE);
    }

    private static String match(Object v, Pattern p) {
        return v instanceof String s && p.matcher(s).matches() ? s : null;
    }
}
