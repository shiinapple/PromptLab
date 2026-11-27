package cn.shiinapple.promptpractice.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import cn.shiinapple.promptpractice.judge.JudgeService;

class ProblemCatalogLintTest {
    private static final Path SOURCE = Path.of("../content/problems");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final ResourceLoader resources = new DefaultResourceLoader();
    private final ProblemCatalog catalog = new ProblemCatalog(resources);
    private final JudgeService judge = new JudgeService();

    @Test
    void locksAllVersionDirectoriesAndChecksTheirFixtures() throws Exception {
        Map<String, String> hashes = lockedVersions();
        Set<String> versions = versionDirectories();
        assertThat(hashes.keySet()).as("hashes.json entries must match all version directories")
                .containsExactlyInAnyOrderElementsOf(versions);
        for (var pack : catalog.loadPublished()) {
            assertThat(versions).as("published version").contains(pack.problemId() + "/v" + pack.version());
        }
        for (String version : versions) {
            var pack = catalog.loadVersion(version.substring(0, 3), Integer.parseInt(version.substring(5)));
            assertThat(pack.contentHash()).as("%s SHA-256", version).isEqualTo(hashes.get(version));
            try (var files = Files.walk(SOURCE.resolve(version))) {
                var relativeFiles = files.filter(Files::isRegularFile)
                        .map(SOURCE.resolve(version)::relativize)
                        .map(path -> path.toString().replace(File.separatorChar, '/'))
                        .toList();
                assertThat(relativeFiles).as("%s supported files", version).containsExactlyInAnyOrder(
                        "problem.json", "schema.json", "i18n/zh-CN.json", "i18n/en.json",
                        "cases/" + pack.caseId() + ".json", "fixtures/" + pack.caseId() + ".json");
            }
            checkFixtures(version, pack);
        }
    }

    private Map<String, String> lockedVersions() throws Exception {
        try (var input = resources.getResource("classpath:/content/problems/hashes.json").getInputStream()) {
            JsonNode root = JSON.readTree(input);
            assertThat(root.isObject()).as("hashes.json must contain an object").isTrue();
            Map<String, String> versions = new TreeMap<>();
            for (var problem : root.properties()) {
                assertThat(problem.getKey()).matches("[0-9]{3}");
                assertThat(problem.getValue().isObject() && !problem.getValue().isEmpty()).isTrue();
                for (var version : problem.getValue().properties()) {
                    assertThat(version.getKey()).matches("[1-9][0-9]*");
                    String id = problem.getKey() + "/v" + version.getKey();
                    String hash = version.getValue().stringValue("");
                    assertThat(hash).as("%s SHA-256 in hashes.json", id).matches("[0-9a-f]{64}");
                    versions.put(id, hash);
                }
            }
            return versions;
        }
    }

    private static Set<String> versionDirectories() throws Exception {
        Set<String> versions = new TreeSet<>();
        try (var problems = Files.list(SOURCE)) {
            for (Path problem : problems.filter(Files::isDirectory).toList()) {
                assertThat(problem.getFileName().toString()).matches("[0-9]{3}");
                try (var children = Files.list(problem)) {
                    for (Path version : children.filter(Files::isDirectory).toList()) {
                        assertThat(version.getFileName().toString()).matches("v[1-9][0-9]*");
                        versions.add(problem.getFileName() + "/" + version.getFileName());
                    }
                }
            }
        }
        return versions;
    }

    private void checkFixtures(String version, ProblemCatalog.ProblemPack pack) {
        boolean passed = false;
        boolean failed = false;
        for (JsonNode fixture : pack.fixtures()) {
            String label = version + "/" + pack.caseId() + "/" + fixture.path("id").stringValue();
            var grade = judge.judge(fixture.path("rawOutput").stringValue(), pack.schema(),
                    pack.publicCase().path("assertions"));
            assertThat(grade.verdict()).as("%s verdict", label).isEqualTo(fixture.path("verdict").stringValue());
            // Only rule checks are listed in failedCheckIds. Format failures have their own verdict.
            var failures = grade.verdict().equals("WRONG_ANSWER") ? grade.checks().stream()
                    .filter(check -> "FAIL".equals(check.status())).map(JudgeService.Check::id).toList()
                    : List.<String>of();
            assertThat(failures).as("%s failed check IDs", label).containsExactlyInAnyOrderElementsOf(
                    fixture.path("failedCheckIds").valueStream().map(JsonNode::stringValue).toList());
            passed |= "PASSED".equals(grade.verdict());
            failed |= !"PASSED".equals(grade.verdict());
        }
        assertThat(passed && failed).as("%s must have a passing and a failing fixture", version).isTrue();
    }
}
