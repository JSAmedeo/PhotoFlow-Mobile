# Background (reused in every handoff bundle)

<!--
  Stable narrative, copied into each bundle's 01-brief.md by make_handoff.py.
  Session-specific figures are generated and sit above this.

  Hand-maintained. Revisit "What is not yet proven" and "What happens next" after each
  session — those two sections go stale fastest, and a brief that claims something is
  untested after it has been tested is worse than no brief.
-->

## What PhotoFlow Mobile is

A landscape-only Android app for live photo operations in the field: barcode-based sessions,
capture from either the device camera or a tethered DSLR, and background upload to an FTP server.
It is an operational console for photographers working a venue, not a consumer photo app.

## What was fixed before field testing began

A pre-deployment code review covered the whole app. It found ten issues; three could lose or
corrupt customer photos. All ten were fixed, unit-tested and deployed before the first session.

Two of the three are visible in the devices' **own historical data**, which is what shows they
were real problems rather than theoretical ones.

**1. Photos could be filed to the wrong customer.** If the operator opened an earlier session to
review its photos, subsequent shots from the tethered camera were silently saved into *that*
session — wrong customer, wrong filename, wrong upload destination — while the screen still
showed the current session as active. Nothing warned anyone.

Fixed by making both capture paths agree on one session, and adding an on-screen banner whenever
capture has moved to an earlier session. This also turned the flaw into a feature: adding a photo
to an earlier session is now something an operator can do deliberately.

**2. A failed upload retried forever.** A photo that could not upload was re-sent every four
seconds indefinitely. The devices' history shows it happening: on two occasions images retried
**over 700 times — roughly 48 minutes of continuously re-uploading full-size photos** to a server
that was not answering. They did eventually succeed, but at that cost and with no way to stop it.

Fixed by bounding automatic retries (3 by default, operator-configurable) while leaving the
underlying network-aware retry in place for genuine outages.

**3. Interrupted transfers could produce corrupted photos.** If the USB cable was disturbed
mid-transfer, a partially received image was saved and uploaded as though complete — no error, no
indication, a corrupt file that looked entirely normal. Now rejected and re-fetched.

### Also addressed

- **Credentials.** Exported settings files contained FTP passwords in plain text and were written
  to the phone's shared Downloads folder. Exports now carry configuration only.
- **A silent data-loss case.** Photos taken while the USB cable was unplugged were never imported
  and nothing said so. The app now warns that those shots are still on the camera card.
- Smaller reliability fixes to tethering recovery and transfer-queue accuracy.

## What is not yet proven

State this plainly in any report:

- **Upload failure handling has never been exercised.** No photo on either device has ever been
  recorded as failed. So the error display and recovery flow, while implemented, have not been
  seen under real conditions. A deliberate test against an unreachable server is planned.
- **Two tethering recovery paths have never executed.** They run only when something goes wrong,
  and nothing has yet gone wrong in the specific way that triggers them.
- Testing so far is one operator, two devices, one camera model.

## What happens next

1. **On-device logging** — built, being installed on the handsets. Today, if something goes wrong
   in the field the diagnostic record is lost within minutes; this makes each session
   self-documenting.
2. **Deliberate failure testing** — running against an unreachable server to confirm photos
   recover cleanly and the operator sees an accurate queue.
3. Further sessions across more varied conditions.
