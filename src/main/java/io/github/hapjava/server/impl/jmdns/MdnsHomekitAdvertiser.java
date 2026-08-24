package io.github.hapjava.server.impl.jmdns;

import static io.github.hapjava.server.impl.crypto.HAPSetupCodeUtils.generateSHA512Hash;

import io.github.hapjava.server.impl.mdns.MdnsPublisher;
import io.github.hapjava.server.impl.mdns.MdnsService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MdnsHomekitAdvertiser {

  private static final String SERVICE_TYPE = "_hap._tcp.local.";

  private final MdnsPublisher publisher;
  private boolean discoverable = true;
  private static final Logger logger = LoggerFactory.getLogger(MdnsHomekitAdvertiser.class);
  private boolean isAdvertising = false;
  private boolean isStarted = false;

  private String label;
  private String mac;
  private String setupId;
  private int port;
  private int configurationIndex;
  private MdnsService service;
  private int category;
  private int stateIndex = 1;

  public MdnsHomekitAdvertiser(MdnsPublisher publisher) {
    this.publisher = publisher;
  }

  public MdnsHomekitAdvertiser(InetAddress localAddress) throws UnknownHostException, IOException {
    publisher = new MdnsPublisher(localAddress, localAddress.getHostName());
  }

  public synchronized void advertise(
      String label,
      int category,
      String mac,
      int port,
      int configurationIndex,
      String setupId,
      int stateIndex)
      throws Exception {
    if (isAdvertising) {
      throw new IllegalStateException("HomeKit advertiser is already running");
    }
    this.label = label;
    this.mac = mac;
    this.port = port;
    this.setupId = setupId;
    this.category = category;
    this.configurationIndex = configurationIndex;
    this.stateIndex = stateIndex;

    logger.trace("Advertising accessory " + label);

    registerService();

    if (isStarted) {
      return;
    }
    this.isStarted = true;

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.trace("Stopping advertising in response to shutdown.");
                  unregisterService();
                }));
  }

  public synchronized void stop() {
    unregisterService();
  }

  public void setStateIndex(int stateIndex) {
    this.stateIndex = stateIndex;
  }

  public synchronized void setDiscoverable(boolean discoverable) throws IOException {
    if (this.discoverable != discoverable) {
      if (isAdvertising) {
        logger.trace("Re-creating service due to change in discoverability to " + discoverable);
        unregisterService();
        this.discoverable = discoverable;
        registerService();
      } else {
        this.discoverable = discoverable;
      }
    }
  }

  public synchronized void setMac(String mac) throws IOException {
    if (!java.util.Objects.equals(this.mac, mac)) {
      if (isAdvertising) {
        logger.trace("Re-creating service due to change in mac to " + mac);
        unregisterService();
        this.mac = mac;
        registerService();
      } else {
        this.mac = mac;
      }
    }
  }

  public synchronized void setConfigurationIndex(int revision) throws IOException {
    if (this.configurationIndex != revision) {
      if (isAdvertising) {
        logger.trace("Re-creating service due to change in configuration index to " + revision);
        unregisterService();
        this.configurationIndex = revision;
        registerService();
      } else {
        this.configurationIndex = revision;
      }
    }
  }

  private void unregisterService() {
    if (service != null) {
      publisher.unregisterService(service);
      service = null;
    }
    isAdvertising = false;
  }

  private void registerService() throws IOException {
    logger.info("Registering " + SERVICE_TYPE + " on port " + port);
    service = buildService();
    publisher.registerService(service);
    isAdvertising = true;
  }

  private MdnsService buildService() {
    logger.trace("MAC:" + mac + " Setup Id:" + setupId);
    Map<String, String> props = new HashMap<>();
    props.put("sf", discoverable ? "1" : "0");
    props.put("id", mac);
    props.put("md", label);
    props.put("sh", generateSHA512Hash(setupId + mac));
    props.put("c#", Integer.toString(configurationIndex));
    props.put("s#", Integer.toString(stateIndex));
    props.put("ff", "0");
    props.put("ci", Integer.toString(category));
    props.put("pv", "1.1");

    return new MdnsService(SERVICE_TYPE, label, port, props);
  }
}
