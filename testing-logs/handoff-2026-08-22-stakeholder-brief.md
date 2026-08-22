# Handoff — stakeholder brief on the first field test

**Purpose:** everything needed to write a stakeholder-facing report on PhotoFlow Mobile's first
real-workflow field test. Self-contained: the figures below are all here, so this file alone is
enough to work from. Attach the two session reports for supporting detail.

---

## For the assistant picking this up

You are helping write a **stakeholder update**, not an engineering document. The audience is
interested in whether the tool works in the field, what was at risk, and what happens next. They
do not need internal identifiers (FR-1, WI-3, `captureMutex`) — translate those into plain
outcomes. Ask what format is wanted (email, slide outline, one-pager) before drafting.

**Two things to get right:**

1. **Do not overstate readiness.** The test succeeded, and the data supports it. But upload
   failure handling has never once been exercised on these devices, and two defensive tethering
   paths have never executed. A report implying "fully validated" would be wrong.
2. **The historical evidence is the strongest part of the story.** Three bugs were fixed
   pre-emptively; two of them are visible in the devices' own past data, which shows they were
   real rather than theoretical. That is more persuasive than "we found and fixed some bugs."

---

## What happened

PhotoFlow Mobile is a landscape-only Android app for live photo operations in the field:
barcode-based sessions, capture from either the device camera or a tethered DSLR, and background
upload to an FTP server.

Before the first real-workflow test, a pre-deployment code review was run over the whole app. It
found ten issues, three of which could lose or corrupt customer photos. All ten were fixed,
unit-tested and deployed to both handsets before the test.

**The field test ran on 2026-08-20**, across two devices and both capture modes:

| Device | Mode | Camera |
|---|---|---|
| Motorola G 2025 | Tethered DSLR | Canon EOS Rebel T7 over USB |
| Samsung Galaxy S23 | Native camera | device camera |

The operator reported a positive result. The device databases were captured afterwards and
corroborate it.

## Results

| Measure | Result |
|---|---|
| Sessions created | 18 |
| Photos captured | 26 (17 tethered, 9 native) |
| Successfully uploaded | **26 of 26 (100%)** |
| Upload failures | 0 |
| Upload retries needed | 0 |
| Photos filed to the wrong session | **0** |
| Duplicate or missing sequence numbers | **0** |

## The three risks that were fixed, and why they were real

**1. Photos could be filed to the wrong customer.** If the operator opened an earlier session to
review its photos, subsequent shots from the tethered camera were silently saved into *that*
session — wrong customer, wrong filename, wrong upload destination — while the screen still
displayed the current session as active. Nothing warned anyone.

Fixed by making the two capture paths agree, and by adding an on-screen banner whenever capture
has moved to an earlier session. Across all 26 photos in the field test, **every photo was filed
to the correct session**, including on the Samsung where the operator legitimately moved between
two sessions and back again — each continuing its own numbering.

**2. A failed upload retried forever.** A photo that could not upload was re-sent every four
seconds indefinitely. The devices' own history shows this happening: on two occasions, images
retried **over 700 times — roughly 48 minutes of continuously re-uploading full-size photos** to
a server that was not answering. They did eventually succeed, but at that cost, and with no way
to stop it.

Fixed by bounding automatic retries (now 3 by default, operator-configurable), while leaving the
underlying system's own network-aware retry in place for genuine outages.

**3. Interrupted transfers could produce corrupted photos.** If the USB cable was disturbed
mid-transfer, a partially-received image was saved and uploaded as though complete — no error, no
indication, a corrupt file that looked normal. Now rejected and re-fetched instead.

## Also addressed

- **Credentials.** Exported settings files previously contained FTP passwords in plain text and
  were written to the phone's shared Downloads folder. Exports now carry configuration only.
- **A silent data-loss case.** Photos taken while the USB cable was unplugged were never imported
  and nothing said so. The app now warns that shots are still on the camera card.
- Several smaller reliability fixes to tethering recovery and transfer-queue accuracy.

## What is not yet proven

Worth stating plainly in the report:

- **Upload failure handling has never been exercised.** Across 192 photos on these devices, not
  one has ever been recorded as failed. Every upload has succeeded. So the error display and
  recovery flow, while implemented, have not been seen under real conditions. A deliberate test
  against an unreachable server is the next planned step.
- **Two tethering recovery paths have not executed.** They only run when something goes wrong,
  and nothing has yet gone wrong in the right way to trigger them.
- Testing so far is one operator, two devices, one camera model, one venue.

## What happens next

1. **On-device logging** — built and ready, being installed on the handsets next. Currently, if
   something goes wrong in the field, the diagnostic record is lost within minutes; this makes
   every future session self-documenting.
2. **Deliberate failure testing** — running the app against an unreachable server to confirm
   photos recover cleanly.
3. Further field sessions across more varied conditions.

---

## Files to attach

**Include these two — they are the primary evidence:**

- `testing-logs/sessions/2026-08-20-motog-tethered.md` — tethered session: 14 sessions, 17 photos,
  per-photo table and integrity checks
- `testing-logs/sessions/2026-08-20-sam-native.md` — native session: 9 photos, and the clearest
  example of an operator moving between sessions and back

**Optional, for depth:**

- `TESTING.md` — see "What to test next, and why": the evidence behind the next test priorities,
  including the retry-count figures quoted above
- `testing-logs/README.md` — "Baseline findings": the historical defects found in the devices' own
  data, with dates and figures

**Probably too technical for stakeholders:**

- `.claude/prompts/field-readiness-pass.md` — the full ten-item engineering specification. Useful
  if anyone asks for detail on a specific fix; not for the main report.

## Figures, if you need to quote them

- 26 photos, 18 sessions, 2026-08-20; 100% uploaded, 0 failures, 0 retries
- 0 misfiled photos, 0 duplicate or missing sequence numbers, out of 26
- Historical retry storm: 705–719 retries (2026-05-02) and 517–605 (2026-07-04) ≈ 48 minutes
- Historical duplicate: two photos taken 1 second apart overwrote each other (2026-05-01)
- 192 photos across both devices all-time; 0 have ever failed to upload
- Tethered capture pace: median 1.8 s between shots. Native: median 13.8 s
- Typical session size: 1–3 photos
