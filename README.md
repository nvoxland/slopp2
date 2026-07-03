# slopp

An **agent-native codebase**: code lives in a store (SQLite-backed delta log),
not files; the top-level form is the unit of editing, storage, hot-reload,
verification, and provenance; a live JVM image runs the code continuously and
verifies every write the moment it lands. Agents address code as
`namespace/form` — no files, paths, or line numbers.

- Design + decisions: `.context/` (start with `architecture.md`, `decisions.md`)
- Agent workflow guide: `skills/slopp/SKILL.md`
- Benchmarks & baselines: `benchmarks/results.md`

## Requirements

Clojure CLI + Java 21+. (`SLOPP_CLOJURE` env var overrides the `clojure`
launcher path if it's somewhere unusual.)

## Connect from Claude Code

Project-scope config ships in this repo (`.mcp.json`); Claude Code picks it
up automatically and the tools appear as `mcp__slopp__*`. For another
project, register manually:

```bash
claude mcp add slopp -- clojure -M -m slopp.mcp /path/to/project-store
```

The optional last argument is the store directory (durable session at
`<dir>/.slopp/store.db`); omit for an ephemeral session.

## Connect from Codex

Add to `~/.codex/config.toml`:

```toml
[mcp_servers.slopp]
command = "clojure"
args = ["-M", "-m", "slopp.mcp", "/path/to/project-store"]
```

Repo-level agent instructions live in `AGENTS.md` (Codex reads it
automatically); point the agent at `skills/slopp/SKILL.md` before its first
tool call.

## CLI / scripting (HTTP transport)

The same tool dispatch over localhost HTTP — for shell scripts, debugging,
and harness evals:

```bash
clojure -M -m slopp.http 7357 /path/to/project-store &
curl -s -X POST localhost:7357/call \
  -d '{"name":"query_namespaces","arguments":{}}'
curl -s localhost:7357/metrics    # per-call payload sizes
```

## Development

```bash
clojure -M:test        # full suite (spawns real child JVM images; minutes)
clojure -M -m slopp.benchmark   # sample-app benchmark (see .context/dogfooding.md)
```
