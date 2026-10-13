package com.homemine.stealindims.entity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The nine clips of {@code top_evoker.animation.json}, read straight out of this mod's own
 * resources.
 *
 * <p>R27 §3.2 asks for the boss's damage windows to come from the animation's timestamps rather
 * than from guessed constants. This class is that source of truth for the whole encounter: every
 * state's length, every cast's release frame and the five bolt volleys are all derived here from
 * the very file GeckoLib plays, so re-timing the model re-times the fight.
 *
 * <p>The parser is deliberately plain: GeckoLib's own loader cannot be used on the server (it is a
 * client-side class), and the file is small enough that a straight Gson walk is cheaper than
 * standing up a resource-reload listener. The layout it understands is the one
 * {@code tools/bbmodel2geckolib.py} writes, and {@code TopEvokerConversionTest} fails the build if
 * the two ever drift apart.
 *
 * <p>Timestamps in the file are in <em>seconds</em>; GeckoLib multiplies them by 20 to reach ticks
 * ({@code BakedAnimationsAdapter.bakeAnimation} does {@code animation_length * 20.0}), and the same
 * conversion is applied here so the server state machine and the client animation agree.
 */
public final class TopEvokerSkillTiming {

    /** Where the converter writes the clip file inside the mod jar. */
    public static final String RESOURCE =
            "/assets/stealindims/animations/entity/top_evoker.animation.json";

    /** GeckoLib's own seconds-to-ticks factor (see {@code BakedAnimationsAdapter}). */
    public static final double TICKS_PER_SECOND = 20.0D;

    private static final Pattern BOLT_BONE = Pattern.compile("bolt_\\d+_\\d+");

    private static final Map<String, Clip> CLIPS = load();

    private TopEvokerSkillTiming() {
    }

    // ------------------------------------------------------------------ lookups

    /** Every clip in the file, keyed by its Blockbench animation name. */
    public static Map<String, Clip> clips() {
        return CLIPS;
    }

    /** The clip with this Blockbench animation name; throws if the converter emitted something else. */
    public static Clip clip(String animation) {
        Clip clip = CLIPS.get(animation);
        if (clip == null) {
            throw new IllegalStateException("top_evoker.animation.json has no animation "
                    + animation + "; it only has " + CLIPS.keySet());
        }
        return clip;
    }

    /** How long the clip runs, in ticks, rounded to the nearest tick. */
    public static int lengthTicks(String animation) {
        return ticks(clip(animation).lengthSeconds());
    }

    /** Seconds to whole ticks, the same rounding GeckoLib's keyframe math lands on. */
    public static int ticks(double seconds) {
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }

    // ------------------------------------------------------------------ release frames

    /** Picks the instant a cast lands, given one of the {@link #minOf} / {@link #maxOf} rules. */
    public interface Selector {
        double time(Clip clip);
    }

    /**
     * The release frame of a cast, in ticks, as chosen by {@code selector}.
     *
     * <p>The rules below are the ones {@link TopEvokerSkill} declares per clip; they read the
     * caster's arm keyframes, which is where the model actually performs the gesture (the
     * {@code skill_effects} bone is only the static parent of the prop bones and carries no
     * keyframes at all).
     */
    public static int releaseTicks(String animation, Selector selector) {
        return ticks(selector.time(clip(animation)));
    }

    /** The keyframe where the given channel dips lowest on {@code axis} — an outward sweep. */
    public static Selector minOf(String bone, String channel, int axis) {
        return clip -> extreme(clip, bone, channel, axis, true);
    }

    /** The keyframe where the given channel rises highest on {@code axis} — a raised reach. */
    public static Selector maxOf(String bone, String channel, int axis) {
        return clip -> extreme(clip, bone, channel, axis, false);
    }

    /**
     * The keyframe <em>after</em> the channel's peak — the thrust that follows the wind-up.
     *
     * <p>Finger-pointing casts (04_直线尖刺) raise the arm first and only level it on the next
     * frame; that next frame is the one that spawns the spikes, not the wind-up.
     */
    public static Selector afterPeakOf(String bone, String channel, int axis) {
        return clip -> {
            List<Key> keys = clip.keys(bone, channel);
            if (keys.isEmpty()) {
                throw new IllegalStateException(clip.name() + ": " + bone + "." + channel + " is empty");
            }
            int peak = 0;
            for (int i = 1; i < keys.size(); i++) {
                if (component(keys.get(i), axis) > component(keys.get(peak), axis)) {
                    peak = i;
                }
            }
            return keys.get(Math.min(peak + 1, keys.size() - 1)).time();
        };
    }

    /**
     * The keyframe <em>after</em> the channel's trough — the same thrust, read from the other end of
     * the axis.
     *
     * <p>This exists because the converter negates the X rotation of every bone: Bedrock/GeckoLib
     * rotate a bone about +X the opposite way round from Blockbench's model space, so a wind-up that
     * used to be the channel's peak is now its trough (see {@code tools/bbmodel2geckolib.py}'s
     * {@code FLIP_ROTATION_X}). The gesture itself is unchanged, so the selector that reads it has to
     * be mirrored with it — otherwise the spikes would fire on the wrong frame.
     */
    public static Selector afterTroughOf(String bone, String channel, int axis) {
        return clip -> {
            List<Key> keys = clip.keys(bone, channel);
            if (keys.isEmpty()) {
                throw new IllegalStateException(clip.name() + ": " + bone + "." + channel + " is empty");
            }
            int trough = 0;
            for (int i = 1; i < keys.size(); i++) {
                if (component(keys.get(i), axis) < component(keys.get(trough), axis)) {
                    trough = i;
                }
            }
            return keys.get(Math.min(trough + 1, keys.size() - 1)).time();
        };
    }

