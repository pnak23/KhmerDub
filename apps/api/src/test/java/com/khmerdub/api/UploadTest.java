package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.project.ProjectViews.CreateProjectRequest;
import com.khmerdub.api.storage.StorageService;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Uploads go through a real HTTP server (MockMvc would buffer the body, which is exactly what we must avoid). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UploadTest {

    static final byte[] MP4_HEAD = {0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    static final byte[] MKV_HEAD = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};

    @LocalServerPort int port;
    @Autowired ProjectService projects;
    @Autowired MediaAssetRepository assets;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        jdbc.execute(EngineFixtures.TRUNCATE);
    }

    /** Uploaded originals are read-only by design; undo that for test files so `mvn clean` can delete them. */
    @org.junit.jupiter.api.AfterEach
    void makeTestOriginalsWritable() throws Exception {
        try (var files = Files.walk(storage.root())) {
            files.filter(Files::isRegularFile).forEach(f -> f.toFile().setWritable(true));
        }
    }

    @Test
    void streamsALargeMovieToDiskWithoutBufferingIt() throws Exception {
        UUID p = newProject();
        long size = 1536L * 1024 * 1024; // 1.5 GB through a JVM limited to 512 MB heap
        HttpResponse<String> r = upload(p, "Big Movie.mp4", new PatternStream(MP4_HEAD, size), size);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);

        MediaAsset original = assets.findFirstByProjectIdAndType(p, MediaAssetType.ORIGINAL_VIDEO).orElseThrow();
        Path file = storage.resolve(original.getStoragePath());
        assertThat(Files.size(file)).isEqualTo(size);
        assertThat(original.getSha256()).isEqualTo(StorageService.sha256(file));
        assertThat(file.getFileName().toString()).isEqualTo("Big Movie.mp4");
        assertThat(Files.isWritable(file)).as("original is read-only").isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM processing_jobs WHERE project_id = ? AND job_type = 'IMPORT'",
                String.class, p)).isEqualTo("QUEUED");
    }

    @Test
    void aSecondMovieForTheSameProjectIsRefused() throws Exception {
        UUID p = newProject();
        assertThat(upload(p, "a.mp4", new PatternStream(MP4_HEAD, 4096), 4096).statusCode()).isEqualTo(201);
        HttpResponse<String> second = upload(p, "b.mp4", new PatternStream(MP4_HEAD, 4096), 4096);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("MOVIE_EXISTS");
    }

    @Test
    void contentMustMatchTheExtension() throws Exception {
        UUID p = newProject();
        HttpResponse<String> r = upload(p, "movie.mp4", new PatternStream(MKV_HEAD, 4096), 4096);
        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(r.body()).contains("does not look like a MP4");
        assertThat(upload(p, "movie.mkv", new PatternStream(MKV_HEAD, 4096), 4096).statusCode()).isEqualTo(201);
        assertThat(leftoverPartFiles(p)).isZero();
    }

    @Test
    void unsupportedTypesAreRefusedBeforeUploading() throws Exception {
        UUID p = newProject();
        HttpResponse<String> r = post("/api/projects/" + p + "/upload/check", "{\"fileName\":\"clip.avi\",\"sizeBytes\":100}");
        assertThat(r.statusCode()).isEqualTo(415);
        assertThat(r.body()).contains("Only MP4, MKV and MOV");
    }

    @Test
    void fileNamesCannotEscapeTheProjectFolder() throws Exception {
        UUID p = newProject();
        assertThat(upload(p, "..\\..\\..\\evil.mp4", new PatternStream(MP4_HEAD, 1024), 1024).statusCode()).isEqualTo(201);
        MediaAsset original = assets.findFirstByProjectIdAndType(p, MediaAssetType.ORIGINAL_VIDEO).orElseThrow();
        assertThat(original.getStoragePath()).isEqualTo("projects/" + p + "/original/evil.mp4");
    }

    @Test
    void browserPreflightIsAllowedOnlyForTheFrontendOrigin() throws Exception {
        UUID p = newProject();
        HttpResponse<String> ok = preflight(p, "http://localhost:3000");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(preflight(p, "http://evil.example").statusCode()).isEqualTo(403);
    }

    // -------------------------------------------------------------------------------------------------

    private UUID newProject() {
        return projects.create(new CreateProjectRequest("Upload test", null)).summary().id();
    }

    private long leftoverPartFiles(UUID p) throws Exception {
        try (var files = Files.list(storage.resolve("projects/" + p + "/original"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".part")).count();
        }
    }

    private HttpResponse<String> upload(UUID project, String name, InputStream body, long length) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/projects/" + project + "/upload"))
                .header("Content-Type", "application/octet-stream")
                .header("X-File-Name", URLEncoder.encode(name, StandardCharsets.UTF_8))
                .header("Origin", "http://localhost:3000")
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> body), length))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(UUID project, String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/projects/" + project + "/upload"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-file-name")
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Generates {@code size} bytes on the fly: the given header followed by a repeating pattern. */
    static final class PatternStream extends InputStream {
        private final byte[] head;
        private final long size;
        private long position;

        PatternStream(byte[] head, long size) {
            this.head = head;
            this.size = size;
        }

        @Override
        public int read() {
            if (position >= size) {
                return -1;
            }
            int b = position < head.length ? head[(int) position] & 0xFF : (int) (position % 251);
            position++;
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
            if (position >= size) {
                return -1;
            }
            int n = (int) Math.min(len, size - position);
            for (int i = 0; i < n; i++) {
                long pos = position + i;
                buffer[off + i] = pos < head.length ? head[(int) pos] : (byte) (pos % 251);
            }
            position += n;
            return n;
        }
    }
}
