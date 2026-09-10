export const meta = {
  name: 'deep-audit',
  description: 'Read-only range audit: many lenses per slice, two-ends tracing, adversarial verification',
  whenToUse: 'Run by the /deep-audit skill: first with mode "map", then with mode "audit" and the approved map',
  phases: [
    { title: 'Map', detail: 'slice the range, find write/read pairs, classify commits and surfaces' },
    { title: 'Find', detail: 'every lens over every slice, pair, commit batch and surface' },
    { title: 'Verify', detail: 'skeptics try to refute each deduplicated finding' },
    { title: 'Critic', detail: 'name uncovered ground and audit it once' },
    { title: 'Mutate', detail: 'optional: real mutation checks in a throwaway worktree' },
  ],
}

const A = args || {}
if (A.mode !== 'map' && A.mode !== 'audit') throw new Error('args.mode must be "map" or "audit"')
if (!A.base || !A.head) throw new Error('args.base and args.head are required')

const RANGE = `${A.base}...${A.head}`
const SCOPE = A.pathFilter ? `${RANGE}, limited to ${A.pathFilter}` : RANGE
const GROUND = A.ground || {}

const RULE_FILES = ['code-quality.md', 'content-layer.md', 'screen-conventions.md', 'architecture.md', 'testing.md', 'database.md']
const RULE_GROUP = 4
const SIBLING_BATCH = 10
const PARITY_BATCH = 10
const ALL_LENSES = ['correctness', 'security', 'performance', 'async', 'dead', 'rules', 'docs', 'tests', 'wiring', 'parity', 'sibling', 'upstream', 'twoends']

const PREAMBLE = `You are one agent in a read-only audit of Reikai, an Android manga and light-novel reader built on Mihon (Compose + Voyager, Metro DI, SQLDelight). The current directory is the repo root. Read-only reference clones sit beside it in ../refs/ (mihon, tsundoku, komikku, lnreader-main).

The range under audit is ${SCOPE}. Commits whose subject starts with "chore: sync Mihon" are upstream ports and out of scope, except for the // RK islands they touch.

Hard rules:
- Read-only. Never edit, create, stage or commit a file, and never run Gradle.
- Every finding cites a file:line you read in this run. A claim you cannot cite is not a finding.
- Label evidence honestly. "executed" means a command's output decides the claim (a whole-tree grep, git log or show, a small node or pwsh snippet reproducing pure logic, javap on built classes). Everything else is "traced". A traced call chain proves the code exists, not that it does what you claim: follow the data to where it is used, not to the first function that agrees with you.
- Not findings: items on the parked list below; anything a docs/dev/plans/ record defers, declines or rules on (read the record for the surface before reporting; a decline whose stated premise no longer holds IS a finding); ledger entries below whose refutation still holds; formatting that Spotless owns.
- The project law is CLAUDE.md and .claude/rules/*.md. The seam-depth table in content-layer.md says how deep each surface shares code between manga and novels; read it before judging duplication.
- Tool traps on this machine (full list: C:/Users/unseensnick/.claude/projects/E--Code-yokai-y2k-app/memory/reference_shell_tool_traps.md): a revspec like "git show REF:path" must run through PowerShell or with MSYS_NO_PATHCONV=1, because Bash turns it into an empty result; PowerShell -match, -replace and Select-String ignore case; "git grep PATTERN REV -- '*.kt'" silently matches nothing. A search that returns zero is suspect: re-run it another way before believing it.

Parked, not findings:
${GROUND.parked || '(none given)'}

Ledger of previously refuted findings:
${GROUND.ledger || '(empty)'}`

