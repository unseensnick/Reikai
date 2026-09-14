export const meta = {
  name: 'deep-audit',
  description: 'Read-only range audit: tagged lenses per slice, two-ends tracing, severity-tiered verification; full or quick',
  whenToUse: 'Run by the /deep-audit skill: first with mode "map", then with mode "audit" and the approved map',
  phases: [
    { title: 'Map', detail: 'slice and tag the range, find write/read pairs, classify commits and surfaces' },
    { title: 'Find', detail: 'lenses over the slices their tags call for, pairs, commit batches and surfaces' },
    { title: 'Verify', detail: 'skeptics try to refute high and medium findings (quick: high only)' },
    { title: 'Critic', detail: 'full mode only: name up to five high-risk gaps and audit them once' },
    { title: 'Mutate', detail: 'optional: real mutation checks in a throwaway worktree' },
  ],
}

const A = args || {}
if (A.mode !== 'map' && A.mode !== 'audit') throw new Error('args.mode must be "map" or "audit"')
if (!A.base || !A.head) throw new Error('args.base and args.head are required')

const QUICK = !!A.quick
const RANGE = `${A.base}...${A.head}`
const SCOPE = A.pathFilter ? `${RANGE}, limited to ${A.pathFilter}` : RANGE
const GROUND = A.ground || {}

// Every agent inherits the session model (Opus); no smaller model, since it misses what an audit is for.
const FINDER = { effort: 'medium' }
const MAX_PAIRS = QUICK ? 5 : 6
const MAX_GAPS = 5
const MEDIUM_BATCH = 5
const SIBLING_BATCH = 10
const PARITY_BATCH = 10
const ALWAYS_RULES = ['code-quality.md', 'content-layer.md', 'architecture.md']
const TAGGED_RULES = { 'screen-conventions.md': 'screens', 'testing.md': 'tests', 'database.md': 'sql' }
const TAGS = ['untrusted', 'hotpath', 'screens', 'tests', 'sql', 'prefs']
const ALL_LENSES = ['correctness', 'security', 'performance', 'async', 'dead', 'rules', 'docs', 'tests', 'wiring', 'parity', 'sibling', 'upstream', 'twoends']

// Finders get the parked list but not the ledger: only a verifier needs a past refutation, and the
// ledger is long enough that carrying it in every finder prompt was a large share of a run's cost.
const PREAMBLE = `You are one agent in a read-only audit of Reikai, an Android manga and light-novel reader built on Mihon (Compose + Voyager, Metro DI, SQLDelight). The current directory is the repo root. Read-only reference clones sit beside it in ../refs/ (mihon, tsundoku, komikku, lnreader-main).

The range under audit is ${SCOPE}. Commits whose subject starts with "chore: sync Mihon" are upstream ports and out of scope, except for the // RK islands they touch. Translation files outside i18n/src/commonMain/moko-resources/base/ are out of scope.

Hard rules:
- Read-only. Never edit, create, stage or commit a file, and never run Gradle.
- Every finding cites a file:line you read in this run. A claim you cannot cite is not a finding.
- Label evidence honestly. "executed" means a command's output decides the claim (a whole-tree grep, git log or show, a small node or pwsh snippet reproducing pure logic, javap on built classes). Everything else is "traced". A traced call chain proves the code exists, not that it does what you claim: follow the data to where it is used, not to the first function that agrees with you.
- Not findings: items on the parked list below; anything a docs/dev/plans/ record defers, declines or rules on (read the record for the surface before reporting; a decline whose stated premise no longer holds IS a finding); formatting that Spotless owns.
- The project law is CLAUDE.md and .claude/rules/*.md. The seam-depth table in content-layer.md says how deep each surface shares code between manga and novels; read it before judging duplication.
- Tool traps on this machine (full list: C:/Users/unseensnick/.claude/projects/E--Code-yokai-y2k-app/memory/reference_shell_tool_traps.md): a revspec like "git show REF:path" must run through PowerShell or with MSYS_NO_PATHCONV=1, because Bash turns it into an empty result; PowerShell -match, -replace and Select-String ignore case; "git grep PATTERN REV -- '*.kt'" silently matches nothing. A search that returns zero is suspect: re-run it another way before believing it.

Parked, not findings:
${GROUND.parked || '(none given)'}`

const REPORTING = QUICK
  ? 'Report high findings in full. Report at most eight medium findings, the most consequential first. Do not report low findings.'
  : 'Report high and medium findings, at most eight in all, the most consequential first, plus at most three low findings.'

