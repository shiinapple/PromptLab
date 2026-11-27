package cn.shiinapple.promptpractice.practice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

public final class MessageAssembler {
    private static final Pattern TOKEN = Pattern.compile(Pattern.quote("\\\\") + "|"
            + Pattern.quote("\\{{") + "|\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");

    private static final Pattern EMPTY = Pattern.compile("[ \t\r\n]*");

    private MessageAssembler() {
    }

    public static List<Message> assemble(JsonNode problem, Map<String, String> slots, JsonNode variables) {
        var definitions = problem.path("slots");
        if (!slots.keySet().equals(new HashSet<>(definitions.propertyNames()))) {
            throw new IllegalArgumentException("SLOT_SET");
        }

        var messages = new ArrayList<Message>();
        for (var message : problem.path("messages")) {
            var content = new StringBuilder();
            for (var part : message.path("parts")) {
                switch (part.path("type").stringValue("")) {
                    case "fixedText" -> content.append(part.path("text").stringValue());
                    case "caseVariable" -> content.append(variable(variables, part.path("name").stringValue()));
                    case "slot" -> {
                        String name = part.path("name").stringValue();
                        content.append(render(slots.get(name), definitions.path(name).path("requiredVariables"), variables));
                    }
                    default -> throw new IllegalArgumentException("MESSAGE_PART");
                }
            }
            messages.add(new Message(message.path("role").stringValue(), content.toString()));
        }
        return List.copyOf(messages);
    }

    private static String render(String template, JsonNode required, JsonNode variables) {
        if (template == null || EMPTY.matcher(template).matches()) {
            throw new IllegalArgumentException("EMPTY_SLOT");
        }
        var seen = new HashSet<String>();
        var output = new StringBuilder();
        var matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement;
            if (name == null) {
                replacement = matcher.group().substring(1);
            } else {
                replacement = variable(variables, name);
                seen.add(name);
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        for (var key : required) {
            if (!seen.contains(key.stringValue())) {
                throw new IllegalArgumentException("MISSING_VARIABLE");
            }
        }
        return matcher.appendTail(output).toString();
    }

    private static String variable(JsonNode variables, String name) {
        var value = variables.path(name);
        if (!value.isString()) {
            throw new IllegalArgumentException("UNKNOWN_VARIABLE");
        }
        return value.stringValue();
    }

    public record Message(String role, String content) {
    }
}
