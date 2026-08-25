package io.github.hapjava.server.impl.mdns;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small, publisher-only mDNS responder.
 *
 * <p>Unlike a general service discovery implementation, this class ignores every mDNS response and
 * retains no records received from the network. Its memory use is therefore bounded by the services
 * registered by this process.
 */
public class MdnsPublisher implements Closeable {

  private static final Logger LOGGER = LoggerFactory.getLogger(MdnsPublisher.class);
  private static final InetSocketAddress MULTICAST_DESTINATION;
  private static final int MDNS_PORT = 5353;
  private static final int MAX_PACKET_SIZE = 1500;
  private static final int MAX_SERVICES = 4;
  private static final int DEFAULT_TTL_SECONDS = 120;

  static {
    try {
      MULTICAST_DESTINATION =
          new InetSocketAddress(InetAddress.getByName("224.0.0.251"), MDNS_PORT);
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private final InetAddress localAddress;
  private final String hostName;
  private final MulticastSocket socket;
  private final NetworkInterface networkInterface;
  private final Thread listener;
  private final Map<String, MdnsService> registeredServices = new LinkedHashMap<>();
  private volatile List<MdnsService> services = Collections.emptyList();
  private volatile boolean running = true;

  public MdnsPublisher(InetAddress localAddress, String hostName) throws IOException {
    if (localAddress == null || localAddress.getAddress().length != 4) {
      throw new IllegalArgumentException("An IPv4 address is required for mDNS publishing");
    }
    this.localAddress = localAddress;
    this.hostName = normalizeHostName(hostName);
    this.networkInterface = NetworkInterface.getByInetAddress(localAddress);

    socket = new MulticastSocket(null);
    socket.setReuseAddress(true);
    socket.bind(new InetSocketAddress(MDNS_PORT));
    socket.setTimeToLive(255);
    if (networkInterface == null) {
      socket.joinGroup(MULTICAST_DESTINATION.getAddress());
    } else {
      socket.setNetworkInterface(networkInterface);
      socket.joinGroup(MULTICAST_DESTINATION, networkInterface);
    }

    listener = new Thread(this::listen, "mdns-publisher");
    listener.setDaemon(true);
    listener.start();
    Runtime.getRuntime().addShutdownHook(new Thread(this::close, "mdns-publisher-shutdown"));
    sendAnnouncement(Collections.emptyList(), DEFAULT_TTL_SECONDS, true);
  }

  public InetAddress getInetAddress() {
    return localAddress;
  }

  public String getHostName() {
    return hostName;
  }

  public synchronized void registerService(MdnsService service) throws IOException {
    if (!running) {
      throw new IOException("mDNS publisher is closed");
    }
    if (!registeredServices.containsKey(service.getKey())
        && registeredServices.size() >= MAX_SERVICES) {
      throw new IllegalStateException("mDNS publisher service limit reached");
    }
    registeredServices.put(service.getKey(), service);
    services = Collections.unmodifiableList(new ArrayList<>(registeredServices.values()));
    sendAnnouncement(Collections.singletonList(service), DEFAULT_TTL_SECONDS, true);
  }

  public synchronized void unregisterService(MdnsService service) {
    MdnsService removed = registeredServices.remove(service.getKey());
    if (removed == null) {
      return;
    }
    services = Collections.unmodifiableList(new ArrayList<>(registeredServices.values()));
    try {
      sendAnnouncement(Collections.singletonList(removed), 0, false);
    } catch (IOException ex) {
      LOGGER.debug("Failed to send mDNS goodbye", ex);
    }
  }

  private void listen() {
    byte[] buffer = new byte[MAX_PACKET_SIZE];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    while (running) {
      try {
        packet.setLength(buffer.length);
        socket.receive(packet);
        Response response =
            createResponse(
                packet.getData(),
                packet.getLength(),
                localAddress,
                hostName,
                services,
                packet.getPort());
        if (response != null) {
          InetSocketAddress destination =
              response.unicast
                  ? new InetSocketAddress(packet.getAddress(), packet.getPort())
                  : MULTICAST_DESTINATION;
          send(response.data, destination);
        }
      } catch (SocketException ex) {
        if (running) {
          LOGGER.warn("mDNS listener stopped unexpectedly", ex);
        }
      } catch (IOException | RuntimeException ex) {
        LOGGER.debug("Ignoring invalid mDNS query", ex);
      }
    }
  }

  private synchronized void sendAnnouncement(
      List<MdnsService> announcedServices, int ttl, boolean includeHost) throws IOException {
    byte[] data = createAnnouncement(localAddress, hostName, announcedServices, ttl, includeHost);
    send(data, MULTICAST_DESTINATION);
  }

  private void send(byte[] data, InetSocketAddress destination) throws IOException {
    socket.send(new DatagramPacket(data, data.length, destination));
  }

  @Override
  public synchronized void close() {
    if (!running) {
      return;
    }
    try {
      sendAnnouncement(services, 0, true);
    } catch (IOException ex) {
      LOGGER.debug("Failed to send final mDNS goodbye", ex);
    }
    running = false;
    try {
      if (networkInterface == null) {
        socket.leaveGroup(MULTICAST_DESTINATION.getAddress());
      } else {
        socket.leaveGroup(MULTICAST_DESTINATION, networkInterface);
      }
    } catch (IOException ex) {
      LOGGER.debug("Failed to leave mDNS multicast group", ex);
    }
    socket.close();
    registeredServices.clear();
    services = Collections.emptyList();
  }

  static Response createResponse(
      byte[] packet,
      int length,
      InetAddress localAddress,
      String hostName,
      List<MdnsService> services,
      int sourcePort)
      throws IOException {
    if (length < 12 || (readUnsignedShort(packet, 2) & 0x8000) != 0) {
      return null;
    }

    int transactionId = readUnsignedShort(packet, 0);
    int questionCount = readUnsignedShort(packet, 4);
    int[] offset = {12};
    boolean unicast = sourcePort != MDNS_PORT;
    List<DnsRecord> answers = new ArrayList<>();
    List<DnsRecord> additionals = new ArrayList<>();

    for (int i = 0; i < questionCount; i++) {
      String questionName = readName(packet, length, offset);
      if (offset[0] + 4 > length) {
        throw new IOException("Truncated mDNS question");
      }
      int questionType = readUnsignedShort(packet, offset[0]);
      int questionClass = readUnsignedShort(packet, offset[0] + 2);
      offset[0] += 4;
      unicast |= (questionClass & 0x8000) != 0;
      int dnsClass = questionClass & 0x7fff;
      if (dnsClass != 1 && dnsClass != 255) {
        continue;
      }
      addAnswers(
          questionName, questionType, localAddress, hostName, services, answers, additionals);
    }

    if (answers.isEmpty()) {
      return null;
    }
    byte[] data = writeMessage(unicast ? transactionId : 0, answers, additionals);
    return new Response(data, unicast);
  }

  static byte[] createAnnouncement(
      InetAddress localAddress,
      String hostName,
      List<MdnsService> services,
      int ttl,
      boolean includeHost)
      throws IOException {
    List<DnsRecord> answers = new ArrayList<>();
    if (includeHost) {
      addUnique(answers, addressRecord(hostName, localAddress, ttl));
    }
    for (MdnsService service : services) {
      addUnique(answers, pointerRecord(service.getType(), service.getQualifiedName(), ttl));
      addUnique(answers, serviceRecord(service, hostName, ttl));
      addUnique(answers, textRecord(service, ttl));
    }
    return writeMessage(0, answers, Collections.emptyList());
  }

  private static void addAnswers(
      String questionName,
      int questionType,
      InetAddress localAddress,
      String hostName,
      List<MdnsService> services,
      List<DnsRecord> answers,
      List<DnsRecord> additionals)
      throws IOException {
    if (sameName(questionName, hostName) && (questionType == 1 || questionType == 255)) {
      addUnique(answers, addressRecord(hostName, localAddress, DEFAULT_TTL_SECONDS));
    }

    if (sameName(questionName, "_services._dns-sd._udp.local.")) {
      for (MdnsService service : services) {
        if (questionType == 12 || questionType == 255) {
          addUnique(
              answers,
              pointerRecord(
                  "_services._dns-sd._udp.local.", service.getType(), DEFAULT_TTL_SECONDS));
        }
      }
    }

    for (MdnsService service : services) {
      if (sameName(questionName, service.getType())
          && (questionType == 12 || questionType == 255)) {
        addUnique(
            answers,
            pointerRecord(service.getType(), service.getQualifiedName(), DEFAULT_TTL_SECONDS));
        addUnique(additionals, serviceRecord(service, hostName, DEFAULT_TTL_SECONDS));
        addUnique(additionals, textRecord(service, DEFAULT_TTL_SECONDS));
        addUnique(additionals, addressRecord(hostName, localAddress, DEFAULT_TTL_SECONDS));
      }
      if (sameName(questionName, service.getQualifiedName())) {
        if (questionType == 33 || questionType == 255) {
          addUnique(answers, serviceRecord(service, hostName, DEFAULT_TTL_SECONDS));
        }
        if (questionType == 16 || questionType == 255) {
          addUnique(answers, textRecord(service, DEFAULT_TTL_SECONDS));
        }
        if (questionType == 33 || questionType == 16 || questionType == 255) {
          addUnique(additionals, addressRecord(hostName, localAddress, DEFAULT_TTL_SECONDS));
        }
      }
    }
  }

  private static byte[] writeMessage(
      int transactionId, List<DnsRecord> answers, List<DnsRecord> additionals) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeShort(transactionId);
    out.writeShort(0x8400);
    out.writeShort(0);
    out.writeShort(answers.size());
    out.writeShort(0);
    out.writeShort(additionals.size());
    for (DnsRecord record : answers) {
      writeRecord(out, record);
    }
    for (DnsRecord record : additionals) {
      writeRecord(out, record);
    }
    return bytes.toByteArray();
  }

  private static void writeRecord(DataOutputStream out, DnsRecord record) throws IOException {
    writeName(out, record.name);
    out.writeShort(record.type);
    out.writeShort(record.unique ? 0x8001 : 1);
    out.writeInt(record.ttl);
    out.writeShort(record.data.length);
    out.write(record.data);
  }

  private static DnsRecord addressRecord(String hostName, InetAddress address, int ttl) {
    return new DnsRecord(hostName, 1, true, ttl, address.getAddress());
  }

  private static DnsRecord pointerRecord(String name, String target, int ttl) throws IOException {
    return new DnsRecord(name, 12, false, ttl, encodedName(target));
  }

  private static DnsRecord serviceRecord(MdnsService service, String hostName, int ttl)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(service.getPort());
    writeName(out, hostName);
    return new DnsRecord(service.getQualifiedName(), 33, true, ttl, bytes.toByteArray());
  }

