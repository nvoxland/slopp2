# Benchmark: conventional Go baselines (greenfield)

Fresh sub-agents (no context from the slopp session) build three small Go
apps with the conventional files + `go test` workflow. One run per model.

## Protocol
- Empty dir per agent; instrumentation is mechanical: `date +%s > .runs/t0`
  first / `.runs/t1` last; `git commit -qm wip` after EVERY file write;
  every `go test ./...` piped through `tee .runs/run-$(date +%s%N).txt`.
- Metrics: true tokens/duration/tool-calls from the harness usage report;
  payload metrics via benchmarks/measure_go_baseline.sh (tok-in = summed byte
  sizes of changed .go files per commit /4; tok-out = tee'd output bytes /4).
- Done = final `go test ./...` green (rename steps verified by grep).

## App specs (given verbatim)
1. calculator — tokenizer (decimals + `+ - * / ( )`), evaluator with
   precedence (* / before + -, left-assoc, no parens required), tests MUST
   include 2+3=5, 1+2*3=7, 10-3*2=4, tokenize("1.5*2-1"); main prints
   "<expr> = <result>" per arg.
2. inventory — store constructor; AddItem(sku, n) accumulating; total-units;
   tests: widget 3 + widget 2 + gadget 1 => {widget 5, gadget 1}, total 6;
   THEN rename AddItem -> RegisterItem everywhere and re-verify.
3. wordstats — Words (lowercase, split non-alphanumeric), WordFreqs,
   TopWords(n, desc); tests MUST include Words("The cat")=[the cat],
   WordFreqs("a b a")={a 2, b 1}, TopWords("a b a a b c",2)=[a b];
   THEN rename Words -> Tokenize and re-verify.
