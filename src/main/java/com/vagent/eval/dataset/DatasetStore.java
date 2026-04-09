package com.vagent.eval.dataset;

import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.dataset.Model.EvalDataset;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * P0 内存版存储：
 * - 先把 dataset 导入/查询闭环跑通
 * - Day3/Day4 再升级为 DB（H2/Postgres）也不影响接口契约
 */
@Component
public class DatasetStore {

    private final Map<String, EvalDatasetMutable> datasets = new ConcurrentHashMap<>();
    private final Map<String, List<EvalCase>> casesByDatasetId = new ConcurrentHashMap<>();

    public EvalDataset createDataset(String name, String version, String description) {
        String id = "ds_" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        EvalDatasetMutable m = new EvalDatasetMutable(id, name, version, description, now);
        datasets.put(id, m);
        casesByDatasetId.put(id, new CopyOnWriteArrayList<>());
        return m.snapshot(0);
    }

    public Optional<EvalDataset> getDataset(String datasetId) {
        EvalDatasetMutable m = datasets.get(datasetId);
        if (m == null) {
            return Optional.empty();
        }
        int count = casesByDatasetId.getOrDefault(datasetId, List.of()).size();
        return Optional.of(m.snapshot(count));
    }

    public List<EvalDataset> listDatasets() {
        List<EvalDataset> out = new ArrayList<>();
        for (EvalDatasetMutable m : datasets.values()) {
            int count = casesByDatasetId.getOrDefault(m.datasetId, List.of()).size();
            out.add(m.snapshot(count));
        }
        out.sort(Comparator.comparing(Model.EvalDataset::createdAt).reversed());
        return out;
    }

    public int appendCases(String datasetId, List<EvalCase> newCases) {
        List<EvalCase> list = casesByDatasetId.get(datasetId);
        if (list == null) {
            throw new IllegalArgumentException("dataset not found");
        }
        list.addAll(newCases);
        return list.size();
    }

    public List<EvalCase> listCases(String datasetId, int offset, int limit) {
        List<EvalCase> list = casesByDatasetId.get(datasetId);
        if (list == null) {
            throw new IllegalArgumentException("dataset not found");
        }
        int from = Math.max(0, offset);
        int to = Math.min(list.size(), from + Math.max(0, limit));
        if (from >= to) {
            return List.of();
        }
        return List.copyOf(list.subList(from, to));
    }

    public int caseCount(String datasetId) {
        return casesByDatasetId.getOrDefault(datasetId, List.of()).size();
    }

    private static final class EvalDatasetMutable {
        private final String datasetId;
        private final String name;
        private final String version;
        private final String description;
        private final Instant createdAt;

        private EvalDatasetMutable(String datasetId, String name, String version, String description, Instant createdAt) {
            this.datasetId = datasetId;
            this.name = name;
            this.version = version;
            this.description = description;
            this.createdAt = createdAt;
        }

        private EvalDataset snapshot(int caseCount) {
            return new EvalDataset(datasetId, name, version, description, createdAt, caseCount);
        }
    }
}

