package com.vagent.eval.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.dataset.Model.EvalDataset;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Day2：dataset 导入闭环 + 查询。
 * <p>
 * 目标是让「题库（dataset）」能被导入并被 run 使用；P0 先做最小字段集并严格做字段规范化。
 */
@RestController
@RequestMapping(path = "/api/v1/eval/datasets", produces = MediaType.APPLICATION_JSON_VALUE)
public class DatasetApi {

    private final DatasetStore store;
    private final ObjectMapper objectMapper;

    public DatasetApi(DatasetStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    public record CreateDatasetRequest(
            String name,
            String version,
            String description
    ) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public EvalDataset create(@RequestBody CreateDatasetRequest req) {
        String name = req == null ? null : req.name();
        String version = req == null ? null : req.version();
        String description = req == null ? null : req.description();
        if (!StringUtils.hasText(name) || !StringUtils.hasText(version)) {
            throw new IllegalArgumentException("name and version are required");
        }
        return store.createDataset(name.trim(), version.trim(), description == null ? "" : description.trim());
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("datasets", store.listDatasets());
    }

    @GetMapping("/{datasetId}")
    public EvalDataset get(@PathVariable String datasetId) {
        return store.getDataset(datasetId).orElseThrow(() -> new NoSuchElementException("dataset not found"));
    }

    @GetMapping("/{datasetId}/cases")
    public Map<String, Object> listCases(
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<EvalCase> rows = store.listCases(datasetId, offset, Math.min(limit, 200));
        return Map.of(
                "dataset_id", datasetId,
                "offset", offset,
                "limit", Math.min(limit, 200),
                "cases", rows
        );
    }

    /**
     * 支持两种导入：
     * - JSONL：Content-Type=application/x-ndjson（或 application/jsonl）
     * - CSV：Content-Type=text/csv
     */
    @PostMapping(path = "/{datasetId}/import")
    public ResponseEntity<Map<String, Object>> importCases(@PathVariable String datasetId, HttpServletRequest request) throws Exception {
        String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        byte[] payload = normalizeToUtf8(request.getInputStream().readAllBytes());

        int imported;
        if (contentType.contains("text/csv")) {
            imported = importCsv(datasetId, payload);
        } else if (contentType.contains("application/x-ndjson") || contentType.contains("application/jsonl") || contentType.contains("application/ndjson")) {
            imported = importJsonl(datasetId, payload);
        } else {
            // 尽量宽容：没带 content-type 时，按首行判断
            imported = sniffAndImport(datasetId, payload);
        }

        int total = store.caseCount(datasetId);
        return ResponseEntity.ok(Map.of(
                "dataset_id", datasetId,
                "imported", imported,
                "case_count", total
        ));
    }

    private int sniffAndImport(String datasetId, byte[] payload) throws Exception {
        String text = new String(payload, StandardCharsets.UTF_8);
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("{")) {
                return importJsonl(datasetId, payload);
            }
            return importCsv(datasetId, payload);
        }
        return 0;
    }

    private int importJsonl(String datasetId, byte[] payload) throws Exception {
        List<EvalCase> out = new ArrayList<>();
        Instant now = Instant.now();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(payload), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                String s = line.trim();
                if (s.isEmpty()) continue;
                JsonNode node;
                try {
                    node = objectMapper.readTree(s);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("invalid jsonl at line " + lineNo);
                }
                try {
                    out.add(normalizeCase(datasetId, node, now, "line:" + lineNo));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid case at line " + lineNo + ": " + e.getMessage());
                }
            }
        }

        store.getDataset(datasetId).orElseThrow(() -> new NoSuchElementException("dataset not found"));
        store.appendCases(datasetId, out);
        return out.size();
    }

    private int importCsv(String datasetId, byte[] payload) throws Exception {
        store.getDataset(datasetId).orElseThrow(() -> new NoSuchElementException("dataset not found"));

        List<EvalCase> out = new ArrayList<>();
        Instant now = Instant.now();

        CSVFormat fmt = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();

        try (CSVParser parser = CSVParser.parse(new java.io.ByteArrayInputStream(payload), StandardCharsets.UTF_8, fmt)) {
            int rowNo = 0;
            for (CSVRecord r : parser) {
                rowNo++;
                ObjectNodeLike node = new ObjectNodeLike();
                node.put("case_id", r.get("case_id"));
                node.put("question", r.get("question"));
                node.put("expected_behavior", r.get("expected_behavior"));
                node.put("requires_citations", r.get("requires_citations"));
                node.put("tags", r.isMapped("tags") ? r.get("tags") : "");
                try {
                    out.add(normalizeCase(datasetId, node.toJsonNode(objectMapper), now, "row:" + rowNo));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid case at row " + rowNo + ": " + e.getMessage());
                }
            }
        }

        store.appendCases(datasetId, out);
        return out.size();
    }

    /**
     * PowerShell 的 Invoke-RestMethod/Invoke-WebRequest 在发送字符串 body 时，可能使用 UTF-16LE 编码；
     * 但服务端按 UTF-8 读取会导致 JSON 解析在第 1 行就失败（出现大量 \\u0000）。\n
     * 这里做一个 P0 兼容：检测 BOM 或 NUL 字节密度，必要时按 UTF-16 解码后再转回 UTF-8 bytes。
     */
    static byte[] normalizeToUtf8(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return new byte[0];
        }

        // UTF-8 BOM: EF BB BF
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return Arrays.copyOfRange(raw, 3, raw.length);
        }

        // UTF-16 BOM
        if (raw.length >= 2) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) { // UTF-16LE
                String s = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
                return s.getBytes(StandardCharsets.UTF_8);
            }
            if (b0 == 0xFE && b1 == 0xFF) { // UTF-16BE
                String s = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
                return s.getBytes(StandardCharsets.UTF_8);
            }
        }

        // Heuristic: 如果 NUL 字节很多，按 UTF-16LE 试解码
        int nul = 0;
        for (byte b : raw) {
            if (b == 0) nul++;
        }
        if (nul > raw.length / 10) {
            String s = new String(raw, StandardCharsets.UTF_16LE);
            return s.getBytes(StandardCharsets.UTF_8);
        }

        return raw;
    }

    private EvalCase normalizeCase(String datasetId, JsonNode node, Instant now, String fallbackSuffix) {
        String caseId = text(node, "case_id");
        if (!StringUtils.hasText(caseId)) {
            caseId = "case_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_" + fallbackSuffix;
        }

        String question = text(node, "question");
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question is required");
        }

        EvalExpectedBehavior expected = EvalExpectedBehavior.parse(text(node, "expected_behavior"));

        Boolean requiresCitations = bool(node, "requires_citations");
        if (requiresCitations == null) {
            throw new IllegalArgumentException("requires_citations is required");
        }

        List<String> tags = tags(node.get("tags"));
        return new EvalCase(
                caseId.trim(),
                datasetId,
                question.trim(),
                expected,
                requiresCitations,
                tags,
                now
        );
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return "";
        if (v.isTextual()) return v.asText();
        return v.toString();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) {
            String s = v.asText().trim().toLowerCase(Locale.ROOT);
            if (s.equals("true")) return true;
            if (s.equals("false")) return false;
        }
        if (v.isNumber()) {
            return v.asInt() != 0;
        }
        return null;
    }

    private static List<String> tags(JsonNode tagsNode) {
        if (tagsNode == null || tagsNode.isNull()) {
            return List.of();
        }
        if (tagsNode.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode n : tagsNode) {
                if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                    out.add(n.asText().trim());
                }
            }
            return List.copyOf(out);
        }
        if (tagsNode.isTextual()) {
            String s = tagsNode.asText().trim();
            if (s.isEmpty()) return List.of();
            // Day2 约定：CSV tags 用 '|' 分隔
            String[] parts = s.split("\\|");
            List<String> out = new ArrayList<>();
            for (String p : parts) {
                if (StringUtils.hasText(p)) out.add(p.trim());
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    /**
     * 简化：用 Map 暂存 CSV 行，再转 JsonNode 走统一 normalizeCase。
     */
    private static final class ObjectNodeLike {
        private final Map<String, String> m = new LinkedHashMap<>();

        void put(String k, String v) {
            m.put(k, v == null ? "" : v);
        }

        JsonNode toJsonNode(ObjectMapper om) {
            return om.valueToTree(m);
        }
    }
}

