package co.eafit.dfsha.control.security;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BlockTokenSignerTest {
  @Test void signsVerifiableWriteTokenWithExpectedClaims() throws Exception {
    KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Instant now=Instant.parse("2026-10-21T12:00:00Z");
    BlockTokenSigner signer=new BlockTokenSigner(pair.getPrivate(),Clock.fixed(now,ZoneOffset.UTC),900);
    UUID block=UUID.randomUUID(),file=UUID.randomUUID(),owner=UUID.randomUUID();
    BlockTokenSigner.SignedToken token=signer.issue(BlockTokenSigner.Action.WRITE,block,file,owner);
    String[] parts=token.value().split("\\.");assertEquals(2,parts.length);
    Base64.Decoder decoder=Base64.getUrlDecoder();byte[] payload=decoder.decode(parts[0]);
    Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(pair.getPublic());verifier.update(payload);
    assertTrue(verifier.verify(decoder.decode(parts[1])));
    assertEquals("v1|WRITE|"+block+"|"+file+"|"+owner+"|1792584900",new String(payload,StandardCharsets.UTF_8));
    assertEquals(1792584900L,token.expiresAtEpochSeconds());
  }
}
