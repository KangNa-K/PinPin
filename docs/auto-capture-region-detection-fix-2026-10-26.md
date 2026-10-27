# Automatic image absorption fix (2026-10-26)

## Cause

The long press was reaching the automatic screenshot path. The failure happened afterward: `ImageRegionDetector` only accepted strong texture/color boundaries, and rejected low-frequency or mostly-flat artwork after finding its outer rectangle. That returned `region_not_found`, so the service showed the manual selection fallback instead of cropping and absorbing the image.

## Fix

- Keep the conservative boundary and seed checks, while allowing a varied palette to establish that smooth artwork inside a detected rectangle is real content.
- Permit flat illustrations with at least two distinct, substantial colors instead of requiring photographic texture.
- Add unit cases for smooth gradient and flat illustration artwork.
- Update the production-service instrumentation test to capture a smooth illustration through a real overlay screenshot, assert it reports a non-full-screen centered crop, asserts no manual selection overlay appears, and waits for the image to load into the ball.

## Verification

- `:app:testDebugUnitTest`: 73 tests passed.
- `AutoCarryServiceTest.heldBallCapturesFeedArtworkWithoutManualSelection`: passed on the Android 15 API 35 emulator; production screenshot, crop bounds, no-fallback state, and absorbed-ball state were verified.
- Full instrumentation run: 75 passed, 1 skipped, 1 failed. The unrelated existing `BallWindowGestureTest.transparentBallKeepsGestureWhileOverlayIsAdded` has a one-pixel selection-rectangle mismatch (`bottom=861` expected, `bottom=860` actual); it also failed when rerun alone.
- Samsung tablet, One UI 8.5, and Xiaohongshu were not connected; verify the real device and target page before treating device-specific compatibility as confirmed.

## Follow-up: excess crop area and successful-drop parking

The previous verification above describes the earlier smooth-artwork fix. The
following checks cover the subsequent excess-area report.

- The detector's strongest-edge search could skip a nearby artwork edge and
  include stronger neighboring headers, captions, or action bars.
- Keep the initial rectangle estimate, then refine using probes across nearly
  the whole estimated card and the nearest convincing boundary. A sustained
  blank separator matching the surrounding surface stops the crop before text.
- On a successful native drag result, clear the carried image and run the
  existing nearest-edge snap animation. Rejected and cancelled drops retain the
  image and do not change the floating ball's position.
- Added unit regressions for stronger neighboring header/footer edges and a
  white illustration above captions and an action bar.
- Strengthened the real screenshot service fixture with black neighboring
  header/footer regions and assertions on the actual crop top and bottom.
- `:app:testDebugUnitTest`: 75 tests, zero failures.
- `:app:lintDebug` and `:app:assembleRelease`: passed.
- The strengthened `AutoCarryServiceTest` passed on the Android 15 emulator.
- The final combined service/drag instrumentation run reported `OK (8 tests)`:
  seven executed successfully and the opt-in external Magpie test was skipped.
  Checks include successful-drop edge parking, cancelled/rejected position
  preservation, and three consecutive capture/drop/rearm cycles.
- Samsung is connected, but this follow-up build has not been installed or
  accepted against the actual Xiaohongshu page or Magpie. Pixel-only detection
  remains heuristic where white artwork and white page background have no
  visible boundary. No public release was performed for this follow-up.