const LENS_BRIEFS = {
  core: { agentType: 'code-reviewer', brief: 'Find real correctness and ordering defects. Correctness: wrong logic, null and empty handling, state bugs, coroutine and flow misuse, broken Compose and Voyager conventions, error handling that swallows failures; the changes have to work together, so follow each changed public function to its callers inside and outside the slice. Ordering and lifecycle: work sent before the receiver is ready (a call into a WebView or view before load or attach), a dialog that clears state on dismiss before its confirm reads it, View.postDelayed on a detached view (it never fires), stateIn or WhileSubscribed windows that never close, flows collected without a lifecycle, a hot provider flow that outlives its Activity, an init block reading a property declared below it, cancellation dropping side effects batched to the end of a loop. Name the ordering that breaks and what the user sees.' },
  security: { agentType: 'security-reviewer', brief: 'Find security defects per .claude/rules/security.md: untrusted source and extension input, WebView hardening and JavaScript bridges, secrets or personal data in logs, crash reports or backups, file paths built from input.' },
  performance: { agentType: 'performance-reviewer', brief: 'Find real bottlenecks: recomposition storms, main-thread I/O, per-entry work in library-sized loops, queries in loops, leaked scopes, listeners and WebViews.' },
  dead: { brief: 'Find dead and unused code the range added or left behind: unused functions, parameters, preferences, strings, resources and flags, branches that can no longer be reached, commented-out blocks, // RK islands whose purpose is gone. Before calling anything unused, search the WHOLE repository, including src/test, src/androidTest, src/debug, scripts/, .github/, build files, manifests and resources; reflection, @JavascriptInterface, XML references and Metro-generated code all count as uses. "Nothing calls X" is the claim class that fails most, so put exactly what you searched in evidenceDetail. If Spotless or the compiler would already flag it, it is not a finding.' },
  rules: { brief: 'Audit against the rule files named below. Read each one first and turn it into a checklist, then walk the code the range changed. Each finding quotes a few words of the exact rule it breaks. Rules a hook already enforces (comment length cap, em dashes, dates in comments, plan codenames) are findings only where the hook cannot see them.' },
  docs: { agentType: 'doc-reviewer', brief: 'Check every claim in the docs in this slice against current code: files, symbols, counts, behaviour. Re-derive any stated count rather than trusting it. Also check CLAUDE.md and .claude/rules claims about code the range changed.' },
  tests: { brief: 'Audit the tests: tests that cannot fail (assertTrue(true), asserting a mock was called without checking arguments, asserting the value just set), names that claim more than the body checks, twin tests that .claude/rules/testing.md says should be one conformance test, and a rule for both content types pinned on only one. For up to five new tests, do a reasoned mutation: name the production clause the test pins and say whether deleting it would turn the test red. If it would not, that is a finding, labelled traced.' },
  parity: { brief: 'For each user-visible change below, check that manga and novels both got it in this range, per the write-once rule in .claude/rules/content-layer.md. A gap is a finding unless a named mechanism makes the type unable to support it and the surface plan doc records that, or ROADMAP.md labels the gap gated. Also report any "twin of" marker in the range that names no pin (a shared kernel, a typed capability or a conformance test).' },
  sibling: { brief: 'Each commit below fixed a defect. For each one, read its diff, state the defect as a pattern, then search the whole tree for other sites with the same pattern: the other content type\'s twin, the other rendering mode, the other viewer, the same call shape elsewhere. Report every site that still carries the defect. A site the commit message deliberately left, with a reason, is not a finding.' },
  upstream: { brief: 'This surface was taken over, or code on it was deleted. Walk the replaced code\'s behaviour end to end, starting from theirs rather than ours: for a Mihon takeover read ../refs/mihon (docs/dev/off-path-manifest.md lists the replaced files); for Reikai code the range deleted read it with git show at the range base. Mark each behaviour present (cite ours), deliberately dropped (cite the plan doc) or missing. Report only the missing ones as findings.' },
}

const WIRING_TASKS = [
  { target: 'build and platform wiring', brief: 'Audit build and platform wiring for the range: AndroidManifest entries, ProGuard keeps for surviving Injekt call sites (the keep list and its reasons are in .claude/rules/architecture.md), Gradle dependencies and version-catalog entries that are unused or missing, Metro DI scoping and registration (read scripts/di-interop-check.ps1 and apply its rules by reading, do not run it), build variants and applicationId suffixes.' },
  { target: 'data and settings wiring', brief: 'Audit data and settings wiring for the range: each preference migration gates on its own versionCode (CLAUDE.md states the rule), .sqm numbering against the schema, backup fields written versus restored, preferences with no reader, settings shown with no effect, effects with no setting, strings defined and unused or used and missing.' },
]

// A per-slice sweep for quick mode: the code lenses and the slice's own wiring in one read.
const SWEEP_BRIEF = `${LENS_BRIEFS.core.brief}\n\nIn the same pass: ${LENS_BRIEFS.security.brief}\n\nAnd: ${LENS_BRIEFS.dead.brief}\n\nAlso check this slice's own wiring: DI registration and scoping of classes it adds or removes, preferences and strings it adds or drops, and migrations or backup fields it touches.`

