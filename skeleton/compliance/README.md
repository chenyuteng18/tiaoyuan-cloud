# ADR-12 build-time compliance scan

The compliance gate for the client (mini-program) package. A hit fails the
build. There is no warning mode.

## Why this exists

Two failure modes are being closed, and both are silent:

1. **The channel that was forgotten.** The page copy gets changed and the
   subscription-message template does not. The customer keeps receiving the
   wording the compliance ruling forbids, and nothing in the build notices,
   because the change that should have triggered a review never touched the file
   anybody was reviewing.
2. **The gate that rotted.** A scan rule with no owner, or a scan root that has
   been renamed, produces a clean report forever. It reports PASS because it is
   scanning nothing. The decay is invisible from the outside, so it is only
   found after the incident.

Every mechanism below exists because of one of those two. In particular, the
gate treats *its own misconfiguration* as a build failure, not as a pass.

## The three scan faces

These are fixed by the ADR-12 ruling. They must not be renamed, removed or added
to; the gate refuses to run (exit 4) if the manifest drifts, and the self-test
re-asserts the list from an independent copy.

| face | what it catches | scope |
|---|---|---|
| `scan1_refund` | the refund wording, in every channel it can reach the customer through | `client-package/**` + `frontends/client-mp/miniprogram/**` |
| `scan2_negative` | negative counting and gap-attribution wording; band data described as a medical basis | `client-package/**` + `frontends/client-mp/miniprogram/**` |
| `scan3_derived` | derived field and derived verdict names (`gap_reason`, `effect_verdict`, `AS_refund`, ...) | `client-package/**` + `frontends/client-mp/miniprogram/**` |

All three scan the **client surfaces only** -- two roots since 2026-09-28. The
second root is the real mini-program project (G-B B-3). Until it was added, the
discipline covered the SAMPLE package while the project that actually gets
compiled and uploaded was an unscanned word surface; the gate printed PASS and
nothing in the output looked wrong. S1-6 carries the same two roots, and there
the count is frozen (`EXPECTED_CLIENT_ROOTS`) so that deleting a root -- which
leaves every remaining root present, and so would otherwise pass silently --
is a refusal.

The refund wording is a legitimate internal business term; it appears in the
repository's own documents and in the manager and therapist surfaces by design,
and the gate must not fire there. A gate that cries wolf on correct content gets
switched off, and it takes the client-package protection down with it. The
self-test asserts this containment in both directions.

### Coverage boundary, registered explicitly

> **scan1 does NOT cover `doc_template.content` (the server-side agreement
> template column) and does NOT cover any client-side artefact rendered from it.
> It covers such an artefact only if a build step writes the rendered result into
> `client-package/**` with an extension in `TEXT_SUFFIXES`.**

Physical basis: scan1's only root is `client-package` (`scan-manifest.json`,
`faces.scan1_refund.roots`), resolved against `--repo-root`; `iter_source_files()`
walks that directory and nothing else. The template source is a database column
(PRD 附录 C.1 `doc_template` 实体), rendered by the super-admin backend or the
e-sign vendor page — not a file in the bundle. So this face protects "files that
have already landed in the client package", not "text in the database" and not
"pages rendered elsewhere".

The ruling this registration comes from (**2026-09-21**) and the three-layer
disposition it enforces are recorded in full at the foot of
`wordlists/scan1_refund.words`. The short version: agreement body text lives in
`doc_template.content` and is never shipped as a client-package asset; client-side
identifiers must never spell server-side refund-domain vocabulary; and the gate is
**not** to be loosened — no wordlist relaxation, no path exception, no allow-list.
A hit here is a true signal that a name reached the client bundle, and the fix is
to move the name out, not to widen the gate. Sending agreement text to the client
bundle would require an ADR-12 amendment first, because it would need a hole in
the channel-parity guarantee.

### Channel parity for face 1

`subscribe-messages/` is the channel ADR-12 calls out loudest, because a push
template is edited in a different place from the UI and reviewed by a different
person. Its directory is therefore required to exist, and the gate fails the
build if it is missing or has been dropped from the manifest.

The six channels named by ADR-12, plus the disabled-interface path list from the
contract:

```
client-package/i18n                 client-package/errors
client-package/enums                client-package/analytics
client-package/constants            client-package/subscribe-messages
client-package/api
```

## Owner discipline

Every rule must carry an owner, recorded in `owners.csv`. An empty owner is not
a cosmetic problem: it is the mechanism by which the rule stops being maintained,
and nothing else in the system will ever report it.

A blank owner produces an `OWNER_DISCIPLINE` violation and **fails the build**,
even when the tree is otherwise completely clean. That is deliberate — the
failure has to be visible at the moment the owner is removed, because after that
there is no second chance to notice.

The owner identity currently recorded is a **placeholder role**
(`role:dev-compliance-lead`). Naming the real person is the T-2 / G6 action item
and is still open; see the handover notes.

## Running it

