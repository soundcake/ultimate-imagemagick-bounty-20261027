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

The tool returns paths on success and a bounded error string on failure, including
a missing executable, invalid path, corrupt image or timeout. It does not send
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