const LENS_BRIEFS = {
  correctness: { agentType: 'code-reviewer', brief: 'Find real correctness defects: wrong logic, null and empty handling, state bugs, coroutine and flow misuse, broken Compose and Voyager conventions, error handling that swallows failures. The changes have to work together, so follow each changed public function to its callers inside and outside the slice.' },
  security: { agentType: 'security-reviewer', brief: 'Find security defects per .claude/rules/security.md: untrusted source and extension input, WebView hardening and JavaScript bridges, secrets or personal data in logs, crash reports or backups, file paths built from input.' },
  performance: { agentType: 'performance-reviewer', brief: 'Find real bottlenecks: recomposition storms, main-thread I/O, per-entry work in library-sized loops, queries in loops, leaked scopes, listeners and WebViews.' },
  async: { brief: 'Hunt ordering and lifecycle bugs: work sent before the receiver is ready (a call into a WebView or view before load or attach), a dialog that clears state on dismiss before its confirm reads it, View.postDelayed on a detached view (it never fires), stateIn or WhileSubscribed windows that never close, flows collected without a lifecycle, a hot provider flow that outlives its Activity, an init block reading a property declared below it, cancellation dropping side effects that were batched to the end of a loop. Name the ordering that breaks and what the user sees.' },
  dead: { brief: 'Find dead and unused code the range added or left behind: unused functions, parameters, preferences, strings, resources and flags, branches that can no longer be reached, commented-out blocks, // RK islands whose purpose is gone. Before calling anything unused, search the WHOLE repository, including src/test, src/androidTest, src/debug, scripts/, .github/, build files, manifests and resources; reflection, @JavascriptInterface, XML references and Metro-generated code all count as uses. "Nothing calls X" is the claim class that fails most, so put exactly what you searched in evidenceDetail. If Spotless or the compiler would already flag it, it is not a finding.' },
  rules: { brief: 'Audit against one rule file. Read it first and turn it into a checklist, then walk the slices. Each finding quotes a few words of the exact rule it breaks. Rules a hook already enforces (comment length cap, em dashes, dates in comments, plan codenames) are findings only where the hook cannot see them.' },
  docs: { agentType: 'doc-reviewer', brief: 'Check every claim in the docs in this slice against current code: files, symbols, counts, behaviour. Re-derive any stated count rather than trusting it. Also check CLAUDE.md and .claude/rules claims about code the range changed.' },
  tests: { brief: 'Audit the tests in this slice: tests that cannot fail (assertTrue(true), asserting a mock was called without checking arguments, asserting the value just set), names that claim more than the body checks, twin tests that .claude/rules/testing.md says should be one conformance test, and a rule for both content types pinned on only one. For up to five new tests, do a reasoned mutation: name the production clause the test pins and say whether deleting it would turn the test red. If it would not, that is a finding, labelled traced.' },
  wiring: { brief: '' },
  parity: { brief: 'For each user-visible change below, check that manga and novels both got it in this range, per the write-once rule in .claude/rules/content-layer.md. A gap is a finding unless a named mechanism makes the type unable to support it and the surface plan doc records that, or ROADMAP.md labels the gap gated. Also report any "twin of" marker in the range that names no pin (a shared kernel, a typed capability or a conformance test).' },
  sibling: { brief: 'Each commit below fixed a defect. For each one, read its diff, state the defect as a pattern, then search the whole tree for other sites with the same pattern: the other content type\'s twin, the other rendering mode, the other viewer, the same call shape elsewhere. Report every site that still carries the defect. A site the commit message deliberately left, with a reason, is not a finding.' },
  upstream: { brief: 'This surface was taken over from Mihon. Walk the replaced Mihon code\'s behaviour end to end in ../refs/mihon (docs/dev/off-path-manifest.md lists the replaced files), starting from theirs rather than ours. Mark each behaviour present (cite ours), deliberately dropped (cite the plan doc) or missing. Report only the missing ones as findings.' },
  twoends: { brief: '' },
}

const WIRING_TASKS = [
  { target: 'build and platform wiring', brief: 'Audit build and platform wiring for the range: AndroidManifest entries, ProGuard keeps for surviving Injekt call sites (the keep list and its reasons are in .claude/rules/architecture.md), Gradle dependencies and version-catalog entries that are unused or missing, Metro DI scoping and registration (read scripts/di-interop-check.ps1 and apply its rules by reading, do not run it), build variants and applicationId suffixes.' },
  { target: 'data and settings wiring', brief: 'Audit data and settings wiring for the range: each preference migration gates on its own versionCode (CLAUDE.md states the rule), .sqm numbering against the schema, backup fields written versus restored, preferences with no reader, settings shown with no effect, effects with no setting, strings defined and unused or used and missing.' },
]

