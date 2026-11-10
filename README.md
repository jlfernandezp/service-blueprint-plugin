# Service Blueprint generation from event logs: tool, dataset and evaluation

Anonymised artefact accompanying the paper. It contains three parts:

- `tool/`: the ProM plugin (source code, build file and instructions) that transforms XES event logs into Service Blueprints. See `tool/README.md`.
- `dataset/`: the synthetic event logs used in the evaluation.
- `evaluation/`: the results of the evaluation.

## Dataset and evaluation material

This repository also contains the material used in the evaluation of the accompanying paper.

- `dataset/synthetic_logs_1000.zip`: 1,000 synthetic XES event logs (100 per domain, in one folder per domain: banking, ecommerce, hospital, hotel, insurance, logistics, municipality, restaurant, telecom, university). Each event carries an `expected:level` attribute with its ground-truth Service Blueprint layer.
- `evaluation/`: results of the evaluation on the 1,000 logs. `eval_synth1000_haiku.xlsx`, `eval_synth1000_sonnet.xlsx`, `eval_synth1000_opus.xlsx` and `eval_synth1000_keyword.xlsx` hold the per-log results of each classifier (Claude Haiku 4.5, Claude Sonnet 4.6, Claude Opus 4.8 and the keyword-based mode), and `resultados_logs_sinteticos_1000.xlsx` consolidates them (summary, per-layer metrics, confusion matrices, frequent errors, and the prediction of each classifier for each of the 21,282 activity classifications in the `Actividades` sheet). The spreadsheets are written in Spanish.

The prompt used for classification is in `tool/src/main/java/org/processmining/serviceblueprint/classifier/LLMClassifier.java`.

## License

MIT (see `LICENSE`).
