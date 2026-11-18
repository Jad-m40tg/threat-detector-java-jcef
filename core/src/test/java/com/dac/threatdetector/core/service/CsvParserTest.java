package com.dac.threatdetector.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dac.threatdetector.core.error.ThreatException;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.service.CsvParser.CsvRowItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvParserTest {

    @Test
    void plainDialectReadsTheAppSampleFormat() throws Exception {
        String text = "input,label\r\n"
                + "https://payments-secure.example/verify-account,High risk\r\n"
                + "https://developer.mozilla.org,Low risk\r\n";
        List<CsvRowItem> items = CsvParser.parseCsvText("sample.csv", text);
        assertEquals(2, items.size());
        assertEquals("https://payments-secure.example/verify-account", items.get(0).content());
        assertEquals(InputType.URL, items.get(0).inputType());
        assertNull(items.get(0).parseError());
        assertEquals(InputType.URL, items.get(1).inputType());
    }

    @Test
    void plainDialectAutoDetectsMessages() throws Exception {
        String text = "Your lab appointment is confirmed for tomorrow.\nAccount locked! Verify now.";
        List<CsvRowItem> items = CsvParser.parseCsvText("msgs.csv", text);
        assertEquals(2, items.size());
        for (CsvRowItem item : items) {
            assertEquals(InputType.MESSAGE, item.inputType());
            assertNull(item.parseError());
        }
    }

    @Test
    void typedDialectReadsTheDatasetFormat() throws Exception {
        String text = "id,input_type,content,extra\n"
                + "1,URL,https://example.edu/x,\n"
                + "2,MESSAGE,hello world,\n"
                + "3,SMS,bad type,";
        List<CsvRowItem> items = CsvParser.parseCsvText("data.csv", text);
        assertEquals(3, items.size());
        assertEquals("1", items.get(0).id());
        assertEquals(InputType.URL, items.get(0).inputType());
        assertEquals("hello world", items.get(1).content());
        assertNotNull(items.get(2).parseError());
    }

    @Test
    void headerlessTypedFileKeepsItsFirstRow() throws Exception {
        String text = "1,URL,https://a.example\n2,MESSAGE,plain text here";
        List<CsvRowItem> items = CsvParser.parseCsvText("nohdr.csv", text);
        assertEquals(2, items.size());
        assertEquals("1", items.get(0).id());
        assertEquals("https://a.example", items.get(0).content());
        assertEquals("plain text here", items.get(1).content());
    }

    @Test
    void headerlessPlainFileIsSniffed() throws Exception {
        String text = "https://a.example\nplain message without columns";
        List<CsvRowItem> items = CsvParser.parseCsvText("nohdr.csv", text);
        assertEquals(2, items.size());
        assertEquals(InputType.URL, items.get(0).inputType());
        assertEquals(InputType.MESSAGE, items.get(1).inputType());
    }

    @Test
    void rowLimitIsATypedErrorNotTruncation() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i <= CsvParser.MAX_CSV_ROWS; i++) {
            text.append("https://example.com/page/").append(i).append('\n');
        }
        ThreatException error = assertThrows(ThreatException.class,
                () -> CsvParser.parseCsvText("big.csv", text.toString()));
        assertTrue(error.getMessage().contains(String.valueOf(CsvParser.MAX_CSV_ROWS)));
    }

    @Test
    void headerOnlyAndEmptyFilesAreTypedErrors() {
        ThreatException header = assertThrows(ThreatException.class,
                () -> CsvParser.parseCsvText("h.csv", "id,input_type,content"));
        assertTrue(header.getMessage().contains("no readable rows"));
        ThreatException empty = assertThrows(ThreatException.class,
                () -> CsvParser.parseCsvText("e.csv", ""));
        assertTrue(empty.getMessage().contains("is empty"));
    }

    @Test
    void badRowsFixtureKeepsEveryRowAndTypesTheUnknownOne() throws Exception {
        var resource = getClass().getClassLoader().getResource("bad_rows.csv");
        assertNotNull(resource, "bad_rows.csv must be on the test classpath");
        List<CsvRowItem> items = CsvParser.parseCsvFile(java.nio.file.Path.of(resource.toURI()));

        assertEquals(5, items.size(), "row errors must not abort the file");
        assertEquals("2", items.get(1).id());
        assertNotNull(items.get(1).parseError());
        assertTrue(items.get(1).parseError().message().contains("UNKNOWN_TYPE"));
        assertEquals("some random content", items.get(1).content());
        assertEquals(InputType.MESSAGE, items.get(1).inputType(),
                "unknown type falls back to Message for the typed error row");

        assertNull(items.get(3).parseError(), "ordinary message row stays clean");
        assertNull(items.get(4).parseError(), "empty content is a row, not a parse error");
        assertEquals("", items.get(4).content());
    }

    @Test
    void blankLinesAndBomAreTolerated() throws Exception {
        String text = "\uFEFF\n\nid,input_type,content\n\n1,URL,https://a.example\n\n";
        List<CsvRowItem> items = CsvParser.parseCsvText("bom.csv", text);
        assertEquals(1, items.size());
        assertEquals("https://a.example", items.get(0).content());
    }
}
