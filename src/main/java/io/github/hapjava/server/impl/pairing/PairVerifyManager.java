package io.github.hapjava.server.impl.pairing;

import djb.Curve25519;
import io.github.hapjava.server.HomekitAuthInfo;
import io.github.hapjava.server.impl.HomekitRegistry;
import io.github.hapjava.server.impl.crypto.ChachaDecoder;
import io.github.hapjava.server.impl.crypto.ChachaEncoder;
import io.github.hapjava.server.impl.crypto.EdsaSigner;
import io.github.hapjava.server.impl.crypto.EdsaVerifier;
import io.github.hapjava.server.impl.http.HttpRequest;
import io.github.hapjava.server.impl.http.HttpResponse;
import io.github.hapjava.server.impl.pairing.PairVerifyRequest.VerifyFinishRequest;
import io.github.hapjava.server.impl.pairing.PairVerifyRequest.VerifyStartRequest;
import io.github.hapjava.server.impl.pairing.TypeLengthValueUtils.DecodeResult;
import io.github.hapjava.server.impl.pairing.TypeLengthValueUtils.Encoder;
import io.github.hapjava.server.impl.responses.NotFoundResponse;
import io.github.hapjava.server.impl.responses.OkResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PairVerifyManager {

  private static final Logger logger = LoggerFactory.getLogger(PairVerifyManager.class);
  private static volatile SecureRandom secureRandom;

  private final HomekitAuthInfo authInfo;
  private final HomekitRegistry registry;

  private byte[] hkdfKey;
  private byte[] clientPublicKey;
  private byte[] publicKey;
  private byte[] sharedSecret;

  public PairVerifyManager(HomekitAuthInfo authInfo, HomekitRegistry registry) {
    this.authInfo = authInfo;
    this.registry = registry;
  }

  public HttpResponse handle(HttpRequest rawRequest) throws Exception {
    PairVerifyRequest request = PairVerifyRequest.of(rawRequest.getBody());
    switch (request.getState()) {
      case 1:
        return handleVerifyStartRequest((VerifyStartRequest) request);

      case 3:
        return handleVerifyFinishRequest((VerifyFinishRequest) request);

      default:
        return new NotFoundResponse();
    }
  }

  private HttpResponse handleVerifyStartRequest(VerifyStartRequest request) throws Exception {
    logger.trace("Starting pair verification for " + registry.getLabel());
    clientPublicKey = request.getClientPublicKey();
    publicKey = new byte[32];
    byte[] privateKey = new byte[32];
    getSecureRandom().nextBytes(privateKey);
    Curve25519.keygen(publicKey, null, privateKey);

    sharedSecret = new byte[32];
    Curve25519.curve(sharedSecret, privateKey, clientPublicKey);

    byte[] material =
        ByteUtils.joinBytes(
            publicKey, authInfo.getMac().getBytes(StandardCharsets.UTF_8), clientPublicKey);

    byte[] proof = new EdsaSigner(authInfo.getPrivateKey()).sign(material);

    HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA512Digest());
    hkdf.init(
        new HKDFParameters(
            sharedSecret,
            "Pair-Verify-Encrypt-Salt".getBytes(StandardCharsets.UTF_8),
            "Pair-Verify-Encrypt-Info".getBytes(StandardCharsets.UTF_8)));
    hkdfKey = new byte[32];
    hkdf.generateBytes(hkdfKey, 0, 32);

    Encoder encoder = TypeLengthValueUtils.getEncoder();
    encoder.add(MessageType.USERNAME, authInfo.getMac().getBytes(StandardCharsets.UTF_8));
    encoder.add(MessageType.SIGNATURE, proof);
    byte[] plaintext = encoder.toByteArray();

    ChachaEncoder chacha = new ChachaEncoder(hkdfKey, "PV-Msg02".getBytes(StandardCharsets.UTF_8));
    byte[] ciphertext = chacha.encodeCiphertext(plaintext);

    encoder = TypeLengthValueUtils.getEncoder();
    encoder.add(MessageType.STATE, (short) 2);
    encoder.add(MessageType.ENCRYPTED_DATA, ciphertext);
    encoder.add(MessageType.PUBLIC_KEY, publicKey);
    return new PairingResponse(encoder.toByteArray());
  }

  private HttpResponse handleVerifyFinishRequest(VerifyFinishRequest request) throws Exception {
    ChachaDecoder chacha = new ChachaDecoder(hkdfKey, "PV-Msg03".getBytes(StandardCharsets.UTF_8));
    byte[] plaintext = chacha.decodeCiphertext(request.getAuthTagData(), request.getMessageData());

    DecodeResult d = TypeLengthValueUtils.decode(plaintext);
    byte[] clientUsername = d.getBytes(MessageType.USERNAME);
    byte[] clientSignature = d.getBytes(MessageType.SIGNATURE);

    byte[] material = ByteUtils.joinBytes(clientPublicKey, clientUsername, publicKey);

    byte[] clientLtpk =
        authInfo.getUserPublicKey(
            authInfo.getMac() + new String(clientUsername, StandardCharsets.UTF_8));
    if (clientLtpk == null) {
      logger.warn("Unknown user: {}", new String(clientUsername, StandardCharsets.UTF_8));
      return new PairingResponse(4, ErrorCode.AUTHENTICATION);
    }

    Encoder encoder = TypeLengthValueUtils.getEncoder();
    encoder.add(MessageType.STATE, (short) 4);
    if (clientLtpk == null) {
      logger.error("Unknown user: {}", new String(clientUsername, StandardCharsets.UTF_8));
      return new OkResponse(encoder.toByteArray());
    }

    if (new EdsaVerifier(clientLtpk).verify(material, clientSignature)) {
      logger.trace("Completed pair verification for " + registry.getLabel());
      return new UpgradeResponse(
          encoder.toByteArray(),
          createKey("Control-Write-Encryption-Key"),
          createKey("Control-Read-Encryption-Key"));
    } else {
      logger.warn("Invalid signature. Could not pair " + registry.getLabel());
      return new PairingResponse(4, ErrorCode.AUTHENTICATION);
    }
  }

  private byte[] createKey(String info) {
    HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA512Digest());
    hkdf.init(
        new HKDFParameters(
            sharedSecret,
            "Control-Salt".getBytes(StandardCharsets.UTF_8),
            info.getBytes(StandardCharsets.UTF_8)));
    byte[] key = new byte[32];
    hkdf.generateBytes(key, 0, 32);
    return key;
  }

  private static SecureRandom getSecureRandom() {
    if (secureRandom == null) {
      synchronized (PairVerifyManager.class) {
        if (secureRandom == null) {
          secureRandom = new SecureRandom();
        }
      }
    }
    return secureRandom;
  }
}