    private static double extreme(Clip clip, String bone, String channel, int axis, boolean lowest) {
        List<Key> keys = clip.keys(bone, channel);
        if (keys.isEmpty()) {
            throw new IllegalStateException(clip.name() + ": " + bone + "." + channel + " is empty");
        }
        Key best = keys.get(0);
        for (Key key : keys) {
            double value = component(key, axis);
            if (lowest ? value < component(best, axis) : value > component(best, axis)) {
                best = key;
            }
        }
        return best.time();
    }

    private static double component(Key key, int axis) {
        return switch (axis) {
            case 0 -> key.x();
            case 1 -> key.y();
            default -> key.z();
        };
    }

    // ------------------------------------------------------------------ the five volleys

    /**
     * When each of the five crossbows fires, in ticks.
     *
     * <p>02_弩箭大师 is authored as fifteen {@code bolt_i_j} bones that sit still until their
     * volley and then travel out to {@code z = -40} model units. The instant a bolt leaves is
     * therefore the last {@code position} keyframe at which it is still at rest, which is what this
     * scans for. All three bolts of one crossbow share a frame, so the result has five entries.
     */
    public static int[] volleyTicks(String animation) {
        Clip clip = clip(animation);
        TreeSet<Double> instants = new TreeSet<>();
        for (String bone : clip.bones().keySet()) {
            if (!BOLT_BONE.matcher(bone).matches()) {
                continue;
            }
            List<Key> keys = clip.keys(bone, "position");
            for (int i = 0; i + 1 < keys.size(); i++) {
                if (atRest(keys.get(i)) && !atRest(keys.get(i + 1))) {
                    instants.add(keys.get(i).time());
                }
            }
        }
        int[] ticks = new int[instants.size()];
        int index = 0;
        for (double time : instants) {
            ticks[index++] = ticks(time);
        }
        return ticks;
    }

    private static boolean atRest(Key key) {
        return key.x() == 0.0D && key.y() == 0.0D && key.z() == 0.0D;
    }

    // ------------------------------------------------------------------ parsing

    private static Map<String, Clip> load() {
        try (InputStream stream = TopEvokerSkillTiming.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE);
            }
            JsonObject root = JsonParser
                    .parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject animations = root.getAsJsonObject("animations");
            if (animations == null) {
                throw new IllegalStateException(RESOURCE + " has no \"animations\" object");
            }
            Map<String, Clip> clips = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
                clips.put(entry.getKey(), readClip(entry.getKey(), entry.getValue().getAsJsonObject()));
            }
            if (clips.isEmpty()) {
                throw new IllegalStateException(RESOURCE + " declares no animations");
            }
            return Collections.unmodifiableMap(clips);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    private static Clip readClip(String name, JsonObject json) {
        String loop = json.has("loop") ? json.get("loop").getAsString() : "play_once";
        double length = json.has("animation_length") ? json.get("animation_length").getAsDouble() : 0.0D;
        Map<String, Map<String, List<Key>>> bones = new LinkedHashMap<>();
        JsonObject boneJson = json.getAsJsonObject("bones");
        if (boneJson != null) {
            for (Map.Entry<String, JsonElement> boneEntry : boneJson.entrySet()) {
                Map<String, List<Key>> channels = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> channelEntry
                        : boneEntry.getValue().getAsJsonObject().entrySet()) {
                    List<Key> keys = new ArrayList<>();
                    for (Map.Entry<String, JsonElement> frameEntry
                            : channelEntry.getValue().getAsJsonObject().entrySet()) {
                        double time = Double.parseDouble(frameEntry.getKey());
                        var vector = frameEntry.getValue().getAsJsonObject().getAsJsonArray("vector");
                        keys.add(new Key(time, vector.get(0).getAsDouble(),
                                vector.get(1).getAsDouble(), vector.get(2).getAsDouble()));
                    }
                    keys.sort((a, b) -> Double.compare(a.time(), b.time()));
                    channels.put(channelEntry.getKey(), Collections.unmodifiableList(keys));
                }
                bones.put(boneEntry.getKey(), Collections.unmodifiableMap(channels));
            }
        }
        return new Clip(name, loop, length, Collections.unmodifiableMap(bones));
    }

    /** One keyframe: a timestamp in seconds plus the three components of its vector. */
    public record Key(double time, double x, double y, double z) {
    }

    /** One animation: its name, its Bedrock loop id, its length and its keyframes by bone/channel. */
    public static final class Clip {

        private final String name;
        private final String loop;
        private final double lengthSeconds;
        private final Map<String, Map<String, List<Key>>> bones;

        Clip(String name, String loop, double lengthSeconds, Map<String, Map<String, List<Key>>> bones) {
            this.name = name;
            this.loop = loop;
            this.lengthSeconds = lengthSeconds;
            this.bones = bones;
        }

        public String name() {
            return this.name;
        }

        /** {@code "loop"}, {@code "play_once"} or {@code "hold_on_last_frame"}. */
        public String loop() {
            return this.loop;
        }

        public double lengthSeconds() {
            return this.lengthSeconds;
        }

        public int lengthTicks() {
            return TopEvokerSkillTiming.ticks(this.lengthSeconds);
        }

        public Map<String, Map<String, List<Key>>> bones() {
            return this.bones;
        }

        /** The keyframes of one bone/channel, sorted by time; empty when the clip does not use it. */
        public List<Key> keys(String bone, String channel) {
            Map<String, List<Key>> channels = this.bones.get(bone);
            if (channels == null) {
                return List.of();
            }
            return channels.getOrDefault(channel, List.of());
        }
    }
}
