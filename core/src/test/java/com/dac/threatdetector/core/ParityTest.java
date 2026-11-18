package com.dac.threatdetector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dac.threatdetector.core.engine.Engine;
import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.ItemResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Differential test against the Rust implementation.
 *
 * <p>The goldens were produced by running the original Rust `threat-core`
 * (unmodified rules, scorer, parser and IPC conversion) over the shipped
 * dataset, the 500-row stress file and 52 hand-written edge cases: 562 inputs
 * in total. Every IPC field except the wall-clock duration must match exactly.
 */
class ParityTest {

    private static final List<String> COMPARED_FIELDS = List.of(
            "id", "inputType", "input", "riskLevel", "risk", "state", "score", "status",
            "category", "details", "reasons", "triggeredRules", "recommendation", "error", "ts");

    @Test
    void javaMatchesRustGoldensOnEveryInput() throws Exception {
        Path golden = resource("golden/rust_golden.jsonl");
        ObjectMapper mapper = new ObjectMapper();
        int checked = 0;
        List<String> failures = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(golden, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = mapper.readTree(line);
                String id = row.path("id").asText();
                String typeWire = row.path("input_type").asText();
                String content = row.path("content").asText();

                assertFalse(row.path("parse_error").asBoolean(false),
                        "Golden " + id + " contains a parse error; add an explicit mapping");

                ItemResult result = Engine.analyzeItem(
                        new AnalysisRequest(id, InputType.fromWire(typeWire), content));
                JsonNode actual = mapper.valueToTree(result.toIpcShape(1000));
                JsonNode expected = row.path("result");

                for (String field : COMPARED_FIELDS) {
                    // Reparse both sides so numeric node widths (IntNode vs LongNode)
                    // cannot create false mismatches; the JSON values must still match.
                    JsonNode want = mapper.readTree(expected.path(field).toString());
                    JsonNode got = mapper.readTree(actual.path(field).toString());
                    if (!want.equals(got)) {
                        failures.add("golden " + id + " field '" + field + "': expected "
                                + want + " but was " + got);
                    }
                }
                checked++;
            }
        }

        assertTrue(failures.isEmpty(), "Rust/Java parity failures:\n" + String.join("\n", failures));
        assertTrue(checked >= 500, "Differential test must cover 500+ inputs, covered " + checked);
    }

    private static Path resource(String name) throws Exception {
        var url = ParityTest.class.getClassLoader().getResource(name);
        assertNotNull(url, "Test resource " + name + " must exist");
        return Path.of(url.toURI());
    }
}
