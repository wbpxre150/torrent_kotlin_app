/*
 * ftc: a tiny C ABI over the fastText inference path (Stage 4).
 *
 * Built from the fastText inference sources only (args, dictionary, matrix,
 * quantmatrix, model, loss, fasttext, productquantizer, vector, densematrix,
 * utils) plus meter when the linker asks for it; see native/ftc/CMakeLists.txt.
 * The Android crawler links this library through the NDK build of the same
 * CMakeLists, so the header stays C11 and free of C++ types.
 *
 * Thread safety: one loaded ftc_model_t may be shared by many threads calling
 * ftc_predict concurrently. The wrapper keeps no shared mutable state on the
 * predict path; the upstream predict path is const and allocates its own
 * Model::State per call.
 */
#ifndef FTC_FTC_H_
#define FTC_FTC_H_

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ftc_model ftc_model_t;

/*
 * Load a quantised supervised model (.ftz). Returns NULL when the path is NULL,
 * cannot be opened or is not a fastText model. The model is heap owned: release
 * it with ftc_free().
 */
ftc_model_t *ftc_load(const char *path_ftz);

/*
 * Classify one feature-text line (whitespace-separated features, i.e. one line
 * of a fastText input file; no trailing newline needed).
 *
 * Writes up to `k` predictions into `labels`/`probs`, best first: `labels[i]`
 * is fastText's label as an int: the suffix of `__label__<int>` for the numeric
 * label space (adult 0/1), or, for the name-labelled students, the taxonomy
 * index of `__label__<name>` from native/tparse/taxonomy.h (`__label__tv` ->
 * TP_CAT_TV, `__label__episode` -> TP_TV_EPISODE); -1 when the label is
 * neither, and `probs[i]` the label's probability as `predict-prob` prints it.
 *
 * Returns the number of predictions written, or 0 when: the model, feature_text
 * or one of the output arrays is NULL, k <= 0, the model is not supervised, or
 * the line yields no prediction at the threshold - the case `predict-prob`
 * renders as an empty line. An empty or whitespace-only text is not one of
 * those cases: the wrapper appends the line ending, exactly as reading a blank
 * line does, so the EOS token is scored like any other line end and a prediction
 * is normally returned. `labels`/`probs` must have room for `k` entries; they
 * are untouched when the result is 0.
 */
int ftc_predict(
    ftc_model_t *model,
    const char *feature_text,
    int k,
    int *labels,
    float *probs);

/* Release a model returned by ftc_load(). NULL is a no-op. */
void ftc_free(ftc_model_t *model);

#ifdef __cplusplus
}
#endif

#endif /* FTC_FTC_H_ */
