# White-margin artwork crop correction

## Live evidence

The Samsung tablet showed Xiaohongshu in a floating window over Magpie.
Its accessibility tree reported the reference image as an ImageView with screen
bounds [55,345]-[733,1249]. The title began at y=1288. Thus the image has a
well-defined semantic boundary even though its white background blends into
the page. Pixel-only detection lost the top margin and included the caption.

## Changes

- Enable interactive-window retrieval and disclose its limited use in the
  service description and README.
- Immediately before automatic screenshot capture, choose the top application
  window under the seed. Query visible ImageView bounds containing the seed,
  reject small icons, and clip to the visible window. Do not read node text.
- Prefer those exact screen coordinates over downsampled pixel segmentation.
  Keep the existing pixel fallback for apps without exposed image widgets.
- Limit traversal depth and count; do not search lower application windows
  through the top window.
- Log only the crop coordinate/source diagnostics, not page content.

## Verification

- 78 unit tests passed, including the actual observed Xiaohongshu geometry,
  white-margin seed, caption exclusion, clipping, and small-icon rejection.
- Instrumentation: eight executed tests passed; the opt-in external Magpie
  test was skipped (runner summary: OK, 9 tests).
- ImageWidgetCaptureTest uses the user-supplied 480x642 reference PNG in a real
  ImageView above a caption. The production service captures and saves exactly
  that rectangle; output dimensions and the original white margin are checked.
- Pixel-fallback screenshot, continuous drag, clearing, edge parking and repeat
  capture regressions also passed on the Android 15 emulator.
- Final unit tests, lintDebug and signed assembleRelease passed. The final
  rebuild adds only crop diagnostics relative to the instrumented production
  source.
- The Samsung tablet disconnected after the live page inspection. This fix
  has not been installed or end-to-end accepted on the actual Xiaohongshu page.
  No public release or version bump was performed.

## Limits

Only visible screen pixels can be captured. Image widgets with internal
letterboxing may include that padding. Apps without image accessibility nodes
still use heuristic pixel detection. If the upgraded accessibility service
does not refresh its capabilities, toggle PinPin's service off and on.
