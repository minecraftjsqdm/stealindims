package com.homemine.stealindims.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate on R27's asset conversion.
 *
 * <p>{@code tools/bbmodel2geckolib.py} turns the user's {@code .bbmodel} into a GeckoLib geometry
 * file, a clip file and a merged texture atlas. This test re-derives the truth straight from the
 * {@code .bbmodel} — including decoding the two embedded base64 textures and re-sampling every
 * face — and fails the build if anything was dropped, re-timed or shifted by the wrong number of
 * pixels.
 *
 * <p>It deliberately does not call into the converter: the point is to check the *artefacts* that
 * ship in the jar, not the code that produced them.
 */
class TopEvokerConversionTest {

    /** Pinned hash of the user's asset (R27 §1); a different file means "re-run the converter". */
    private static final String SOURCE_SHA256 =
            "4FE27F41D48D7E3623A6F44D185895E8E4DF8B90673DC924D11162B55490918A";

    private static final int EXPECTED_BONES = 44;
    private static final int EXPECTED_CUBES = 151;
    private static final int EXPECTED_ANIMATIONS = 9;

    /**
     * The clips play this much faster than the source document — the converter's {@code ANIMATION_SPEED}
     * (user request, 2026-10-06). The server reads its release frames and volleys out of the same file,
     * so the performances and the hit windows speed up together.
     */
    private static final double PLAYBACK_SPEED = 1.10D;
    private static final int EXPECTED_KEYFRAMES = 1065;

    private static Path projectDir;
    private static JsonObject bbmodel;
    private static JsonObject geometryDoc;
    private static JsonObject animations;
    private static BufferedImage atlas;
    private static List<BufferedImage> sources;

    @BeforeAll
    static void load() throws IOException {
        projectDir = projectDir();
        Path model = sourceModel();
        assertTrue(Files.isRegularFile(model), "the user's .bbmodel must stay where it is: " + model);
        assertEquals(SOURCE_SHA256, sha256(model),
                "the user's asset changed; re-run tools/bbmodel2geckolib.py and update the pin");

        bbmodel = readJson(model);
        geometryDoc = readJson(projectDir.resolve(
                "src/main/resources/assets/stealindims/geo/entity/top_evoker.geo.json"));
        animations = readJson(projectDir.resolve(
                "src/main/resources/assets/stealindims/animations/entity/top_evoker.animation.json"));
        atlas = ImageIO.read(projectDir.resolve(
                "src/main/resources/assets/stealindims/textures/entity/top_evoker.png").toFile());
        assertNotNull(atlas, "the merged atlas must decode");

        sources = new ArrayList<>();
        for (JsonElement texture : bbmodel.getAsJsonArray("textures")) {
            String source = texture.getAsJsonObject().get("source").getAsString();
            byte[] bytes = Base64.getDecoder().decode(source.substring(source.indexOf(',') + 1));
            sources.add(ImageIO.read(new ByteArrayInputStream(bytes)));
        }
        assertEquals(2, sources.size(), "the asset ships two textures; the atlas must merge both");
    }

    // ------------------------------------------------------------------ structure

    @Test
    @DisplayName("the .bbmodel still is the asset R27 was written against")
    void sourceShapeIsUnchanged() {
        assertEquals(EXPECTED_BONES, bbmodel.getAsJsonArray("groups").size());
        assertEquals(EXPECTED_CUBES, bbmodel.getAsJsonArray("elements").size());
        assertEquals(EXPECTED_ANIMATIONS, bbmodel.getAsJsonArray("animations").size());
        assertFalse(bbmodel.getAsJsonObject("meta").get("box_uv").getAsBoolean(),
                "this converter only handles per-face UV models");
    }

