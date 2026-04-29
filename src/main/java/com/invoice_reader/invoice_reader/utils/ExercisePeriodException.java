package com.invoice_reader.invoice_reader.utils;

import java.time.LocalDate;

public class ExercisePeriodException extends RuntimeException {
    private final LocalDate exerciseStartDate;
    private final LocalDate exerciseEndDate;
    private final LocalDate documentDate;

    public ExercisePeriodException(LocalDate exerciseStartDate, LocalDate exerciseEndDate, LocalDate documentDate) {
        super("exercise_period_mismatch");
        this.exerciseStartDate = exerciseStartDate;
        this.exerciseEndDate = exerciseEndDate;
        this.documentDate = documentDate;
    }

    public LocalDate getExerciseStartDate() {
        return exerciseStartDate;
    }

    public LocalDate getExerciseEndDate() {
        return exerciseEndDate;
    }

    public LocalDate getDocumentDate() {
        return documentDate;
    }
}
