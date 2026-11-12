package com.yourorg.qbank.core.importer;

import java.util.ArrayList;
import java.util.List;

public class ParsedQuestionRow {

    public enum RowStatus {
        VALID,
        WARNING,
        ERROR,
        DUPLICATE_SKIPPED,
        EXCLUDED
    }

    private int sourceRow;
    private String questionText;
    private String normalizedHash;
    private List<String> options = new ArrayList<>();
    private String rawAnswerInput;
    private Integer correctIndex;
    private boolean answerInferredFromText;
    private boolean duplicateSkipped;
    private boolean excluded;
    private RowStatus status = RowStatus.VALID;
    private List<ImportIssue> issues = new ArrayList<>();

    public ParsedQuestionRow() {}

    public int getSourceRow() { return sourceRow; }
    public void setSourceRow(int sourceRow) { this.sourceRow = sourceRow; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public String getNormalizedHash() { return normalizedHash; }
    public void setNormalizedHash(String normalizedHash) { this.normalizedHash = normalizedHash; }

    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) { this.options = options == null ? new ArrayList<>() : new ArrayList<>(options); }

    public String getRawAnswerInput() { return rawAnswerInput; }
    public void setRawAnswerInput(String rawAnswerInput) { this.rawAnswerInput = rawAnswerInput; }

    public Integer getCorrectIndex() { return correctIndex; }
    public void setCorrectIndex(Integer correctIndex) { this.correctIndex = correctIndex; }

    public boolean isAnswerInferredFromText() { return answerInferredFromText; }
    public void setAnswerInferredFromText(boolean answerInferredFromText) { this.answerInferredFromText = answerInferredFromText; }

    public boolean isDuplicateSkipped() { return duplicateSkipped; }
    public void setDuplicateSkipped(boolean duplicateSkipped) { this.duplicateSkipped = duplicateSkipped; }

    public boolean isExcluded() { return excluded; }
    public void setExcluded(boolean excluded) { this.excluded = excluded; }

    public RowStatus getStatus() { return status; }
    public void setStatus(RowStatus status) { this.status = status; }

    public List<ImportIssue> getIssues() { return issues; }
    public void setIssues(List<ImportIssue> issues) { this.issues = issues == null ? new ArrayList<>() : new ArrayList<>(issues); }
}