const SEV = { type: 'string', enum: ['high', 'medium', 'low'] }
const FINDING = {
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
}
const FINDINGS = {
  type: 'object',
  properties: {
    findings: { type: 'array', items: FINDING },
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

const VERDICT_FIELDS = {
  refuted: { type: 'boolean' },
  reason: { type: 'string' },
  evidence: { type: 'string', enum: ['executed', 'traced'] },
  probe: { type: 'string', description: 'if not settled by execution, the one probe (test, log line, device step) that would settle it' },
  severity: SEV,
}
const VERDICT = { type: 'object', properties: VERDICT_FIELDS, required: Object.keys(VERDICT_FIELDS) }
const BATCH_VERDICT = {
  type: 'object',
  properties: {
    verdicts: {
      type: 'array',
      items: { type: 'object', properties: { index: { type: 'integer' }, ...VERDICT_FIELDS }, required: ['index', ...Object.keys(VERDICT_FIELDS)] },
    },
  },
  required: ['verdicts'],
}

const chunk = (xs, n) => {
  const out = []
  for (let i = 0; i < xs.length; i += n) out.push(xs.slice(i, i + n))
  return out
}
const sliceText = s => `Slice "${s.id}" (${s.title}; surface: ${s.surface}; content types: ${s.contentTypes}; tags: ${(s.tags || []).join(', ') || 'none'}). Its changed files: run git diff --name-only ${RANGE} -- ${s.paths.join(' ')}`
const findingsPrompt = (brief, target) => `${PREAMBLE}\n\nYour lens: ${brief}\n\nYour target:\n${target}\n\n${REPORTING} Return only findings that survive your own re-read. An empty list is a valid answer when "searched" says what you covered.`
const tagged = (slices, tag) => slices.filter(s => (s.tags || []).includes(tag))

// The ledger rows whose location names one of these files, each reason cut to its first sentence.
function ledgerFor(files) {
  const names = files.map(f => f.split('/').pop())
  const rows = (GROUND.ledger || '').split('\n').filter(l => l.startsWith('|') && names.some(n => l.includes(n)))
  const trimmed = rows.map(r => {
    const cells = r.split('|')
    const reason = (cells[4] || '').trim()
    const first = reason.split(/(?<=\.)\s/)[0].slice(0, 300)
    return `- ${(cells[2] || '').trim()}: ${(cells[3] || '').trim()}. Refuted: ${first}`
  })
  return trimmed.length ? trimmed.join('\n') : '(no ledger rows for these files)'
}

// One task per lens and target. Shared by map mode (for the estimate) and audit mode.
function planTasks(map, lenses) {
  const on = new Set(lenses && lenses.length ? lenses : ALL_LENSES)
  const code = map.slices.filter(s => s.kind === 'code')
  const tests = map.slices.filter(s => s.kind === 'tests')
  const tasks = []
  const add = (lens, target, prompt, agentType) => tasks.push({ lens, target, prompt, agentType })
  const whole = `The code the range changed: ${code.map(s => s.id).join(', ')}.\n${code.map(sliceText).join('\n')}`

  if (QUICK) {
    if (['correctness', 'async', 'security', 'dead'].some(l => on.has(l))) {
      for (const s of code) add('sweep', s.id, findingsPrompt(SWEEP_BRIEF, sliceText(s)))
    }
    if (on.has('wiring')) add('wiring', 'build, platform, data and settings', findingsPrompt(WIRING_TASKS.map(w => w.brief).join('\n\n'), `The whole range ${SCOPE}.`))
    if (on.has('rules')) add('rules', 'all rule files', findingsPrompt(`${LENS_BRIEFS.rules.brief} The rule files are ${[...ALWAYS_RULES, ...Object.keys(TAGGED_RULES)].map(r => `.claude/rules/${r}`).join(', ')}.`, whole))
    if ((on.has('parity') || on.has('sibling')) && (map.userVisibleChanges.length || map.fixCommits.length)) {
      add('parity', 'user-visible changes and fix commits', findingsPrompt(`${LENS_BRIEFS.parity.brief}\n\nThen: ${LENS_BRIEFS.sibling.brief}`,
        `User-visible changes:\n${map.userVisibleChanges.map(c => `- ${c.summary} (source: ${c.source}; content types: ${c.contentTypes})`).join('\n') || '(none)'}\n\nFix commits:\n${map.fixCommits.map(c => `- ${c.sha} ${c.subject}`).join('\n') || '(none)'}`))
    }
    if (on.has('tests') && tests.length) add('tests', 'all test slices', findingsPrompt(LENS_BRIEFS.tests.brief, tests.map(sliceText).join('\n')))
    if (on.has('upstream') && map.surfaces.length) {
      add('upstream', 'all taken-over or deleted surfaces', findingsPrompt(LENS_BRIEFS.upstream.brief,
        map.surfaces.map(s => `Surface: ${s.surface}. Plan doc: ${s.planDoc}. Replaced files: ${s.replacedFiles.join(', ')}`).join('\n')))
    }
    if (on.has('twoends')) for (const p of map.pairs.slice(0, MAX_PAIRS)) tasks.push({ lens: 'twoends', target: p.id, pair: p })
    return tasks
  }

  if (on.has('correctness') || on.has('async')) for (const s of code) add('core', s.id, findingsPrompt(LENS_BRIEFS.core.brief, sliceText(s)), LENS_BRIEFS.core.agentType)
  if (on.has('dead')) for (const s of code) add('dead', s.id, findingsPrompt(LENS_BRIEFS.dead.brief, sliceText(s)))
  if (on.has('security')) for (const s of tagged(code, 'untrusted')) add('security', s.id, findingsPrompt(LENS_BRIEFS.security.brief, sliceText(s)), LENS_BRIEFS.security.agentType)
  if (on.has('performance')) for (const s of tagged(code, 'hotpath')) add('performance', s.id, findingsPrompt(LENS_BRIEFS.performance.brief, sliceText(s)), LENS_BRIEFS.performance.agentType)
  if (on.has('rules')) {
    for (const rule of ALWAYS_RULES) add('rules', rule, findingsPrompt(`${LENS_BRIEFS.rules.brief} The rule file is .claude/rules/${rule}.`, whole))
    for (const [rule, tag] of Object.entries(TAGGED_RULES)) {
      const hit = tag === 'tests' ? [...tagged(code, 'tests'), ...tests] : tagged(code, tag)
      if (hit.length) add('rules', rule, findingsPrompt(`${LENS_BRIEFS.rules.brief} The rule file is .claude/rules/${rule}.`, hit.map(sliceText).join('\n')))
    }
  }
  if (on.has('docs')) for (const s of map.slices.filter(x => x.kind === 'docs')) add('docs', s.id, findingsPrompt(LENS_BRIEFS.docs.brief, sliceText(s)), LENS_BRIEFS.docs.agentType)
  if (on.has('tests')) for (const s of tests) add('tests', s.id, findingsPrompt(LENS_BRIEFS.tests.brief, sliceText(s)))
  if (on.has('wiring')) for (const w of WIRING_TASKS) add('wiring', w.target, findingsPrompt(w.brief, `The whole range ${SCOPE}.`))
  if (on.has('parity')) {
    for (const batch of chunk(map.userVisibleChanges, PARITY_BATCH)) {
      add('parity', batch.map(c => c.summary).join(' | ').slice(0, 120),
        findingsPrompt(LENS_BRIEFS.parity.brief, batch.map(c => `- ${c.summary} (source: ${c.source}; content types: ${c.contentTypes})`).join('\n')))
    }
  }
  if (on.has('sibling')) {
    for (const batch of chunk(map.fixCommits, SIBLING_BATCH)) {
      add('sibling', batch.map(c => c.sha).join(','), findingsPrompt(LENS_BRIEFS.sibling.brief, batch.map(c => `- ${c.sha} ${c.subject}`).join('\n')))
    }
  }
  if (on.has('upstream')) {
    for (const s of map.surfaces) {
      add('upstream', s.surface, findingsPrompt(LENS_BRIEFS.upstream.brief, `Surface: ${s.surface}. Plan doc: ${s.planDoc}. Replaced files: ${s.replacedFiles.join(', ')}`))
    }
  }
  if (on.has('twoends')) for (const p of map.pairs.slice(0, MAX_PAIRS)) tasks.push({ lens: 'twoends', target: p.id, pair: p })
  return tasks
}

// Quick mode traces both ends of a pair in one agent; full mode uses two blind tracers and a reconciler.
function countAgents(tasks) {
  return tasks.reduce((n, t) => n + (t.lens === 'twoends' && !QUICK ? 3 : 1), 0)
}

// ---------------------------------------------------------------- map mode

const SLICE_ITEM = {
  type: 'object',
  properties: {
    id: { type: 'string' }, kind: { type: 'string', enum: ['code', 'docs', 'tests'] }, title: { type: 'string' },
    paths: { type: 'array', items: { type: 'string' } }, surface: { type: 'string' },
    contentTypes: { type: 'string', enum: ['manga', 'novel', 'both', 'neutral'] },
    tags: { type: 'array', items: { type: 'string', enum: TAGS } },
  },
  required: ['id', 'kind', 'title', 'paths', 'surface', 'contentTypes', 'tags'],
}
const SLICES_PROPS = {
  slices: { type: 'array', items: SLICE_ITEM },
  excluded: { type: 'array', items: { type: 'object', properties: { what: { type: 'string' }, why: { type: 'string' } }, required: ['what', 'why'] } },
}
const PAIRS_PROPS = {
  pairs: { type: 'array', items: { type: 'object', properties: {
    id: { type: 'string' }, value: { type: 'string' }, writeSide: { type: 'string', description: 'file:symbol' },
    readSide: { type: 'string', description: 'file:symbol' }, kind: { type: 'string', enum: ['write-read', 'round-trip', 'host-guest'] }, why: { type: 'string' },
  }, required: ['id', 'value', 'writeSide', 'readSide', 'kind', 'why'] } },
}
const COMMITS_PROPS = {
  fixCommits: { type: 'array', items: { type: 'object', properties: { sha: { type: 'string' }, subject: { type: 'string' } }, required: ['sha', 'subject'] } },
  userVisibleChanges: { type: 'array', items: { type: 'object', properties: {
    summary: { type: 'string' }, source: { type: 'string' }, contentTypes: { type: 'string', enum: ['manga', 'novel', 'both', 'neutral'] },
  }, required: ['summary', 'source', 'contentTypes'] } },
}
const SURFACES_PROPS = {
  surfaces: { type: 'array', items: { type: 'object', properties: {
    surface: { type: 'string' }, planDoc: { type: 'string' }, replacedFiles: { type: 'array', items: { type: 'string' } },
  }, required: ['surface', 'planDoc', 'replacedFiles'] } },
}

const SLICES_ASK = `Split the files changed in ${SCOPE} (git diff --name-only ${RANGE}${A.pathFilter ? ' -- ' + A.pathFilter : ''}) into cohesive slices of about 8 to 15 files that belong together (one feature, one surface, one subsystem). Kind is code, docs or tests. Tag each slice with what applies: untrusted (source or extension input, network, WebView, JavaScript bridges, file paths from input), hotpath (library-sized lists, reader rendering, update jobs, recomposition-heavy screens), screens (Compose screens or Voyager navigation), tests (test sources), sql (.sq, .sqm, repositories), prefs (preferences, migrations, backup). Exclude, and list under excluded with the reason: translation files outside i18n/src/commonMain/moko-resources/base/, and files whose only changes in the range come from "chore: sync Mihon" commits (check with git log --format=%s ${RANGE} -- <file>); a synced file that also carries // RK changes stays in.`
const PAIRS_ASK = `Find the write/read pairs this range touches where a value crosses a boundary and could disagree: stored values first (preferences and their migrations, database columns, backup fields, intent extras), then host/guest messages (a WebView bridge). Rank by risk and return at most ${MAX_PAIRS}.`
const COMMITS_ASK = `Classify commits in ${SCOPE}. "fixCommits": every commit that fixed a defect (usually a "fix" subject; exclude "chore: sync Mihon"). "userVisibleChanges": what a user could notice, from CHANGELOG.md [Unreleased] entries added in the range (git diff ${RANGE} -- CHANGELOG.md) or commit subjects, with the content types each affects.`
const SURFACES_ASK = `List the surfaces this range took over from Mihon (the seam-depth table in .claude/rules/content-layer.md; docs/dev/off-path-manifest.md names replaced Mihon files) or on which it deleted Reikai code with behaviour of its own (git diff --diff-filter=D --name-only ${RANGE}). For each, the plan doc and the replaced or deleted files.`

if (A.mode === 'map') {
  phase('Map')
  const mapBase = `${PREAMBLE}\n\nYou are building the audit's map, not auditing. Be complete: whatever you leave out goes unaudited.`
  let map
  if (QUICK) {
    const m = await agent(`${mapBase}\n\nDo all four parts in one pass.\n\n1. ${SLICES_ASK}\n\n2. ${PAIRS_ASK}\n\n3. ${COMMITS_ASK}\n\n4. ${SURFACES_ASK}`, {
      label: 'map', phase: 'Map', effort: 'medium',
      schema: { type: 'object', properties: { ...SLICES_PROPS, ...PAIRS_PROPS, ...COMMITS_PROPS, ...SURFACES_PROPS }, required: ['slices', 'excluded', 'pairs', 'fixCommits', 'userVisibleChanges', 'surfaces'] },
    })
    map = m
  } else {
    const [sl, pr, cm, su] = await parallel([
      () => agent(`${mapBase}\n\n${SLICES_ASK}`, { label: 'map:slices', phase: 'Map', effort: 'high', schema: { type: 'object', properties: SLICES_PROPS, required: ['slices', 'excluded'] } }),
      () => agent(`${mapBase}\n\n${PAIRS_ASK}`, { label: 'map:pairs', phase: 'Map', effort: 'high', schema: { type: 'object', properties: PAIRS_PROPS, required: ['pairs'] } }),
      () => agent(`${mapBase}\n\n${COMMITS_ASK}`, { label: 'map:commits', phase: 'Map', effort: 'low', schema: { type: 'object', properties: COMMITS_PROPS, required: ['fixCommits', 'userVisibleChanges'] } }),
      () => agent(`${mapBase}\n\n${SURFACES_ASK}`, { label: 'map:surfaces', phase: 'Map', effort: 'low', schema: { type: 'object', properties: SURFACES_PROPS, required: ['surfaces'] } }),
    ])
    map = sl && pr && cm && su ? { slices: sl.slices, excluded: sl.excluded, pairs: pr.pairs, fixCommits: cm.fixCommits, userVisibleChanges: cm.userVisibleChanges, surfaces: su.surfaces } : null
  }
  if (!map) throw new Error('a map agent failed; re-run the map')
  const tasks = planTasks(map, A.lenses)
  const byLens = {}
  for (const t of tasks) byLens[t.lens] = (byLens[t.lens] || 0) + 1
  const verification = QUICK
    ? 'plus one Opus skeptic per high finding; medium findings are listed unverified'
    : 'plus two skeptics per high finding, one per batch of up to five medium findings (lows are listed unverified), and at most five critic gaps with their own finders and verifiers'
  return { mode: QUICK ? 'quick' : 'full', map, estimate: { finderAgents: countAgents(tasks), byLens, verification } }
}

// ---------------------------------------------------------------- audit mode

if (!A.map) throw new Error('audit mode needs args.map from an approved map run')

async function runTask(t) {
  if (t.lens === 'twoends') return runPair(t)
  const out = await agent(t.prompt, { label: `${t.lens}:${t.target}`.slice(0, 80), phase: 'Find', schema: FINDINGS, agentType: t.agentType, ...FINDER })
  return { t, out }
}

async function runPair(t) {
  const p = t.pair
  if (QUICK) {
    const out = await agent(`${PREAMBLE}\n\nTwo-ends tracing of one value (${p.value}, ${p.kind}). Trace the write side from ${p.writeSide} and the read side from ${p.readSide} independently: for each, the exact key, type, units and scale, default and empty meaning, timing and every other party. Then compare them and report each mismatch that can change what a user sees. ${REPORTING}`, {
      label: `twoends:${p.id}`.slice(0, 80), phase: 'Find', schema: FINDINGS, ...FINDER,
    })
    return { t, out }
  }
  const side = (which, start) => agent(`${PREAMBLE}\n\nTwo-ends tracing. You own ONE end of a value that crosses a boundary; another agent owns the other end and you will not see its work. Value: ${p.value} (${p.kind}). Your side: the ${which} side, starting at ${start}. Describe exactly what your side does, not what it should do.`, {
    label: `twoends:${which}:${p.id}`.slice(0, 80), phase: 'Find', schema: CONTRACT, ...FINDER,
  })
  const [w, r] = await parallel([() => side('write', p.writeSide), () => side('read', p.readSide)])
  if (!w || !r) return { t, out: null }
  const out = await agent(`${PREAMBLE}\n\nReconcile two independent traces of one value (${p.value}, ${p.kind}). Writer's contract:\n${JSON.stringify(w, null, 2)}\n\nReader's contract:\n${JSON.stringify(r, null, 2)}\n\nRe-read both sides where they disagree and report each mismatch that can change what a user sees: key, type, units, default, timing, a party one side missed. ${REPORTING}`, {
    label: `twoends:reconcile:${p.id}`.slice(0, 80), phase: 'Find', schema: FINDINGS, ...FINDER,
  })
  return { t, out }
}

const RANK = { high: 3, medium: 2, low: 1 }
function dedupe(found) {
  const out = []
  for (const f of found) {
    const m = out.find(o => o.file === f.file && Math.abs(o.line - f.line) <= 3)
    if (!m) { out.push({ ...f, lenses: [f.lens], claims: [f.claim] }); continue }
    if (!m.lenses.includes(f.lens)) m.lenses.push(f.lens)
    m.claims.push(f.claim)
    if (RANK[f.severity] > RANK[m.severity]) { m.severity = f.severity; m.title = f.title; m.failureScenario = f.failureScenario }
    if (f.evidence === 'executed') { m.evidence = 'executed'; m.evidenceDetail = f.evidenceDetail }
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

const CHECKS = {
  code: 'Re-read the cited lines and enough surrounding code to decide whether the defect is real as stated. Check the callers and the data actually flowing in. Settle it by running something read-only where you can: a whole-tree grep, git log or show, a node or pwsh snippet reproducing the logic. If only Gradle or a device could settle it, do not refute on that ground; set evidence to traced and name the probe.',
  ruled: 'Decide whether this is deferred, declined or ruled in a docs/dev/plans record or ROADMAP.md, on the parked list, in the ledger rows below with a reason that still holds, intended per an owner ruling, or contradicted by a gate that passes (Spotless, the compiler, di-interop-check). Any of those refutes it.',
}
const subjectOf = f => `${f.title}\n${f.file}:${f.line} (lenses: ${f.lenses.join(', ')}; severity ${f.severity})\nClaims:\n${f.claims.map(c => '- ' + c).join('\n')}\nFailure scenario: ${f.failureScenario}\nFinder's evidence (${f.evidence}): ${f.evidenceDetail}`
const skepticPreamble = files => `${PREAMBLE}\n\nLedger rows for these files (previously refuted findings):\n${ledgerFor(files)}\n\nYou are a skeptic. Try to REFUTE. Default to refuted=true when uncertain. A negative claim ("nothing calls X", "never read") must be re-searched across the whole repository, tests and scripts included, before you accept it.`

function settle(f, votes, needAll) {
  if (!votes.length) return { ...f, survives: false, verdicts: [], unverifiable: true }
  const holding = votes.filter(v => !v.refuted)
  const survives = needAll ? holding.length === votes.length : holding.length > 0
  const executed = holding.some(v => v.evidence === 'executed') || f.evidence === 'executed'
  const probe = (holding.find(v => v.probe) || {}).probe || ''
  const severity = holding.length ? holding.map(v => v.severity).sort((a, b) => RANK[b] - RANK[a])[0] : f.severity
  return { ...f, survives, evidence: executed ? 'executed' : 'traced', probe, severity, verdicts: votes }
}

async function verifyHigh(f) {
  const ask = check => agent(`${skepticPreamble([f.file])}\n\nYour check: ${check}\n\nFinding:\n${subjectOf(f)}`, {
    label: `verify:${f.file.split('/').pop()}:${f.line}`, phase: 'Verify', schema: VERDICT, effort: 'high',
  })
  const checks = QUICK ? [`${CHECKS.code} ${CHECKS.ruled}`] : [CHECKS.code, CHECKS.ruled]
  const votes = (await parallel(checks.map(c => () => ask(c)))).filter(Boolean)
  return settle(f, votes, true)
}

async function verifyMediums(batch) {
  const out = await agent(`${skepticPreamble(batch.map(f => f.file))}\n\nYour check, for each finding: ${CHECKS.code} ${CHECKS.ruled}\n\nReturn one verdict per finding, keyed by its index.\n\n${batch.map((f, i) => `Finding ${i}:\n${subjectOf(f)}`).join('\n\n')}`, {
    label: `verify:batch:${batch[0].surface}`.slice(0, 80), phase: 'Verify', schema: BATCH_VERDICT, effort: 'medium',
  })
  return batch.map((f, i) => settle(f, out ? out.verdicts.filter(v => v.index === i) : [], true))
}

function groupBySurface(fs) {
  const groups = {}
  for (const f of fs) (groups[f.surface] = groups[f.surface] || []).push(f)
  return Object.values(groups).flatMap(g => chunk(g, MEDIUM_BATCH))
}

async function verifyAll(deduped) {
  const highs = deduped.filter(f => f.severity === 'high')
  const mediums = deduped.filter(f => f.severity === 'medium')
  const verified = (await parallel([
    ...highs.map(f => () => verifyHigh(f)),
    ...(QUICK ? [] : groupBySurface(mediums).map(b => () => verifyMediums(b))),
  ])).filter(Boolean).flat()
  const unverified = deduped.filter(f => f.severity === 'low' || (QUICK && f.severity === 'medium'))
  return { verified, unverified }
}

phase('Find')
const tasks = planTasks(A.map, A.lenses)
log(`${QUICK ? 'quick' : 'full'} mode: ${tasks.length} finder tasks, about ${countAgents(tasks)} finder agents`)
if (A.map.pairs.length > MAX_PAIRS) log(`tracing the first ${MAX_PAIRS} of ${A.map.pairs.length} pairs; the rest are reported as uncovered`)
const coverage = []
let found = collect(await parallel(tasks.map(t => () => runTask(t))), coverage)
const failed = coverage.filter(c => c.status === 'failed')
if (failed.length) log(`${failed.length} finder tasks failed and are reported as uncovered`)

// Barrier on purpose: lenses overlap, so dedupe before paying for verification.
phase('Verify')
let deduped = dedupe(found)
log(`${found.length} raw findings, ${deduped.length} after dedupe`)
let { verified, unverified } = await verifyAll(deduped)

let gapList = []
if (!QUICK) {
  phase('Critic')
  const gaps = await agent(`${PREAMBLE}\n\nYou are the completeness critic for this audit. Coverage so far (lens, target, status, findings, what was searched):\n${JSON.stringify(coverage, null, 1)}\n\nSlices in the map:\n${A.map.slices.map(s => `${s.id} (${s.kind}; tags ${(s.tags || []).join(', ') || 'none'}): ${s.paths.join(' ')}`).join('\n')}\n\nExcluded:\n${A.map.excluded.map(e => `${e.what}: ${e.why}`).join('\n')}\n\nConfirmed so far:\n${verified.filter(v => v.survives).map(v => `- ${v.title} (${v.file}:${v.line})`).join('\n') || '(none)'}\n\nName what is missing on HIGH-RISK ground only (stored data, DI and build wiring, concurrency, deletions, untrusted input): a failed or thin task, a slice whose "searched" shows it was skimmed, an exclusion that hides real code, a write/read pair nobody traced. Return at most ${MAX_GAPS} gaps, most important first; an empty list is fine.`, {
    label: 'critic', phase: 'Critic', effort: 'high',
    schema: {
      type: 'object',
      properties: { gaps: { type: 'array', items: { type: 'object', properties: {
        lens: { type: 'string', enum: ['core', 'dead', 'security', 'performance', 'rules', 'docs', 'tests', 'parity', 'sibling', 'upstream'] },
        target: { type: 'string', description: 'paths, commits or the behaviour to audit' }, why: { type: 'string' },
      }, required: ['lens', 'target', 'why'] } } },
      required: ['gaps'],
    },
  })
  gapList = gaps ? gaps.gaps.slice(0, MAX_GAPS) : []
  if (gapList.length) {
    log(`critic named ${gapList.length} gaps; auditing them once (no further rounds)`)
    const gapTasks = gapList.map(g => ({
      lens: g.lens, target: `gap: ${g.target}`.slice(0, 120), agentType: LENS_BRIEFS[g.lens].agentType,
      prompt: findingsPrompt(g.lens === 'rules' ? `${LENS_BRIEFS.rules.brief} Pick the rule file the gap names.` : LENS_BRIEFS[g.lens].brief, `${g.target}\n(Why this was missed: ${g.why})`),
    }))
    const more = collect(await parallel(gapTasks.map(t => () => runTask(t))), coverage)
    found = found.concat(more)
    const fresh = dedupe(more).filter(f => !deduped.some(o => o.file === f.file && Math.abs(o.line - f.line) <= 3))
    const again = await verifyAll(fresh)
    verified = verified.concat(again.verified)
    unverified = unverified.concat(again.unverified)
  }
}

let mutation = null
if (A.mutate) {
  phase('Mutate')
  const suspects = verified.filter(v => v.survives && v.lenses.includes('tests')).map(v => `${v.file}:${v.line} ${v.title}`)
  mutation = await agent(`You work in a throwaway git worktree of the Reikai repo, so edits here never reach the owner's tree. Pick up to five tests added in ${RANGE}, preferring these suspects:\n${suspects.join('\n') || '(none flagged; pick tests whose production clause is easy to isolate)'}\n\nFor each, one at a time: delete the production clause the test claims to pin, run only that test class through the PowerShell tool (never Bash, which cannot run Gradle here): $env:JAVA_HOME='C:\\Users\\unseensnick\\.jdks\\temurin-21.0.11'; .\\gradlew :<module>:testDebugUnitTest --tests <FQCN> (the domain module uses :domain:test). Never pipe gradlew into Select-String, which hides a failed build. Record red or green, restore the clause, move on. A test that stays green with its clause deleted is a confirmed finding with evidence "executed". If Gradle will not run, report that rather than guessing.`, {
    label: 'mutate', phase: 'Mutate', isolation: 'worktree', schema: FINDINGS,
  })
}

if (budget.total && budget.remaining() === 0) log('the token cap was reached; tasks that could not start are reported as failed')

const confirmed = verified.filter(v => v.survives).sort((a, b) => RANK[b.severity] - RANK[a.severity])
return {
  mode: QUICK ? 'quick' : 'full',
  confirmed,
  unverified: unverified.map(f => ({ title: f.title, file: f.file, line: f.line, severity: f.severity, lenses: f.lenses, evidence: f.evidence })),
  mutationFindings: mutation ? mutation.findings : [],
  refuted: verified.filter(v => !v.survives).map(v => ({ title: v.title, file: v.file, line: v.line, lenses: v.lenses, reasons: v.verdicts.filter(x => x.refuted).map(x => x.reason), unverifiable: !!v.unverifiable })),
  coverage,
  gaps: gapList,
  counts: { tasks: tasks.length, rawFindings: found.length, verified: verified.length, confirmed: confirmed.length, unverified: unverified.length },
}
