package io.github.hapjava.server.impl.mdns;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class MdnsPublisherTest {

  private static final InetAddress HUB_ADDRESS;

  static {
    try {
      HUB_ADDRESS = InetAddress.getByName("192.0.2.25");
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Test
  public void answersOnlyThePublishedHostName() throws Exception {
    MdnsPublisher.Response response = respondTo("myhub.local.", 1, Collections.emptyList());

    assertThat(response).isNotNull();
    assertThat(response.data).containsSequence((byte) 192, (byte) 0, (byte) 2, (byte) 25);
    assertThat(respondTo("printer.local.", 1, Collections.emptyList())).isNull();
  }

  @Test
  public void replacesSpacesInPublishedHostNameWithHyphens() {
    assertThat(MdnsPublisher.normalizeHostName("My Rako Hub")).isEqualTo("my-rako-hub.local.");
  }

  @Test
  public void ignoresAllIncomingMdnsResponses() throws Exception {
    byte[] packet = query("printer.local.", 1);
    packet[2] = (byte) 0x84;

    assertThat(
            MdnsPublisher.createResponse(
                packet, packet.length, HUB_ADDRESS, "myhub.local.", Collections.emptyList(), 5353))
        .isNull();
  }

  @Test
  public void advertisesTheHomekitServiceWithItsTxtData() throws Exception {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("sf", "1");
    properties.put("id", "00:11:22:33:44:55");
    MdnsService homekit = new MdnsService("_hap._tcp.local.", "myhub", 51826, properties);

    MdnsPublisher.Response response =
        respondTo("_hap._tcp.local.", 12, Collections.singletonList(homekit));

    assertThat(response).isNotNull();
    assertThat(new String(response.data, StandardCharsets.ISO_8859_1))
        .contains("_hap", "myhub", "sf=1", "id=00:11:22:33:44:55");
  }

  private static MdnsPublisher.Response respondTo(
      String name, int type, java.util.List<MdnsService> services) throws Exception {
    byte[] packet = query(name, type);
    return MdnsPublisher.createResponse(
        packet, packet.length, HUB_ADDRESS, "myhub.local.", services, 5353);
  }

  private static byte[] query(String name, int type) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(1);
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(0);
    for (String label : name.substring(0, name.length() - 1).split("\\.")) {
      byte[] encoded = label.getBytes(StandardCharsets.UTF_8);
      out.writeByte(encoded.length);
      out.write(encoded);
    }
    out.writeByte(0);
    out.writeShort(type);
    out.writeShort(1);
    return bytes.toByteArray();
  }
}
