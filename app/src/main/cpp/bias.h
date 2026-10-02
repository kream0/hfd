// Steers whisper.cpp's decoding toward the text the reciter is expected to say ("contextual
// biasing"): among the model's likeliest next tokens, those that keep what was heard on one of the
// expected continuations (letter skeletons, as app.hfd.core.recite.Arabic compares words) get a
// bonus of [bonus] nats, less that continuation's cost (a skipped āya is less likely than going
// on). With greedy decoding that takes a token on the text when it is nearly as likely as the
// model's own guess; with beam search, whole readings compete and the one the audio and the text
// both support best wins. The model still decides: a word the audio doesn't support stays out of
// reach, and once what was heard has left every continuation, decoding goes on freely.
// Header-only, shared by the app's JNI (whisper_jni.c) and the bench's decoder
// (tools/recite/decoder.c), so the bench measures what the phone runs.
#ifndef HFD_BIAS_H
#define HFD_BIAS_H

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

/** The app's bonus (nats): measured on the owner's sessions and the Recite bench (2 Oct). */
#define HFD_BIAS_BONUS 4.0f
#define HFD_BIAS_TOP 20
#define HFD_MAX_LETTERS 512
/** Rows kept for this many readings so far (beam search: one per beam, and their parents). */
#define HFD_CACHE 12

typedef struct {
    uint16_t heard[HFD_MAX_LETTERS];
    int len;        // letters of the reading so far
    int *rows;      // per continuation c and prefix length j: edit distance of the reading to c's first j letters
    int *mins;      // per continuation: the closest beginning of it
    unsigned stamp; // last used
    int valid;
} hfd_rows;

typedef struct {
    int n;               // expected continuations
    int *len;            // their skeleton lengths
    uint16_t **skel;     // their skeletons
    int *pen;            // their cost (less likely continuations cost more): bonus − cost
    float bonus;
    int steered;         // greedy: tokens taken because of the bonus
    // scratch
    int cap;             // longest skeleton
    hfd_rows cache[HFD_CACHE];
    unsigned clock;
    int *tmp;
    int *tmp_mins;
    char *buf;
    size_t buf_cap;
} hfd_bias;

// The next UTF-8 code point at s[*i] (n bytes); -1 if its last bytes are missing, 0xFFFD if invalid.
static int hfd_utf8(const unsigned char *s, size_t n, size_t *i) {
    unsigned c = s[*i];
    int extra = c < 0x80 ? 0 : (c & 0xE0) == 0xC0 ? 1 : (c & 0xF0) == 0xE0 ? 2 : (c & 0xF8) == 0xF0 ? 3 : -1;
    if (extra < 0) { (*i)++; return 0xFFFD; }
    if (*i + extra >= n) return -1;
    unsigned cp = extra == 0 ? c : c & (0x3F >> extra);
    for (int k = 1; k <= extra; k++) cp = (cp << 6) | (s[*i + k] & 0x3F);
    *i += extra + 1;
    return (int) cp;
}

// Arabic.skeleton for one character: the letter kept (0 for none).
static uint16_t hfd_skel_char(int c) {
    if ((c >= 0x064B && c <= 0x065F) || c == 0x0670 || (c >= 0x06D6 && c <= 0x06ED) || c == 0x0640) return 0;
    switch (c) {
        case 0x0627: case 0x0671: case 0x0623: case 0x0625: case 0x0622: case 0x0621: return 0; // alif, hamza
        case 0x0624: return 0x0648;                                                         // ؤ → و
        case 0x0626: case 0x064A: case 0x0649: case 0x06CC: return 0x0649;                   // ئ ي ى ی → ى
        case 0x0629: return 0x0647;                                                         // ة → ه
        case 0x06A9: return 0x0643;                                                         // ک → ك
    }
    if ((c >= 0x0621 && c <= 0x064A) || (c >= 0x0671 && c <= 0x06D3)) return (uint16_t) c;
    return 0;
}

// The skeleton of UTF-8 text s (n bytes) into out (at most cap letters); an unfinished last character is left out.
static int hfd_skeleton(const char *s, size_t n, uint16_t *out, int cap) {
    int k = 0;
    size_t i = 0;
    while (i < n && k < cap) {
        int c = hfd_utf8((const unsigned char *) s, n, &i);
        if (c < 0) break;
        uint16_t l = hfd_skel_char(c);
        if (l) out[k++] = l;
    }
    return k;
}

