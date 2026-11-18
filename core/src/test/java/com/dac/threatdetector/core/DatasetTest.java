package com.dac.threatdetector.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.dac.threatdetector.core.engine.Engine;
import com.dac.threatdetector.core.model.AnalysisRequest;
import com.dac.threatdetector.core.model.ItemResult;
import com.dac.threatdetector.core.model.NotVerifiedReason;
import com.dac.threatdetector.core.model.RiskLevel;
import com.dac.threatdetector.core.model.RuleHit;
import com.dac.threatdetector.core.model.Verdict;
import com.dac.threatdetector.core.service.CsvParser;
import com.dac.threatdetector.core.service.CsvParser.CsvRowItem;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * G1 acceptance: the shipped 10-row dataset must produce the exact risk level,
 * display score and ordered rule list from the Rust oracle.
 */
class DatasetTest {

    private record Expected(RiskLevel level, Integer score, List<String> ruleIds) {
    }

    private static final List<Expected> EXPECTED = java.util.Arrays.asList(
            new Expected(RiskLevel.LOW, 12, List.of()),
            new Expected(RiskLevel.HIGH, 90, List.of("http_protocol", "ip_host")),
            new Expected(RiskLevel.HIGH, 99,
                    List.of("http_protocol", "nested_subdomains", "suspicious_keyword")),
            new Expected(RiskLevel.HIGH, 99,
                    List.of("http_protocol", "authority_userinfo", "suspicious_keyword")),
            new Expected(RiskLevel.MEDIUM, 30, List.of("http_protocol")),
            new Expected(RiskLevel.LOW, 15, List.of("action_request")),
            new Expected(RiskLevel.HIGH, 99,
                    List.of("urgency_language", "suspension_threat", "account_reference",
                            "embedded_http_protocol", "embedded_ip_host")),
            new Expected(RiskLevel.MEDIUM, 30, List.of("action_request", "account_reference")),
            null,
            null);

    @Test
    void tenRowDatasetAcceptance() throws Exception {
        Path fixture = fixturePath();
        assertTrue(fixture.toFile().exists(), "Test dataset fixture must exist at " + fixture);

        List<CsvRowItem> items = CsvParser.parseCsvFile(fixture);
        assertEquals(10, items.size(), "Expected exactly 10 rows in test dataset");

        int low = 0;
        int medium = 0;
        int high = 0;
        int notVerified = 0;

        for (int index = 0; index < items.size(); index++) {
            CsvRowItem item = items.get(index);
            Expected expected = EXPECTED.get(index);
            assertEquals(String.valueOf(index + 1), item.id(), "Dataset IDs are 1..10 in order");

            ItemResult result = item.parseError() == null
                    ? Engine.analyzeItem(new AnalysisRequest(item.id(), item.inputType(), item.content()))
                    : new ItemResult(item.id(), item.inputType(), item.content(),
                    new Verdict.NotVerified(item.parseError()), 0);

            if (expected == null) {
                assertTrue(result.verdict() instanceof Verdict.NotVerified,
                        "Row " + item.id() + " must be NotVerified");
                notVerified++;
                continue;
            }

            Verdict.Assessed assessed = assessed(result, "Row " + item.id());
            assertEquals(expected.level(), assessed.level(), "Row " + item.id() + " level");
            assertEquals(expected.score(), assessed.score(), "Row " + item.id() + " score");
            assertEquals(expected.ruleIds(),
                    assessed.hits().stream().map(RuleHit::id).toList(),
                    "Row " + item.id() + " rules");

            switch (expected.level()) {
                case LOW -> low++;
                case MEDIUM -> medium++;
                case HIGH -> high++;
            }
        }

        assertEquals(2, low);
        assertEquals(2, medium);
        assertEquals(4, high);
        assertEquals(2, notVerified);
    }

    private static Verdict.Assessed assessed(ItemResult result, String label) {
        if (result.verdict() instanceof Verdict.Assessed assessed) {
            return assessed;
        }
        NotVerifiedReason reason = ((Verdict.NotVerified) result.verdict()).reason();
        return fail(label + " should be Assessed but was NotVerified: " + reason.message());
    }

    private static Path fixturePath() throws URISyntaxException {
        var resource = DatasetTest.class.getClassLoader().getResource("project_test_dataset.csv");
        assertNotNull(resource, "project_test_dataset.csv must be on the test classpath");
        return Path.of(resource.toURI());
    }
}
