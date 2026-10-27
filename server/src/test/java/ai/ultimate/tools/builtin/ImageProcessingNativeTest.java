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
            assumeTrue(finished && check.exitValue()==0,"ImageMagick 7 is required for native tests");
        } catch(java.io.IOException e){assumeTrue(false,"ImageMagick unavailable");}
        workspace=Files.createDirectory(root.resolve("tenant")).toRealPath();
        Files.createDirectory(workspace.resolve("out"));
        tool=new ImageProcessingTool(root,binary,new ImageProcessingTool.NativeRunner());
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
        assertTrue(result.startsWith("Created 2"),result);
        assertEquals("WEBP 40 20",magick("identify","-format","%m %w %h",workspace.resolve("out/batch-1.webp").toString()));
        assertEquals("WEBP 13 40",magick("identify","-format","%m %w %h",workspace.resolve("out/batch-2.webp").toString()));
        noScratch();
    }

    @Test void cropColorCorrectionAndWatermarkChangePixels() throws Exception {
        image("a.png",80,80,Color.RED);image("watermark.png",10,10,Color.BLUE);
        String result=tool.processImages(workspace.toString(),List.of("a.png"),"out","watermarked",0,0,"40x40+5+5",50,100,100,"watermark.png",100);
        assertTrue(result.startsWith("Created"),result);
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
        assertTrue(result.startsWith("Created"),result);
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
        assertTrue(result.startsWith("Error"),result);
        try(var entries=Files.list(workspace.resolve("out"))){assertEquals(0,entries.count());}
        noScratch();
    }
}
