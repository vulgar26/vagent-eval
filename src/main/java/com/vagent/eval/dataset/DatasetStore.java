package com.vagent.eval.dataset;

import com.vagent.eval.dataset.Model.EvalCase;
import com.vagent.eval.dataset.Model.EvalDataset;

import java.util.List;
import java.util.Optional;

/**
 * 题库存储抽象：生产环境为 {@link JdbcDatasetStore}；单测可用 {@link InMemoryDatasetStore}。
 */
public interface DatasetStore {

    EvalDataset createDataset(String name, String version, String description);

    Optional<EvalDataset> getDataset(String datasetId);

    List<EvalDataset> listDatasets();

    int appendCases(String datasetId, List<EvalCase> newCases);

    List<EvalCase> listCases(String datasetId, int offset, int limit);

    List<EvalCase> listAllCases(String datasetId);

    int caseCount(String datasetId);

    /**
     * 删除 dataset 及其全部 case；并删除引用该 {@code dataset_id} 的 {@code eval_run}（级联删 {@code eval_result}）。
     */
    boolean deleteDataset(String datasetId);
}
