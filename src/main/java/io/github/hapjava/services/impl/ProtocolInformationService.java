package io.github.hapjava.services.impl;

import static io.github.hapjava.server.impl.HomekitServer.PROTOCOL_VERSION;

import io.github.hapjava.accessories.HomekitAccessory;
import io.github.hapjava.characteristics.impl.common.VersionCharacteristic;
import java.util.concurrent.CompletableFuture;

/** Accessory Information service. */
public class ProtocolInformationService extends AbstractServiceImpl {

  public ProtocolInformationService(VersionCharacteristic version) {
    super("000000A2‐0000‐1000‐8000‐0026BB765291");
    addCharacteristic(version);
  }

  public ProtocolInformationService(HomekitAccessory accessory) {
    this(new VersionCharacteristic(() -> CompletableFuture.completedFuture(PROTOCOL_VERSION)));
  }
}
