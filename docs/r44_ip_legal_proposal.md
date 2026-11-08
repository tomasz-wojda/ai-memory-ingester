# R44 proposal: IP Legal confirmation of licences

Status: proposal, 2026-11-08. Nothing is sent. This document collects the evidence and a ready request. It is not a legal opinion. The owner decides the scope and sends the request. The licence policy is [security-privacy.md](semantic-retrieval/security-privacy.md) section 5.

## 1. Why and what it blocks

Policy 5.1 allows Apache-2.0, BSD and ISC only after IP Legal has confirmed them once, and it prefers MIT. R44 is that confirmation. Until it exists, no model change (R41 bake-off result, R42 cascade, R45, R50) goes into daily use. The current default model (mmarco) is already used for benchmarks and, if the owner starts the service, for queries.

## 2. Proposed scope

| Group | Items | Proposal |
|---|---|---|
| A. Models for use | `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (default), `BAAI/bge-reranker-v2-m3` (opt-in) | Ask for confirmation |
| B. Models for benchmarks only | `cross-encoder/ms-marco-MiniLM-L-6-v2`, `cross-encoder/ms-marco-MiniLM-L-12-v2` | Put in the same request. If IP Legal declines, delete the weights and drop them from the benchmark |
| C. Excluded model | `sdadas/polish-reranker-roberta-v3` (Gemma terms) | Do not ask. Delete the weights (see section 5) unless the owner wants IP Legal to review the Gemma terms |
| D. Python packages | 41 packages pinned in `service/requirements/models.txt` (section 4) | Ask for confirmation of the licence classes. Two entries need a decision: `tqdm` (MPL-2.0 AND MIT) and `regex` (Apache-2.0 AND CNRI-Python) |
| E. Java libraries in the repository | 6 jars in `vendor/` (section 4) | Same request. They are committed to git (finding F4 of the production plan) |

## 3. Models: evidence

| Model | Commit | Licence in `models.manifest.json` | Size of weights | Evidence |
|---|---|---|---|---|
| mmarco-mMiniLMv2-L12-H384-v1 | 1427fd65 | Apache-2.0 | 470,592,698 bytes | read from the model card on 2026-11-03 (owner clone) |
| bge-reranker-v2-m3 | 953dc6f6 | Apache-2.0 | 2,271,071,852 bytes | read from the model card on 2026-11-03 |
| ms-marco-MiniLM-L-6-v2 | 233902d2 | Apache-2.0 | 90,870,598 bytes | card metadata from the official API |
| ms-marco-MiniLM-L-12-v2 | 7b023523 | Apache-2.0 | 133,469,020 bytes | read from the model card on 2026-11-03 |
| polish-reranker-roberta-v3 | 8943df3d | Gemma terms (custom) | 1,771,613,516 bytes | read from the model card |

Gaps I found on 2026-11-08:
- The model folders under `~/.local/share/ai-memory-service/models/` hold only weights and tokenizer files. They have no `LICENSE` file and no model card. Policy 5.2 item 4 says the licence text must be stored next to the weights. The licence evidence is therefore only the manifest and the notes in `security-privacy.md` 5.3.
- The host `huggingface.co` did not answer on 2026-10-31 (30 of 30 requests timed out). I have not tried to fetch the licence texts today.

Questions that IP Legal should answer, not me (from my general knowledge, not verified here):
- The `ms-marco-*` models and `mmarco` were trained on MS MARCO or its translation (mMARCO). I recall that the MS MARCO data has research-use terms. The model cards say Apache-2.0. Does the data licence limit internal business use of the weights?
- `bge-reranker-v2-m3` is an XLM-RoBERTa derivative. Do the base-model terms add duties?

## 4. Packages: evidence from the installed environment (2026-11-08)

All 41 pinned packages are installed with the pinned version. The licence is read from each package's own metadata (`License-Expression`, else the `License` field, else the classifier).

Summary: 36 of the 41 packages carry one simple licence name (MIT, BSD, ISC, PSF-2.0, Apache-2.0, or a choice of two). Five need a look (`tqdm`, `regex`, `torch`, `numpy`, `transformers`). Notes on them and on entries without an SPDX expression:
- `tqdm` 4.70.1: MPL-2.0 AND MIT. File-level copyleft. We use it unmodified, as a dependency of `huggingface-hub` and `transformers`. We do not copy or change its files.
- `regex` 2026.9.29: Apache-2.0 AND CNRI-Python.
- `torch` 2.15.0 and `numpy` 2.5.4: composite expressions (for `torch`: Apache-2.0, Apache-2.0 WITH LLVM-exception, BSD-2-Clause, BSD-3-Clause, BSL-1.0, ISC, MIT, Zlib; for `numpy`: BSD-3-Clause, 0BSD, MIT, Zlib, CC0-1.0).
- `packaging`: Apache-2.0 OR BSD-2-Clause (a choice).
- `transformers`: licence string "Apache 2.0 License" (not an SPDX name).
- `scipy`, `jinja2`, `markdown-it-py`, `mdurl`, `safetensors`, `tokenizers`: no SPDX expression; the licence comes from the classifier or the licence text.

| Package | Version | Licence | Source of the value | Note |
|---|---|---|---|---|
| annotated-doc | 0.0.5 | MIT | License-Expression |  |
| anyio | 4.16.0 | MIT | License-Expression |  |
| click | 8.5.0 | BSD-3-Clause | License-Expression |  |
| cloudpickle | 3.1.2 | BSD-3-Clause | License field |  |
| filelock | 4.2.7 | MIT | License-Expression |  |
| fsspec | 2026.9.0 | BSD-3-Clause | License-Expression |  |
| h11 | 0.16.0 | MIT | License field |  |
| hf-xet | 1.8.0 | Apache-2.0 | License-Expression |  |
| httpcore2 | 2.16.0 | BSD-3-Clause | License-Expression |  |
| httpx2 | 2.16.0 | BSD-3-Clause | License-Expression |  |
| huggingface-hub | 2.4.0 | Apache-2.0 | License field |  |
| idna | 3.20 | BSD-3-Clause | License-Expression |  |
| jinja2 | 3.1.6 | BSD License | classifier only | no SPDX expression |
| joblib | 1.6.0 | BSD-3-Clause | License-Expression |  |
| markdown-it-py | 4.2.0 | MIT License | classifier only | no SPDX expression |
| markupsafe | 3.0.4 | BSD-3-Clause | License-Expression |  |
| mdurl | 0.1.2 | MIT License | classifier only | no SPDX expression |
| mpmath | 1.3.0 | BSD | License field |  |
| narwhals | 2.28.0 | MIT | License-Expression |  |
| networkx | 3.7 | BSD-3-Clause | License-Expression |  |
| numpy | 2.5.4 | BSD-3-Clause AND 0BSD AND MIT AND Zlib AND CC0-1.0 | License-Expression | composite expression |
| packaging | 26.3 | Apache-2.0 OR BSD-2-Clause | License-Expression | choice of two |
| pygments | 2.21.0 | BSD-2-Clause | License-Expression |  |
| pyyaml | 6.0.3 | MIT | License field |  |
| regex | 2026.9.29 | Apache-2.0 AND CNRI-Python | License-Expression | CNRI-Python part |
| rich | 15.0.0 | MIT | License field |  |
| safetensors | 0.8.0 | Apache Software License | classifier only | no SPDX expression |
| scikit-learn | 1.9.1 | BSD-3-Clause | License-Expression |  |
| scipy | 1.18.1 | BSD License | classifier only | no SPDX expression |
| sentence-transformers | 6.2.0 | Apache-2.0 | License-Expression |  |
| setuptools | 84.2.1 | MIT | License-Expression |  |
| shellingham | 1.5.4 | ISC License | License field |  |
| sympy | 1.14.0 | BSD | License field |  |
| threadpoolctl | 3.7.0 | BSD-3-Clause | License-Expression |  |
| tokenizers | 0.23.3 | Apache Software License | classifier only | no SPDX expression |
| torch | 2.15.0 | Apache-2.0 AND Apache-2.0 WITH LLVM-exception AND BSD-2-Clause AND BSD-3-Clause AND BSL-1.0 AND ISC AND MIT AND Zlib | License-Expression | composite expression |
| tqdm | 4.70.1 | MPL-2.0 AND MIT | License field | file-level copyleft; used unmodified |
| transformers | 5.22.1 | Apache 2.0 License | License field | non-SPDX string |
| truststore | 0.10.5 | MIT | License-Expression |  |
| typer | 0.27.3 | MIT | License-Expression |  |
| typing-extensions | 4.16.0 | PSF-2.0 | License-Expression |  |

Java libraries in `vendor/` (licence files read from inside each jar, 2026-11-08):

| Jar | Licence file in the jar |
|---|---|
| commons-logging-1.2 | Apache License 2.0 (`META-INF/LICENSE.txt`, `NOTICE.txt`) |
| fontbox-2.0.30 | Apache License 2.0 (`META-INF/LICENSE`, `NOTICE`) |
| pdfbox-2.0.30 | Apache License 2.0 (`META-INF/LICENSE`, `NOTICE`) |
| slf4j-api-2.0.12 | MIT text (`META-INF/LICENSE.txt`) |
| slf4j-nop-2.0.12 | MIT text (`META-INF/LICENSE.txt`) |
| sqlite-jdbc-3.45.1.0 | Apache-2.0 in the POM (`META-INF/maven/.../pom.xml`) and a BSD-style file `LICENSE.zentus` (copyright 2006, David Crawshaw) |

## 5. Findings for the owner (not for IP Legal)

1. The Gemma-licensed Polish model is on disk (`.../models/polish-reranker-roberta-v3`, 1.7 GB) and was loaded for a benchmark on 2026-11-03. Policy 5.1 excludes custom licences and says such a model is never loaded on the company laptop. I recommend: delete the folder and the manifest entry, or ask IP Legal. I did not delete anything.
2. Store the licence text and model card next to each model that stays (policy 5.2 item 4). I can do this if the host is reachable or if you give me the files.
3. No model or package was downloaded or changed for this proposal.

## 6. Draft request (ready to send; the owner edits and sends it)

Subject: Licence confirmation for internal retrieval tool (models and libraries)

Hello,

We use a local search tool for internal work notes. It runs on company laptops and processes internal business information. It uses third-party software. Please confirm that we may use the items below for internal use under the stated licences. We do not sell or give them to third parties.

1. Two AI models (weights stay on the laptop, used for ranking search results): `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` and `BAAI/bge-reranker-v2-m3`. Licence on the model card: Apache-2.0. Two English benchmark-only models, `cross-encoder/ms-marco-MiniLM-L-6-v2` and `cross-encoder/ms-marco-MiniLM-L-12-v2` (Apache-2.0), are included for the same question.
2. 41 Python packages (list and licences in the attached table). Please look at `tqdm` (MPL-2.0 AND MIT, used unmodified) and `regex` (Apache-2.0 AND CNRI-Python).
3. Six Java libraries stored in our repository (commons-logging, fontbox, pdfbox, slf4j-api, slf4j-nop, sqlite-jdbc; Apache-2.0, MIT and BSD-style).

Questions: (a) Is Apache-2.0 acceptable for these items, with the licence text and notices kept next to them? (b) Is the MPL-2.0 part of `tqdm` acceptable for unmodified use? (c) The models were trained on MS MARCO-type data. Does the data licence limit internal business use of the weights? (d) Do the base-model terms of `bge-reranker-v2-m3` add duties?

Attached: the licence table (section 4 of this document) and the model manifest.

Thank you.

## 7. Next steps

1. Owner: choose the scope (section 2) and send the request (section 6). I cannot send it.
2. Owner: decide about the Gemma model (finding 1).
3. After the answer: record it in `decision-log.md`, update the statuses in `security-privacy.md` 5.3, and tick R44. R50 and any model change are then free to proceed.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. The content is not legal advice. It lists third-party licence facts for IP Legal to review.