```bash
# run it from the repository root (the parent of compliance/ and client-package/)
cd <repository-root>

# the gate, as CI runs it
python compliance/scan_compliance.py --repo-root . --report compliance/reports/ci-scan.json

# the witness: 12 assertions that the gate is still load-bearing
python compliance/tests/compliance_injection_test.py

# the owner-readiness witness: 6 assertions that a placeholder is not an owner
python compliance/tests/owner_readiness_test.py

# the release gate: same scan, but a placeholder owner fails the build
python compliance/scan_compliance.py --repo-root . --require-named-owners
mvn -Pgo-live validate          # the Maven profile that does the above

# reverse verification: 3 injections, one per face, with the evidence report
python compliance/run-reverse-verification.py

# end-to-end: inject a hit and prove `mvn package` fails and ships nothing
python compliance/tests/build-gate-injection.py

# --- S1-6 structural gate (a separate gate, NOT a fourth scan face) ---------
# the gate itself. Needs TWO roots: the contract sits one level ABOVE this
# repository root, beside the PRD. --docs-root is auto-detected upward and the
# gate refuses to run (exit 2) rather than verify the wrong tree.
python compliance/client-zero-derived-gate.py --repo-root .

# the witness: 13 assertions that the structural gate is still load-bearing
python compliance/tests/zero_derived_gate_test.py

# --- S1-8 contract conformance gate (a THIRD gate, not a wider scan face) ----
# Requires PyYAML. It parses the OpenAPI contract with a real parser ON PURPOSE:
# two hand-rolled regex probes were measured to disagree with each other on the
# same file (42 vs 2 fields), and a probe that contradicts itself cannot underpin
# a gate. When PyYAML is missing the gate exits 2 -- it does NOT degrade to
# regexes and keep printing PASS.
python -m pip install "pyyaml>=6.0"

# the gate itself. Same two-root layout as S1-6.
python compliance/contract-conformance-gate.py --repo-root .

# the witness: 18 assertions, each destroying a piece of the gate's protection --
# in the artifact, in the truth source, or in the gate's own runtime (W8 hides
# PyYAML and requires exit 2 rather than a regex fallback).
python compliance/tests/contract_conformance_gate_test.py

# --- S1-9 SDK surface gate (a FOURTH gate: the generated SDKs themselves) ----
# Same PyYAML requirement; reuses the S1-8 contract reader by import.
python compliance/sdk-surface-gate.py --repo-root .

# the witness: 7 cases, including X5 (paths != methods must refuse, never
# zip-truncate into a silent PASS on a half-read surface).
python compliance/tests/sdk_surface_gate_test.py
```

It also runs automatically, bound to the Maven `validate` phase, so:

```bash
mvn clean package
```

runs the gate first and stops before compiling anything if it fails.

### Exit codes

| code | meaning |
|---|---|
| 0 | pass |
| 1 | a term fired, or a rule has no owner |
| 2 | the gate is misconfigured (owner missing, channel missing, client package root absent, wordlist unreadable / not UTF-8, scan root absent) |
| 3 | internal scanner error |
| 4 | the declared scan faces no longer match the ADR-12 ruling |

Everything non-zero fails the build, including "the gate itself is broken".
That is the point: a gate that cannot run must look like a red build, not like a
clean bill of health.

## Matching model

The rule differs by writing system, and both halves matter.

- **ASCII terms** are matched against *identifier segments*, at three levels.
  A term fires when it equals a whole token (`gap_reason`), when it equals one
  segment (`refund` inside `mp_refund_entry_tap` or `RefundView`), or when its
  segments appear as a **contiguous run** inside a token (`blood_sugar` inside
  `mp_blood_sugar_tap`, `band_blood_sugar`, `getBloodSugar`,
  `CLIENT_BAND_BLOOD_SUGAR_KEY`). The first two levels answer the channel
  ADR-12 names loudest; the third exists because a multi-word term used to fire
  only when an identifier was spelled *exactly* like the term — the one shape a
  real leak is least likely to have. The run must be **contiguous**: `sugar_blood`
  and `blood_x_sugar` stay quiet, because a subset test would report both and a
  rule nobody can explain gets switched off. No level matches a fragment, so
  `refundable` stays quiet.

  **Known, accepted, explainable false positive.** The strongest of the three
  levels is the contiguous run, and it has exactly one known over-reach:
  `blood_pressure` (segments `blood` + `pressure`) also fires on an unrelated
  name of the same shape such as `bloodPressureCuff`. This is **accepted, not
  special-cased.** Two reasons. First, the report is explainable: the identifier
  literally spells the forbidden metric, so a human clears it in seconds. Second,
  the only way to suppress it is to condition the rule on "does this term have a
  sensitive tail", which makes the rule unexplainable — and an unexplainable rule
  gets switched off. Trading one explainable suspect hit for 14 terms that go
  from "never fires when wrapped" to "can fire" is the right trade. Measured
  cost: an exhaustive sweep of the real `client-package` vocabulary
  (591 tokens x 15 ASCII terms = 8865 combinations) produces **zero** hits, and
  of 19 deliberately innocent identifiers only `bloodPressureCuff` fires. The
  guard test `T9` re-checks the innocent set on every build, so this stays a
  measured property rather than a one-off observation.
- **CJK terms** are matched as *substrings*. A Chinese sentence has no spaces, so
  the whole sentence is one token; token-exact matching would never find the term
  inside real copy. This shifts a duty onto the wordlist: **do not add a short
  CJK term that sits inside an innocuous customer-facing word.** The wordlist
  documents the cases considered and rejected, with the reason.
- **Escape sequences are expanded before matching.** `"\u9000\u6b3e"` is the
  cheapest way to smuggle a forbidden word past a grep: the file bytes are pure
  ASCII, yet the string is the forbidden word once evaluated. Every line is
  scanned in its decoded form as well.