    @Test
    @DisplayName("every bone, cube and face survives the conversion")
    void geometryIsComplete() {
        JsonObject description = description();
        assertEquals("geometry.stealindims.top_evoker", description.get("identifier").getAsString());

        JsonArray bones = bones();
        assertEquals(EXPECTED_BONES, bones.size(), "no bone may be dropped");

        Set<String> names = new HashSet<>();
        int cubes = 0;
        int faces = 0;
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            assertTrue(names.add(name), "duplicate bone " + name);
            assertTrue(bone.has("pivot"), name + " must carry its origin as a pivot");
            if (bone.has("cubes")) {
                cubes += bone.getAsJsonArray("cubes").size();
                for (JsonElement cube : bone.getAsJsonArray("cubes")) {
                    assertTrue(cube.getAsJsonObject().has("uv"), "every cube keeps its faces");
                    faces += cube.getAsJsonObject().getAsJsonObject("uv").size();
                }
            }
        }
        assertEquals(EXPECTED_CUBES, cubes, "no cube may be dropped");
        assertEquals(6 * EXPECTED_CUBES, faces, "every one of the 151 cubes keeps all six faces");

        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (bone.has("parent")) {
                assertTrue(names.contains(bone.get("parent").getAsString()),
                        bone.get("name").getAsString() + " points at a parent that does not exist");
            }
        }

        // The two rest rotations the model actually uses (R27 §3.1: only book_left / book_right).
        Map<String, double[]> rest = new HashMap<>();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (bone.has("rotation")) {
                rest.put(bone.get("name").getAsString(), readVector(bone.getAsJsonArray("rotation")));
            }
        }
        assertEquals(Set.of("book_left", "book_right"), rest.keySet());
        assertEquals(-12.0D, rest.get("book_left")[2], 1.0E-6D);
        assertEquals(12.0D, rest.get("book_right")[2], 1.0E-6D);
    }

    @Test
    @DisplayName("all nine clips survive with their loop modes and keyframe counts")
    void animationsAreComplete() {
        JsonObject clips = animations.getAsJsonObject("animations");
        assertEquals(EXPECTED_ANIMATIONS, clips.size());

        Map<String, String> expectedLoops = new LinkedHashMap<>();
        expectedLoops.put("00_威严待机", "loop");
        expectedLoops.put("01_开战_翻书回首转身", "play_once");
        expectedLoops.put("02_弩箭大师_五弩依次三重射击", "play_once");
        expectedLoops.put("03_圆形尖刺_环形召唤", "play_once");
        expectedLoops.put("04_直线尖刺_前指递进", "play_once");
        expectedLoops.put("05_恼鬼释放_召唤施法", "play_once");
        expectedLoops.put("06_斗士冲撞_前摇", "hold_on_last_frame");
        expectedLoops.put("07_斗士冲撞_冲撞期间", "loop");
        expectedLoops.put("08_斗士冲撞_后摇", "play_once");

        Set<String> boneNames = new HashSet<>();
        for (JsonElement bone : bones()) {
            boneNames.add(bone.getAsJsonObject().get("name").getAsString());
        }

        Set<String> easings = Set.of("linear", "step", "catmullrom");
        int keyframes = 0;
        for (Map.Entry<String, String> expected : expectedLoops.entrySet()) {
            JsonElement element = clips.get(expected.getKey());
            assertNotNull(element, "the clip " + expected.getKey() + " is missing");
            JsonObject clip = element.getAsJsonObject();
            assertEquals(expected.getValue(), clip.get("loop").getAsString());
            assertTrue(clip.get("animation_length").getAsDouble() > 0.0D);

            for (Map.Entry<String, JsonElement> bone : clip.getAsJsonObject("bones").entrySet()) {
                assertTrue(boneNames.contains(bone.getKey()),
                        expected.getKey() + " animates unknown bone " + bone.getKey());
                for (Map.Entry<String, JsonElement> channel : bone.getValue().getAsJsonObject().entrySet()) {
                    assertTrue(Set.of("rotation", "position", "scale").contains(channel.getKey()));
                    for (Map.Entry<String, JsonElement> frame
                            : channel.getValue().getAsJsonObject().entrySet()) {
                        assertTrue(Double.parseDouble(frame.getKey()) >= 0.0D);
                        JsonObject value = frame.getValue().getAsJsonObject();
                        assertEquals(3, value.getAsJsonArray("vector").size());
                        assertTrue(easings.contains(value.get("easing").getAsString()),
                                "unexpected easing " + value.get("easing"));
                        keyframes++;
                    }
                }
            }
        }
        assertEquals(EXPECTED_KEYFRAMES, keyframes, "no keyframe may be dropped or merged");

        // The hide-the-props trick R27 §1 calls out is carried over verbatim. The clips play 10% fast
        // (see PLAYBACK_SPEED), so this reads the first two frames in time order rather than
        // hard-coding timestamps the speed factor moves.
        JsonObject bolt = clips.get("02_弩箭大师_五弩依次三重射击")
                .getAsJsonObject().getAsJsonObject("bones").getAsJsonObject("bolt_1_1")
                .getAsJsonObject("scale").getAsJsonObject();
        java.util.List<JsonElement> boltFrames = new java.util.ArrayList<>();
        for (Map.Entry<String, JsonElement> frame : bolt.entrySet()) {
            boltFrames.add(frame.getValue());
        }
        assertEquals(6, boltFrames.size(), "bolt_1_1 keeps its six scale frames");
        // The trick is "hidden by default, switched on for the shot, switched off again" — asserted on
        // the sequence rather than on timestamps, which the 10% speed-up moves.
        assertEquals(0, boltFrames.get(0).getAsJsonObject().getAsJsonArray("vector").get(0).getAsInt());
        assertEquals(1, boltFrames.get(2).getAsJsonObject().getAsJsonArray("vector").get(0).getAsInt());
        assertEquals(0, boltFrames.get(5).getAsJsonObject().getAsJsonArray("vector").get(0).getAsInt());
    }

    @Test
    @DisplayName("the merged atlas holds both source sheets pixel for pixel")
    void atlasMergesBothTextures() {
        assertEquals(128, atlas.getWidth());
        assertEquals(128 + sources.get(1).getHeight(), atlas.getHeight());
        assertEquals(atlas.getWidth(), description().get("texture_width").getAsInt());
        assertEquals(atlas.getHeight(), description().get("texture_height").getAsInt());

        for (int y = 0; y < sources.get(0).getHeight(); y++) {
            for (int x = 0; x < sources.get(0).getWidth(); x++) {
                assertEquals(sources.get(0).getRGB(x, y), atlas.getRGB(x, y),
                        "the primary sheet must not move: " + x + "," + y);
            }
        }
        for (int y = 0; y < sources.get(1).getHeight(); y++) {
            for (int x = 0; x < sources.get(1).getWidth(); x++) {
                assertEquals(sources.get(1).getRGB(x, y), atlas.getRGB(x, 128 + y),
                        "the evoker sheet must land at y+128: " + x + "," + y);
            }
        }
    }

    @Test
    @DisplayName("every face samples the same pixels it did before the atlas merge")
    void everyFaceKeepsItsPixels() {
        List<String> sourceRects = new ArrayList<>();
        for (JsonElement element : bbmodel.getAsJsonArray("elements")) {
            JsonObject cube = element.getAsJsonObject();
            for (String face : new String[] {"north", "south", "east", "west", "up", "down"}) {
                JsonObject side = cube.getAsJsonObject("faces").getAsJsonObject(face);
                if (side == null || !side.has("uv")) {
                    continue;
                }
                double[] uv = readVector(side.getAsJsonArray("uv"));
                int texture = side.has("texture") ? side.get("texture").getAsInt() : 0;
                sourceRects.add(texture + ":" + key(uv));
            }
        }

        List<String> atlasRects = new ArrayList<>();
        int mismatchedColours = 0;
        JsonArray bones = bones();
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            if (!bone.has("cubes")) {
                continue;
            }
            for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                JsonObject faces = cubeElement.getAsJsonObject().getAsJsonObject("uv");
                for (Map.Entry<String, JsonElement> entry : faces.entrySet()) {
                    JsonObject face = entry.getValue().getAsJsonObject();
                    double[] uv = readVector(face.getAsJsonArray("uv"));
                    double[] size = readVector(face.getAsJsonArray("uv_size"));
                    double x0 = uv[0];
                    double y0 = uv[1];
                    double x1 = uv[0] + size[0];
                    double y1 = uv[1] + size[1];

                    int texture;
                    double dy;
                    if (Math.min(y0, y1) >= sources.get(0).getHeight()) {
                        texture = 1;
                        dy = -sources.get(0).getHeight();
                    } else if (Math.max(y0, y1) <= sources.get(0).getHeight()) {
                        texture = 0;
                        dy = 0.0D;
                    } else {
                        throw new AssertionError("face " + entry.getKey() + " straddles both sheets: " + key(uv));
                    }
                    atlasRects.add(texture + ":" + key(new double[] {x0, y0 + dy, x1, y1 + dy}));
                    mismatchedColours += coloursDiffer(sources.get(texture), dy, atlas, x0, y0, x1, y1);
                }
            }
        }

        assertEquals(906, sourceRects.size(), "all 906 faces of the .bbmodel are covered");
        java.util.Collections.sort(sourceRects);
        java.util.Collections.sort(atlasRects);
        assertEquals(sourceRects, atlasRects,
                "every face must sample exactly the rectangle it sampled in the source sheet");
        assertEquals(0, mismatchedColours, "no face may change colour across the merge");
    }

    // ------------------------------------------------------------------ timings

    @Test
    @DisplayName("the server reads its windows out of the clip file")
    void timingsComeFromTheClipFile() {
        assertEquals(EXPECTED_ANIMATIONS, TopEvokerSkillTiming.clips().size());
        assertEquals(EXPECTED_ANIMATIONS, TopEvokerSkill.values().length);
        for (TopEvokerSkill skill : TopEvokerSkill.values()) {
            assertTrue(TopEvokerSkillTiming.clips().containsKey(skill.animation()),
                    skill + " names a clip the file does not have");
        }

        assertEquals(58, TopEvokerSkill.IDLE.lengthTicks());
        assertEquals(153, TopEvokerSkill.INTRO.lengthTicks());
        assertEquals(95, TopEvokerSkill.CROSSBOW.lengthTicks());

        // The release frames the state machine fires on, derived from the arm keyframes — 10% earlier
        // than the raw document because the whole clip plays 10% fast.
        assertEquals(19, TopEvokerSkill.RING.releaseTicks());
        assertEquals(15, TopEvokerSkill.LINE.releaseTicks());
        assertEquals(19, TopEvokerSkill.VEX.releaseTicks());
        assertFalse(TopEvokerSkill.CROSSBOW.hasRelease());
        assertFalse(TopEvokerSkill.CHARGE.hasRelease());

        // Five volleys of three bolts, one every 0.5 s starting at 1.7 s in the document.
        assertArrayEquals(new int[] {31, 40, 49, 58, 67},
                TopEvokerSkillTiming.volleyTicks(TopEvokerSkill.CROSSBOW.animation()));

        assertTrue(TopEvokerSkill.IDLE.loops());
        assertTrue(TopEvokerSkill.CHARGE.loops());
        assertFalse(TopEvokerSkill.CROSSBOW.loops());
        assertFalse(TopEvokerSkill.CHARGE_WINDUP.loops());
    }

    @Test
    @DisplayName("the clip lengths in the file match the source document")
    void clipLengthsMatchTheSource() {
        Map<String, Double> lengths = new LinkedHashMap<>();
        for (JsonElement element : bbmodel.getAsJsonArray("animations")) {
            JsonObject animation = element.getAsJsonObject();
            lengths.put(animation.get("name").getAsString(),
                    animation.get("length").getAsDouble());
        }
        TreeSet<String> names = new TreeSet<>(lengths.keySet());
        assertEquals(EXPECTED_ANIMATIONS, names.size());
        for (String name : names) {
            // The converter divides every timestamp by ANIMATION_SPEED, so the file's length is the
            // document's length over that factor. Nothing else about the clip may change.
            assertEquals(lengths.get(name) / PLAYBACK_SPEED,
                    TopEvokerSkillTiming.clip(name).lengthSeconds(), 1.0E-4D, name);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void assertArrayEquals(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "entry " + i);
        }
    }

    /**
     * Samples a 3x3 grid inside a face and reports whether the source sheet and the atlas disagree.
     *
     * <p>The atlas rectangle is {@code (x0,y0)-(x1,y1)}; the same face lives at {@code y + dy} in
     * its own source sheet, because the merge stacked the second texture 128 rows down.
     */
    private static int coloursDiffer(BufferedImage source, double dy, BufferedImage atlas,
            double x0, double y0, double x1, double y1) {
        int left = clamp((int) Math.min(x0, x1), atlas.getWidth() - 1);
        int right = clamp((int) Math.max(Math.max(x0, x1) - 1, left), atlas.getWidth() - 1);
        int top = clamp((int) Math.min(y0, y1), atlas.getHeight() - 1);
        int bottom = clamp((int) Math.max(Math.max(y0, y1) - 1, top), atlas.getHeight() - 1);
        int dyi = (int) dy;
        int[] xs = {left, (left + right) / 2, right};
        int[] ys = {top, (top + bottom) / 2, bottom};
        for (int y : ys) {
            for (int x : xs) {
                if (source.getRGB(x, y + dyi) != atlas.getRGB(x, y)) {
                    return 1;
                }
            }
        }
        return 0;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private static String key(double[] uv) {
        return String.format("%.4f/%.4f/%.4f/%.4f", uv[0], uv[1], uv[2], uv[3]);
    }

    private static double[] readVector(JsonArray array) {
        double[] out = new double[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsDouble();
        }
        return out;
    }

    private static JsonObject geometryObject() {
        return geometryDoc.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
    }

    private static JsonObject description() {
        return geometryObject().getAsJsonObject("description");
    }

    private static JsonArray bones() {
        return geometryObject().getAsJsonArray("bones");
    }

    private static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Path projectDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("build.gradle"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "cannot locate the project directory");
        return dir;
    }

    private static Path sourceModel() {
        Path beside = projectDir.getParent().resolve("models/top_evoker/top_evoker.bbmodel");
        if (Files.isRegularFile(beside)) {
            return beside;
        }
        return projectDir.resolve("src/test/resources/top_evoker.bbmodel");
    }

    private static String sha256(Path path) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString().toUpperCase(java.util.Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
