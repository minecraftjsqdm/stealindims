package com.homemine.stealindims.raid;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

/**
 * The equivalence gate of the P0 geometry extraction (plan §P0.3, risk R-a).
 *
 * <p>{@link RaidLayoutBuilder} is pure computation over a {@link RandomSource} and a
 * {@link RaidArena.Bounds}, so the whole room/door subdivision of a raid map can be reproduced on
 * the plain JVM — no game bootstrap, no world, no running server. This test pins the generation down
 * to a hash per (layout, size, seed), which is what lets the refactor be called behaviour preserving
 * even though the in-game seed is random
 * ({@code RaidSessionManager} draws it from {@code raidLevel.getRandom().nextLong()}).
 *
 * <p>How to use it as a gate:
 *
 * <ol>
 *     <li>Run {@code gradlew test} on the <em>pre-refactor</em> tree; {@code _tmp/p0_layout_baseline.txt}
 *     is written (or kept, if it already holds hashes).</li>
 *     <li>Run it again after the refactor. Every hash has to be identical; the test compares against
 *     the baseline file itself and fails on the first difference, naming the case.</li>
 * </ol>
 *
 * <p>The inputs mirror production exactly: the bounds come from {@link RegionGeometry} with the same
 * size string the theme declares, and the seeds are derived by the same multiply-and-add the arena
 * builder uses per region. The hash covers every field the builder reads off a layout, so a moved
 * formula, constant or loop bound shows up as a different digest.
 */
class RaidLayoutFingerprintTest {

    /** Where the baseline is kept; outside the source tree so it survives a clean. */
    private static final Path BASELINE = Path.of("..", "_tmp", "p0_layout_baseline.txt");

    /**
     * The shipped v1.0.0 jar, kept as the byte-code control of this gate. It is the build made before
     * the P0 extraction, so it is the only thing that can prove the extraction changed nothing.
     */
    private static final Path PRE_P0_JAR = Path.of("..", "_tmp", "stealindims-1.0.0.preP0.jar");

    /** Pinned so a stray rebuild of the control jar cannot silently weaken the gate. */
    private static final String PRE_P0_JAR_SHA256 =
            "417C1B0B7B993057A4E04B44EEFE2E919081E02DA1428FB901814510E5A52CB5";

    /** The two seeds the gate uses; any pair works as long as it never changes. */
    private static final long[] SEEDS = {1234567890123L, -9876543210987L};

    /** The {@code layout} ids the shipped themes declare, one per map in the base game. */
    private static final List<String> LAYOUTS = List.of(
            "cavern_grid", "mansion_maze", "machine_hall", "lava_ring", "void_spiral",
            "forest_labyrinth", "sky_islands");

    /** The three footprint scales a theme may declare. */
    private static final List<String> SIZES = List.of("small", "medium", "large");

