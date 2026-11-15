package fr.cobblepulse.utils.net;

import fr.cobblepulse.utils.CobbleUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Deux paquets generiques (type + JSON) entre les ecrans client (Hub, Wiki) et le serveur. */
public final class Payloads {
    private static final StreamCodec<ByteBuf, String> BIG_STRING = ByteBufCodecs.stringUtf8(1 << 22);

    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> makeType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CobbleUtils.MOD_ID, path));
    }

    /** Client -> serveur : action + arguments JSON. */
    public record Action(String action, String json) implements CustomPacketPayload {
        public static final Type<Action> TYPE = makeType("action");
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Action::action, BIG_STRING, Action::json, Action::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Serveur -> client : donnees. */
    public record Data(String kind, String json) implements CustomPacketPayload {
        public static final Type<Data> TYPE = makeType("data");
        public static final StreamCodec<ByteBuf, Data> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Data::kind, BIG_STRING, Data::json, Data::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