// Expected continuations: UTF-8 texts separated by '\n', each possibly after "<cost>\t" (0 by default).
static hfd_bias *hfd_bias_new(const char *expected, float bonus) {
    hfd_bias *b = calloc(1, sizeof(hfd_bias));
    if (!b) return NULL;
    b->bonus = bonus;
    int lines = 1;
    for (const char *p = expected; *p; p++) if (*p == '\n') lines++;
    b->len = calloc(lines, sizeof(int));
    b->skel = calloc(lines, sizeof(uint16_t *));
    b->pen = calloc(lines, sizeof(int));
    const char *p = expected;
    while (1) {
        const char *e = strchr(p, '\n');
        size_t n = e ? (size_t) (e - p) : strlen(p);
        int pen = 0;
        if (n > 1 && p[0] >= '0' && p[0] <= '9' && p[1] == '\t') {
            pen = p[0] - '0';
            p += 2;
            n -= 2;
        }
        if (n > 0) {
            uint16_t *s = malloc(sizeof(uint16_t) * (n + 1));
            int k = hfd_skeleton(p, n, s, (int) n);
            if (k > 0) {
                b->skel[b->n] = s;
                b->len[b->n] = k;
                b->pen[b->n] = pen;
                if (k > b->cap) b->cap = k;
                b->n++;
            } else free(s);
        }
        if (!e) break;
        p = e + 1;
    }
    size_t rows = (size_t) (b->n > 0 ? b->n : 1) * (b->cap + 1);
    for (int i = 0; i < HFD_CACHE; i++) {
        b->cache[i].rows = malloc(sizeof(int) * rows);
        b->cache[i].mins = malloc(sizeof(int) * (b->n > 0 ? b->n : 1));
    }
    b->tmp = malloc(sizeof(int) * rows);
    b->tmp_mins = malloc(sizeof(int) * (b->n > 0 ? b->n : 1));
    return b;
}

static void hfd_bias_free(hfd_bias *b) {
    if (!b) return;
    for (int i = 0; i < b->n; i++) free(b->skel[i]);
    for (int i = 0; i < HFD_CACHE; i++) { free(b->cache[i].rows); free(b->cache[i].mins); }
    free(b->skel); free(b->len); free(b->pen); free(b->tmp); free(b->tmp_mins); free(b->buf);
    free(b);
}

// Rows extended by letters[0..k); mins gets, per continuation, the distance to its closest beginning.
static void hfd_extend(const hfd_bias *b, int *rows, int *mins, const uint16_t *letters, int k) {
    for (int c = 0; c < b->n; c++) {
        int *r = rows + (size_t) c * (b->cap + 1);
        const uint16_t *e = b->skel[c];
        int m = b->len[c];
        for (int t = 0; t < k; t++) {
            int diag = r[0];
            r[0] = r[0] + 1;
            for (int j = 1; j <= m; j++) {
                int up = r[j];
                int v = up + 1;
                if (r[j - 1] + 1 < v) v = r[j - 1] + 1;
                int d = diag + (e[j - 1] != letters[t]);
                if (d < v) v = d;
                diag = up;
                r[j] = v;
            }
        }
        int best = 1 << 28;
        for (int j = 0; j <= m; j++) if (r[j] < best) best = r[j];
        mins[c] = best;
    }
}

// Letters heard may differ from the expected text's by about one in four (spellings: الصلاة / ٱلصَّلَوٰةَ).
static int hfd_tolerance(int letters) { return 1 + letters / 4; }

static void hfd_buf_fit(hfd_bias *b, size_t need) {
    if (need > b->buf_cap) { b->buf = realloc(b->buf, need + 256); b->buf_cap = need + 256; }
}

// The rows for reading heard[0..h): from the cache, extended from the longest reading it holds
// that this one goes on from (each beam extends its own by a token or so).
static hfd_rows *hfd_rows_for(hfd_bias *b, const uint16_t *heard, int h) {
    size_t width = (size_t) b->cap + 1;
    hfd_rows *from = NULL;
    for (int i = 0; i < HFD_CACHE; i++) {
        hfd_rows *e = &b->cache[i];
        if (e->valid && e->len <= h && memcmp(e->heard, heard, sizeof(uint16_t) * e->len) == 0 && (!from || e->len > from->len)) from = e;
    }
    b->clock++;
    if (from && from->len == h) { from->stamp = b->clock; return from; }
    // A free slot, or the least recently used one (not the one extended from).
    hfd_rows *to = NULL;
    for (int i = 0; i < HFD_CACHE; i++) {
        hfd_rows *e = &b->cache[i];
        if (e == from) continue;
        if (!e->valid) { to = e; break; }
        if (!to || e->stamp < to->stamp) to = e;
    }
    if (from) {
        memcpy(to->rows, from->rows, sizeof(int) * width * b->n);
        memcpy(to->mins, from->mins, sizeof(int) * b->n);
        hfd_extend(b, to->rows, to->mins, heard + from->len, h - from->len);
    } else {
        for (int c = 0; c < b->n; c++) for (size_t j = 0; j < width; j++) to->rows[c * width + j] = (int) j;
        hfd_extend(b, to->rows, to->mins, heard, h);
    }
    memcpy(to->heard, heard, sizeof(uint16_t) * h);
    to->len = h;
    to->valid = 1;
    to->stamp = b->clock;
    return to;
}