const SEV = { type: 'string', enum: ['high', 'medium', 'low'] }
const FINDINGS = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string', description: 'the defect as a one-line claim' },
          file: { type: 'string', description: 'repo-relative path' },
          line: { type: 'integer' },
          severity: SEV,
          claim: { type: 'string', description: 'the defect in two or three sentences' },
          failureScenario: { type: 'string', description: 'concrete input or state, and the wrong result a user sees' },
          evidence: { type: 'string', enum: ['executed', 'traced'] },
          evidenceDetail: { type: 'string', description: 'the command and its output, or the lines read' },
          surface: { type: 'string', description: 'surface or subsystem, e.g. "novel reader", "library", "backup"' },
        },
        required: ['title', 'file', 'line', 'severity', 'claim', 'failureScenario', 'evidence', 'evidenceDetail', 'surface'],
      },
    },
    searched: { type: 'string', description: 'what you covered, so an empty result is distinguishable from an unsearched one' },
  },
  required: ['findings', 'searched'],
}

const CONTRACT = {
  type: 'object',
  properties: {
    sites: { type: 'array', items: { type: 'string' }, description: 'file:line of every site on your side' },
    key: { type: 'string', description: 'exact name, key, column, field or message shape' },
    type: { type: 'string' },
    unitsAndScale: { type: 'string', description: 'e.g. fraction 0..1, percent, px, dp, ms, 0-based index' },
    defaultAndEmpty: { type: 'string', description: 'default value, and what null, empty or missing means' },
    timing: { type: 'string', description: 'when this side acts relative to lifecycle, load, attach, and the other side' },
    otherParties: { type: 'string', description: 'every other writer or reader you found' },
    notes: { type: 'string' },
  },
  required: ['sites', 'key', 'type', 'unitsAndScale', 'defaultAndEmpty', 'timing', 'otherParties', 'notes'],
}

const VERDICT = {
  type: 'object',
  properties: {
    refuted: { type: 'boolean' },
    reason: { type: 'string' },
    evidence: { type: 'string', enum: ['executed', 'traced'] },
    probe: { type: 'string', description: 'if not settled by execution, the one probe (test, log line, device step) that would settle it' },
    severity: SEV,
  },
  required: ['refuted', 'reason', 'evidence', 'probe', 'severity'],
}

const chunk = (xs, n) => {
  const out = []
  for (let i = 0; i < xs.length; i += n) out.push(xs.slice(i, i + n))
  return out
}
const sliceText = s => `Slice "${s.id}" (${s.title}; surface: ${s.surface}; content types: ${s.contentTypes}). Its changed files: run git diff --name-only ${RANGE} -- ${s.paths.join(' ')}`
const findingsPrompt = (brief, target) => `${PREAMBLE}\n\nYour lens: ${brief}\n\nYour target:\n${target}\n\nReturn every finding that survives your own re-read. An empty list is a valid answer when "searched" says what you covered.`

// One task per lens and target. Shared by map mode (for the estimate) and audit mode.
function planTasks(map, lenses) {
  const on = new Set(lenses && lenses.length ? lenses : ALL_LENSES)
  const code = map.slices.filter(s => s.kind === 'code')
  const tasks = []
  const add = (lens, target, prompt, agentType) => tasks.push({ lens, target, prompt, agentType })
  for (const lens of ['correctness', 'security', 'performance', 'async', 'dead']) {
    if (!on.has(lens)) continue
    for (const s of code) add(lens, s.id, findingsPrompt(LENS_BRIEFS[lens].brief, sliceText(s)), LENS_BRIEFS[lens].agentType)
  }
  if (on.has('rules')) {
    for (const rule of RULE_FILES) {
      for (const group of chunk(code, RULE_GROUP)) {
        add('rules', `${rule}: ${group.map(s => s.id).join(', ')}`,
          findingsPrompt(`${LENS_BRIEFS.rules.brief} The rule file is .claude/rules/${rule}.`, group.map(sliceText).join('\n')))
      }
    }
  }
  if (on.has('docs')) for (const s of map.slices.filter(x => x.kind === 'docs')) add('docs', s.id, findingsPrompt(LENS_BRIEFS.docs.brief, sliceText(s)), 'doc-reviewer')
  if (on.has('tests')) for (const s of map.slices.filter(x => x.kind === 'tests')) add('tests', s.id, findingsPrompt(LENS_BRIEFS.tests.brief, sliceText(s)))
  if (on.has('wiring')) for (const w of WIRING_TASKS) add('wiring', w.target, findingsPrompt(w.brief, `The whole range ${SCOPE}.`))
  if (on.has('parity')) {
    for (const batch of chunk(map.userVisibleChanges, PARITY_BATCH)) {
      add('parity', batch.map(c => c.summary).join(' | ').slice(0, 120),
        findingsPrompt(LENS_BRIEFS.parity.brief, batch.map(c => `- ${c.summary} (source: ${c.source}; content types: ${c.contentTypes})`).join('\n')))
    }
  }
  if (on.has('sibling')) {
    for (const batch of chunk(map.fixCommits, SIBLING_BATCH)) {
      add('sibling', batch.map(c => c.sha).join(','),
        findingsPrompt(LENS_BRIEFS.sibling.brief, batch.map(c => `- ${c.sha} ${c.subject}`).join('\n')))
    }
  }
  if (on.has('upstream')) {
    for (const s of map.surfaces) {
      add('upstream', s.surface, findingsPrompt(LENS_BRIEFS.upstream.brief,
        `Surface: ${s.surface}. Plan doc: ${s.planDoc}. Replaced Mihon files: ${s.replacedMihonFiles.join(', ')}`))
    }
  }
  if (on.has('twoends')) for (const p of map.pairs) tasks.push({ lens: 'twoends', target: p.id, pair: p })
  return tasks
}

