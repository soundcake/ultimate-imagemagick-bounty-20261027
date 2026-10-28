package ai.ultimate.tools.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageProcessingNativeTest {
    @TempDir Path root;
    Path workspace;
    String binary;
    ImageProcessingTool tool;

    @BeforeEach void setup() throws Exception {
        binary=System.getProperty("ultimate.imagemagick.binary","magick");
        try {
            Process check=new ProcessBuilder(binary,"-version").redirectErrorStream(true).start();
            boolean finished=check.waitFor(5,TimeUnit.SECONDS);
            if(!finished)check.destroyForcibly();
            boolean available=finished && check.exitValue()==0
                    && new String(check.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("ImageMagick 7.");
            if(Boolean.getBoolean("ultimate.imagemagick.required"))assertTrue(available,"ImageMagick 7 is required");
            assumeTrue(available,"ImageMagick 7 is required for native tests");
        } catch(java.io.IOException e){
            if(Boolean.getBoolean("ultimate.imagemagick.required"))fail("ImageMagick 7 is required",e);
            assumeTrue(false,"ImageMagick unavailable");
        }
        workspace=Files.createDirectory(root.resolve("tenant")).toRealPath();
        Files.createDirectory(workspace.resolve("out"));
        tool=new ImageProcessingTool(root,workspace,binary,new ImageProcessingTool.NativeRunner());
    }

    void image(String name,int width,int height,Color color) throws Exception {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(color);graphics.fillRect(0,0,width,height);graphics.dispose();
        ImageIO.write(image,name.endsWith(".jpg")?"jpeg":"png",workspace.resolve(name).toFile());
    }

    String magick(String... args) throws Exception {
        List<String> command=new ArrayList<>();command.add(binary);command.addAll(List.of(args));
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
        assertTrue(p.waitFor(10,TimeUnit.SECONDS));
        String text=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,p.exitValue(),text);return text;
    }

    void noScratch() throws Exception {
        try(var entries=Files.list(workspace)){assertTrue(entries.noneMatch(p->p.getFileName().toString().startsWith(".ultimate-image-")));}
    }

    @Test void realPngAndJpegBatchBecomeResizedWebp() throws Exception {
        image("a.png",80,40,Color.RED);image("b.jpg",30,90,Color.GREEN);
        String result=tool.processImages(workspace.toString(),List.of("a.png","b.jpg"),"out","batch",40,40,"",100,100,100,"",85);
        assertTrue(result.contains("Created 2"),result);
        assertEquals("WEBP 40 20",magick("identify","-format","%m %w %h",workspace.resolve("out/batch-1.webp").toString()));
        assertEquals("WEBP 13 40",magick("identify","-format","%m %w %h",workspace.resolve("out/batch-2.webp").toString()));
        noScratch();
    }

    @Test void registeredCallbackRunsTheRealNativeProcess() throws Exception {
        image("a.png",80,40,Color.RED);
        var registry=new ai.ultimate.tools.ToolRegistry(List.of(tool));
        var callback=org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(registry.asArray()).build().getToolCallbacks()[0];
        var args=new java.util.HashMap<String,Object>();
        args.put("workspacePath",workspace.toString());args.put("inputPaths",List.of("a.png"));
        args.put("outputDirectory","out");args.put("outputPrefix","callback");args.put("width",40);args.put("height",40);
        args.put("crop","");args.put("brightness",100);args.put("saturation",100);args.put("hue",100);
        args.put("watermarkPath","");args.put("quality",85);
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var result=json.readTree(callback.call(json.writeValueAsString(args)));
        assertEquals("SUCCESS",result.path("status").asText(),result.toString());
        assertEquals("WEBP 40 20",magick("identify","-format","%m %w %h",result.path("outputs").get(0).asText()));
        assertEquals(result.get("outputs"),json.readTree(Files.readString(Path.of(result.path("manifest").asText()))).get("outputs"));
        noScratch();
    }

    @Test void cropColorCorrectionAndWatermarkChangePixels() throws Exception {
        image("a.png",80,80,Color.RED);image("watermark.png",10,10,Color.BLUE);
        String result=tool.processImages(workspace.toString(),List.of("a.png"),"out","watermarked",0,0,"40x40+5+5",50,100,100,"watermark.png",100);
        assertTrue(result.contains("\"status\":\"SUCCESS\""),result);
        Path output=workspace.resolve("out/watermarked-1.webp");
        assertEquals("40 40",magick("identify","-format","%w %h",output.toString()));
        Path decoded=workspace.resolve("decoded.png");magick(output.toString(),decoded.toString());
        BufferedImage pixels=ImageIO.read(decoded.toFile());
        Color base=new Color(pixels.getRGB(3,3));Color mark=new Color(pixels.getRGB(25,25));
        assertTrue(base.getRed()>100 && base.getRed()<150,base.toString());
        assertTrue(base.getGreen()<20 && base.getBlue()<20,base.toString());
        assertTrue(mark.getBlue()>220 && mark.getRed()<30,mark.toString());
        noScratch();
    }

    @Test void exifIsRemovedAfterOrientationIsApplied() throws Exception {
        image("oriented.jpg",80,40,Color.GREEN);
        byte[] original=Files.readAllBytes(workspace.resolve("oriented.jpg"));
        ByteBuffer tiff=ByteBuffer.allocate(45).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)2);
        tiff.putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0);
        tiff.putShort((short)0x13b).putShort((short)2).putInt(7).putInt(38).putInt(0);
        tiff.put("SECRET\0".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream jpeg=new ByteArrayOutputStream();jpeg.write(original,0,2);
        jpeg.write(new byte[]{(byte)255,(byte)225,0,53});jpeg.write("Exif\0\0".getBytes(StandardCharsets.US_ASCII));jpeg.write(tiff.array());jpeg.write(original,2,original.length-2);
        Files.write(workspace.resolve("oriented.jpg"),jpeg.toByteArray());
        assertTrue(magick("identify","-verbose",workspace.resolve("oriented.jpg").toString()).contains("SECRET"));
        String result=tool.processImages(workspace.toString(),List.of("oriented.jpg"),"out","private",0,0,"",100,100,100,"",90);
        assertTrue(result.contains("\"status\":\"SUCCESS\""),result);
        Path output=workspace.resolve("out/private-1.webp");
        assertEquals("40 80",magick("identify","-format","%w %h",output.toString()));
        String metadata=magick("identify","-verbose",output.toString());
        assertFalse(metadata.contains("SECRET"));assertFalse(metadata.toLowerCase().contains("exif:"));
        assertArrayEquals(jpeg.toByteArray(),Files.readAllBytes(workspace.resolve("oriented.jpg")));
        noScratch();
    }

    @Test void truncatedRasterFailsWithoutPublishingEarlierImage() throws Exception {
        image("good.png",20,20,Color.RED);Files.write(workspace.resolve("broken.png"),ImageProcessingToolTest.PNG);
        String result=tool.processImages(workspace.toString(),List.of("good.png","broken.png"),"out","partial",0,0,"",100,100,100,"",80);
        assertTrue(result.contains("\"status\":\"ERROR\""),result);
        try(var entries=Files.list(workspace.resolve("out"))){assertEquals(0,entries.count());}
        noScratch();
    }

    BufferedImage decode(Path output) throws Exception {
        Path decoded=Files.createTempFile(workspace,"decoded-",".png");
        magick(output.toString(),decoded.toString());
        return ImageIO.read(decoded.toFile());
    }

    @Test void referenceQuadrantsProveCropOriginAndWebpRoundTrip() throws Exception {
        BufferedImage source=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);
        var g=source.createGraphics();
        g.setColor(Color.RED);g.fillRect(0,0,32,32);
        g.setColor(Color.GREEN);g.fillRect(32,0,32,32);
        g.setColor(Color.BLUE);g.fillRect(0,32,32,32);
        g.setColor(Color.WHITE);g.fillRect(32,32,32,32);g.dispose();
        ImageIO.write(source,"png",workspace.resolve("quadrants.png").toFile());
        String result=tool.processImages(workspace.toString(),List.of("quadrants.png"),"out","crop",0,0,"24x24+36+4",100,100,100,"",100);
        assertTrue(result.contains("SUCCESS"),result);
        Color pixel=new Color(decode(workspace.resolve("out/crop-1.webp")).getRGB(12,12));
        assertTrue(pixel.getGreen()>240 && pixel.getRed()<15 && pixel.getBlue()<15,pixel.toString());
        result=tool.processImages(workspace.toString(),List.of("out/crop-1.webp"),"out","roundtrip",0,0,"",100,100,100,"",100);
        assertTrue(result.contains("SUCCESS"),result);
        assertEquals("WEBP 24 24",magick("identify","-format","%m %w %h",workspace.resolve("out/roundtrip-1.webp").toString()));
        Color roundtrip=new Color(decode(workspace.resolve("out/roundtrip-1.webp")).getRGB(12,12));
        assertTrue(Math.abs(pixel.getGreen()-roundtrip.getGreen())<=5);
        noScratch();
    }

    @Test void alphaAndSrgbProfileHaveDefinedOutputProperties() throws Exception {
        BufferedImage source=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)source.setRGB(x,y,new Color(240,20,40,x<16?0:128).getRGB());
        ImageIO.write(source,"png",workspace.resolve("alpha.png").toFile());
        Path profile=workspace.resolve("srgb.icc");
        Files.write(profile,java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData());
        magick(workspace.resolve("alpha.png").toString(),"-profile",profile.toString(),workspace.resolve("profiled.png").toString());
        assertTrue(magick("identify","-verbose",workspace.resolve("profiled.png").toString()).contains("Profile-icc"));
        String result=tool.processImages(workspace.toString(),List.of("profiled.png"),"out","alpha",0,0,"",100,100,100,"",100);
        assertTrue(result.contains("SUCCESS"),result);
        Path output=workspace.resolve("out/alpha-1.webp");
        BufferedImage pixels=decode(output);
        assertEquals(0,(pixels.getRGB(4,4)>>>24)&255);
        assertEquals(128,(pixels.getRGB(24,24)>>>24)&255,1);
        Color opaque=new Color(pixels.getRGB(24,24),true);
        assertTrue(opaque.getRed()>220 && opaque.getGreen()<40 && opaque.getBlue()<60,opaque.toString());
        assertFalse(magick("identify","-verbose",output.toString()).contains("Profile-icc"));
        noScratch();
    }

    @Test void saturationAndHueTransformKnownColorChannels() throws Exception {
        image("red.png",32,32,Color.RED);
        String result=tool.processImages(workspace.toString(),List.of("red.png"),"out","gray",0,0,"",100,0,100,"",100);
        assertTrue(result.contains("SUCCESS"),result);
        Color gray=new Color(decode(workspace.resolve("out/gray-1.webp")).getRGB(16,16));
        assertTrue(Math.abs(gray.getRed()-gray.getGreen())<5 && Math.abs(gray.getGreen()-gray.getBlue())<5,gray.toString());
        result=tool.processImages(workspace.toString(),List.of("red.png"),"out","cyan",0,0,"",100,100,200,"",100);
        assertTrue(result.contains("SUCCESS"),result);
        Color cyan=new Color(decode(workspace.resolve("out/cyan-1.webp")).getRGB(16,16));
        assertTrue(cyan.getRed()<20 && cyan.getGreen()>230 && cyan.getBlue()>230,cyan.toString());
        noScratch();
    }

    @Test void overPolicyDimensionsFailWithoutChangingUnrelatedFiles() throws Exception {
        image("wide.png",16385,1,Color.RED);
        Files.writeString(workspace.resolve("out/keep.txt"),"existing unrelated artifact");
        String result=tool.processImages(workspace.toString(),List.of("wide.png"),"out","wide",0,0,"",100,100,100,"",85);
        assertTrue(result.contains("ERROR"),result);
        assertEquals("existing unrelated artifact",Files.readString(workspace.resolve("out/keep.txt")));
        assertFalse(Files.exists(workspace.resolve("out/wide-1.webp")));
        noScratch();
    }

    @Test void simultaneousBatchesUseIndependentArtifactsAndCleanScratch() throws Exception {
        image("a.png",512,512,Color.RED);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<java.util.concurrent.Future<String>>();
            for(int i=0;i<2;i++) {
                String prefix="parallel"+i;
                tasks.add(executor.submit(()->tool.processImages(workspace.toString(),List.of("a.png"),"out",prefix,256,256,"",100,100,100,"",85)));
            }
            for(var task:tasks)assertTrue(task.get(20,TimeUnit.SECONDS).contains("SUCCESS"));
        }
        assertEquals("WEBP 256 256",magick("identify","-format","%m %w %h",workspace.resolve("out/parallel0-1.webp").toString()));
        assertEquals("WEBP 256 256",magick("identify","-format","%m %w %h",workspace.resolve("out/parallel1-1.webp").toString()));
        noScratch();
    }

    @Test void reproducibleLocalWorkloadMeasurements() throws Exception {
        // Measurements are reported, not compared with hardware-dependent acceptance thresholds.
        image("small.png",512,512,Color.RED);image("large.png",4096,4096,Color.GREEN);
        List<java.util.Map<String,Object>> samples=new java.util.concurrent.CopyOnWriteArrayList<>();
        ImageProcessingTool measured=new ImageProcessingTool(root,workspace,binary,(command,cwd,environment,timeout)->{
            var peakDisk=new java.util.concurrent.atomic.AtomicLong();
            var sampler=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            sampler.scheduleAtFixedRate(()->{
                try(var files=Files.walk(cwd)) {
                    long bytes=files.filter(Files::isRegularFile).mapToLong(path->{try{return Files.size(path);}catch(Exception e){return 0;}}).sum();
                    peakDisk.accumulateAndGet(bytes,Math::max);
                } catch(Exception ignored) { }
            },0,5,TimeUnit.MILLISECONDS);
            Path metrics=workspace.resolve("metrics-"+java.util.UUID.randomUUID()+".txt");
            List<String> timed=new ArrayList<>();
            boolean timedAvailable=Files.isExecutable(Path.of("/usr/bin/time"));
            if(timedAvailable)timed.addAll(List.of("/usr/bin/time",System.getProperty("os.name").contains("Mac")?"-l":"-v","-o",metrics.toString()));
            timed.addAll(command);
            long start=System.nanoTime();
            try {new ImageProcessingTool.NativeRunner().run(timed,cwd,environment,timeout);}
            finally {sampler.shutdownNow();}
            var sample=new java.util.LinkedHashMap<String,Object>();
            sample.put("nativeWallMs",(System.nanoTime()-start)/1_000_000.0);
            sample.put("sampledScratchPeakBytes",peakDisk.get());
            if(timedAvailable){sample.put("timeReport",Files.readString(metrics));Files.delete(metrics);}
            samples.add(sample);
        });
        var totals=new java.util.LinkedHashMap<String,Object>();
        for(String scenario:List.of("first512","warm512","large4096","batch10","repeat512")) {
            String input=scenario.equals("large4096")?"large.png":"small.png";
            List<String> inputs=scenario.equals("batch10")?java.util.Collections.nCopies(10,input):List.of(input);
            int repeats=scenario.equals("repeat512")?10:1;
            long start=System.nanoTime();
            for(int repeat=0;repeat<repeats;repeat++) {
                String result=measured.processImages(workspace.toString(),inputs,"out",scenario+repeat,256,256,"",100,100,100,"",85);
                assertTrue(result.contains("SUCCESS"),result);
            }
            totals.put(scenario+"TotalMs",(System.nanoTime()-start)/1_000_000.0);
        }
        long start=System.nanoTime();
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->measured.processImages(workspace.toString(),List.of("large.png"),"out","concurrentA",256,256,"",100,100,100,"",85));
            var second=executor.submit(()->measured.processImages(workspace.toString(),List.of("large.png"),"out","concurrentB",256,256,"",100,100,100,"",85));
            assertTrue(first.get().contains("SUCCESS"));assertTrue(second.get().contains("SUCCESS"));
        }
        totals.put("concurrent2LargeTotalMs",(System.nanoTime()-start)/1_000_000.0);
        long versionStart=System.nanoTime();
        totals.put("runtime",magick("-version").split("\n")[0]);
        totals.put("processStartupVersionMs",(System.nanoTime()-versionStart)/1_000_000.0);
        totals.put("platform",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        totals.put("samples",samples);
        System.out.println("IMAGE_BENCHMARK "+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(totals));
        noScratch();
    }
}
