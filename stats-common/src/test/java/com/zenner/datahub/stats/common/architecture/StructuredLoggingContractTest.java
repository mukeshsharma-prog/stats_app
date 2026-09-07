package com.zenner.datahub.stats.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Build guard for the structured fields and payload boundary of T-005. */
class StructuredLoggingContractTest {

    private static final Pattern LOG_STATEMENT = Pattern.compile(
            "\\b(?:LOGGER|log|logger)\\s*\\.\\s*"
                    + "(?:at(?:Trace|Debug|Info|Warn|Error)\\s*\\(\\)|"
                    + "(?:trace|debug|info|warn|error)\\s*\\()"
                    + "(?:(?!;).)*;",
            Pattern.DOTALL);

    private static final Pattern RAW_PAYLOAD_REFERENCE = Pattern.compile(
            "\\b(?:body|payload|rawRecord)\\b|\\.\\s*(?:data|getBody)\\s*\\(");

    /** Any intentional payload log must be reviewed here with its governing decision. */
    private static final Map<String, String> JUSTIFIED_PAYLOAD_LOGS = Map.of();

    @Test
    void everyApplicationLogStatementHasABoundedEventField() {
        List<String> offences = new ArrayList<>();
        int statements = 0;

        for (Path file : applicationSources()) {
            String code = MainSourceScan.codeWithoutComments(file);
            Matcher matcher = LOG_STATEMENT.matcher(code);
            while (matcher.find()) {
                statements++;
                if (!matcher.group().contains(".addKeyValue(\"event\",")) {
                    offences.add(MainSourceScan.relativePath(file));
                }
            }
        }

        assertThat(statements).as("the guard must see the application's log statements").isGreaterThan(10);
        assertThat(offences)
                .as("Every application-owned log statement needs a bounded T-005 event field")
                .isEmpty();
    }

    @Test
    void noApplicationLogStatementReceivesARawPayloadExpression() {
        List<String> offences = new ArrayList<>();

        for (Path file : applicationSources()) {
            String relative = MainSourceScan.relativePath(file);
            if (JUSTIFIED_PAYLOAD_LOGS.containsKey(relative)) {
                continue;
            }
            String code = withoutLiterals(MainSourceScan.codeWithoutComments(file));
            Matcher matcher = LOG_STATEMENT.matcher(code);
            while (matcher.find()) {
                if (RAW_PAYLOAD_REFERENCE.matcher(matcher.group()).find()) {
                    offences.add(relative);
                }
            }
        }

        assertThat(offences)
                .as(
                        "A raw body/data expression handed to a logger can expose an entire record. "
                                + "ltestats and netstats payloads are forbidden at every level (P1); "
                                + "any other deliberate DEBUG exception belongs in JUSTIFIED with "
                                + "its decision")
                .isEmpty();
    }

    @Test
    void eachApplicationAddsItsStableModuleField() throws Exception {
        assertModule("stats-input", "input");
        assertModule("stats-writer", "writer");
        assertModule("stats-api", "api");
    }

    private static List<Path> applicationSources() {
        return MainSourceScan.files().stream()
                .filter(path -> {
                    String relative = MainSourceScan.relativePath(path);
                    return relative.startsWith("stats-input/")
                            || relative.startsWith("stats-writer/")
                            || relative.startsWith("stats-api/");
                })
                .toList();
    }

    private static void assertModule(String module, String expected) throws Exception {
        Path properties = MainSourceScan.repositoryRoot()
                .resolve(module)
                .resolve("src/main/resources/application.properties");
        assertThat(Files.readString(properties))
                .contains("logging.structured.format.console=ecs")
                .contains("logging.structured.json.add.module=" + expected);
    }

    /** Removes string/character contents so words in a safe log message do not look like variables. */
    private static String withoutLiterals(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current != '"' && current != '\'') {
                result.append(current);
                index++;
                continue;
            }

            char quote = current;
            result.append(quote);
            index++;
            while (index < source.length()) {
                current = source.charAt(index++);
                if (current == '\\' && index < source.length()) {
                    index++;
                } else if (current == quote) {
                    result.append(quote);
                    break;
                }
            }
        }
        return result.toString();
    }
}
