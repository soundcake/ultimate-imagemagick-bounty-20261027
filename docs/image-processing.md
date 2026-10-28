# Image processing tool

`ImageProcessingTool` is discovered by the existing `UltimateTool` registry. It
uses ImageMagick 7 locally; install `magick` on the server host and put it on the
server's PATH. Alternatively set the server JVM property
`-Dultimate.imagemagick.binary=/absolute/path/to/magick`.

Create an existing workspace directly under `~/ultimate-managed-workspaces` and
an output directory inside it. Inputs and the optional watermark are relative
to that workspace. PNG, JPEG and WebP are recognized from their file headers;
SVG, PDF, URLs and arbitrary ImageMagick commands are not accepted.

Example tool arguments:

```json
{
  "workspacePath": "/home/user/ultimate-managed-workspaces/photos",
  "inputPaths": ["one.jpg", "two.png"],
  "outputDirectory": "out",
  "outputPrefix": "web",
  "width": 1200,
  "height": 1200,
  "crop": "",
  "brightness": 100,
  "saturation": 100,
  "hue": 100,
  "watermarkPath": "logo.png",
  "quality": 85
}
```

This creates `out/web-1.webp` and `out/web-2.webp`. Existing files are never
replaced. Leave `watermarkPath` empty to omit the watermark. The watermark is
composited at its original dimensions, ten pixels from the bottom and right.
Use a watermark sized to fit the processed image.

The processing order is EXIF orientation, optional crop (`WIDTHxHEIGHT+X+Y`),
aspect-preserving resize, brightness/saturation/hue adjustment, watermark, then
metadata removal and WebP encoding. Resize never enlarges an image. Use width
and height zero to preserve dimensions. All three color percentages use 100
for no change. Every output has metadata stripped, including EXIF. Animated or
multi-image inputs use their first frame.

Each call accepts 1–10 files, at most 25 MiB each, with resize/crop dimensions
up to 8192. Canonical input/output paths must remain within the selected
workspace. Inputs are copied into unique temporary storage before processing.
ImageMagick runs with a raster-only policy, disabled delegates/filters/indirect
file reads, resource limits and a 60-second batch deadline. Scratch files are
removed on success and error. Outputs are published only after the entire batch
has processed; publication failure rolls back outputs created by that call.

The tool returns a JSON string with `status`, `code`, `requestId`, `message`,
`outputs` and `recoveryErrors`. A fresh UUID identifies each invocation. Successful
outputs contain the published absolute paths; failed calls return an empty outputs
array. `code` is one of `OK`, `INVALID_REQUEST`, `BUSY`, `TIMEOUT`, `INTERRUPTED`,
`NATIVE_FAILED`, `IO_ERROR`, `PUBLICATION_FAILED` or `PROCESSING_FAILED`.
`recoveryErrors` separately reports any rollback/cleanup that could not complete.
Two calls may run concurrently per Spring tool instance; additional calls return
`BUSY` without starting a process. Slots are released after success, failure or
interruption. It does not send
images to an external service. Workspace access follows the server's existing
managed-workspace model.

## Validation

From `server/`, run `sh mvnw test` and `sh mvnw compile`. Native tests in
`ImageProcessingNativeTest` use the installed ImageMagick executable to check
JPEG/PNG conversion, dimensions, crop/color/watermark pixels, EXIF removal,
orientation and failed-batch cleanup. They are skipped if the executable is
unavailable; install ImageMagick to run those checks. Unit tests exercise path
containment, existing-output preservation, publication races, process deadlines,
invalid inputs and cleanup without needing ImageMagick.

## Reproducible review corpus

The tests generate their fixtures locally; no downloads or external AI provider
are required. Lossy WebP assertions use explicit channel tolerances.

| Fixture | Verified properties |
| --- | --- |
| 80×40 PNG / 30×90 JPEG | WebP headers, aspect ratio, bounded dimensions |
| Four-color quadrants | Crop origin and dimensions, WebP input and round trip |
| Red raster / blue watermark | Brightness, grayscale saturation, hue channel rotation, compositing pixels |
| Transparent PNG with embedded sRGB ICC | Alpha 0/128, RGB channel tolerances, removed ICC profile |
| JPEG with orientation 6 and EXIF artist | Rotated dimensions, removed EXIF, original bytes preserved |
| Truncated raster / unsupported SVG / oversized file | Rejection, no partial batch publication |
| 16385×1 PNG | Width policy rejection, unrelated artifact preserved |
| Simultaneous batches | Independent names, completed files, scratch removal |
| Spring registry and MethodToolCallbackProvider | Discovery, JSON argument binding, actual published path, same validation as direct invocation |

ICC profiles are stripped, not retained. The reference color fixture is sRGB;
this implementation does not perform explicit ICC conversion of arbitrary source
profiles to a chosen target profile. Animated inputs use their first frame.

The process environment is cleared before launch. It retains only the host PATH
and Windows SystemRoot when present, a fixed C locale, scratch-local HOME/config/
temporary paths, and the tool's ImageMagick policy/resource settings. Tests check
the effective environment, live-child termination on deadline, timeout recovery,
concurrent admission, cross-workspace path rejection, directory substitution,
publication rollback and replacement-scratch protection.

The existing registry passes tool arguments without a caller principal or
workspace ownership grant. Root containment is enforced here; authorization of
which managed workspace a caller may select needs the platform's trusted identity
and grant contract. File-key/path checks detect the tested replacements, but do
not provide an OS sandbox against a hostile local process continuously racing
ancestor directories. `finally` cleanup covers handled errors and interruption;
a killed JVM cannot run it. Recovery after JVM kill and descendants that escape
parent tracking require an external worker supervisor. These are architecture
integration points for the requested production boundary.

## Workload measurements

Run from `server/` with Java 21 and ImageMagick 7:

```sh
sh mvnw -B -ntp -Dtest=ImageProcessingNativeTest#reproducibleLocalWorkloadMeasurements test
```

The `IMAGE_BENCHMARK` JSON reports end-to-end first/warm 512×512 calls, a
4096×4096 call, a ten-image batch, ten repeated calls and two concurrent large
calls, all resized to 256×256. `processStartupVersionMs` measures executable
startup plus `-version`. First-call timing uses a new tool instance in an existing
JVM; it does not measure a cold application boot or guarantee cold filesystem
caches. Each process includes an OS `time` report when `/usr/bin/time` is present
(CPU user/system time and peak RSS), plus scratch bytes sampled every 5 ms.
Scratch sampling includes staged input and decoder cache and can miss short peaks.
The sampler and timing wrapper add overhead. Native RSS excludes the JVM.

Fixtures are solid colors and do not represent every compressed-image workload.
The script reports measurements without imposing hardware-dependent performance
thresholds. Target hardware, an application-wide memory/CPU budget, accepted
latency percentiles and adversarial workload criteria are needed to turn this
report into a production acceptance gate.

The recorded [sample run](image-processing-benchmark.json) on 2026-10-28 used
macOS arm64, Java 21 and ImageMagick 7.1.2-33 Q16-HDRI: first/warm 512×512 calls
20.1/18.4 ms, 4096×4096 call 399.5 ms, ten-image batch 176.5 ms, ten repeated
small calls 183.4 ms total, and two concurrent large calls 422.6 ms total.
Executable startup plus version output was 5.6 ms. Across these 25 native
processes, maximum recorded native RSS was 219,529,216 bytes and maximum sampled
scratch storage per call was 201,383,473 bytes. Per-process CPU/RSS reports are
included in the sample JSON.