  private static DnsRecord textRecord(MdnsService service, int ttl) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (Map.Entry<String, String> property : service.getProperties().entrySet()) {
      String value = property.getKey() + "=" + property.getValue();
      byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
      if (encoded.length > 255) {
        throw new IllegalArgumentException(
            "mDNS TXT property exceeds 255 bytes: " + property.getKey());
      }
      bytes.write(encoded.length);
      bytes.write(encoded);
    }
    return new DnsRecord(service.getQualifiedName(), 16, true, ttl, bytes.toByteArray());
  }

  private static byte[] encodedName(String name) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    writeName(new DataOutputStream(bytes), name);
    return bytes.toByteArray();
  }

  private static void writeName(DataOutputStream out, String name) throws IOException {
    String normalized = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    for (String label : normalized.split("\\.")) {
      byte[] encoded = label.getBytes(StandardCharsets.UTF_8);
      if (encoded.length == 0 || encoded.length > 63) {
        throw new IllegalArgumentException("Invalid DNS label in " + name);
      }
      out.writeByte(encoded.length);
      out.write(encoded);
    }
    out.writeByte(0);
  }

  private static String readName(byte[] packet, int length, int[] offset) throws IOException {
    StringBuilder name = new StringBuilder();
    int position = offset[0];
    int nextOffset = -1;
    int jumps = 0;
    while (position < length) {
      int labelLength = packet[position++] & 0xff;
      if ((labelLength & 0xc0) == 0xc0) {
        if (position >= length || ++jumps > 16) {
          throw new IOException("Invalid compressed DNS name");
        }
        if (nextOffset < 0) {
          nextOffset = position + 1;
        }
        position = ((labelLength & 0x3f) << 8) | (packet[position] & 0xff);
        continue;
      }
      if (labelLength == 0) {
        offset[0] = nextOffset < 0 ? position : nextOffset;
        return name.append('.').toString();
      }
      if (labelLength > 63 || position + labelLength > length) {
        throw new IOException("Invalid DNS label");
      }
      if (name.length() > 0) {
        name.append('.');
      }
      name.append(new String(packet, position, labelLength, StandardCharsets.UTF_8));
      position += labelLength;
    }
    throw new IOException("Truncated DNS name");
  }

  private static int readUnsignedShort(byte[] packet, int offset) {
    return ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
  }

  private static void addUnique(List<DnsRecord> records, DnsRecord candidate) {
    for (DnsRecord record : records) {
      if (record.type == candidate.type && sameName(record.name, candidate.name)) {
        return;
      }
    }
    records.add(candidate);
  }

  private static boolean sameName(String left, String right) {
    return left.equalsIgnoreCase(right);
  }

  static String normalizeHostName(String value) {
    String name = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    if (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    if (name.endsWith(".local")) {
      name = name.substring(0, name.length() - 6);
    }
    if (name.isEmpty() || name.length() > 63 || !name.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) {
      throw new IllegalArgumentException("Invalid mDNS hostname: " + value);
    }
    return name + ".local.";
  }

  static final class Response {
    final byte[] data;
    final boolean unicast;

    Response(byte[] data, boolean unicast) {
      this.data = data;
      this.unicast = unicast;
    }
  }

  private static final class DnsRecord {
    final String name;
    final int type;
    final boolean unique;
    final int ttl;
    final byte[] data;

    DnsRecord(String name, int type, boolean unique, int ttl, byte[] data) {
      this.name = name;
      this.type = type;
      this.unique = unique;
      this.ttl = ttl;
      this.data = data;
    }
  }
}
