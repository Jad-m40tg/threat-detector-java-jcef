package com.dac.threatdetector.core.service;

import com.dac.threatdetector.core.error.ThreatException;
import com.dac.threatdetector.core.model.InputType;
import com.dac.threatdetector.core.model.NotVerifiedReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Flexible CSV parser for batch analysis, translated from csv_parser.rs.
 *
 * <p>Recognizes two dialects: typed (id,input_type,content) and plain (first
 * column is the input). Bad rows become NotVerified items; only file-level
 * limits abort the whole file.
 */
public final class CsvParser {

    public static final long MAX_CSV_SIZE = 1024L * 1024L;
    public static final int MAX_CSV_ROWS = 5000;

    public record CsvRowItem(
            String id,
            InputType inputType,
            String content,
            int rowIndex,
            NotVerifiedReason parseError) {
    }

    private enum Dialect {
        TYPED,
        PLAIN
    }

    private sealed interface HeaderKind permits HeaderTyped, HeaderPlain, HeaderData {
    }

    private record HeaderTyped() implements HeaderKind {
    }

    private record HeaderPlain() implements HeaderKind {
    }

    private record HeaderData(Dialect dialect) implements HeaderKind {
    }

    private CsvParser() {
    }

    public static List<CsvRowItem> parseCsvFile(Path path) throws ThreatException {
        if (path == null || !Files.exists(path)) {
            throw ThreatException.csvFile("File does not exist: " + path);
        }
        long size;
        try {
            size = Files.size(path);
        } catch (IOException e) {
            throw ThreatException.io(String.valueOf(e.getMessage()));
        }
        if (size == 0) {
            throw ThreatException.csvFile("CSV file is empty");
        }
        if (size > MAX_CSV_SIZE) {
            throw ThreatException.csvFile("CSV file size (" + size
                    + " bytes) exceeds limit (" + MAX_CSV_SIZE + " bytes)");
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ThreatException.io(String.valueOf(e.getMessage()));
        }
        return parseRows(path.toString(), text);
    }

    public static List<CsvRowItem> parseCsvText(String label, String text) throws ThreatException {
        if (text == null || text.isEmpty()) {
            throw ThreatException.csvFile("CSV file '" + label + "' is empty");
        }
        long size = text.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_CSV_SIZE) {
            throw ThreatException.csvFile("CSV file '" + label + "' size (" + size
                    + " bytes) exceeds limit (" + MAX_CSV_SIZE + " bytes)");
        }
        return parseRows(label, text);
    }

    private static List<CsvRowItem> parseRows(String label, String rawText) throws ThreatException {
        // Rust strip_prefix removes exactly one leading BOM.
        String text = rawText.startsWith("\uFEFF") ? rawText.substring(1) : rawText;
        String[] lines = text.split("\n", -1);
        List<CsvRowItem> items = new ArrayList<>();
        Dialect dialect = null;
        int rowNum = 0;

        for (String rawLine : lines) {
            rowNum++;
            String line = rawLine.endsWith("\r")
                    ? rawLine.substring(0, rawLine.length() - 1)
                    : rawLine;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }

            Dialect current = dialect;
            if (current == null) {
                HeaderKind kind = classifyHeader(trimmed);
                if (kind instanceof HeaderTyped) {
                    dialect = Dialect.TYPED;
                    continue;
                }
                if (kind instanceof HeaderPlain) {
                    dialect = Dialect.PLAIN;
                    continue;
                }
                current = ((HeaderData) kind).dialect();
                dialect = current;
            }

            if (items.size() >= MAX_CSV_ROWS) {
                throw ThreatException.csvFile("CSV file '" + label
                        + "' has more than the maximum of " + MAX_CSV_ROWS + " data rows");
            }

            CsvRowItem item = current == Dialect.TYPED
                    ? parseTypedRow(trimmed, rowNum)
                    : parsePlainRow(trimmed, rowNum);
            if (item != null) {
                items.add(item);
            }
        }

        if (items.isEmpty()) {
            throw ThreatException.csvFile("CSV file '" + label + "' contains no readable rows");
        }
        return items;
    }

    private static HeaderKind classifyHeader(String line) {
        String[] fields = line.split(",", -1);
        String f0 = unquote(fields.length > 0 ? fields[0] : "").strip().toLowerCase(Locale.ROOT);
        String f1 = unquote(fields.length > 1 ? fields[1] : "").strip().toLowerCase(Locale.ROOT);
        if ("input".equals(f0)) {
            return new HeaderPlain();
        }
        if ("input_type".equals(f1) || "type".equals(f1)) {
            return new HeaderTyped();
        }
        return new HeaderData(sniffDialect(line));
    }

    private static Dialect sniffDialect(String line) {
        String[] fields = line.split(",", -1);
        String f1 = fields.length > 1 ? fields[1].strip().toUpperCase(Locale.ROOT) : "";
        return "URL".equals(f1) || "MESSAGE".equals(f1) ? Dialect.TYPED : Dialect.PLAIN;
    }

    private static String unquote(String field) {
        String f = field.strip();
        if (f.length() >= 2 && f.startsWith("\"") && f.endsWith("\"")) {
            return f.substring(1, f.length() - 1);
        }
        return f;
    }

    private static CsvRowItem parseTypedRow(String line, int rowNum) {
        List<String> fields = readRecord(line);
        if (fields == null) {
            return new CsvRowItem("row-" + rowNum, InputType.MESSAGE, "", rowNum,
                    new NotVerifiedReason.InvalidCsvRow(rowNum, "Malformed CSV line"));
        }
        if (fields.isEmpty()) {
            return null;
        }
        String id = field(fields, 0).strip();
        String rawType = field(fields, 1).strip().toUpperCase(Locale.ROOT);
        String content = field(fields, 2).strip();
        String rowId = id.isEmpty() ? "row-" + rowNum : id;
        InputType type = switch (rawType) {
            case "URL" -> InputType.URL;
            case "MESSAGE" -> InputType.MESSAGE;
            default -> null;
        };
        if (type == null) {
            return new CsvRowItem(rowId, InputType.MESSAGE, content, rowNum,
                    new NotVerifiedReason.InvalidCsvRow(rowNum,
                            "Unknown input type '" + rawType + "'"));
        }
        return new CsvRowItem(rowId, type, content, rowNum, null);
    }

    private static CsvRowItem parsePlainRow(String line, int rowNum) {
        List<String> fields = readRecord(line);
        if (fields == null) {
            return new CsvRowItem("row-" + rowNum, InputType.MESSAGE, "", rowNum,
                    new NotVerifiedReason.InvalidCsvRow(rowNum, "Malformed CSV line"));
        }
        String content = field(fields, 0).strip();
        if (content.isEmpty()) {
            return null;
        }
        return new CsvRowItem("row-" + rowNum, Validator.detectInputType(content),
                content, rowNum, null);
    }

    /**
     * Reads one CSV record with flexible width. Returns null when the row is
     * malformed, matching the Rust parser's typed NotVerified row.
     */
    private static List<String> readRecord(String line) {
        try (CSVParser parser = CSVParser.parse(line, CSVFormat.DEFAULT)) {
            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                return List.of();
            }
            CSVRecord record = records.get(0);
            List<String> fields = new ArrayList<>(record.size());
            for (int i = 0; i < record.size(); i++) {
                fields.add(record.get(i));
            }
            return fields;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String field(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index) : "";
    }
}
