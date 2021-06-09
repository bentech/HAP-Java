package io.github.hapjava.server.impl.connections;

import io.github.hapjava.characteristics.EventableCharacteristic;

public class PendingNotification {
  public long aid;
  public int iid;
  public EventableCharacteristic characteristic;

  public PendingNotification(long aid, int iid, EventableCharacteristic characteristic) {
    this.aid = aid;
    this.iid = iid;
    this.characteristic = characteristic;
  }
}