function countAgents(tasks) {
  return tasks.reduce((n, t) => n + (t.lens === 'twoends' ? 3 : 1), 0)
}

// ---------------------------------------------------------------- map mode

if (A.mode === 'map') {
  phase('Map')
  const mapBase = `${PREAMBLE}\n\nYou are building the audit's map, not auditing. Be complete: whatever you leave out goes unaudited.`
  const [sl, pr, cm, su] = await parallel([
    () => agent(`${mapBase}\n\nSplit the files changed in ${SCOPE} (git diff --name-only ${RANGE}${A.pathFilter ? ' -- ' + A.pathFilter : ''}) into cohesive slices by subsystem, not by file. Kinds: "code" (Kotlin, SQL, JS, CSS and other sources), "docs" (markdown), "tests" (src/test, src/androidTest). Keep each code slice to roughly 60 changed files or 5000 changed lines, and each docs or tests slice to roughly 30 files. Give each slice repo-relative paths or directories that pathspec-match its files. List under "excluded", each with why: translations under i18n values-* directories, binary assets, generated files, files changed only by "chore: sync Mihon" commits, and build files (Gradle scripts, manifests, ProGuard, the version catalog) since a separate wiring lens covers those.`, {
      label: 'map:slices', phase: 'Map', effort: 'high',
      schema: {
        type: 'object',
        properties: {
          slices: { type: 'array', items: { type: 'object', properties: {
            id: { type: 'string' }, kind: { type: 'string', enum: ['code', 'docs', 'tests'] }, title: { type: 'string' },
            paths: { type: 'array', items: { type: 'string' } }, surface: { type: 'string' },
            contentTypes: { type: 'string', enum: ['manga', 'novel', 'both', 'neutral'] }, approxFiles: { type: 'integer' },
          }, required: ['id', 'kind', 'title', 'paths', 'surface', 'contentTypes', 'approxFiles'] } },
          excluded: { type: 'array', items: { type: 'object', properties: { what: { type: 'string' }, why: { type: 'string' } }, required: ['what', 'why'] } },
        },
        required: ['slices', 'excluded'],
      },
    }),
    () => agent(`${mapBase}\n\nFind the write/read pairs this range touches: places where one side produces a value and another consumes it, so two independent tracers can meet in the middle. Look especially at backup and restore, preference writes and reads, host calls into a WebView and the JavaScript that receives them, .sqm migrations and the queries over those tables, DI registration and resolution, reading-position save and restore, intent extras and Voyager screen arguments, and seams between subsystems the range changed together. Mark a value that goes out and comes back (serialise and deserialise, save and restore) as "round-trip". Rank by risk; return at most 15.`, {
      label: 'map:pairs', phase: 'Map', effort: 'high',
      schema: {
        type: 'object',
        properties: { pairs: { type: 'array', items: { type: 'object', properties: {
          id: { type: 'string' }, value: { type: 'string' }, writeSide: { type: 'string', description: 'file:symbol' },
          readSide: { type: 'string', description: 'file:symbol' }, kind: { type: 'string', enum: ['write-read', 'round-trip', 'host-guest'] }, why: { type: 'string' },
        }, required: ['id', 'value', 'writeSide', 'readSide', 'kind', 'why'] } } },
        required: ['pairs'],
      },
    }),
    () => agent(`${mapBase}\n\nClassify commits in ${SCOPE}. "fixCommits": every commit that fixed a defect (usually a "fix" subject; exclude "chore: sync Mihon"), with sha and subject. "userVisibleChanges": everything a user can observe that the range changed. When the range ends at the release branch, CHANGELOG.md's [Unreleased] section is the primary source (one entry per bullet); otherwise derive them from feat and fix commits. Say which content types each change claims to cover.`, {
      label: 'map:commits', phase: 'Map', effort: 'medium',
      schema: {
        type: 'object',
        properties: {
          fixCommits: { type: 'array', items: { type: 'object', properties: { sha: { type: 'string' }, subject: { type: 'string' } }, required: ['sha', 'subject'] } },
          userVisibleChanges: { type: 'array', items: { type: 'object', properties: {
            summary: { type: 'string' }, source: { type: 'string' }, contentTypes: { type: 'string', enum: ['manga', 'novel', 'both', 'neutral'] },
          }, required: ['summary', 'source', 'contentTypes'] } },
        },
        required: ['fixCommits', 'userVisibleChanges'],
      },
    }),
    () => agent(`${mapBase}\n\nList the surfaces this range touches that were taken over from Mihon (the seam-depth table in .claude/rules/content-layer.md names them; docs/dev/off-path-manifest.md lists the Mihon files each replaced). For each, give its plan doc and the replaced Mihon files.`, {
      label: 'map:surfaces', phase: 'Map', effort: 'medium',
      schema: {
        type: 'object',
        properties: { surfaces: { type: 'array', items: { type: 'object', properties: {
          surface: { type: 'string' }, planDoc: { type: 'string' }, replacedMihonFiles: { type: 'array', items: { type: 'string' } },
        }, required: ['surface', 'planDoc', 'replacedMihonFiles'] } } },
        required: ['surfaces'],
      },
    }),
  ])
  if (!sl || !pr || !cm || !su) throw new Error('a map agent failed; re-run map mode')
  const map = {
    slices: sl.slices, excluded: sl.excluded, pairs: pr.pairs,
    fixCommits: cm.fixCommits, userVisibleChanges: cm.userVisibleChanges, surfaces: su.surfaces,
  }
  const tasks = planTasks(map, A.lenses)
  const byLens = {}
  for (const t of tasks) byLens[t.lens] = (byLens[t.lens] || 0) + (t.lens === 'twoends' ? 3 : 1)
  return { map, estimate: { finderAgents: countAgents(tasks), byLens, note: 'verification adds about one agent per medium or low finding and three per high one, plus one critic' } }
}

