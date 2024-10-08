package io.github.hapjava.server.impl.pairing;

import com.nimbusds.srp6.BigIntegerUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class TypeLengthValueUtils {

  private TypeLengthValueUtils() {}

  public static DecodeResult decode(byte[] content) throws IOException {
    DecodeResult ret = new DecodeResult();
    ByteArrayInputStream bais = new ByteArrayInputStream(content);
    while (bais.available() > 0) {
      byte type = (byte) (bais.read() & 0xFF);
      int length = bais.read();
      byte[] part = new byte[length];
      bais.read(part);
      ret.add(type, part);
    }
    return ret;
  }

  public static Encoder getEncoder() {
    return new Encoder();
  }

  public static final class Encoder {

    private final ByteArrayOutputStream baos;

    private Encoder() {
      baos = new ByteArrayOutputStream();
    }

    public void add(MessageType type) {
      baos.write(type.getKey());
      baos.write(0);
    }

    public void add(MessageType type, BigInteger i) throws IOException {
      add(type, BigIntegerUtils.bigIntegerToBytes(i));
    }

    public void add(MessageType type, short b) {
      baos.write(type.getKey());
      baos.write(1);
      baos.write(b);
    }

    public void add(MessageType type, byte[] bytes) throws IOException {
      InputStream bais = new ByteArrayInputStream(bytes);
      while (bais.available() > 0) {
        int toWrite = bais.available();
        toWrite = toWrite > 255 ? 255 : toWrite;
        baos.write(type.getKey());
        baos.write(toWrite);
        ByteUtils.copyStream(bais, baos, toWrite);
      }
    }

    public void add(MessageType type, String string) throws IOException {
      add(type, string.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toByteArray() {
      return baos.toByteArray();
    }
  }

  public static final class DecodeResult {
    private final Map<Short, byte[]> result = new HashMap<>();

    private DecodeResult() {}

    public String toString() {
      return result.toString();
    }

    public boolean hasMessage(MessageType type) {
      return result.containsKey(type.getKey());
    }

    public byte getByte(MessageType type) {
      return result.get(type.getKey())[0];
    }

    public int getInt(MessageType type) {
      ByteBuffer wrapped = ByteBuffer.wrap(result.get(type.getKey()));
      return wrapped.getInt();
    }

    public BigInteger getBigInt(MessageType type) {
      return new BigInteger(1, result.get(type.getKey()));
    }

    public byte[] getBytes(MessageType type) {
      return result.get(type.getKey());
    }

    public void getBytes(MessageType type, byte[] dest, int srcOffset) {
      byte[] b = result.get(type.getKey());
      System.arraycopy(b, srcOffset, dest, 0, Math.min(dest.length, b.length));
    }

    public int getLength(MessageType type) {
      return result.get(type.getKey()).length;
    }

    private void add(short type, byte[] bytes) {
      result.merge(type, bytes, ByteUtils::joinBytes);
    }
  }
}