static void hfd_bias_filter(struct whisper_context *ctx, struct whisper_state *state, const whisper_token_data *tokens,
                            int n_tokens, float *logits, void *user) {
    (void) state;
    hfd_bias *b = (hfd_bias *) user;
    if (!b || b->n == 0) return;
    const whisper_token eot = whisper_token_eot(ctx);
    const int n_vocab = whisper_n_vocab(ctx);
    // The model's own guess: if it is the end, so be it.
    int best = 0;
    for (int id = 1; id < n_vocab; id++) if (logits[id] > logits[best]) best = id;
    if (best == eot || !(logits[best] > -INFINITY)) return;
    // The reading so far.
    size_t n = 0;
    for (int i = 0; i < n_tokens; i++) if (tokens[i].id < eot) n += strlen(whisper_token_to_str(ctx, tokens[i].id));
    hfd_buf_fit(b, n + 64);
    n = 0;
    for (int i = 0; i < n_tokens; i++) {
        if (tokens[i].id >= eot) continue;
        const char *t = whisper_token_to_str(ctx, tokens[i].id);
        size_t l = strlen(t);
        memcpy(b->buf + n, t, l);
        n += l;
    }
    uint16_t heard[HFD_MAX_LETTERS];
    int h = hfd_skeleton(b->buf, n, heard, HFD_MAX_LETTERS);
    hfd_rows *r = hfd_rows_for(b, heard, h);
    // Continuations still possible.
    int any = 0;
    for (int c = 0; c < b->n; c++) if (r->mins[c] <= hfd_tolerance(h)) any = 1;
    if (!any) return; // off the expected text: decode freely
    // Tokens without a letter in a row (vowel signs, alifs): a fourth one would only wander.
    int bare = 0;
    for (int i = n_tokens - 1; i >= 0 && tokens[i].id < eot; i--) {
        const char *t = whisper_token_to_str(ctx, tokens[i].id);
        uint16_t one[64];
        if (hfd_skeleton(t, strlen(t), one, 64) > 0) break;
        bare++;
    }
    // The likeliest text tokens.
    int top[HFD_BIAS_TOP];
    int k = 0;
    for (int id = 0; id < eot && id < n_vocab; id++) {
        float v = logits[id];
        if (!(v > -INFINITY)) continue;
        if (k < HFD_BIAS_TOP) k++;
        else if (v <= logits[top[k - 1]]) continue;
        int at = k - 1;
        while (at > 0 && logits[top[at - 1]] < v) { top[at] = top[at - 1]; at--; }
        top[at] = id;
    }
    // Each gets the bonus of the likeliest continuation it keeps to (no further from it than before).
    size_t width = (size_t) b->cap + 1;
    float add[HFD_BIAS_TOP];
    for (int i = 0; i < k; i++) {
        add[i] = 0;
        const char *t = whisper_token_to_str(ctx, top[i]);
        size_t l = strlen(t);
        hfd_buf_fit(b, n + l + 1);
        memcpy(b->buf + n, t, l);
        uint16_t all[HFD_MAX_LETTERS];
        int hl = hfd_skeleton(b->buf, n + l, all, HFD_MAX_LETTERS);
        if (hl < h || (hl == h && bare >= 3)) continue;
        memcpy(b->tmp, r->rows, sizeof(int) * width * b->n);
        memcpy(b->tmp_mins, r->mins, sizeof(int) * b->n);
        hfd_extend(b, b->tmp, b->tmp_mins, all + h, hl - h);
        for (int c = 0; c < b->n; c++) {
            if (r->mins[c] > hfd_tolerance(h) || b->tmp_mins[c] > r->mins[c]) continue;
            float bonus = b->bonus - (float) b->pen[c];
            if (bonus > add[i]) add[i] = bonus;
        }
    }
    for (int i = 0; i < k; i++) logits[top[i]] += add[i];
    int after = best;
    for (int i = 0; i < k; i++) if (logits[top[i]] > logits[after]) after = top[i];
    if (after != best) b->steered++;
}

#endif