// ---------------------------------------------------------------- audit mode

if (!A.map) throw new Error('audit mode needs args.map from an approved map run')

async function runTwoEnds(p) {
  const side = (which, start) => agent(`${PREAMBLE}\n\nTwo-ends tracing. You own ONE end of a value that crosses a boundary; another agent owns the other end and neither sees the other. Value: ${p.value}. Start at the ${which} side: ${start}. Trace where the value is ${which === 'write' ? 'produced and written' : 'read and consumed'} and document the contract as your side sees it. Do not read the other side's code beyond finding its name.`, {
    label: `twoends:${p.id}:${which}`, phase: 'Find', schema: CONTRACT,
  })
  const [w, r] = await parallel([() => side('write', p.writeSide), () => side('read', p.readSide)])
  if (!w || !r) return null
  return agent(`${PREAMBLE}\n\nReconcile two independent traces of one value (${p.value}, ${p.kind}). Writer's contract:\n${JSON.stringify(w, null, 2)}\n\nReader's contract:\n${JSON.stringify(r, null, 2)}\n\nEvery mismatch is a candidate: a key written but never read or read under another name, different defaults, different units or scale, empty meaning different things, a reader that can run before the writer, a second writer one side does not know about, a field that goes out and does not come back. Re-read the cited lines before reporting each one.`, {
    label: `twoends:${p.id}:reconcile`, phase: 'Find', schema: FINDINGS,
  })
}

async function runTask(t) {
  try {
    const out = t.lens === 'twoends'
      ? await runTwoEnds(t.pair)
      : await agent(t.prompt, { label: `${t.lens}:${t.target}`.slice(0, 80), phase: 'Find', schema: FINDINGS, agentType: t.agentType })
    return { t, out }
  } catch (e) {
    return { t, out: null }
  }
}

