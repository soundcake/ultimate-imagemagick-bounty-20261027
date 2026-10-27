package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingToolTest {
    @TempDir Path root;
    Path workspace;
    Path output;
    static final byte[] PNG = {(byte)137,80,78,71,13,10,26,10};
    static final byte[] WEBP = "RIFFxxxxWEBP".getBytes(StandardCharsets.US_ASCII);
    final List<List<String>> commands = new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        workspace = Files.createDirectory(root.resolve("tenant")).toRealPath();
        output = Files.createDirectory(workspace.resolve("out"));
        Files.write(workspace.resolve("a.png"), PNG);
    }

    ImageProcessingTool tool(ImageProcessingTool.Runner runner) {
        return new ImageProcessingTool(root, "magick", runner);
    }

    String call(ImageProcessingTool tool, List<String> inputs) {
        return tool.processImages(workspace.toString(), inputs, "out", "web", 100, 100, "",
                100, 100, 100, "", 85);
    }

    void produce(List<String> args, Path cwd, Map<String,String> env, Duration timeout) throws IOException {
        commands.add(List.copyOf(args));
        assertTrue(Files.exists(cwd.resolve("policy.xml")));
        assertEquals(cwd.toString(), env.get("MAGICK_CONFIGURE_PATH"));
        assertTrue(timeout.toMillis() > 0 && timeout.toSeconds() <= 60);
        Files.write(Path.of(args.getLast().substring("WEBP:".length())), WEBP);
    }

    void noScratch() throws IOException {
        try(var files = Files.list(workspace)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".ultimate-image-")));
        }
    }

    @Test void batchConvertsWithBoundedArgumentsAndCleansStaging() throws Exception {
        Files.write(workspace.resolve("b.jpg"), new byte[]{(byte)255,(byte)216,(byte)255});
        assertTrue(call(tool(this::produce), List.of("a.png", "b.jpg")).startsWith("Created 2"));
        assertEquals(2,commands.size());
        assertTrue(commands.getFirst().contains("-strip"));
        assertTrue(commands.getFirst().contains("100x100>"));
        assertTrue(commands.get(1).stream().anyMatch(x -> x.startsWith("JPEG:") && x.endsWith("[0]")));
        assertArrayEquals(WEBP,Files.readAllBytes(output.resolve("web-1.webp")));
        assertArrayEquals(WEBP,Files.readAllBytes(output.resolve("web-2.webp")));
        assertArrayEquals(PNG,Files.readAllBytes(workspace.resolve("a.png")));
        noScratch();
    }

    @Test void cropColorAndWatermarkAreExplicitArguments() throws Exception {
        String response=tool(this::produce).processImages(workspace.toString(),List.of("a.png"),"out","crop",
                50,25,"80x40+2+3",110,80,125,"a.png",90);
        assertTrue(response.startsWith("Created"), response);
        List<String> args=commands.getFirst();
        assertTrue(args.contains("80x40+2+3")); assertTrue(args.contains("110.0,80.0,125.0"));
        assertTrue(args.contains("-composite"));assertTrue(args.contains("southeast"));
        noScratch();
    }

    @Test void secondImageFailurePublishesNothing() throws Exception {
        String response=call(tool((args,cwd,env,timeout) -> {
            if(!commands.isEmpty()) throw new IOException("corrupt second image");
            produce(args,cwd,env,timeout);
        }),List.of("a.png","a.png"));
        assertTrue(response.contains("corrupt second image"));
        try(var paths=Files.list(output)){assertEquals(0,paths.count());}
        noScratch();
    }

    @Test void existingOutputNeverChanges() throws Exception {
        Files.writeString(output.resolve("web-1.webp"),"keep");
        assertTrue(call(tool(this::produce),List.of("a.png")).contains("already exists"));
        assertEquals("keep",Files.readString(output.resolve("web-1.webp")));
        assertTrue(commands.isEmpty());noScratch();
    }

    @Test void competingOutputDuringPublicationRollsBackOnlyOurFiles() throws Exception {
        String response=call(tool((args,cwd,env,timeout) -> {
            produce(args,cwd,env,timeout);
            if(commands.size()==2)Files.writeString(output.resolve("web-2.webp"),"other request");
        }),List.of("a.png","a.png"));
        assertTrue(response.startsWith("Error"));
        assertFalse(Files.exists(output.resolve("web-1.webp")));
        assertEquals("other request",Files.readString(output.resolve("web-2.webp")));
        noScratch();
    }

    @Test void traversalAndCrossTenantSymlinksFailBeforeNativeProcess() throws Exception {
        Path other=Files.createDirectory(root.resolve("other")); Files.write(other.resolve("a.png"),PNG);
        assertTrue(call(tool(this::produce),List.of("../other/a.png")).contains("escapes"));
        try { Files.createSymbolicLink(workspace.resolve("link.png"),other.resolve("a.png")); }
        catch(UnsupportedOperationException|IOException e){assumeTrue(false,"symlink creation unavailable");}
        assertTrue(call(tool(this::produce),List.of("link.png")).contains("escapes"));
        Files.createSymbolicLink(workspace.resolve("outside"),other);
        assertTrue(tool(this::produce).processImages(workspace.toString(),List.of("a.png"),"outside","web",
                0,0,"",100,100,100,"",85).contains("escapes"));
        assertTrue(commands.isEmpty());noScratch();
    }

    @Test void managedRootIsNotATenantWorkspace() {
        assertTrue(tool(this::produce).processImages(root.toString(),List.of("tenant/a.png"),"tenant/out","web",
                0,0,"",100,100,100,"",85).contains("direct child"));
        assertTrue(commands.isEmpty());
    }

    @Test void rejectsNonRasterAndOversizedFiles() throws Exception {
        Files.writeString(workspace.resolve("payload.png"),"<svg><image href='https://example.invalid/x'/></svg>");
        assertTrue(call(tool(this::produce),List.of("payload.png")).contains("Only JPEG"));
        try(var file=new java.io.RandomAccessFile(workspace.resolve("huge.png").toFile(),"rw")) {
            file.write(PNG);file.setLength(25L*1024*1024+1);
        }
        assertTrue(call(tool(this::produce),List.of("huge.png")).contains("25MiB"));
        assertTrue(commands.isEmpty());
    }

    @Test void nativeFailureMissingOutputAndWrongFormatCleanScratch() throws Exception {
        assertTrue(call(tool((a,c,e,t)->{throw new IOException("magick missing");}),List.of("a.png")).contains("magick missing"));
        noScratch();
        assertTrue(call(tool((a,c,e,t)->{}),List.of("a.png")).startsWith("Error"));noScratch();
        assertTrue(call(tool((a,c,e,t)->Files.write(Path.of(a.getLast().substring(5)),PNG)),List.of("a.png")).contains("did not produce WebP"));
        noScratch();
    }

    @Test void interruptionPreservesFlagAndCleansScratch() throws Exception {
        try {
            assertTrue(call(tool((a,c,e,t)->{throw new InterruptedException();}),List.of("a.png")).contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        noScratch();
    }

    @Test void validationRejectsOptionInjectionAndNonFiniteColor() throws Exception {
        ImageProcessingTool tool=tool(this::produce);
        assertTrue(tool.processImages(workspace.toString(),List.of("a.png"),"out","--bad",0,0,"",100,100,100,"",85).startsWith("Error"));
        assertTrue(tool.processImages(workspace.toString(),List.of("a.png"),"out","web",0,0,"1x1;touch x",100,100,100,"",85).startsWith("Error"));
        assertTrue(tool.processImages(workspace.toString(),List.of("a.png"),"out","web",0,0,"",Double.NaN,100,100,"",85).startsWith("Error"));
        assertTrue(call(tool,null).startsWith("Error"));assertTrue(commands.isEmpty());noScratch();
    }

    @Test void filenamesAreStagedBeforeImageMagickParsesThem() throws Exception {
        Files.write(workspace.resolve("strange[1];-write.png"),PNG);
        assertTrue(call(tool(this::produce),List.of("strange[1];-write.png")).startsWith("Created"));
        assertTrue(commands.getFirst().stream().noneMatch(x->x.contains("strange")));
        noScratch();
    }

    @Test void nativeRunnerDeadlineTerminatesProcessAndBoundsDiagnostic() throws Exception {
        assumeTrue(Files.exists(Path.of("/bin/sh")));
        long start=System.nanoTime();
        IOException error=assertThrows(IOException.class,()->new ImageProcessingTool.NativeRunner().run(
                List.of("/bin/sh","-c","while :; do printf 'diagnostic output'; done"),workspace,Map.of(),Duration.ofMillis(150)));
        assertTrue(error.getMessage().contains("deadline"));
        assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<5);
    }

    @Test void changedOutputDirectoryCannotPublishOutsideWorkspace() throws Exception {
        Path external=Files.createDirectory(root.resolve("external"));
        Files.writeString(external.resolve("web-1.webp"),"keep external");
        try { Files.createSymbolicLink(workspace.resolve("probe-link"),external); }
        catch(UnsupportedOperationException|IOException e){assumeTrue(false,"symlink creation unavailable");}
        Files.delete(workspace.resolve("probe-link"));
        String response=call(tool((args,cwd,env,timeout)->{
            produce(args,cwd,env,timeout);
            Files.move(output,workspace.resolve("oldout"));
            Files.createSymbolicLink(output,external);
        }),List.of("a.png"));
        assertTrue(response.contains("Output directory changed"),response);
        assertEquals("keep external",Files.readString(external.resolve("web-1.webp")));
        noScratch();
    }

    @Test void cleanupRefusesAReplacementScratchDirectory() throws Exception {
        List<Path> replaced=new ArrayList<>();
        String response=call(tool((args,cwd,env,timeout)->{
            produce(args,cwd,env,timeout);
            Files.move(cwd,cwd.resolveSibling(cwd.getFileName()+"-moved"));
            Files.createDirectory(cwd);Files.writeString(cwd.resolve("keep.txt"),"another owner");
            replaced.add(cwd);
        }),List.of("a.png"));
        assertTrue(response.contains("Cleanup error"),response);
        assertEquals("another owner",Files.readString(replaced.getFirst().resolve("keep.txt")));
    }
}
