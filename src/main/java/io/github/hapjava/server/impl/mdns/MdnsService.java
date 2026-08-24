package io.github.hapjava.server.impl.mdns;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A service advertised by {@link MdnsPublisher}. */
public final class MdnsService {

  private final String type;
  private final String name;
  private final int port;
  private final Map<String, String> properties;

  public MdnsService(String type, String name, int port, Map<String, String> properties) {
    this.type = normalizeType(type);
    this.name = requireLabel(name, "Service name");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Service port must be between 1 and 65535");
    }
    this.port = port;
    this.properties =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(properties == null ? Collections.emptyMap() : properties));
  }

  public String getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public int getPort() {
    return port;
  }

  public Map<String, String> getProperties() {
    return properties;
  }

  public String getProperty(String key) {
    return properties.get(key);
  }

  String getQualifiedName() {
    return name + "." + type;
  }

  String getKey() {
    return getQualifiedName().toLowerCase();
  }

  private static String normalizeType(String value) {
    String type = Objects.requireNonNull(value, "Service type").trim();
    if (!type.endsWith(".")) {
      type += ".";
    }
    if (!type.toLowerCase().endsWith(".local.")) {
      throw new IllegalArgumentException("Service type must end in .local");
    }
    return type;
  }

  private static String requireLabel(String value, String description) {
    String label = Objects.requireNonNull(value, description).trim();
    if (label.isEmpty()) {
      throw new IllegalArgumentException(description + " cannot be empty");
    }
    return label;
  }
}
