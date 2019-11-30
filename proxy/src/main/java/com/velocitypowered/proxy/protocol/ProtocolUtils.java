package com.velocitypowered.proxy.protocol;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.util.except.QuietDecodeException;
import com.velocitypowered.proxy.util.except.QuietException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.util.ByteProcessor;
import io.netty.util.concurrent.FastThreadLocal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public enum ProtocolUtils {
  ;
  private static final int DEFAULT_MAX_STRING_SIZE = 65536; // 64KiB
  private static final FastThreadLocal<VarintByteDecoder> VARINT_DECODER = new FastThreadLocal<>() {
    @Override
    protected VarintByteDecoder initialValue() {
      return new VarintByteDecoder();
    }
  };

  /**
   * Reads a Minecraft-style VarInt from the specified {@code buf}.
   * @param buf the buffer to read from
   * @return the decoded VarInt
   */
  public static int readVarInt(ByteBuf buf) {
    VarintByteDecoder decoder = VARINT_DECODER.get();
    try {
      int idx = buf.forEachByte(decoder);
      if (decoder.successfulDecode && idx >= 0) {
        buf.readerIndex(idx + 1);
        return decoder.accumulated;
      } else {
        throw new QuietDecodeException("Incomplete VarInt or VarInt too big!");
      }
    } finally {
      decoder.reset();
    }
  }

  /**
   * Writes a Minecraft-style VarInt to the specified {@code buf}.
   * @param buf the buffer to read from
   * @param value the integer to write
   */
  public static void writeVarInt(ByteBuf buf, int value) {
    while (true) {
      if ((value & 0xFFFFFF80) == 0) {
        buf.writeByte(value);
        return;
      }

      buf.writeByte(value & 0x7F | 0x80);
      value >>>= 7;
    }
  }

  public static String readString(ByteBuf buf) {
    return readString(buf, DEFAULT_MAX_STRING_SIZE);
  }

  /**
   * Reads a VarInt length-prefixed string from the {@code buf}, making sure to not go over
   * {@code cap} size.
   * @param buf the buffer to read from
   * @param cap the maximum size of the string, in UTF-8 character length
   * @return the decoded string
   */
  public static String readString(ByteBuf buf, int cap) {
    int length = readVarInt(buf);
    checkArgument(length >= 0, "Got a negative-length string (%s)", length);
    // `cap` is interpreted as a UTF-8 character length. To cover the full Unicode plane, we must
    // consider the length of a UTF-8 character, which can be up to a 4 bytes. We do an initial
    // sanity check and then check again to make sure our optimistic guess was good.
    checkArgument(length <= cap * 4, "Bad string size (got %s, maximum is %s)", length, cap);
    checkState(buf.isReadable(length),
        "Trying to read a string that is too long (wanted %s, only have %s)", length,
        buf.readableBytes());
    String str = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
    buf.skipBytes(length);
    checkState(str.length() <= cap, "Got a too-long string (got %s, max %s)",
        str.length(), cap);
    return str;
  }

  /**
   * Writes the specified {@code str} to the {@code buf} with a VarInt prefix.
   * @param buf the buffer to write to
   * @param str the string to write
   */
  public static void writeString(ByteBuf buf, CharSequence str) {
    int size = ByteBufUtil.utf8Bytes(str);
    writeVarInt(buf, size);
    ByteBufUtil.writeUtf8(buf, str);
  }

  public static byte[] readByteArray(ByteBuf buf) {
    return readByteArray(buf, DEFAULT_MAX_STRING_SIZE);
  }

  /**
   * Reads a VarInt length-prefixed byte array from the {@code buf}, making sure to not go over
   * {@code cap} size.
   * @param buf the buffer to read from
   * @param cap the maximum size of the string, in UTF-8 character length
   * @return the byte array
   */
  public static byte[] readByteArray(ByteBuf buf, int cap) {
    int length = readVarInt(buf);
    checkArgument(length >= 0, "Got a negative-length array (%s)", length);
    checkArgument(length <= cap, "Bad array size (got %s, maximum is %s)", length, cap);
    checkState(buf.isReadable(length),
        "Trying to read an array that is too long (wanted %s, only have %s)", length,
        buf.readableBytes());
    byte[] array = new byte[length];
    buf.readBytes(array);
    return array;
  }

  public static void writeByteArray(ByteBuf buf, byte[] array) {
    writeVarInt(buf, array.length);
    buf.writeBytes(array);
  }

  /**
   * Reads an VarInt-prefixed array of VarInt integers from the {@code buf}.
   * @param buf the buffer to read from
   * @return an array of integers
   */
  public static int[] readIntegerArray(ByteBuf buf) {
    int len = readVarInt(buf);
    checkArgument(len >= 0, "Got a negative-length integer array (%s)", len);
    int[] array = new int[len];
    for (int i = 0; i < len; i++) {
      array[i] = readVarInt(buf);
    }
    return array;
  }

  /**
   * Reads an UUID from the {@code buf}.
   * @param buf the buffer to read from
   * @return the UUID from the buffer
   */
  public static UUID readUuid(ByteBuf buf) {
    long msb = buf.readLong();
    long lsb = buf.readLong();
    return new UUID(msb, lsb);
  }

  public static void writeUuid(ByteBuf buf, UUID uuid) {
    buf.writeLong(uuid.getMostSignificantBits());
    buf.writeLong(uuid.getLeastSignificantBits());
  }

  /**
   * Writes a list of {@link com.velocitypowered.api.util.GameProfile.Property} to the buffer.
   * @param buf the buffer to write to
   * @param properties the properties to serialize
   */
  public static void writeProperties(ByteBuf buf, List<GameProfile.Property> properties) {
    writeVarInt(buf, properties.size());
    for (GameProfile.Property property : properties) {
      writeString(buf, property.getName());
      writeString(buf, property.getValue());
      String signature = property.getSignature();
      if (signature != null) {
        buf.writeBoolean(true);
        writeString(buf, signature);
      } else {
        buf.writeBoolean(false);
      }
    }
  }

  /**
   * Reads a list of {@link com.velocitypowered.api.util.GameProfile.Property} from the buffer.
   * @param buf the buffer to read from
   * @return the read properties
   */
  public static List<GameProfile.Property> readProperties(ByteBuf buf) {
    List<GameProfile.Property> properties = new ArrayList<>();
    int size = readVarInt(buf);
    for (int i = 0; i < size; i++) {
      String name = readString(buf);
      String value = readString(buf);
      String signature = "";
      boolean hasSignature = buf.readBoolean();
      if (hasSignature) {
        signature = readString(buf);
      }
      properties.add(new GameProfile.Property(name, value, signature));
    }
    return properties;
  }

  private static final int FORGE_MAX_ARRAY_LENGTH = Integer.MAX_VALUE & 0x1FFF9A;

  /**
   * Reads an byte array for legacy version 1.7 from the specified {@code buf}
   *
   * @param buf the buffer to read from
   * @return the read byte array
   */
  public static byte[] readByteArray17(ByteBuf buf) {
    // Read in a 2 or 3 byte number that represents the length of the packet. (3 byte "shorts" for
    // Forge only)
    // No vanilla packet should give a 3 byte packet
    int len = readExtendedForgeShort(buf);

    Preconditions.checkArgument(len <= (FORGE_MAX_ARRAY_LENGTH),
        "Cannot receive array longer than %s (got %s bytes)", FORGE_MAX_ARRAY_LENGTH, len);

    byte[] ret = new byte[len];
    buf.readBytes(ret);
    return ret;
  }

  /**
   * Reads a retained {@link ByteBuf} slice of the specified {@code buf} with the 1.7 style length.
   *
   * @param buf the buffer to read from
   * @return the retained slice
   */
  public static ByteBuf readRetainedByteBufSlice17(ByteBuf buf) {
    // Read in a 2 or 3 byte number that represents the length of the packet. (3 byte "shorts" for
    // Forge only)
    // No vanilla packet should give a 3 byte packet
    int len = readExtendedForgeShort(buf);

    Preconditions.checkArgument(len <= (FORGE_MAX_ARRAY_LENGTH),
        "Cannot receive array longer than %s (got %s bytes)", FORGE_MAX_ARRAY_LENGTH, len);

    return buf.readRetainedSlice(len);
  }

  /**
   * Writes an byte array for legacy version 1.7 to the specified {@code buf}
   *
   * @param b array
   * @param buf buf
   * @param allowExtended forge
   */
  public static void writeByteArray17(byte[] b, ByteBuf buf, boolean allowExtended) {
    if (allowExtended) {
      Preconditions
          .checkArgument(b.length <= (FORGE_MAX_ARRAY_LENGTH),
              "Cannot send array longer than %s (got %s bytes)", FORGE_MAX_ARRAY_LENGTH,
              b.length);
    } else {
      Preconditions.checkArgument(b.length <= Short.MAX_VALUE,
          "Cannot send array longer than Short.MAX_VALUE (got %s bytes)", b.length);
    }
    // Write a 2 or 3 byte number that represents the length of the packet. (3 byte "shorts" for
    // Forge only)
    // No vanilla packet should give a 3 byte packet, this method will still retain vanilla
    // behaviour.
    writeExtendedForgeShort(buf, b.length);
    buf.writeBytes(b);
  }

  /**
   * Writes an {@link ByteBuf} for legacy version 1.7 to the specified {@code buf}
   *
   * @param b array
   * @param buf buf
   * @param allowExtended forge
   */
  public static void writeByteBuf17(ByteBuf b, ByteBuf buf, boolean allowExtended) {
    if (allowExtended) {
      Preconditions
          .checkArgument(b.readableBytes() <= (FORGE_MAX_ARRAY_LENGTH),
              "Cannot send array longer than %s (got %s bytes)", FORGE_MAX_ARRAY_LENGTH,
              b.readableBytes());
    } else {
      Preconditions.checkArgument(b.readableBytes() <= Short.MAX_VALUE,
          "Cannot send array longer than Short.MAX_VALUE (got %s bytes)", b.readableBytes());
    }
    // Write a 2 or 3 byte number that represents the length of the packet. (3 byte "shorts" for
    // Forge only)
    // No vanilla packet should give a 3 byte packet, this method will still retain vanilla
    // behaviour.
    writeExtendedForgeShort(buf, b.readableBytes());
    buf.writeBytes(b);
  }

  /**
   * Reads a Minecraft-style extended short from the specified {@code buf}.
   *
   * @param buf buf to write
   * @return read extended short
   */
  public static int readExtendedForgeShort(ByteBuf buf) {
    int low = buf.readUnsignedShort();
    int high = 0;
    if ((low & 0x8000) != 0) {
      low = low & 0x7FFF;
      high = buf.readUnsignedByte();
    }
    return ((high & 0xFF) << 15) | low;
  }

  /**
   * Writes a Minecraft-style extended short to the specified {@code buf}.
   *
   * @param buf buf to write
   * @param toWrite the extended short to write
   */
  public static void writeExtendedForgeShort(ByteBuf buf, int toWrite) {
    int low = toWrite & 0x7FFF;
    int high = (toWrite & 0x7F8000) >> 15;
    if (high != 0) {
      low = low | 0x8000;
    }
    buf.writeShort(low);
    if (high != 0) {
      buf.writeByte(high);
    }
  }

  /**
   * Reads a non length-prefixed string from the {@code buf}. We need this for the legacy 1.7
   * version, being inconsistent when sending the brand.
   *
   * @param buf the buffer to read from
   * @return the decoded string
   */
  public static String readStringWithoutLength(ByteBuf buf) {
    int length = buf.readableBytes();
    int cap = DEFAULT_MAX_STRING_SIZE;
    checkArgument(length >= 0, "Got a negative-length string (%s)", length);
    // `cap` is interpreted as a UTF-8 character length. To cover the full Unicode plane, we must
    // consider the length of a UTF-8 character, which can be up to a 4 bytes. We do an initial
    // sanity check and then check again to make sure our optimistic guess was good.
    checkArgument(length <= cap * 4, "Bad string size (got %s, maximum is %s)", length, cap);
    checkState(buf.isReadable(length),
        "Trying to read a string that is too long (wanted %s, only have %s)", length,
        buf.readableBytes());
    String str = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
    buf.skipBytes(length);
    checkState(str.length() <= cap, "Got a too-long string (got %s, max %s)",
        str.length(), cap);
    return str;
  }

  public enum Direction {
    SERVERBOUND,
    CLIENTBOUND;

    public StateRegistry.PacketRegistry.ProtocolRegistry getProtocolRegistry(StateRegistry state,
        ProtocolVersion version) {
      return (this == SERVERBOUND ? state.serverbound : state.clientbound)
          .getProtocolRegistry(version);
    }
  }

  private static class VarintByteDecoder implements ByteProcessor {

    private int accumulated;
    private int read;
    private boolean successfulDecode;

    @Override
    public boolean process(byte k) {
      accumulated |= (k & 0x7F) << read++ * 7;
      if (read > 5) {
        return false;
      }
      if ((k & 0x80) != 128) {
        successfulDecode = true;
        return false;
      }
      return true;
    }

    public void reset() {
      accumulated = 0;
      read = 0;
      successfulDecode = false;
    }
  }
}