const RANK = { high: 3, medium: 2, low: 1 }
function dedupe(findings) {
  const out = []
  for (const f of findings) {
    const m = out.find(o => o.file === f.file && Math.abs(o.line - f.line) <= 3)
    if (!m) { out.push({ ...f, lenses: [f.lens], claims: [f.claim] }); continue }
    if (!m.lenses.includes(f.lens)) m.lenses.push(f.lens)
    m.claims.push(f.claim)
    if (RANK[f.severity] > RANK[m.severity]) m.severity = f.severity
    if (f.evidence === 'executed') m.evidence = 'executed'
  }
  return out
}

function collect(results, coverage) {
  const found = []
  for (const r of results) {
    if (!r) continue
    coverage.push({ lens: r.t.lens, target: r.t.target, status: r.out ? 'ok' : 'failed', findings: r.out ? r.out.findings.length : 0, searched: r.out ? r.out.searched : '' })
    if (r.out) for (const f of r.out.findings) found.push({ ...f, lens: r.t.lens })
  }
  return found
}

const VERIFY_LENSES = {
  code: 'Re-read the cited lines and enough surrounding code to decide whether the defect is real as stated. Check the callers and the data actually flowing in.',
  execute: 'Settle it by running something read-only: a whole-tree grep, git log or show, a node or pwsh snippet reproducing the logic. If only Gradle or a device could settle it, do not refute on that ground; set evidence to traced and name the probe.',
  ruled: 'Decide whether this is deferred, declined or ruled in a docs/dev/plans record or ROADMAP.md, on the parked list, in the ledger with a reason that still holds, intended per an owner ruling, or contradicted by a gate that passes (Spotless, the compiler, di-interop-check). Any of those refutes it.',
}
const ALL_VERIFY = Object.values(VERIFY_LENSES).join(' ')

async function verify(f) {
  const subject = `Finding (from lenses: ${f.lenses.join(', ')}; severity ${f.severity}):\n${f.title}\n${f.file}:${f.line}\nClaims:\n${f.claims.map(c => '- ' + c).join('\n')}\nFailure scenario: ${f.failureScenario}\nFinder's evidence (${f.evidence}): ${f.evidenceDetail}`
  const ask = lens => agent(`${PREAMBLE}\n\nYou are a skeptic. Try to REFUTE the finding below. Default to refuted=true when uncertain. A negative claim ("nothing calls X", "never read") must be re-searched across the whole repository, tests and scripts included, before you accept it.\n\nYour check: ${lens}\n\n${subject}`, {
    label: `verify:${f.file.split('/').pop()}:${f.line}`, phase: 'Verify', schema: VERDICT, effort: f.severity === 'high' ? 'high' : undefined,
  })
  const votes = (f.severity === 'high'
    ? await parallel(Object.values(VERIFY_LENSES).map(l => () => ask(l)))
    : [await ask(ALL_VERIFY)]).filter(Boolean)
  if (!votes.length) return { ...f, survives: false, verdicts: [], unverifiable: true }
  const holding = votes.filter(v => !v.refuted)
  const survives = f.severity === 'high' ? holding.length >= 2 : holding.length === 1
  const executed = holding.some(v => v.evidence === 'executed')
  const probe = (holding.find(v => v.probe) || {}).probe || ''
  const severity = holding.length ? holding.map(v => v.severity).sort((a, b) => RANK[b] - RANK[a])[0] : f.severity
  return { ...f, survives, evidence: executed || f.evidence === 'executed' ? 'executed' : 'traced', probe, severity, verdicts: votes }
}

phase('Find')
const tasks = planTasks(A.map, A.lenses)
log(`${tasks.length} finder tasks, about ${countAgents(tasks)} finder agents`)
const coverage = []
let found = collect(await parallel(tasks.map(t => () => runTask(t))), coverage)
const failed = coverage.filter(c => c.status === 'failed')
if (failed.length) log(`${failed.length} finder tasks failed and are reported as uncovered`)

// Barrier on purpose: lenses overlap (correctness and async flag the same line), so dedupe before paying for verification.
phase('Verify')
let deduped = dedupe(found)
log(`${found.length} raw findings, ${deduped.length} after dedupe`)
let verified = (await parallel(deduped.map(f => () => verify(f)))).filter(Boolean)

