/* ABOUTME: Bounded ImageMagick raster batches in managed workspaces. */
package ai.ultimate.tools.builtin;

import ai.ultimate.tools.UltimateTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class ImageProcessingTool implements UltimateTool {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private final Path root;
    private final String binary;
    private final Runner runner;

    public ImageProcessingTool() {
        this(Path.of(System.getProperty("user.home"), "ultimate-managed-workspaces"),
                System.getProperty("ultimate.imagemagick.binary", "magick"), new NativeRunner());
    }

    ImageProcessingTool(Path root, String binary, Runner runner) {
        this.root = root.toAbsolutePath().normalize();
        this.binary = binary;
        this.runner = runner;
    }

    @Tool(description = "Batch process local JPEG, PNG or WebP images with ImageMagick 7. "
            + "Use for WebP conversion, crop, resize, color correction, image watermarking and EXIF removal. "
            + "Applies crop, then aspect-preserving resize, color adjustment and optional bottom-right watermark. "
            + "Always auto-orients and strips metadata. Returns new WebP paths or an error; never overwrites inputs. "
            + "Example: inputPaths=['photo.jpg'], outputDirectory='out', outputPrefix='web', "
            + "width=1200, height=1200, crop='', brightness=100, saturation=100, hue=100, "
            + "watermarkPath='', quality=85. Up to 10 images per call.")
    public String processImages(
            @ToolParam(description = "Absolute existing workspace directly below ~/ultimate-managed-workspaces") String workspacePath,
            @ToolParam(description = "1–10 workspace-relative raster file paths") List<String> inputPaths,
            @ToolParam(description = "Existing workspace-relative output directory, e.g. out") String outputDirectory,
            @ToolParam(description = "Output basename prefix, letters/digits/underscore/hyphen only") String outputPrefix,
            @ToolParam(description = "Resize bounding width, 1–8192; use 0 with height 0 to preserve size") int width,
            @ToolParam(description = "Resize bounding height, 1–8192; use 0 with width 0 to preserve size") int height,
            @ToolParam(description = "Crop WIDTHxHEIGHT+X+Y, e.g. 200x100+10+20; empty for no crop") String crop,
            @ToolParam(description = "Brightness percent 0–200; 100 preserves it") double brightness,
            @ToolParam(description = "Saturation percent 0–200; 100 preserves it") double saturation,
            @ToolParam(description = "Hue rotation percent 0–200; 100 preserves it") double hue,
            @ToolParam(description = "Relative PNG/JPEG/WebP watermark image; empty for none") String watermarkPath,
            @ToolParam(description = "WebP quality 1–100") int quality) {
        Path scratch = null;
        Object scratchKey = null;
        List<Path> published = new ArrayList<>();
        Map<Path, Object> publishedKeys = new HashMap<>();
        String result = "Error: Image processing did not complete";
        boolean success = false;
        try {
            validate(inputPaths, outputPrefix, width, height, crop, brightness, saturation, hue, quality);
            Path allowed = root.toRealPath();
            Path workspace = Path.of(workspacePath).toRealPath();
            require(allowed.equals(workspace.getParent()), "Workspace must be a direct child of the managed root");
            Path output = relative(workspace, outputDirectory).toRealPath();
            require(Files.isDirectory(output), "Output directory must exist");
            List<Path> inputs = new ArrayList<>();
            for (String input : inputPaths) inputs.add(raster(workspace, input));
            Path watermark = watermarkPath == null || watermarkPath.isBlank() ? null : raster(workspace, watermarkPath);
            List<Path> destinations = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) {
                Path target = output.resolve(outputPrefix + "-" + (i + 1) + ".webp");
                require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), "Output already exists: " + target.getFileName());
                destinations.add(target);
            }
            scratch = Files.createTempDirectory(workspace, ".ultimate-image-").toRealPath();
            scratchKey = fileKey(scratch);
            Files.writeString(scratch.resolve("policy.xml"), policy(), StandardCharsets.UTF_8);
            List<Path> staged = new ArrayList<>();
            for (int i = 0; i < inputs.size(); i++) staged.add(stage(inputs.get(i), scratch, "input-" + i));
            Path mark = watermark == null ? null : stage(watermark, scratch, "watermark");
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            Map<String, String> environment = Map.of("MAGICK_CONFIGURE_PATH", scratch.toString(),
                    "MAGICK_TEMPORARY_PATH", scratch.toString(), "OMP_NUM_THREADS", "2");
            List<Path> results = new ArrayList<>();
            for (int i = 0; i < staged.size(); i++) {
                Path target = scratch.resolve("result-" + i + ".webp");
                List<String> args = new ArrayList<>(List.of(binary, "-limit", "thread", "2",
                        "-limit", "memory", "128MiB", "-limit", "map", "256MiB", "-limit", "disk", "256MiB",
                        "-limit", "time", "55", coder(staged.get(i)) + ":" + staged.get(i) + "[0]", "-auto-orient"));
                if (crop != null && !crop.isBlank()) args.addAll(List.of("-crop", crop, "+repage"));
                if (width > 0) args.addAll(List.of("-resize", width + "x" + height + ">"));
                args.addAll(List.of("-modulate", String.format(Locale.ROOT, "%s,%s,%s", brightness, saturation, hue)));
                if (mark != null) args.addAll(List.of("(", coder(mark) + ":" + mark + "[0]", ")",
                        "-gravity", "southeast", "-geometry", "+10+10", "-compose", "over", "-composite"));
                args.addAll(List.of("-strip", "-quality", Integer.toString(quality), "WEBP:" + target));
                long left = deadline - System.nanoTime();
                require(left > 0, "Batch exceeded 60 second deadline");
                runner.run(args, scratch, environment, Duration.ofNanos(left));
                require(target.toRealPath().getParent().equals(scratch), "Unexpected output location");
                require(Files.size(target) > 0 && Files.size(target) <= MAX_BYTES, "Invalid output size");
                require("WEBP".equals(coder(target)), "ImageMagick did not produce WebP");
                results.add(target);
            }
            // Publish only after every image succeeds. CREATE_NEW never replaces existing files.
            for (int i = 0; i < results.size(); i++) {
                require(output.toRealPath().equals(output), "Output directory changed during processing");
                Path target = destinations.get(i);
                try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    published.add(target);
                    publishedKeys.put(target, fileKey(target));
                    Files.copy(results.get(i), out);
                }
                require(target.toRealPath().getParent().equals(output), "Unexpected published output location");
            }
            result = "Created " + published.size() + " WebP image(s); metadata removed:\n"
                    + String.join("\n", published.stream().map(Path::toString).toList());
            success = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "Error: Image processing interrupted";
        } catch (Exception e) {
            result = "Error: " + safeMessage(e);
        } finally {
            if (!success) {
                for (Path output : published) {
                    try {
                        if (!output.getParent().toRealPath().equals(output.getParent())
                                || !Objects.equals(publishedKeys.get(output), fileKey(output)))
                            throw new IOException("Output changed; refusing rollback of " + output.getFileName());
                        Files.deleteIfExists(output);
                    }
                    catch (IOException e) { result += "\nOutput rollback error: " + safeMessage(e); }
                }
            }
            if (scratch != null) {
                try { cleanup(scratch, scratchKey); }
                catch (IOException e) { result += "\nCleanup error: " + safeMessage(e); }
            }
        }
        return result;
    }

    private static void validate(List<String> inputs, String prefix, int width, int height, String crop,
                                 double brightness, double saturation, double hue, int quality) {
        require(inputs != null && !inputs.isEmpty() && inputs.size() <= 10, "Supply 1–10 inputs");
        require(prefix != null && prefix.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), "Invalid output prefix");
        require((width == 0 && height == 0) || (width > 0 && height > 0 && width <= 8192 && height <= 8192), "Invalid resize bounds");
        require(quality >= 1 && quality <= 100, "Quality must be 1–100");
        for (double value : new double[]{brightness, saturation, hue})
            require(Double.isFinite(value) && value >= 0 && value <= 200, "Color values must be finite percentages 0–200");
        if (crop != null && !crop.isBlank()) {
            require(crop.matches("[1-9][0-9]{0,3}x[1-9][0-9]{0,3}\\+[0-9]{1,5}\\+[0-9]{1,5}"), "Invalid crop geometry");
            String[] parts = crop.split("[x+]");
            require(Integer.parseInt(parts[0]) <= 8192 && Integer.parseInt(parts[1]) <= 8192, "Crop dimensions exceed 8192");
        }
    }

    private static Path relative(Path workspace, String value) throws IOException {
        require(value != null && !value.isBlank(), "Missing relative path");
        Path path = Path.of(value);
        require(!path.isAbsolute(), "Paths must be relative to the workspace");
        Path real = workspace.resolve(path).toRealPath();
        require(real.startsWith(workspace), "Path escapes workspace");
        return real;
    }

    private static Path raster(Path workspace, String value) throws IOException {
        Path path = relative(workspace, value);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Input must be a regular file");
        require(Files.size(path) > 0 && Files.size(path) <= MAX_BYTES, "Input exceeds 25MiB or is empty");
        coder(path);
        return path;
    }

    private static Path stage(Path input, Path scratch, String name) throws IOException {
        Path target = scratch.resolve(name + "." + coder(input).toLowerCase(Locale.ROOT));
        try (InputStream in = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS);
             var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[8192]; long count = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                count += read; require(count <= MAX_BYTES, "Input grew beyond 25MiB"); out.write(buffer, 0, read);
            }
        }
        coder(target);
        return target.toRealPath();
    }

    private static String coder(Path path) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = in.readNBytes(12); }
        if (bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "PNG";
        if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "JPEG";
        if (bytes.length == 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "WEBP";
        throw new IllegalArgumentException("Only JPEG, PNG and WebP raster inputs are supported");
    }

    static String policy() {
        return """
                <policymap>
                  <policy domain="delegate" rights="none" pattern="*"/>
                  <policy domain="filter" rights="none" pattern="*"/>
                  <policy domain="coder" rights="none" pattern="*"/>
                  <policy domain="coder" rights="read|write" pattern="{PNG,JPEG,WEBP}"/>
                  <policy domain="path" rights="none" pattern="@*"/>
                  <policy domain="resource" name="width" value="16384"/>
                  <policy domain="resource" name="height" value="16384"/>
                  <policy domain="resource" name="list-length" value="4"/>
                  <policy domain="resource" name="memory" value="128MiB"/>
                  <policy domain="resource" name="map" value="256MiB"/>
                  <policy domain="resource" name="disk" value="256MiB"/>
                  <policy domain="resource" name="thread" value="2"/>
                  <policy domain="resource" name="time" value="55"/>
                </policymap>
                """;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.substring(0, Math.min(1000, message.length()));
    }

    private static Object fileKey(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static void cleanup(Path directory, Object expectedKey) throws IOException {
        if (!directory.toRealPath().equals(directory) || !Objects.equals(expectedKey, fileKey(directory)))
            throw new IOException("Scratch directory changed; refusing to delete replacement contents");
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    interface Runner {
        void run(List<String> command, Path cwd, Map<String, String> environment, Duration timeout)
                throws IOException, InterruptedException;
    }

    static final class NativeRunner implements Runner {
        public void run(List<String> command, Path cwd, Map<String, String> environment, Duration timeout)
                throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            process.getOutputStream().close();
            var pool = Executors.newSingleThreadExecutor();
            var diagnostic = pool.submit(() -> {
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                try (InputStream stream = process.getInputStream()) {
                    byte[] buffer = new byte[4096]; int read;
                    while ((read = stream.read(buffer)) != -1) {
                        int keep = Math.min(read, 4096 - captured.size());
                        if (keep > 0) captured.write(buffer, 0, keep);
                    }
                }
                return captured.toString(StandardCharsets.UTF_8);
            });
            try {
                if (!process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS))
                    throw new IOException("ImageMagick exceeded the batch deadline");
                if (process.exitValue() != 0) {
                    String message;
                    try { message = diagnostic.get(2, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { message = "diagnostic unavailable"; }
                    throw new IOException("ImageMagick exited " + process.exitValue() + ": " + message);
                }
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                diagnostic.cancel(true);
                pool.shutdownNow();
            }
        }
    }
}
