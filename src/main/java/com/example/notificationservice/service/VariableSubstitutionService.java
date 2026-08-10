package com.example.notificationservice.service;

import com.example.notificationservice.exception.InvalidTemplateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VariableSubstitutionService {

    private static final Logger log = LoggerFactory.getLogger(VariableSubstitutionService.class);

    // First alternative matches an escaped placeholder ($${...}); it has no capturing
    // group, so group(1) is null whenever that branch matches and the whole thing is
    // treated as literal text (with the doubled $ collapsed to one). Second alternative
    // is a real placeholder, captured in group 1 so it can be looked up and substituted;
    // it deliberately allows an empty body ([^}]*) so ${} is recognized (and rejected)
    // rather than silently ignored.
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\$\\{[^}]*}|\\$\\{([^}]*)}");
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.]+");

    public String render(String template, Map<String, String> variables) {
        if (template == null || variables == null) {
            throw new IllegalArgumentException("Template and variables must not be null");
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        int substitutionCount = 0;

        while (matcher.find()) {
            if (matcher.group(1) == null) {
                // Escaped placeholder: drop one leading '$' and keep the rest literal.
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0).substring(1)));
                continue;
            }

            String variableName = matcher.group(1);
            validateVariableName(variableName);

            if (!variables.containsKey(variableName)) {
                throw new InvalidTemplateException("Missing required variable: " + variableName);
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(variables.get(variableName)));
            substitutionCount++;
        }
        matcher.appendTail(result);

        log.debug("Rendered template with {} substitutions", substitutionCount);
        return result.toString();
    }

    public void validateRequiredVariables(String template, Map<String, String> provided) {
        if (template == null || provided == null) {
            throw new IllegalArgumentException("Template and provided variables must not be null");
        }

        Set<String> required = extractVariableNames(template);
        Set<String> missing = new TreeSet<>();
        for (String variableName : required) {
            if (!provided.containsKey(variableName)) {
                missing.add(variableName);
            }
        }

        if (!missing.isEmpty()) {
            throw new InvalidTemplateException("Missing required variables: {" + String.join(", ", missing) + "}");
        }

        log.debug("Template validation passed, all {} variables present", required.size());
    }

    public Set<String> extractVariableNames(String template) {
        if (template == null) {
            throw new IllegalArgumentException("Template must not be null");
        }

        Set<String> names = new HashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                continue; // escaped placeholder, not a real variable
            }
            String variableName = matcher.group(1);
            validateVariableName(variableName);
            names.add(variableName);
        }
        return names;
    }

    private void validateVariableName(String variableName) {
        if (variableName.isEmpty()) {
            throw new InvalidTemplateException("Empty variable name in template: ${}");
        }
        if (!VALID_NAME_PATTERN.matcher(variableName).matches()) {
            throw new InvalidTemplateException("Invalid variable name in template: ${" + variableName + "}");
        }
    }
}