- **The hyphen is a token character** (fixed 2026-09-24). The hyphen is a legal
  separator in JSON keys, URL slugs, CSS class names and YAML keys, so an
  identifier reaches a bundle as `gap-reason` at least as easily as `gap_reason`.
  It used to be split into two tokens, which made it **unreachable by every match
  level**:

  | term | `_` spelling | `-` spelling (before the fix) |
  |---|---|---|
  | `gap_reason` | HIT | **MISS** |
  | `effect_verdict` | HIT | **MISS** |
  | `blood_sugar` | HIT | **MISS** |
  | `AS_refund` | HIT | **MISS** |
  | `refund` (single word) | HIT | HIT -- caught only because the hyphen fell between the term and its suffix |

  The miss hit exactly the **multi-word** terms -- the shape the adjacent-run
  level was added for. The fix widens the **token**, not the match rule:
  `gap-reason` becomes one token whose segments are `[gap, reason]`, which the
  existing adjacent-run level already matches. Verified no false positives: 16
  hyphenated identifiers a real bundle plausibly contains (`no-data-today`,
  `client-sync-state`, `read-only`, `end-to-end`, `self-check`, `high-level`, ...)
  stay quiet on all three faces. `T5` asserts both directions.
- **Normalisation** is NFKC plus case folding, so case and Unicode homoglyphs are
  not a way past the gate.

## How to verify a change (read this before quoting a green build)

A build that fails is not by itself proof that the gate caught anything, and a
build that succeeds is not by itself proof that the gate ran. Four traps, all of
which have already produced a misleading reading once:

- **Stale artifacts make "nothing was packaged" meaningless.** The gate is bound
  to the `validate` phase, so when it fails the reactor stops there and the child
  modules never reach their own `clean`. A plain
  `find . -name "*.jar" -path "*target*"` therefore lists the jars from the
  *previous successful build* and looks like a packaged delivery. Delete the jars
  **before** the run, then assert the count is zero after it.
  `tests/build-gate-injection.py` does this (`os.remove(jar)`); a hand-run does
  not unless you remember to.
- **A quiet run is not an exercised run.** Inject the hit, watch the build fail,
  name the offending file *and* the face in the output, then remove the injection
  and watch the build pass again. Asserting only one of the two directions
  cannot distinguish a working rule from a rule that never fires.
- **Check the counts the gate prints.** Each run prints, per face, the number of
  terms and roots it loaded. A face silently reduced to zero terms would
  otherwise report a clean scan. `terms=0` or `roots=0` is a configuration
  failure, not a pass.
- **Run it from the repository root, or pass `--repo-root` explicitly.**
  `--repo-root` defaults to `os.getcwd()`, so invoking
  `python compliance/scan_compliance.py` from *inside* `compliance/` makes
  `client-package` resolve to `compliance/client-package`, which does not exist.
  Since 2026-09-20 this is reported as its own distinct error, naming the
  resolved absolute path and stating that no channel was dropped from the
  manifest:

      [GATE-ERROR] client package root not found: <repo-root>/compliance/client-package

  It is **not** reported as a channel-parity violation and it does **not**
  mention `DY_COMPLIANCE_ALLOW_MISSING_ROOT`. That distinction is the point:
  before the split, a wrong cwd produced seven `declared channel directory
  absent` lines — the same wording a genuine parity break uses — and closed by
  suggesting a dry-run opt-out, which invited the reader to convert a working
  build-blocking gate into a green no-op over a problem that was never a content
  problem. The tell is still there to read: all seven channels looking absent at
  once means the **root** is wrong, whereas a real parity break names the one
  channel that was **dropped from the manifest**. `T10` asserts both directions.
  Exit code is 2 either way — fail-closed, and no PASS is printed.

**One known cosmetic wart, ruled accepted (2026-09-20).** In dry-run mode
(`DY_COMPLIANCE_ALLOW_MISSING_ROOT=1`) a *wrong cwd* is doubly reported: the new
`client package root absent, NOT scanned` line **and** the seven legacy
`CHANNEL PARITY NOT ENFORCED` lines, plus one `root absent, NOT scanned` line per
scan face. Measured on the wrong-cwd dry-run: **11 NOTE lines** for what is a
single fact. This is deliberate and stays. Dry-run is an explicit, manual,
human-typed opt-out whose entire purpose is to enumerate what is *not* being
checked — collapsing the lines would mean either suppressing that enumeration or
editing the dry-run contract asserted by `T4`. A redundant enumeration of
suppressed checks is harmless; a suppressed one is misleading. The non-dry-run
path — the one that gates a build — is unaffected: it exits 2 with the single
root-not-found message.

Cleanup is itself asserted, and it self-heals. Two distinct mechanisms:

1. **Reclaim at start-up.** Each witness calls `reclaim_own_leftovers()` before its
   first assertion. A witness that is interrupted (Ctrl-C, CI timeout, `SIGKILL` —
   none of which `finally` covers) leaves its own injected probe files behind.
   Left alone, those leftovers turn the *baseline* case (W0 / T0) permanently red
   and the failure text blames the real repository. The reclaim step therefore
   removes **only** files it can prove are its own: a file whose bytes match a
   registered injection verbatim, or one sitting on a path the suite itself
   writes (`__scope_probe_*`). Anything else is left untouched — a witness must
   never delete a file it cannot account for.
