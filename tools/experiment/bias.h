// Steers whisper.cpp's greedy decoding toward the text the reciter is expected to say: at each
// token, if the model's best guess takes what was heard further from every expected continuation
// (letter skeletons, as app.hfd.core.recite.Arabic compares words) while a token that doesn't is
// nearly as likely (within [margin] nats), that one is taken. The model still decides: a word
// the audio doesn't support stays out of reach, and once what was heard has left the expected
// text, decoding goes on freely. With [hard], only the expected text may be written (the end of
// the text otherwise): what the model makes of the audio as that text, and how likely it finds it.
// Header-only, shared by the app's JNI and the bench's decoder.
#ifndef HFD_BIAS_H
#define HFD_BIAS_H

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define HFD_BIAS_TOP 20
#define HFD_BIAS_TOP_HARD 200
#define HFD_MAX_LETTERS 512

typedef struct {
    int n;               // expected continuations
    int *len;            // their skeleton lengths
    uint16_t **skel;     // their skeletons
    float margin;        // how much less likely (nats) a token on the expected text may be
    int hard;            // only the expected text
    int steered;         // tokens chosen by the bias
    double logprob;      // the model's own log-probabilities of the tokens taken: sum and count
    int taken;
    float own;           // the logit of the token taken, before steering
    // scratch
    int cap;             // longest skeleton
    int *row;            // one DP row per continuation, (cap + 1) each
    int *tmp;
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

// Expected continuations: UTF-8 texts separated by '\n'.
static hfd_bias *hfd_bias_new(const char *expected, float margin, int hard) {
    hfd_bias *b = calloc(1, sizeof(hfd_bias));
    if (!b) return NULL;
    b->margin = margin;
    b->hard = hard;
    int lines = 1;
    for (const char *p = expected; *p; p++) if (*p == '\n') lines++;
    b->len = calloc(lines, sizeof(int));
    b->skel = calloc(lines, sizeof(uint16_t *));
    const char *p = expected;
    while (1) {
        const char *e = strchr(p, '\n');
        size_t n = e ? (size_t) (e - p) : strlen(p);
        if (n > 0) {
            uint16_t *s = malloc(sizeof(uint16_t) * (n + 1));
            int k = hfd_skeleton(p, n, s, (int) n);
            if (k > 0) {
                b->skel[b->n] = s;
                b->len[b->n] = k;
                if (k > b->cap) b->cap = k;
                b->n++;
            } else free(s);
        }
        if (!e) break;
        p = e + 1;
    }
    size_t rows = (size_t) (b->n > 0 ? b->n : 1) * (b->cap + 1);
    b->row = malloc(sizeof(int) * rows);
    b->tmp = malloc(sizeof(int) * rows);
    return b;
}

static void hfd_bias_free(hfd_bias *b) {
    if (!b) return;
    for (int i = 0; i < b->n; i++) free(b->skel[i]);
    free(b->skel); free(b->len); free(b->row); free(b->tmp); free(b->buf);
    free(b);
}

// Rows extended by letters[0..k) (per continuation c, the edit distance of what was heard to
// c's first j letters); returns the distance to the closest beginning of a continuation.
static int hfd_extend(const hfd_bias *b, int *rows, const uint16_t *letters, int k) {
    int best = 1 << 28;
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
        for (int j = 0; j <= m; j++) if (r[j] < best) best = r[j];
    }
    return best;
}

// Letters heard may differ from the expected text's by about one in four (spellings: الصلاة / ٱلصَّلَوٰةَ).
static int hfd_tolerance(int letters) { return 1 + letters / 4; }

static void hfd_buf_fit(hfd_bias *b, size_t need) {
    if (need > b->buf_cap) { b->buf = realloc(b->buf, need + 256); b->buf_cap = need + 256; }
}

// Chooses the token (by raising its logit); returns it.
static int hfd_bias_steer(hfd_bias *b, struct whisper_context *ctx, const whisper_token_data *tokens, int n_tokens,
                          float *logits, int best) {
    const whisper_token eot = whisper_token_eot(ctx);
    if (b->n == 0 || best == eot) return best;
    // The text so far.
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
    // Tokens without a letter in a row (vowel signs, alifs): a fourth one would only wander.
    int bare = 0;
    for (int i = n_tokens - 1; i >= 0 && tokens[i].id < eot; i--) {
        const char *t = whisper_token_to_str(ctx, tokens[i].id);
        uint16_t one[64];
        if (hfd_skeleton(t, strlen(t), one, 64) > 0) break;
        bare++;
    }
    size_t width = (size_t) b->cap + 1;
    for (int c = 0; c < b->n; c++) for (int j = 0; j <= b->cap; j++) b->row[c * width + j] = j;
    int cost = hfd_extend(b, b->row, heard, h);
    if (!b->hard && cost > hfd_tolerance(h)) return best; // already off the expected text: decode freely
    // The likeliest text tokens.
    const int want = b->hard ? HFD_BIAS_TOP_HARD : HFD_BIAS_TOP;
    int top[HFD_BIAS_TOP_HARD];
    int k = 0;
    const int n_vocab = whisper_n_vocab(ctx);
    for (int id = 0; id < eot && id < n_vocab; id++) {
        float v = logits[id];
        if (!(v > -INFINITY)) continue;
        if (k < want) k++;
        else if (v <= logits[top[k - 1]]) continue;
        int at = k - 1;
        while (at > 0 && logits[top[at - 1]] < v) { top[at] = top[at - 1]; at--; }
        top[at] = id;
    }
    // The likeliest token that doesn't take what was heard further from the expected text.
    for (int i = 0; i < k; i++) {
        if (!b->hard && logits[best] - logits[top[i]] > b->margin) return best;
        const char *t = whisper_token_to_str(ctx, top[i]);
        size_t l = strlen(t);
        hfd_buf_fit(b, n + l + 1);
        memcpy(b->buf + n, t, l);
        uint16_t all[HFD_MAX_LETTERS];
        int hl = hfd_skeleton(b->buf, n + l, all, HFD_MAX_LETTERS);
        if (hl < h || (hl == h && bare >= 3)) continue;
        memcpy(b->tmp, b->row, sizeof(int) * width * b->n);
        if (hfd_extend(b, b->tmp, all + h, hl - h) > cost) continue;
        if (top[i] != best) {
            b->own = logits[top[i]];
            logits[top[i]] = logits[best] + 1.0f;
            b->steered++;
        }
        return top[i];
    }
    if (b->hard) {
        // Nothing likely goes on with the expected text: it ends here.
        b->own = logits[eot];
        logits[eot] = logits[best] + 1.0f;
        return eot;
    }
    return best;
}

static void hfd_bias_filter(struct whisper_context *ctx, struct whisper_state *state, const whisper_token_data *tokens,
                            int n_tokens, float *logits, void *user) {
    (void) state;
    hfd_bias *b = (hfd_bias *) user;
    if (!b) return;
    const int n_vocab = whisper_n_vocab(ctx);
    float mx = -INFINITY;
    int best = 0;
    for (int id = 0; id < n_vocab; id++) if (logits[id] > mx) { mx = logits[id]; best = id; }
    if (!(mx > -INFINITY)) return;
    double z = 0;
    for (int id = 0; id < n_vocab; id++) if (logits[id] > -INFINITY) z += exp(logits[id] - mx);
    const double lse = mx + log(z);
    b->own = logits[best];
    hfd_bias_steer(b, ctx, tokens, n_tokens, logits, best);
    // The model's own log-probability of the token taken (steering only raised its logit).
    b->logprob += (double) b->own - lse;
    b->taken++;
}

#endif
