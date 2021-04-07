package io.github.hapjava.characteristics.impl.protocolinformation;

import io.github.hapjava.characteristics.impl.base.StaticStringCharacteristic;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** This characteristic contains a version string. */
public class VersionCharacteristic extends StaticStringCharacteristic {

  public VersionCharacteristic(Supplier<CompletableFuture<String>> getter) {
    super(
        "00000037‐0000‐1000‐8000‐0026BB765291",
        "version",
        Optional.of(getter),
        Optional.empty(),
        Optional.empty());
  }
}