2. **Assert at end.** Each witness scans `client-package/` for residual probe
   files and fails if any survive. The probe recogniser is **name-based**
   (`_is_probe_name` matches any filename containing `probe`), not a prefix
   whitelist: the original whitelist `("__inject", "_probe")` was blind to the
   suite's own `__scope_probe_*` files, which is precisely the bug that made the
   guard unable to see its own residue. `T11` pins this: the guard must recognise
   all six real probe names and must not flag innocent ones. The scan is scoped to
   the `client-package` recorded in `scan-manifest.json`, not the whole tree, so
   unrelated `*probe*` fixtures elsewhere are not misreported.

A leftover probe the guard *can* see is still a hard failure — silently tolerating
residue would defeat the point of injecting at all. Self-healing applies to the
suite's **own** residue only, never to a foreign file that happens to collide.

## Wordlists and their encoding

Wordlists are UTF-8 and are decoded explicitly as UTF-8, never with the platform
default. This machine's platform encoding is GBK, and Python 3.15+ flips text
mode to UTF-8 by default; pinning the encoding keeps the gate's behaviour from
changing under an interpreter upgrade.

Every script in `compliance/` is pure ASCII. Non-ASCII literals live only in the
wordlists and in `owners.csv`, which is why the wordlists are external files
rather than constants in the source. The wordlist files prefer raw characters
over escapes: escaped forms work, but a raw file stays readable in a diff, and
readable is the difference between a wordlist that gets reviewed and one that
does not.

## Files

```
compliance/
  scan_compliance.py                  the ADR-12 gate (vocabulary, three faces)
  scan-manifest.json                  faces, roots, wordlists, required channels
  owners.csv                          owner per rule (must not be blank)
  wordlists/scan1_refund.words        face 1 terms
  wordlists/scan2_negative.words      face 2 terms
  wordlists/scan3_derived.words       face 3 terms
  client-zero-derived-gate.py         S1-6 structural gate (claims, not vocabulary)
  tests/compliance_injection_test.py  12 assertions that the ADR-12 gate is load-bearing
  tests/owner_readiness_test.py       6 assertions that placeholders are not owners
  tests/zero_derived_gate_test.py     13 assertions that the S1-6 gate is load-bearing
  contract-conformance-gate.py        S1-8 gate (facts, not claims) -- needs PyYAML
  tests/contract_conformance_gate_test.py  18 assertions that the S1-8 gate is load-bearing
  tests/build-gate-injection.py       end-to-end: mvn fails, nothing is packaged
  run-reverse-verification.py         3-injection evidence report
  reports/                            generated output

client-package/                       the scanned surface: the client bundle scaffold
  i18n/ enums/ constants/ errors/ analytics/ subscribe-messages/ api/
```

`client-package/` is a scaffold of the real client bundle. It exists so the gate
has a real surface with the real channel layout, rather than a directory that is
empty because the client work has not started. Replacing the scaffold file by
file with the generated bundle requires no change to the gate. Copying the layout
correctly **does** matter: a channel left out of the scaffold is a channel the
gate is not checking.

## S1-6: the structural gate (a separate gate, not a fourth scan face)

`client-zero-derived-gate.py` answers a question the three ADR-12 faces cannot:
**is every path the client bundle says it may call actually one the contract
permits?**

### Why vocabulary scanning could not see the defect

The three ADR-12 faces scan the client package for **forbidden vocabulary**. This
gate was written because a green build was lying: the client package carried, in
its own outbound allow-list, `/api/v1/band/derived` -- a route that does not exist
in the contract, spelling a concept the contract denies to the client (row E4,
`x-client-explicitly-denied: true`, `x-callable-roles: [therapist, meridian,
admin]`). Measured at the time: **8 allow-list entries, 2 correct.**

No word-scan could have caught it, for two independent reasons:

- **The concept word was not a term.** `derived` was absent from
  `scan3_derived.words`, so no face could fire on it. (It has since been added,
  together with the derived field names that were also missing -- see the
  curation note in that wordlist.)
- **The allow-list was itself the wrong artefact.** A word-scan asks "did a
  forbidden word reach the bundle". It cannot ask "does the bundle's own
  permission list permit a forbidden thing", because a permission list is not a
  word, it is a **claim**. The claim was checkable against the contract all along
  and nothing checked it.

That second reason is the general one, and it is why this is a separate gate
rather than a new term. Two hand-maintained lists of the same fact -- the
bundle's allow-list and the contract's `x-callable-roles` -- drift apart silently,
and they drift in the direction that opens a hole.

### The faces

| face | what it asserts | truth source |
|---|---|---|
| `no-zero-derived-names` | every field of every client-invisible group is absent from the bundle | `contract/visibility/band-visibility-matrix.json` -- **derived**, not a second hand-written list |
| `allowlist-within-contract` | every allow-list entry is a path the contract permits the client to call | `contract/openapi-v1.0.0.yaml` `x-callable-roles` |
| `no-client-forbidden-paths` | no path carrying `x-client-forbidden: true` appears in the bundle | `contract/openapi-v1.0.0.yaml` |
| `gate-self-check` | the inputs above are non-empty and were actually read | all of the above |

The forbidden field set is **derived from the visibility matrix**, deliberately.
A second hand-maintained list of the same fact is the exact defect being fixed, so
the fix must not reintroduce one. Add a field to group ③ or ④ in the contract and
this gate covers it without touching the gate.

