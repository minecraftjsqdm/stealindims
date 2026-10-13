package com.homemine.stealindims.raid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * The "don't change it" gate of R23 (spec §5.4).
 *
 * <p>The illager statue is no longer built by the mod: it is the user's own
 * {@code data/stealindims/structure/illager_statue.nbt}, stamped verbatim. That promise is only worth
 * something if the file in the mod's resources is still the file the user supplied, so the first test
 * pins it by SHA-256 — any replaced block, added bonemeal, re-saved palette or even a re-gzipped copy
 * changes the digest — and the second one reads the compressed structure back to prove the pieces
 * that make the statue work are still inside it: the two {@code lime_wool} eyes, the Create drivetrain
 * and, above all, the single {@code create:super_glue} entity that makes the nose a contraption.
 *
 * <p>No NBT library is needed for either: the digest is over the bytes, and the entity and block ids
 * are stored as plain UTF-8 strings in the payload, so a byte search finds them.
 */
class IllagerStatueTemplateTest {

    /** The shipped statue, relative to the project directory a Gradle test runs in. */
    private static final Path TEMPLATE = Path.of("src", "main", "resources", "data", "stealindims",
            "structure", "illager_statue.nbt");

    /** The digest of the user's original file, recorded in spec §5.4. */
    private static final String SHA256 =
            "4CC21B7640900F73B40D94DEE6E5F1B327FFCE287B4B63C63AE49F4F81080F5D";

    /** The size of the user's original file, in bytes. */
    private static final int SIZE = 970;

    @Test
    void theShippedStatueIsStillTheUsersFileByteForByte() throws IOException, NoSuchAlgorithmException {
        assertTrue(Files.isRegularFile(TEMPLATE), "the statue template is missing: " + TEMPLATE);
        byte[] bytes = Files.readAllBytes(TEMPLATE);
        assertEquals(SIZE, bytes.length,
                "the statue template is not the " + SIZE + " byte file the user supplied");
        assertEquals(SHA256, hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "the statue template was modified; R23 asks for the user's file placed untouched");
    }

    @Test
    void theStatueStillCarriesItsFaceDrivetrainAndGlue() throws IOException {
        byte[] bytes = Files.readAllBytes(TEMPLATE);
        String payload = new String(gunzip(bytes), StandardCharsets.ISO_8859_1);
        for (String needle : new String[]{
                "minecraft:light_gray_wool",
                "minecraft:gray_wool",
                "minecraft:white_wool",
                "minecraft:lime_wool",
                "create:hand_crank",
                "create:encased_chain_drive",
                "create:mechanical_bearing",
                "create:super_glue"}) {
            assertTrue(payload.contains(needle),
                    "the statue template no longer contains " + needle);
        }
    }

    /** The file is a vanilla structure, so it is gzipped NBT; read it back to search the payload. */
    private static byte[] gunzip(byte[] bytes) throws IOException {
        if (bytes.length < 2 || bytes[0] != (byte) 0x1F || bytes[1] != (byte) 0x8B) {
            return bytes;
        }
        try (GZIPInputStream stream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return stream.readAllBytes();
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16));
            out.append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
