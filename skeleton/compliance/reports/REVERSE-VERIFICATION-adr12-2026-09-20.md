# ADR-12 build-time compliance scan -- reverse verification

- run at: 2026-09-20 23:24:47
- gate: `compliance/scan_compliance.py`
- scan faces under test (ADR-12, immutable): `scan1_refund`, `scan2_negative`, `scan3_derived`
- driving command: `python compliance/scan_compliance.py --repo-root .`
- assertions executed by the injection harness: `compliance/tests/compliance_injection_test.py`

## Verdict

| injection | expected failure point | actual failure assertion | exit | result |
|---|---|---|---|---|
| `scan1_refund` -> `client-package/analytics/__inject_refund_event.js` | scan face 1 must report the injected analytics event name as a violation of the refund-wording rule and exit 1 | see block below | 1 | **CAUGHT** |
| `scan2_negative` -> `client-package/i18n/__inject_negative_copy.json` | scan face 2 must report the injected negative-counting copy as a violation and exit 1 | see block below | 1 | **CAUGHT** |
| `scan3_derived` -> `client-package/enums/__inject_derived_field.js` | scan face 3 must report the injected derived field name as a violation and exit 1 | see block below | 1 | **CAUGHT** |

## Injection detail

### scan1_refund

**Injected content** (`client-package/analytics/__inject_refund_event.js`)

```javascript
// injection for scan face 1: the analytics event name -- the channel that survives a UI-only review.
'use strict';
const EVENTS = Object.freeze({ MP_REFUND_ENTRY_TAP: 'mp_refund_entry_tap' });
module.exports = { EVENTS };
```

**Expected failure point**

scan face 1 must report the injected analytics event name as a violation of the refund-wording rule and exit 1

**Actual failure assertion text (verbatim from the gate)**

```text
-------------------------------------------------------------------------------
  [scan1_refund] term='refund'
      at client-package\analytics\__inject_refund_event.js:3
      source: const EVENTS = Object.freeze({ MP_REFUND_ENTRY_TAP: 'mp_refund_entry_tap' });
[COMPLIANCE-GATE] FAIL  faces=3  violations=1  owners_ok=yes
```

**Exit code**: 1 -- **CAUGHT**

### scan2_negative

**Injected content** (`client-package/i18n/__inject_negative_copy.json`)

```javascript
{
  "_injection": "scan face 2",
  "band_gap": "\u672a\u4f69\u6234\u7f3a\u5931 12 \u5929"
}
```

**Expected failure point**

scan face 2 must report the injected negative-counting copy as a violation and exit 1

**Actual failure assertion text (verbatim from the gate)**

```text
-------------------------------------------------------------------------------
  [scan2_negative] term='缺失'
      at client-package\i18n\__inject_negative_copy.json:3
      source: "band_gap": "\u672a\u4f69\u6234\u7f3a\u5931 12 \u5929"
  [scan2_negative] term='未佩戴'
      at client-package\i18n\__inject_negative_copy.json:3
      source: "band_gap": "\u672a\u4f69\u6234\u7f3a\u5931 12 \u5929"
[COMPLIANCE-GATE] FAIL  faces=3  violations=2  owners_ok=yes
```

**Exit code**: 1 -- **CAUGHT**

### scan3_derived

**Injected content** (`client-package/enums/__inject_derived_field.js`)

```javascript
// injection for scan face 3: a derived field name reaching the client.
'use strict';
const BAND_FIELDS = ['synced_at_day', 'gap_reason'];
module.exports = { BAND_FIELDS };
```

**Expected failure point**

scan face 3 must report the injected derived field name as a violation and exit 1

**Actual failure assertion text (verbatim from the gate)**

```text
-------------------------------------------------------------------------------
  [scan3_derived] term='gap_reason'
      at client-package\enums\__inject_derived_field.js:3
      source: const BAND_FIELDS = ['synced_at_day', 'gap_reason'];
[COMPLIANCE-GATE] FAIL  faces=3  violations=1  owners_ok=yes
```

**Exit code**: 1 -- **CAUGHT**

## Restoration

After the injections were removed, the gate was re-run:

```text
exit code: 0
[COMPLIANCE-GATE] PASS  faces=3  violations=0  owners_ok=yes
```

## Harness self-test

The full assertion set (including owner discipline, scan-surface mutation, channel parity and fail-closed encoding) lives in `compliance/tests/compliance_injection_test.py`; run it with

```bash
python compliance/tests/compliance_injection_test.py
```