`allowlist-within-contract` is a **subset** check, one direction only: a bundle
may use fewer paths than it is permitted, but must never reach one it is denied.
An equality check was implemented first and rejected -- it fails on every
permitted-but-unused endpoint, which is not a defect, and a gate that fails on
correct content gets switched off. The reverse delta is still **reported** as
information, because it is how an intended path goes missing.

### Two roots, because the contract and the bundle do not live together

`client-package/` and `compliance/` sit under this repository root; `contract/`
sits one level **above** it, beside the PRD. The ADR-12 scan only ever needed one
root, which is why the asymmetry did not surface until a gate had to read both.
`--docs-root` is auto-detected by walking up, and the gate **refuses to run
(exit 2)** if it cannot find the contract -- it does not fall back to verifying
whatever tree it happens to be in.

### Exit codes

| code | meaning |
|---|---|
| 0 | pass |
| 1 | a violation (derived name in the bundle / allow-list mismatch / client-forbidden path in the bundle) |
| 2 | misconfigured: a truth source absent, unparseable or empty, or a root missing |
| 3 | internal error |

An internal error gets **its own exit code on purpose**. A traceback in Python
exits 1 -- the same status a real violation uses -- so an uncaught crash would be
indistinguishable from a finding, and a witness that only checks "nonzero means
caught" would count the gate's own brokenness as detection. That happened for
real while this gate was being written (a `NameError` inside face 2), which is why
`zero_derived_gate_test.py` asserts *classification*, not just a nonzero exit.

### What this gate does NOT do

It does not scan for wording and it does not replace the ADR-12 faces. It also
does not add, remove or rename a scan face: the manifest fixes that list at three
and refuses to run if it drifts, so adding a fourth from here would be surface
drift. ADR-12 catches a forbidden **word** reaching the bundle; this gate catches
a forbidden **thing** being permitted by a bundle artefact, even when every word
in it is innocuous. Neither subsumes the other.

## S1-6 open questions (registered, not decided here)

Two questions were surfaced by this work and are **not** resolved by it. They are
recorded so that the next reader does not mistake silence for a ruling.

- **`/receipt/list` and `/receipt/detail` did not exist in the contract.** The
  original allow-list cited both. The PRD records that the customer's
  acknowledgement is delivered by **WeChat subscription message**, and the G5
  receipt state (`已推送` / `未授权（转线下）` / `推送失败`) is ruled visible **on
  the therapist surface only, never to the client**. So nothing in the frozen
  contract answers these two paths. Whether they were invented or whether the
  contract is **missing a client acknowledgement endpoint** is a question for the
  contract owner and product co-sign -- this gate removed them from the
  allow-list and records the gap rather than ruling on it.
- **`CLIENT_BAND_DISPLAY_DAYS = 14` is a hard-coded display window.** Contract E5
  rules that `N` must be the **runtime-probed** value and that no constant number
  of days may be baked in. Whether a purely *display* window is covered by that
  rule or is a separate thing is unruled. Recorded, not decided.

## S1-8: the contract conformance gate (a third gate, not a wider scan face)

### Three gates, three different questions

Each gate asks a question the others structurally cannot:

| gate | question | blind spot it exists to close |
|---|---|---|
| ADR-12 (`scan_compliance.py`) | did a forbidden **word** reach the bundle? | cannot see a wrong *claim* built from innocuous words |
| S1-6 (`client-zero-derived-gate.py`) | is every path the bundle says it may call one the contract **permits**? | aggregates roles **per path**, so cannot see a false **citation** on a path that is legitimately callable |
| **S1-8 (`contract-conformance-gate.py`)** | is every **fact** the bundle restates actually **true**? | — |

### Why the third gate had to exist

Both earlier gates judge the bundle against **hand-copies of contract facts**. A
hand-copy can rot independently of the thing it copies, and nothing was comparing
the two. This is the same failure mode three times on this project:

1. the allow-list (`clientPaths.js`) -- fixed by S1-6;
2. S1-6's own wordlist additions -- seeded from the **already-drifted** matrix
   list, so the gate built to catch hand-copies was itself fed by one;
3. the visibility matrix's group ④ field list.

On its **first run** this gate reported three real defects, none of which any
earlier gate could see:

- `/customers/{id}/visits` cited **D1**, the POST, whose `x-callable-roles` is
  `[therapist, meridian, admin]` -- no `client`. The *entry* was right (the path is
  client-callable through **D2**, the GET); the *citation* was false. S1-6 verified
  that entry as correct.
- group ④ listed `adherence_dimension_score` and `a3`, which appear **zero times**
  in any contract schema. The upstream semantic source uses `a3_applicable` /
  `a3_value` verbatim.

### The faces

| face | assertion | violation kinds |
|---|---|---|
| FACE 1 `path-citations-are-true` | each of the 13 allow-list entries must cite a row that is **on that path** and whose operation allows `client` | `citation-missing`, `row-not-in-contract`, `row-not-on-this-path`, `row-excludes-client` |
| FACE 2 `error-copy-codes-are-real` | every code key in the client copy table must be one of the contract's 12 codes | `code-not-in-contract` |
| FACE 3 `client-enum-namespace-isolated` | the client `client_sync_state` enum must not contain a value that exists **only** in the internal `gap_reason` enum | `internal-value-in-client-namespace`, `value-not-in-contract-enum` |
| FACE 4 `no-client-invisible-field-names` | none of the **19** contract fields whose `x-visible-to` omits `client` may be named in the bundle | `client-invisible-field-name` |
| FACE 5 `matrix-field-lists-agree` | the matrix's ③④ field lists must match the contract's `x-field-group` **both ways** | `phantom-field`, `matrix-omits-contract-field`, `group-absent-in-contract` |
| FACE 6 `gate-self-check` | inputs non-empty, `TEXT_SUFFIXES` agrees with `scan_compliance.py`, verified artifacts present | exit 2 |

