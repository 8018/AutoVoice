# Navigation verification requirement

The user requires a repeated fix → benchmark → next feature cycle. For every navigation-related change (including model prompts, tool configuration, protocol, Android integration and telemetry), run relevant offline tests and the full dev cloud text benchmark before declaring that batch complete or moving on to the next feature batch.

- Run `node --test tools/qa/tests/navigation-benchmark.test.mjs` for the judge contract.
- After deploying the batch to dev, run `node tools/qa/navigation-cloud-text.mjs --release=<actual-deployed-revision>`. The command exits nonzero on semantic failure, missing replies, timeout or latency budget failure. A focused subset is diagnostic only and does not satisfy the full gate.
- Keep `docs/qa/navigation-cloud-text-results.json` and `navigation-cloud-text-recheck.json` as the immutable initial baseline. New results are timestamped in `docs/qa/navigation-benchmark-runs/` with source and deployment versions.
- Do not relax expected behavior, remove failing cases, or count safe capability refusal as implemented navigation. Review structured actions as well as text; cloud text success is not device/voice/driving acceptance.
- Fix regressions and rerun before starting the next batch. The user explicitly requires growing the benchmark with business scope: for every new capability, add normal, failure, boundary and multi-turn cases as applicable; map cases to product IDs and version the suite. Preserve all old cases, report coverage and per-version/per-feature quality and latency so expanded totals cannot hide regressions. If credentials, deployment, external data or physical equipment are unavailable, report the exact remaining block; do not mark an unrun gate passed.
- Credentials stay in ignored local config or private temporary files. Never commit or print tokens, credential-bearing URLs, raw private locations or user speech. Use public places for benchmarks.

The dev deployment workflow runs the full benchmark through SSH and preserves results. CI also runs the offline judge tests without secrets. Production deployment is outside this task's authorization.
