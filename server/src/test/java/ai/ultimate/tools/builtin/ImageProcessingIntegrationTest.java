package ai.ultimate.tools.builtin;

import ai.ultimate.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ImageProcessingIntegrationTest {
    @org.junit.jupiter.api.io.TempDir Path root;
    Path workspace;
    Path output;
    final java.util.List<java.util.List<String>> commands=new java.util.ArrayList<>();
    static final byte[] WEBP=ImageProcessingToolTest.WEBP;
    @org.junit.jupiter.api.BeforeEach void setup() throws Exception {
        workspace=Files.createDirectory(root.resolve("tenant")).toRealPath();
        output=Files.createDirectory(workspace.resolve("out"));
        Files.write(workspace.resolve("a.png"),ImageProcessingToolTest.PNG);
    }
    ImageProcessingTool tool(ImageProcessingTool.Runner runner) {return new ImageProcessingTool(root,"magick",runner);}
    void produce(List<String> args, Path cwd, Map<String,String> env, java.time.Duration timeout) throws java.io.IOException {
        commands.add(List.copyOf(args));Files.write(Path.of(args.getLast().substring(5)),WEBP);
    }
    void noScratch() throws Exception {try(var files=Files.list(workspace)){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith(".ultimate-image-")));}}

    @Test void springDiscoversToolInTheActualRegistry() {
        try(var context=new AnnotationConfigApplicationContext(ImageProcessingTool.class,ToolRegistry.class)) {
            assertTrue(context.getBean(ToolRegistry.class).getAll().contains(context.getBean(ImageProcessingTool.class)));
        }
    }

    @Test void structuredSpringAiInvocationMatchesDirectValidationAndArtifacts() throws Exception {
        ImageProcessingTool instance=tool(this::produce);
        var callback=MethodToolCallbackProvider.builder().toolObjects(instance).build().getToolCallbacks()[0];
        assertEquals("processImages",callback.getToolDefinition().name());
        assertTrue(callback.getToolDefinition().inputSchema().contains("inputPaths"));
        var mapper=new ObjectMapper();
        var request=new java.util.HashMap<String,Object>();
        request.put("workspacePath",workspace.toString());request.put("inputPaths",List.of("a.png"));
        request.put("outputDirectory","out");request.put("outputPrefix","ai");request.put("width",32);request.put("height",32);
        request.put("crop","");request.put("brightness",100);request.put("saturation",100);request.put("hue",100);
        request.put("watermarkPath","");request.put("quality",85);
        JsonNode response=mapper.readTree(callback.call(mapper.writeValueAsString(request)));
        assertEquals("SUCCESS",response.path("status").asText());
        assertEquals(output.resolve("ai-1.webp").toString(),response.path("outputs").get(0).asText());
        assertArrayEquals(WEBP,Files.readAllBytes(output.resolve("ai-1.webp")));
        request.put("outputPrefix","--inject");
        response=mapper.readTree(callback.call(mapper.writeValueAsString(request)));
        JsonNode direct=mapper.readTree(instance.processImages(workspace.toString(),List.of("a.png"),"out","--inject",32,32,"",100,100,100,"",85));
        assertEquals(direct.get("code"),response.get("code"));
        assertEquals(direct.get("message"),response.get("message"));
        assertEquals(1,commands.size());
        noScratch();
    }
}