**FACE 4 is a superset of S1-6 FACE 1, and that is measured, not asserted.** S1-6
scans the 6 names the matrix lists for ③④. FACE 4 scans all 19 client-invisible
fields, of which the 13 outside ③④ (`refund_visibility`, `staff_id`,
`store_scope`, `threshold_version`, `evidence_snapshot` and others) had never been
scanned by anything. Witness **W4** injects `refundVisibility` and **runs the S1-6
gate on the same bundle** to prove S1-6 stays silent -- turning "this is a
superset" from a design statement into a measurement.

### FACE 5 is bidirectional, and why

An earlier version reported `contract_has_but_matrix_lacks` as **information**,
reasoning "the matrix may be coarser than the contract." On the safety axis that
reasoning is backwards: **the matrix is an INPUT to S1-6**, which builds its
forbidden-field set from these very lists. If the matrix drops `as_value`, S1-6
does not report a narrower check -- it **keeps printing PASS while guarding one
field less**, and nothing downstream can notice. Extra names over-guard and are
harmless; **missing names silently disable protection**. Witness **W5b** empties
the ③④ lists and requires a violation; before the fix that run exited **0**.

Groups ①② keep a weaker rule on purpose: they use **conceptual** names, not field
names (①'s six names are all values of the contract's `metric` enum; ②'s
`captured_days` / `link_state` are neither properties nor enum values). A
field-name rule applied there would accuse correct content, and **a gate that
fails on correct content gets switched off**. So ①② only require a name to be
*grounded* in the contract, and the rest is surfaced as information for the
contract owner.

### A real YAML parser, or nothing

Two hand-rolled regex probes were run against the same contract file while
building this gate and reported **42** and then **2** fields (block-layout
sensitive). A probe that disagrees with itself cannot underpin a gate. So PyYAML
is a hard prerequisite: it is installed explicitly in CI, and its absence exits
**2** rather than degrading to regexes. Witness **W8** hides PyYAML behind a
shadow module and requires exactly that.

### The dialect blind spot both declaration gates had

Contract fields are snake_case; the bundle is JavaScript, where the natural way to
carry one of those names is camelCase. Measured **before** the fix:

```
refund_visibility   -> caught          effect_verdict  -> caught
refund-visibility   -> caught          effectVerdict   -> [S1-6-GATE] PASS    <-- not caught
refundVisibility    -> NOT caught      <-- not caught
```

Both declaration gates were blind in the dialect most likely to ship. ADR-12's
scanner already split camelCase humps (`_CAMEL_RE`); only these two did not, so
the fix is an alignment with the existing correct semantics rather than a new
invention. S1-6 gained witness **W1b** to pin the camelCase form.

### Exit codes, and the trap they close

`0 pass / 1 violation / 2 misconfigured / 3 internal`.

The fourth code is necessary, and S1-8 found **two independent** ways the trap
fires. Python exits with status **1** on an uncaught exception -- the same status
a violation uses -- so any "non-zero therefore caught" assertion records the
gate's own crash as a successful detection. Both were real here:

- `yaml.YAMLError` is **not** a subclass of `ValueError`, so catching only the
  latter let a `ParserError` escape as **3** -- the exact case the branch exists to
  classify as **2**.
- FACE 4's finding omitted a `kind` key while the report printer indexed `v["kind"]`
  directly, so the first genuine FACE 4 hit raised `KeyError` -- and the traceback
  exited 1.

Fixes: `load_yaml` maps `yaml.YAMLError -> GateConfigError` symmetrically with
`load_json`; the printer uses `v.get("kind", "unclassified")` (a finding that
forgot its kind must still be **reported**); findings carry `file:line` so they
are locatable. The witness's own two vacuous guards -- matching `violations=0` and
the face **names** against the *whole* output -- now read the **verdict line** and
the **VIOLATIONS section** respectively.

### Leftover probes: the suite that could not see its own mess

A witness injects real files into `client-package/`, then removes them in a
`finally`. `finally` does not run on `SIGKILL`, a CI timeout, or a cancelled job,
so an interrupted run can strand a probe. A stranded probe is not merely untidy:
it sits **inside the scanned surface**, so the ADR-12 gate reads it as a genuine
violation. Measured: plant `__inject_refund_event.js`, and `mvn validate` reports
`[COMPLIANCE-GATE] FAIL violations=1` -- with a message that blames the
repository, not the leftover.

**`mvn validate` does not clean up a leftover** -- it only fails on one. The
leftover is removed by the **witness suites**, at their start-up reclaim step.
So the recovery path is: run the witness, then re-run the build. This is worth
stating plainly because it means a stranded probe is not self-limiting: `mvn
validate` will keep failing, identically, until a witness reclaims the file. The
measured proof is a two-step sequence -- plant the probe, `mvn validate` FAILs;
run `compliance_injection_test.py` (prints `reclaimed 1 leftover`), `mvn
validate` PASSes. CI runs the witnesses for exactly this reason, and additionally
because they are the only thing asserting the gates are still load-bearing.

