package com.bannerbound.core.api.research;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;

/** Data-driven learn-by-doing boost attached to a research, culture, or faith node. */
public record InsightDefinition(
        InsightTrigger trigger,
        double boostFraction,
        double boostPoints,
        String message) {

    public static final double DEFAULT_BOOST_FRACTION = 0.40;

    public record InsightTrigger(String type, String target, int count) {
        public static final StreamCodec<ByteBuf, InsightTrigger> STREAM_CODEC = StreamCodec.of(
            (buf, trigger) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, trigger.type());
                ByteBufCodecs.STRING_UTF8.encode(buf, trigger.target());
                ByteBufCodecs.VAR_INT.encode(buf, trigger.count());
            },
            buf -> new InsightTrigger(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf))
        );
    }

    public static final StreamCodec<ByteBuf, InsightDefinition> STREAM_CODEC = StreamCodec.of(
        (buf, insight) -> {
            InsightTrigger.STREAM_CODEC.encode(buf, insight.trigger());
            buf.writeDouble(insight.boostFraction());
            buf.writeDouble(insight.boostPoints());
            ByteBufCodecs.STRING_UTF8.encode(buf, insight.message());
        },
        buf -> new InsightDefinition(
            InsightTrigger.STREAM_CODEC.decode(buf),
            buf.readDouble(),
            buf.readDouble(),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    /**
     * Parses and validates an optional insight. A malformed insight disables only itself so one
     * bad datapack node cannot take down the whole tree.
     */
    public static InsightDefinition parse(JsonObject node, ResourceLocation nodeId) {
        if (!node.has("insight")) return null;
        try {
            JsonObject obj = GsonHelper.getAsJsonObject(node, "insight");
            JsonObject triggerObj = GsonHelper.getAsJsonObject(obj, "trigger");
            String type = GsonHelper.getAsString(triggerObj, "type").trim();
            String target = GsonHelper.getAsString(triggerObj, "target", "").trim();
            int count = GsonHelper.getAsInt(triggerObj, "count", 1);
            if (type.isEmpty() || count <= 0) {
                throw new IllegalArgumentException("trigger type must be non-empty and count must be positive");
            }
            InsightTriggerRegistry.Type registered = InsightTriggerRegistry.get(type);
            if (registered == null) {
                throw new IllegalArgumentException("unknown trigger type '" + type + "'");
            }
            if (registered.targetRequired() && target.isEmpty()) {
                throw new IllegalArgumentException("trigger type '" + type + "' requires a target");
            }
            validateTarget(target);

            boolean hasFraction = obj.has("boost");
            double fraction = hasFraction
                ? GsonHelper.getAsDouble(obj, "boost")
                : (obj.has("boost_points") ? 0.0 : DEFAULT_BOOST_FRACTION);
            double points = hasFraction ? 0.0 : GsonHelper.getAsDouble(obj, "boost_points", 0.0);
            if (!Double.isFinite(fraction) || !Double.isFinite(points)
                    || fraction < 0.0 || points < 0.0 || (fraction == 0.0 && points == 0.0)) {
                throw new IllegalArgumentException("boost must be finite and positive");
            }
            return new InsightDefinition(
                new InsightTrigger(type, target, count),
                fraction,
                points,
                GsonHelper.getAsString(obj, "message", ""));
        } catch (Exception ex) {
            BannerboundCore.LOGGER.warn("Ignoring malformed insight on {}: {}", nodeId, ex.getMessage());
            return null;
        }
    }

    private static void validateTarget(String target) {
        if (target.isEmpty()) return;
        String raw = target.startsWith("#") ? target.substring(1) : target;
        if (ResourceLocation.tryParse(raw) == null) {
            throw new IllegalArgumentException("invalid target '" + target + "'");
        }
    }
}
