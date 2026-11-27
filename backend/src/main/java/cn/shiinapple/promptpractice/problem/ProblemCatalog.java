package cn.shiinapple.promptpractice.problem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProblemCatalog {
    private static final String ROOT = "classpath:/content/problems/";
    private static final String SCHEMA_DRAFT = "https://json-schema.org/draft/2020-12/schema";
    private static final Set<String> LOCALES = Set.of("zh-CN", "en");
    private static final Set<String> MESSAGE_ROLES = Set.of("system", "user", "assistant");
    private static final Set<String> ASSERTIONS = Set.of("arraySize", "uniqueBy", "mapEquals");
    private static final Set<String> VERDICTS = Set.of("PASSED", "FORMAT_ERROR", "WRONG_ANSWER");
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]*)}}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final ResourceLoader resources;

    public ProblemCatalog(ResourceLoader resources) {
        this.resources = resources;
    }

    public List<ProblemPack> loadPublished() {
        return releases().entrySet().stream()
                .map(entry -> loadVersion(entry.getKey(), entry.getValue()))
                .toList();
    }

    public ProblemPack loadPublished(String problemId) {
        return readPublished(problemId, null).pack();
    }

    public PublishedProblem readPublished(String problemId, Integer requestedVersion) {
        Integer publishedVersion = problemId == null ? null : releases().get(problemId);
        if (publishedVersion == null) throw new UnknownProblem(problemId);
        int version = requestedVersion == null ? publishedVersion : requestedVersion;
        if (version > publishedVersion
                || version < publishedVersion && !resources.getResource(
                        ROOT + problemId + "/v" + version + "/problem.json").exists()) {
            throw new UnknownProblem(problemId + "/v" + version);
        }
        return new PublishedProblem(loadVersion(problemId, version), publishedVersion);
    }

    private Map<String, Integer> releases() {
        JsonNode manifest = read("releases.json", new TreeMap<>());
        require(manifest.isObject(), "Expected a problem release object");
        Map<String, Integer> releases = new TreeMap<>();
        for (var entry : manifest.properties()) {
            String id = entry.getKey();
            JsonNode release = entry.getValue();
            require(id.matches("[0-9]{3}") && release.isIntegralNumber()
                            && release.canConvertToInt() && release.intValue() > 0,
                    "Invalid release for " + id);
            releases.put(id, release.intValue());
        }
        return releases;
    }

    public ProblemPack loadVersion(String problemId, int version) {
        require(problemId != null && problemId.matches("[0-9]{3}") && version > 0,
                "Invalid problem ID or version");
        Map<String, byte[]> files = new TreeMap<>();
        String base = problemId + "/v" + version + "/";
        JsonNode problem = read(base + "problem.json", files);
        require(problemId.equals(problem.path("problemId").stringValue(null))
                        && problem.path("version").isIntegralNumber()
                        && problem.path("version").canConvertToInt() && version == problem.path("version").intValue(),
                base + "problem.json ID or version does not match the release");

        JsonNode caseIds = problem.path("caseIds");
        require(caseIds.isArray() && caseIds.size() == 1, base + "Expected one public case");
        String caseId = caseIds.path(0).stringValue(null);
        require(caseId != null && caseId.matches("case-[1-9][0-9]*"), base + "Invalid public case ID");
        JsonNode publicCase = read(base + "cases/" + caseId + ".json", files);
        require(caseId.equals(publicCase.path("caseId").stringValue(null))
                        && LOCALES.contains(publicCase.path("inputLocale").stringValue("")),
                base + "Invalid public case ID or input locale");

        Map<String, JsonNode> translations = Map.of(
                "zh-CN", read(base + "i18n/zh-CN.json", files),
                "en", read(base + "i18n/en.json", files));
        validateTemplates(base, problem, publicCase.path("variables"), translations);
        JsonNode schema = read(base + "schema.json", files);
        require(SCHEMA_DRAFT.equals(schema.path("$schema").stringValue(null))
                        && "object".equals(schema.path("type").stringValue(null)),
                base + "schema.json requires a 2020-12 object schema");
        validateSchema(schema, base + "schema.json", true);

        JsonNode fixtures = read(base + "fixtures/" + caseId + ".json", files);
        validateExpectations(base, publicCase.path("assertions"), fixtures);
        return new ProblemPack(problemId, version, caseId, digest(files), problem, translations, publicCase, schema, fixtures);
    }

    private static void validateTemplates(
            String base, JsonNode problem, JsonNode variables, Map<String, JsonNode> translations) {
        JsonNode slots = problem.path("slots");
        require(slots.isObject() && slots.size() == 1, base + "Expected one editable slot");
        String slotName = slots.propertyNames().iterator().next();
        for (var translation : translations.entrySet()) {
            JsonNode text = translation.getValue();
            String location = base + "i18n/" + translation.getKey() + ".json";
            require(nonBlank(text.path("title")) && nonBlank(text.path("description")),
                    location + " Missing title or description");
            JsonNode defaults = text.path("defaultSlots");
            require(defaults.isObject() && defaults.size() == 1 && defaults.has(slotName),
                    location + " Default slots must match problem slots");
        }
        Set<String> required = strings(slots.path(slotName).path("requiredVariables"),
                base + "Invalid requiredVariables for " + slotName);
        for (var translation : translations.entrySet()) {
            String location = base + "i18n/" + translation.getKey() + ".json";
            JsonNode template = translation.getValue().path("defaultSlots").path(slotName);
            require(nonBlank(template), location + " Missing template for " + slotName);
            var matcher = VARIABLE.matcher(template.stringValue());
            Set<String> placeholders = new HashSet<>();
            while (matcher.find()) {
                placeholders.add(matcher.group(1));
            }
            require(placeholders.equals(required), location + " Variable placeholders do not match " + required);
        }

        Set<String> caseVariables = validateMessages(problem.path("messages"), slotName, base);
        caseVariables.addAll(required);
        require(variables.isObject() && new HashSet<>(variables.propertyNames()).equals(caseVariables),
                base + " Case variables do not match required variables " + caseVariables);
        for (String variable : caseVariables) {
            require(nonBlank(variables.path(variable)), base + "Missing case variable " + variable);
        }
    }

    private static Set<String> validateMessages(JsonNode messages, String slotName, String base) {
        require(messages.isArray() && !messages.isEmpty(), base + "Missing messages");
        Set<String> variables = new HashSet<>();
        int slotReferences = 0;
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            JsonNode message = messages.path(messageIndex);
            String location = base + "problem.json/messages/" + messageIndex;
            require(MESSAGE_ROLES.contains(message.path("role").stringValue("")), location + " Unknown message role");
            JsonNode parts = message.path("parts");
            require(parts.isArray(), location + " Invalid message parts");
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                JsonNode part = parts.path(partIndex);
                String partLocation = location + "/parts/" + partIndex;
                switch (part.path("type").stringValue("")) {
                    case "fixedText" -> require(part.path("text").isString(), partLocation + " Invalid fixed text");
                    case "caseVariable" -> {
                        JsonNode name = part.path("name");
                        require(nonBlank(name), partLocation + " Invalid case variable name");
                        variables.add(name.stringValue());
                    }
                    case "slot" -> {
                        require(slotName.equals(part.path("name").stringValue(null)), partLocation + " Unknown slot");
                        slotReferences++;
                    }
                    default -> throw new IllegalArgumentException(partLocation + " Unknown message part type");
                }
            }
        }
        require(slotReferences == 1, base + " Expected exactly one reference to slot " + slotName);
        return variables;
    }

    // Validate the supported schema subset before passing it to the judge.
    private static void validateSchema(JsonNode schema, String path, boolean root) {
        require(schema.isObject(), path + " must be a schema object");
        Set<String> keys = new HashSet<>(schema.propertyNames());
        if (root) {
            keys.remove("$schema");
        }
        switch (schema.path("type").stringValue("")) {
            case "object" -> {
                require(keys.equals(Set.of("type", "required", "additionalProperties", "properties"))
                                && schema.path("additionalProperties").isBoolean()
                                && !schema.path("additionalProperties").booleanValue()
                                && schema.path("properties").isObject(),
                        path + " Invalid closed object schema");
                JsonNode properties = schema.path("properties");
                Set<String> required = strings(schema.path("required"), path + " Invalid required fields");
                require(properties.propertyNames().containsAll(required),
                        path + " Required fields must be declared in properties");
                for (var property : properties.properties()) {
                    String name = property.getKey();
                    JsonNode child = property.getValue();
                    if (child.isObject() && child.isEmpty() && !required.contains(name)) {
                        continue;
                    }
                    validateSchema(child, path + "/" + name, false);
                }
            }
            case "array" -> {
                require(keys.equals(Set.of("type", "items")), path + " Invalid array schema");
                validateSchema(schema.path("items"), path + "/items", false);
            }
            case "integer", "boolean" -> require(keys.equals(Set.of("type")), path + " Invalid scalar schema");
            case "string" -> require(keys.equals(Set.of("type"))
                            || keys.equals(Set.of("type", "pattern"))
                            && "\\S".equals(schema.path("pattern").stringValue(null)),
                    path + " Invalid string schema");
            default -> throw new IllegalArgumentException(path + " Unsupported schema type");
        }
    }

    private static void validateExpectations(String base, JsonNode assertions, JsonNode fixtures) {
        require(assertions.isArray(), base + "Missing case assertions");
        Set<String> assertionIds = new HashSet<>();
        for (JsonNode assertion : assertions) {
            require(nonBlank(assertion.path("id")) && assertionIds.add(assertion.path("id").stringValue())
                            && ASSERTIONS.contains(assertion.path("op").stringValue("")),
                    base + "Invalid case assertion ID or operation");
        }
        require(fixtures.isArray() && !fixtures.isEmpty(), base + "Missing fixtures");
        Set<String> fixtureIds = new HashSet<>();
        for (JsonNode fixture : fixtures) {
            require(nonBlank(fixture.path("id")) && fixtureIds.add(fixture.path("id").stringValue())
                            && fixture.path("rawOutput").isString()
                            && VERDICTS.contains(fixture.path("verdict").stringValue("")),
                    base + "Invalid fixture ID, raw output or verdict");
            Set<String> failures = strings(fixture.path("failedCheckIds"), base + "Invalid fixture check IDs");
            boolean wrongAnswer = "WRONG_ANSWER".equals(fixture.path("verdict").stringValue());
            require(assertionIds.containsAll(failures) && wrongAnswer == !failures.isEmpty(),
                    base + "Fixture check IDs do not match the verdict or assertions");
        }
    }

    private JsonNode read(String path, Map<String, byte[]> files) {
        try (var input = resources.getResource(ROOT + path).getInputStream()) {
            byte[] bytes = input.readAllBytes();
            files.put(path, bytes);
            return JSON.readTree(bytes);
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Could not load problem resource " + path, error);
        }
    }

    private static Set<String> strings(JsonNode array, String error) {
        require(array.isArray(), error);
        Set<String> values = new HashSet<>();
        for (JsonNode value : array) {
            require(nonBlank(value) && values.add(value.stringValue()), error);
        }
        return values;
    }

    private static boolean nonBlank(JsonNode value) {
        return value.isString() && !value.stringValue().isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String digest(Map<String, byte[]> files) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var file : files.entrySet()) {
                digest.update((file.getKey() + "\n" + file.getValue().length + "\n").getBytes(StandardCharsets.UTF_8));
                digest.update(file.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static class UnknownProblem extends IllegalArgumentException {
        UnknownProblem(String id) {
            super("Missing release for " + id);
        }
    }

    public record PublishedProblem(ProblemPack pack, int publishedVersion) {
    }

    public record ProblemPack(
            String problemId, int version, String caseId, String contentHash, JsonNode problem, Map<String, JsonNode> translations,
            JsonNode publicCase, JsonNode schema, JsonNode fixtures) {
    }
}