The first fix added a start-up `reclaim_own_leftovers()` step plus an end-of-run
guard, but both recognised probes by the **prefix pair** `("__inject", "_probe")`.
That pair is unsound across suites: the ADR-12 witness's scope probe is
`client-package/constants/__scope_probe_client.js` (prefix `__scope`). A stranded
copy was invisible to both the reclaim step and the guard of the S1-6 and S1-8
suites -- it survived their runs and kept failing the next `mvn validate`, which
is how the intermittent red was finally traced. A prefix allow-list cannot
recognise a name it was never told about.

The rule is now **"the filename contains `probe`"** (`_is_probe_name`), applied
inside the scanned `client-package` only -- not the whole tree, where a legitimate
fixture such as `dy-audit/.../probe_owner_revoke.sql` would be misreported and get
the guard switched off. Each suite also pins the property in an assertion, so a
future narrowing fails there instead of showing up as a flaky gate: ADR-12 **T11**,
S1-6 **W9**, S1-8 **W10**. Reverse-verified: restoring the old recogniser turns
`W9`/`W10` red and names `__scope_probe_client.js`.

Self-healing applies to the suite's **own** residue only -- a file whose bytes
match a registered injection, or one on a path the suite itself writes. A foreign
file at such a path is reported, never silently erased.

### What this gate does NOT do

It does not scan for wording, does not replace the ADR-12 faces, and does not
widen S1-6 (FACE 4 overlaps it deliberately and the overlap is measured). It also
does not rule on naming policy: the ①② conceptual names, the `generator-matrix`
DEAD rows and the uncovered error codes are **registered open questions**, not
findings.

## S1-8 open questions (registered, not decided here)

- **Matrix ①② use conceptual names.** Whether they should be rewritten as
  verbatim field names is a naming-policy ruling for the contract owner; the
  matrix `_note` wording also needs review alongside it.
- **~~`generator-matrix.yaml` has DEAD rows per end~~ -- RESOLVED (2026-09-28,
  see S1-9 below).** The registered counts (client-mp 21, therapist-app 15,
  admin-web 6) were reproduced mechanically under one exact definition -- *a
  row whose every operation is role-disjoint from that end's token-roles and
  which the end does not declare in `exclude-contract-rows`* -- which is what
  makes the reconstruction trustworthy. Every row is now either deleted (the one
  genuine no-op declaration) or registered with a machine-derived reason, and a
  gate holds both.
- **`client_error_codes` covers 9 of the contract's 12 codes.** Most of the
  remainder are codes deliberately not rendered to the client, so FACE 2 checks
  only one direction (a code in the copy must really exist). Coverage is a product
  copy question, not a gate duty.

## S1-9: the SDK surface gate (a fourth gate, not a wider scan face)

The three gates above ask, in order: did a forbidden WORD reach the bundle
(ADR-12); is every path the bundle CLAIMS it may call really permitted (S1-6);
are the contract FACTS the bundle replicates true (S1-8). None of them looked at
`contract/sdk/<end>/`, so the **generated SDKs were unchecked artifacts**. That
is the same blind spot that hid defect #45 in the hand-written allow-list for
months, applied to the machine-written surface.

`sdk-surface-gate.py` therefore checks the products, per end:

| Face | Question | Direction |
|---|---|---|
| FACE 1 | is every declared end present and non-empty? | else exit 2 |
| FACE 2 | does the SDK expose anything this end may NOT call? | **VIOLATION** -- a client bundle carrying an admin path is a shipped capability leak |
| FACE 3 | is everything the end MAY call actually exposed? | INFORMATION, printed in full |
| FACE 4 | does every `exclude-contract-rows` entry bite? | **VIOLATION** -- a declaration nothing consumes is not a control |
| FACE 5 | did every end actually contribute inputs? | else exit 2 |

FACE 3 is subset rather than equality for the reason recorded verbatim in
`client-zero-derived-gate.py` (an equality gate that cries wolf on correct
content gets switched off) -- but an unprinted subset check is
indistinguishable from no check at all, so the count is in the audit and every
gap is listed. On the current tree the count is `operations_generated=83 =
operations_in_scope=83`, i.e. **zero gaps and zero overruns**.

FACE 4 carries one exemption that is not a loophole. A row the contract marks
`x-client-forbidden` MUST appear in client-mp's `exclude-contract-rows`,
because the pipeline's own `check_matrix_consistency()` fails when it does not.
Such an entry is a MIRROR of the contract, consumed by a live check in the
other direction -- deleting it turns a different gate red. Those are counted as
`mirror_exclusions` and printed, never skipped silently.

It reuses the S1-8 contract reader by import rather than parsing the contract a
second time: two readers would eventually disagree, and the disagreement would
appear as a gate that passes on one reading and fails on the other.

```bash
python compliance/sdk-surface-gate.py --repo-root .
python compliance/tests/sdk_surface_gate_test.py   # 7 cases, incl. the zip-truncation trap
```

The witness's X5 matters most: deleting one method literal makes paths and
methods stop pairing 1:1, and the gate must REFUSE (exit 2) instead of
zip-and-truncate and reporting PASS on a surface it only half read. Every
injection runs on a scratch copy of the inputs, so a crash between edit and
restore can never leave the tree holding an injected defect.