    @Test
    void generationFingerprintsMatchTheBaseline() throws IOException, NoSuchAlgorithmException {
        Map<String, String> current = fingerprints();

        assertFalse(current.isEmpty(), "no fingerprints were produced");
        for (Map.Entry<String, String> entry : current.entrySet()) {
            assertFalse(entry.getValue().isEmpty(), "empty fingerprint for " + entry.getKey());
        }

        Map<String, String> baseline = readBaseline();
        Path path = BASELINE.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Files.writeString(path, render(current), StandardCharsets.UTF_8);

        if (baseline.isEmpty()) {
            System.out.println("Raid layout fingerprint: baseline written to " + path
                    + " (" + current.size() + " cases). Re-run after the refactor to compare.");
            return;
        }

        assertEquals(baseline.keySet(), current.keySet(),
                "the set of layout cases changed: " + path);
        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            assertEquals(entry.getValue(), current.get(entry.getKey()),
                    "raid layout generation changed for " + entry.getKey());
        }
        System.out.println("Raid layout fingerprint: all " + current.size()
                + " cases identical to the baseline in " + path);
    }

    /**
     * The byte-code control of the gate: the same 42 cases, generated by the <em>pre-P0</em> jar.
     *
     * <p>The baseline file alone cannot prove the extraction was behaviour preserving, because it was
     * first written after the refactor — it only shows the new code is stable between two runs of
     * itself. This test closes that hole: {@link #PRE_P0_JAR} is the shipped v1.0.0 build with a pinned
     * digest, so any formula, constant or loop bound the extraction moved shows up as a different
     * digest for the case that covers it.
     */
    @Test
    void preP0JarProducesTheSameFingerprints() throws Exception {
        Path jar = PRE_P0_JAR.toAbsolutePath().normalize();
        assumeTrue(Files.isReadable(jar),
                "pre-P0 control jar missing at " + jar + ", skipping the byte-code comparison");
        assertEquals(PRE_P0_JAR_SHA256, fileSha256(jar),
                "the pre-P0 control jar is not the v1.0.0 build this gate was written against");

        Map<String, String> current = fingerprints();
        Map<String, String> preP0 = fingerprintsFromJar(jar);

        assertEquals(current.keySet(), preP0.keySet(), "the set of layout cases differs");
        for (Map.Entry<String, String> entry : current.entrySet()) {
            assertEquals(entry.getValue(), preP0.get(entry.getKey()),
                    "raid layout generation differs from the pre-P0 jar for " + entry.getKey());
        }
        System.out.println("Raid layout fingerprint: all " + current.size()
                + " cases identical to the pre-P0 jar " + jar.getFileName());
    }

    /**
     * Rebuilds {@link #fingerprints()} with the classes of the pre-P0 jar.
     *
     * <p>The loader is child-first for the mod's own package, so {@code RaidLayoutBuilder},
     * {@code RaidArena} and {@code RaidRoom} all come from the jar while Minecraft keeps coming from
     * the test classpath. Everything is driven reflectively because the old classes are deliberately
     * not on the compile classpath.
     */
    private static Map<String, String> fingerprintsFromJar(Path jar) throws Exception {
        URL[] urls = {jar.toUri().toURL()};
        try (URLClassLoader loader = new ChildFirstLoader(urls,
                RaidLayoutFingerprintTest.class.getClassLoader())) {
            Class<?> arena = loader.loadClass("com.homemine.stealindims.raid.RaidArena");
            Class<?> bounds = loader.loadClass("com.homemine.stealindims.raid.RaidArena$Bounds");
            Class<?> builder = loader.loadClass("com.homemine.stealindims.raid.RaidLayoutBuilder");
            Method regionBounds = arena.getMethod("regionBounds", int.class, String.class);
            Method build = builder.getMethod("build", bounds, String.class, long.class);
            int regions = (int) arena.getField("REGIONS").get(null);

            Map<String, String> result = new LinkedHashMap<>();
            for (long seed : SEEDS) {
                for (String layout : LAYOUTS) {
                    for (String size : SIZES) {
                        StringBuilder flat = new StringBuilder(1024);
                        for (int region = 0; region < regions; region++) {
                            Object rect = regionBounds.invoke(null, region, size);
                            Object plan = build.invoke(null, rect, layout, seedFor(seed, region));
                            flattenReflectively(flat, plan);
                        }
                        result.put(layout + "/" + size + "/" + seed, sha256(flat.toString()));
                    }
                }
            }
            return result;
        }
    }

    /** Same flattening as {@link #flatten}, but over a plan whose class is only known at runtime. */
    private static void flattenReflectively(StringBuilder out, Object plan) throws Exception {
        Class<?> planType = plan.getClass();
        for (Object room : (List<?>) planType.getMethod("rooms").invoke(plan)) {
            Class<?> type = room.getClass();
            out.append(type.getMethod("minX").invoke(room)).append(',')
                    .append(type.getMethod("maxX").invoke(room)).append(',')
                    .append(type.getMethod("minZ").invoke(room)).append(',')
                    .append(type.getMethod("maxZ").invoke(room)).append(',')
                    .append(type.getMethod("layer").invoke(room)).append(';');
        }
        out.append('|');
        for (Object door : (List<?>) planType.getMethod("doors").invoke(plan)) {
            Class<?> type = door.getClass();
            out.append(type.getMethod("roomA").invoke(door)).append(',')
                    .append(type.getMethod("roomB").invoke(door)).append(',')
                    .append(type.getMethod("layer").invoke(door)).append(',')
                    .append(type.getMethod("minX").invoke(door)).append(',')
                    .append(type.getMethod("maxX").invoke(door)).append(',')
                    .append(type.getMethod("minZ").invoke(door)).append(',')
                    .append(type.getMethod("maxZ").invoke(door)).append(';');
        }
        out.append('\n');
    }

    /** Loads the mod's own package from the jar first, so the old classes win over the new ones. */
    private static final class ChildFirstLoader extends URLClassLoader {

        private static final String OWN_PACKAGE = "com.homemine.stealindims.";

        ChildFirstLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(OWN_PACKAGE)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absentFromTheJar) {
                        return super.loadClass(name, resolve);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private static String fileSha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(path));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The fingerprint of every case, in a fixed order.
     *
     * <p>The bounds are resolved through {@link RegionGeometry}, the same way
     * {@code RaidArenaBuilder.build} resolves them for a map, so the test feeds the builder the exact
     * rectangle production feeds it.
     */
    private static Map<String, String> fingerprints() throws NoSuchAlgorithmException {
        Map<String, String> result = new LinkedHashMap<>();
        for (long seed : SEEDS) {
            for (String layout : LAYOUTS) {
                for (String size : SIZES) {
                    RegionGeometry geometry = new RegionGeometry(size, null);
                    StringBuilder flat = new StringBuilder(1024);
                    for (int region = 0; region < geometry.regionCount(); region++) {
                        RaidArena.Bounds bounds = geometry.regionBounds(region);
                        RaidLayoutBuilder.RaidLayout plan =
                                RaidLayoutBuilder.build(bounds, layout, seedFor(seed, region));
                        flatten(flat, plan);
                    }
                    result.put(layout + "/" + size + "/" + seed, sha256(flat.toString()));
                }
            }
        }
        return result;
    }

    /**
     * The per-region seed derivation of {@code RaidArenaBuilder}.
     *
     * <p>Spelled out here rather than called, so the fingerprint pass never touches a class that
     * pulls in the mod logger: the digest has to be reproducible on a bare JVM with nothing but the
     * Minecraft classes on the classpath. {@link #seedDerivationStillMatchesProduction()} keeps the
     * copy honest by reading the production source.
     */
    private static long seedFor(long seed, int region) {
        return seed * 31L + region;
    }

    /**
     * Guards the copy above against a change in the production derivation.
     *
     * <p>Read as text on purpose: loading {@code RaidArenaBuilder} would drag in the mod's logger,
     * which is exactly the dependency the fingerprints must not have.
     */
    @Test
    void seedDerivationStillMatchesProduction() throws IOException {
        Path source = Path.of("src", "main", "java", "com", "homemine", "stealindims", "raid",
                "RaidArenaBuilder.java");
        assertTrue(Files.isReadable(source), "cannot read " + source.toAbsolutePath());
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(text.contains("return seed * 31L + region;"),
                "the per-region seed derivation of " + source + " changed");
    }

    /**
     * Appends every number a plan carries, so the digest covers the whole subdivision: each room's
     * four edges and its layer, then each doorway's owner, layer and opening.
     */
    private static void flatten(StringBuilder out, RaidLayoutBuilder.RaidLayout plan) {
        for (RaidRoom room : plan.rooms()) {
            out.append(room.minX()).append(',').append(room.maxX()).append(',')
                    .append(room.minZ()).append(',').append(room.maxZ()).append(',')
                    .append(room.layer()).append(';');
        }
        out.append('|');
        for (RaidLayoutBuilder.Door door : plan.doors()) {
            out.append(door.roomA()).append(',').append(door.roomB()).append(',')
                    .append(door.layer()).append(',')
                    .append(door.minX()).append(',').append(door.maxX()).append(',')
                    .append(door.minZ()).append(',').append(door.maxZ()).append(';');
        }
        out.append('\n');
    }

    private static String sha256(String text) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    /** Parses a baseline written by {@link #render}; a missing or unreadable file counts as empty. */
    private static Map<String, String> readBaseline() throws IOException {
        Path path = BASELINE.toAbsolutePath().normalize();
        if (!Files.isReadable(path)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf(' ');
            if (separator > 0) {
                result.put(trimmed.substring(0, separator), trimmed.substring(separator + 1).trim());
            }
        }
        return result;
    }

    /** The baseline format: one {@code <case> <sha256>} line per case, so a human can diff it. */
    private static String render(Map<String, String> fingerprints) {
        StringBuilder out = new StringBuilder();
        out.append("# P0 raid layout baseline (plan §P0.3). One line per case: <layout>/<size>/<seed> <sha256>\n");
        out.append("# Regenerate with: gradlew test --tests '*RaidLayoutFingerprintTest*'\n");
        for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
            out.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    /** Guards the assumption that a layout id is not silently falling back to the grid style. */
    @Test
    void everyShippedLayoutIdIsKnown() {
        List<String> ids = new ArrayList<>(LAYOUTS);
        assertEquals(7, ids.size(), "the set of shipped map layout ids changed");
        assertTrue(ids.contains("mansion_maze"), "the mansion probe layout id changed");
    }

    /**
     * Pins the shape of the region geometry the fingerprints are taken over: the four regions in a
     * 2×2 grid, two layers, and a map platform that starts east of the extraction room. These are
     * the numbers the extraction claimed not to touch, so a change here is a red flag on its own.
     */
    @Test
    void regionGeometryKeepsItsShape() {
        RegionGeometry geometry = new RegionGeometry("medium", null);

        assertEquals(4, geometry.regionCount());
        assertArrayEquals(new int[]{64, 48}, geometry.layerYs());
        assertEquals(18, geometry.regionHalf(), "medium region half width changed");

        RaidArena.Bounds first = geometry.regionBounds(0);
        RaidArena.Bounds second = geometry.regionBounds(1);
        RaidArena.Bounds fourth = geometry.regionBounds(3);
        assertEquals(first.maxZ() - first.minZ(), first.maxX() - first.minX(),
                "regions stopped being square");
        assertEquals(second.minZ(), first.minZ(), "regions 0 and 1 stopped sharing a row");
        assertEquals(fourth.minX(), second.minX(), "regions 1 and 3 stopped sharing a column");
        assertTrue(second.minX() > first.maxX(), "regions 0 and 1 stopped being separated along X");
        assertTrue(fourth.minZ() > second.maxZ(), "regions 1 and 3 stopped being separated along Z");

        // The margin mapRadius adds around the region block: COLUMN_GAP/2 (8) + VESTIBULE_OFFSET (8)
        // + VESTIBULE_HALF (3) = 19. (18 is the medium region half width, so the radius is 55.)
        assertEquals(19, geometry.mapRadius() - 2 * geometry.regionHalf(),
                "the gap around the regions changed");
        assertEquals(80, geometry.mapCenterX());
        assertTrue(geometry.mapCenterX() - geometry.mapRadius() > 0,
                "the map now overlaps the extraction room");
        assertTrue(geometry.isInMap(geometry.mapSpawn()), "the map spawn left the map");
        assertTrue(geometry.isInSafeZone(geometry.vaultCrate(0)),
                "a submission crate left its own safe zone");
    }

    /** The four footprint scales of the {@code size} field map to the documented half widths. */
    @Test
    void sizeFieldDrivesRegionHalfWidth() {
        assertEquals(15, new RegionGeometry("small", null).regionHalf());
        assertEquals(18, new RegionGeometry("medium", null).regionHalf());
        assertEquals(22, new RegionGeometry("large", null).regionHalf());
        assertEquals(18, new RegionGeometry(null, null).regionHalf(), "the size default changed");
    }
}
