package io.github.hapjava.server.impl.jmdns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.hapjava.server.impl.mdns.MdnsPublisher;
import io.github.hapjava.server.impl.mdns.MdnsService;
import java.io.IOException;
import java.net.UnknownHostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class MdnsHomekitAdvertiserTest {

  MdnsHomekitAdvertiser subject;
  MdnsPublisher publisher;

  @BeforeEach
  public void setup() throws UnknownHostException, IOException {
    publisher = mock(MdnsPublisher.class);
    subject = new MdnsHomekitAdvertiser(publisher);
  }

  @Test
  public void testAdvertiseTwiceFails() throws Exception {
    advertise();
    assertThatThrownBy(() -> advertise()).isNotNull();
  }

  /*
   * Verify that the unregister call is for the initial registered service
   * when changing discoverability causes advertising to be toggled.
   */
  @Test
  public void testSetDiscoverableAfterAdvertise() throws Exception {
    subject.setDiscoverable(false);
    advertise();
    subject.setDiscoverable(true);
    assertThat(getArgumentFromUnregister().getProperty("sf")).isEqualTo("0");
  }

  /*
   * Verify that the unregister call is for the initial registered service
   * when changing the config index causes advertising to be toggled.
   */
  @Test
  public void testSetConfigurationIndex() throws Exception {
    subject.setConfigurationIndex(1);
    advertise();
    subject.setConfigurationIndex(2);
    assertThat(getArgumentFromUnregister().getProperty("c#")).isEqualTo("1");
  }

  private MdnsService getArgumentFromUnregister() {
    ArgumentCaptor<MdnsService> serviceCaptor = ArgumentCaptor.forClass(MdnsService.class);
    verify(publisher).unregisterService(serviceCaptor.capture());
    return serviceCaptor.getValue();
  }

  private void advertise() throws Exception {
    subject.advertise("test", 1, "00:00:00:00:00:00", 1234, 1, "1", 1);
  }
}