## Handover: what is still open

- **`client-package/` is a scaffold**, not the generated mini-program bundle.
  Wiring the real build output into this path is the remaining integration step.
- **Branch protection.** The workflow fails the job, but a GitHub workflow cannot
  block a merge on its own. The job **MUST** be added as a required status check
  in the repository settings; until that is done the gate cannot block a merge.
- **The repository is under git now** (as of 2026-09-30), but it has **no remote
  yet**, so the workflow still cannot run. Two things remain, both manual:
  (1) push to a remote that runs GitHub Actions —
  the repository root is **`product-strategy/`**, not `skeleton/`, and
  `.github/workflows/` lives at that root (it used to sit under `skeleton/.github/`,
  where GitHub Actions never loads it);
  (2) add the job as a required status check (see the previous bullet).
  Until (1) happens the workflow is inert — being committed locally is not enough.
  See `docs/CI-ENABLEMENT.md` §0.1 for the exact remaining steps.

## P0 blocker before go-live: name the real owners

`owners.csv` currently holds placeholder roles (`role:dev-compliance-lead`,
`role:client-package-owner`) with `tbd@diaoyuanyun.invalid`. The owner-must-be-non-empty
discipline is mechanically enforced, but a placeholder owner still means nobody is
accountable. Real named owners MUST be written into owners.csv before launch;
until then this gate is a formality.

What still works while the owner is a placeholder: the build fails if the owner
field is emptied, if a scan face is renamed, removed or added, if a channel goes
missing, or if a wordlist becomes unreadable. What does **not** work: nobody is
responsible for deciding that a new term belongs in a wordlist or that an existing
one has been retired. The mechanism is intact; the maintenance is unowned. That is
precisely the decay mode the ruling was written about, which is why it is a blocker
and not a nice-to-have.

**This blocker is now mechanically detectable, not merely documented.** The gate
distinguishes a NAMED owner from a PLACEHOLDER one, by two fixed rules: an
`owner_id` beginning with `role:` is a placeholder, and a blank / `tbd` / `invalid`
`contact` is a placeholder. Everything else is named. The verdict line reports
`owners_named=<named>/<total>` alongside the unchanged `owners_ok`, and the JSON
report carries an `owner_readiness` block.

The default stays advisory: without a switch the placeholder count is printed as a
`[NOTE]` and the build still passes, so landing this check did not redden every
existing build. The go-live pipeline makes it load-bearing with

```bash
python compliance/scan_compliance.py --repo-root . --require-named-owners
```

which fails with exit 1 and prints the roster of still-placeholder subjects, so the
failure says **which** rows to fill in rather than only how many. Naming the real
owners is a decision for the R&D side; `owners.csv` is deliberately left untouched
by the mechanism and by its tests.

### How to trigger it from the build, and what a red means

The switch is wired into `skeleton/pom.xml` as the **`go-live`** Maven profile. Use
the profile for a release decision, not for day-to-day work:

| command | profile | what it is for | expected result today |
|---|---|---|---|
| `mvn validate` / `mvn test` / `mvn clean package` | none | everyday build. Owner readiness is advisory: the gate prints `owners_named=0/4` and a `[NOTE]`, and the build stays green | **exit 0** |
| `mvn -Pgo-live validate` (or any later phase) | `go-live` | the release gate. `--require-named-owners` is passed to the scanner | **exit 1 -- correct, see below** |

The profile is not on by default on purpose. `owners.csv` is wholly placeholder
today, so enabling it everywhere would turn every build in the repository red on
the day it landed -- and a gate that is red from birth gets switched off, taking
the three ADR-12 scan faces down with it. Everyday builds stay advisory; the
release gate blocks.

**A red `-Pgo-live` build is the gate working, not a broken build.** It says
"the named owner has not been written yet, so this must not ship", which is
exactly the P0 blocker recorded above. The fix is for the R&D side to write real
names into `compliance/owners.csv`; it is **not** to add a skip flag to the
profile. The failure is attributable and readable -- it prints an
`OWNER READINESS` block naming every placeholder subject with its current
`owner_id`, and closes with `ACTION REQUIRED: the named owner MUST be written into
owners.csv by the R&D side`.

The scan face audit in that same run still shows `hits=0`: the three ADR-12 content
rules are intact, and the only thing failing is owner readiness. The verdict line
prints both fields so the two are never confused --
`owners_ok=yes` (nobody's owner field is blank) and `owners_named=0/4` (nobody
accountable has been named).

Only after `owners.csv` holds real names does `-Pgo-live` go green, and only then
is the blocker actually closed.

## Decision on record: the medical-claim term group

The second group of scan face 2 (`诊断` `医疗` `医学` `疗效` `治疗` `病症`) is
**retained, not narrowed**. Basis: PRD §2.6 判读口径 **W-3** already rules the
client-side wording for the band view -- the client must show (1) `手环数据为参考之一，不用于单方判定`
and (2) `数据同步时间`. That ruled wording contains **neither** `医疗` **nor** `诊断`.
PRD Non-goals / §2.2 合规边界 (`不出具医疗诊断结论`) are product-positioning
statements about the system as a whole, not client-package copy, and must never be
rendered inside the mini-program bundle. Any occurrence of `诊断` / `医疗` inside
`client-package/` is therefore a violation. The group stays as-is; see the note in
`wordlists/scan2_negative.words`.