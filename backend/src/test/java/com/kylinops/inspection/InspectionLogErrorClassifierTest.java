package com.kylinops.inspection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-02 Task 3 — Log error classifier tests (design §6.3).
 *
 * <p>Positive corpus: {@code error}, {@code failed}, {@code failure}, {@code fatal},
 * {@code panic}, {@code exception}, {@code segfault}, {@code oom}, {@code out of memory},
 * {@code permission denied} (case-insensitive, word-boundary).</p>
 *
 * <p>Negative corpus (must NOT match — design explicitly trades off false positives
 * here for determinism): {@code 0 errors}, {@code failed login count: 0}.</p>
 *
 * <p>Truncation: only the first 50 entries are inspected.</p>
 */
@DisplayName("P1-02 T3 — InspectionLogErrorClassifier")
class InspectionLogErrorClassifierTest {

    @Test
    @DisplayName("Positive corpus: every required marker matches")
    void positiveCorpusAllHit() {
        List<String> entries = List.of(
                "ERROR something broke",
                "service failed to start",
                "catastrophic failure detected",
                "FATAL: kernel panic",
                "panic: stack overflow",
                "Unhandled exception in worker thread",
                "kernel: segfault at 0xffff",
                "killed process (OOM)",
                "Java heap Out of Memory",
                "audit: permission denied for user");

        List<String> errors = InspectionLogErrorClassifier.classify(entries);

        assertThat(errors).hasSize(entries.size());
        assertThat(errors).containsExactlyElementsOf(entries);
    }

    @Test
    @DisplayName("Case-insensitive: Error, FAILED, Out Of Memory all match")
    void caseInsensitive() {
        List<String> entries = List.of(
                "Error: connect timed out",
                "FAILED to flush",
                "Out Of Memory while allocating buffer");

        List<String> errors = InspectionLogErrorClassifier.classify(entries);

        assertThat(errors).hasSize(3);
    }

    @Test
    @DisplayName("Negative corpus: '0 errors' and 'failed login count: 0' do NOT match")
    void negativeCorpusMissed() {
        // Design §6.3: classifier uses fixed word-boundary patterns, not NLP.
        // '0 errors' / 'failed login count: 0' are false-positive-prone input and the
        // design explicitly accepts the false-positive trade-off by NOT adding NLP.
        // These two specific phrases must NOT match the classifier.
        List<String> entries = List.of(
                "0 errors reported in last 24h",
                "failed login count: 0",
                "system healthy: no anomalies detected");

        List<String> errors = InspectionLogErrorClassifier.classify(entries);

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Empty entries → empty errors")
    void emptyEntriesEmptyErrors() {
        assertThat(InspectionLogErrorClassifier.classify(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Null entries → empty errors (defensive)")
    void nullEntriesEmptyErrors() {
        assertThat(InspectionLogErrorClassifier.classify(null)).isEmpty();
    }

    @Test
    @DisplayName(">50 entries: only first 50 inspected")
    void truncationAt50Entries() {
        // First 50 entries are all clean; 51st is the smoking gun.
        List<String> clean50 = IntStream.range(0, 50)
                .mapToObj(i -> "normal log line " + i)
                .toList();
        List<String> withSmoke = new java.util.ArrayList<>(clean50);
        withSmoke.add("ERROR the smoking gun after truncation");

        // Without truncation we'd see 1 error; with truncation we must see 0.
        List<String> errors = InspectionLogErrorClassifier.classify(withSmoke);

        assertThat(errors).isEmpty();
    }

    @Test
    @DisplayName("Exactly 50 entries — boundary inclusive")
    void boundaryAtExactly50() {
        List<String> entries = IntStream.range(0, 49)
                .mapToObj(i -> "clean line " + i)
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        entries.add("ERROR boundary at index 49");

        List<String> errors = InspectionLogErrorClassifier.classify(entries);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("boundary at index 49");
    }
}