phase('Critic')
const gaps = await agent(`${PREAMBLE}\n\nYou are the completeness critic for this audit. Coverage so far (lens, target, status, findings, what was searched):\n${JSON.stringify(coverage, null, 1)}\n\nSlices in the map:\n${A.map.slices.map(s => `${s.id} (${s.kind}): ${s.paths.join(' ')}`).join('\n')}\n\nExcluded:\n${A.map.excluded.map(e => `${e.what}: ${e.why}`).join('\n')}\n\nConfirmed so far:\n${verified.filter(v => v.survives).map(v => `- ${v.title} (${v.file}:${v.line})`).join('\n') || '(none)'}\n\nName what is missing: a failed or thin task, a slice whose "searched" shows it was skimmed, an exclusion that hides real code, a write/read pair nobody traced, a subsystem the range changed that no slice owns. Return at most 10 gaps, most important first; an empty list is fine.`, {
  label: 'critic', phase: 'Critic', effort: 'high',
  schema: {
    type: 'object',
    properties: { gaps: { type: 'array', items: { type: 'object', properties: {
      lens: { type: 'string', enum: ALL_LENSES.filter(l => l !== 'twoends' && l !== 'wiring') }, target: { type: 'string', description: 'paths, commits or the behaviour to audit' }, why: { type: 'string' },
    }, required: ['lens', 'target', 'why'] } } },
    required: ['gaps'],
  },
})
const selected = A.lenses && A.lenses.length ? A.lenses : ALL_LENSES
const gapList = gaps ? gaps.gaps.filter(g => selected.includes(g.lens)) : []
if (gapList.length) {
  log(`critic named ${gapList.length} gaps; auditing them once (no further rounds)`)
  const gapTasks = gapList.map(g => ({
    lens: g.lens, target: `gap: ${g.target}`.slice(0, 120), agentType: LENS_BRIEFS[g.lens].agentType,
    prompt: findingsPrompt(g.lens === 'rules' ? `${LENS_BRIEFS.rules.brief} Pick the rule file the gap names.` : LENS_BRIEFS[g.lens].brief, `${g.target}\n(Why this was missed: ${g.why})`),
  }))
  const more = collect(await parallel(gapTasks.map(t => () => runTask(t))), coverage)
  found = found.concat(more)
  const fresh = dedupe(more).filter(f => !deduped.some(o => o.file === f.file && Math.abs(o.line - f.line) <= 3))
  verified = verified.concat((await parallel(fresh.map(f => () => verify(f)))).filter(Boolean))
}

let mutation = null
if (A.mutate) {
  phase('Mutate')
  const suspects = verified.filter(v => v.survives && v.lenses.includes('tests')).map(v => `${v.file}:${v.line} ${v.title}`)
  mutation = await agent(`You work in a throwaway git worktree of the Reikai repo, so edits here never reach the owner's tree. Pick up to five tests added in ${RANGE}, preferring these suspects:\n${suspects.join('\n') || '(none flagged; pick tests whose production clause is easy to isolate)'}\n\nFor each, one at a time: delete the production clause the test claims to pin, run only that test class through the PowerShell tool (never Bash, which cannot run Gradle here): $env:JAVA_HOME='C:\\Users\\unseensnick\\.jdks\\temurin-21.0.11'; .\\gradlew :<module>:testDebugUnitTest --tests <FQCN> (the domain module uses :domain:test). Never pipe gradlew into Select-String, which hides a failed build. Record red or green, restore the clause, move on. A test that stays green with its clause deleted is a confirmed finding with evidence "executed". If Gradle will not run, report that rather than guessing.`, {
    label: 'mutate', phase: 'Mutate', isolation: 'worktree', schema: FINDINGS,
  })
}

const confirmed = verified.filter(v => v.survives).sort((a, b) => RANK[b.severity] - RANK[a.severity])
return {
  confirmed,
  mutationFindings: mutation ? mutation.findings : [],
  refuted: verified.filter(v => !v.survives).map(v => ({ title: v.title, file: v.file, line: v.line, lenses: v.lenses, reasons: v.verdicts.filter(x => x.refuted).map(x => x.reason), unverifiable: !!v.unverifiable })),
  coverage,
  gaps: gapList,
  counts: { tasks: tasks.length, rawFindings: found.length, verified: verified.length, confirmed: confirmed.length },
}
