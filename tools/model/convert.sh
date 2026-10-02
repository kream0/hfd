#!/usr/bin/env bash
# Converts Tarteel's Qur'an-tuned Whisper models (Hugging Face, Apache-2.0) to whisper.cpp's
# GGML format and quantizes them, for on-device recitation checking. Run by
# .github/workflows/model.yml (the dev sandbox can't reach Hugging Face).
# Output: out/ggml-<size>-ar-quran-<type>.bin
set -euo pipefail
WHISPER_CPP_TAG="${WHISPER_CPP_TAG:-v1.7.6}"
SIZES="${SIZES:-tiny base}"
TYPES="${TYPES:-q5_1 q8_0}"
mkdir -p work out
cd work

[ -d whisper.cpp ] || git clone -q --depth 1 --branch "$WHISPER_CPP_TAG" https://github.com/ggml-org/whisper.cpp
# convert-h5-to-ggml.py reads the mel filters from OpenAI's repository.
[ -d whisper ] || git clone -q --depth 1 https://github.com/openai/whisper
cmake -S whisper.cpp -B whisper.cpp/build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_TESTS=OFF >/dev/null
cmake --build whisper.cpp/build -j"$(nproc)" --config Release >/dev/null
ls whisper.cpp/build/bin
QUANTIZE=$(ls whisper.cpp/build/bin/*quantize* | head -1)

for size in $SIZES; do
  repo="tarteel-ai/whisper-$size-ar-quran"
  python3 - "$repo" "hf-$size" <<'PY'
import sys
from huggingface_hub import snapshot_download
snapshot_download(sys.argv[1], local_dir=sys.argv[2], allow_patterns=["*.json", "*.txt", "pytorch_model.bin"])
PY
  # convert-h5-to-ggml.py takes the decoder's context from max_length (1024 in Tarteel's base
  # config, a generation setting) while its weights hold max_target_positions (448): whisper.cpp
  # then refuses the file ("decoder.positional_embedding has wrong size").
  python3 - "hf-$size/config.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
c["max_length"] = c.get("max_target_positions", 448)
json.dump(c, open(sys.argv[1], "w"), indent=1)
PY
  mkdir -p "ggml-$size"
  python3 whisper.cpp/models/convert-h5-to-ggml.py "hf-$size" whisper "ggml-$size" >/dev/null
  f16="../out/ggml-$size-ar-quran-f16.bin"
  mv "ggml-$size/ggml-model.bin" "$f16"
  for t in $TYPES; do
    "$QUANTIZE" "$f16" "../out/ggml-$size-ar-quran-$t.bin" "$t" >/dev/null
  done
done
ls -l ../out
