package ro.ppc.filegrid.security;

import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.FileGridProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

@Component
public class MeterKeys {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec masterKey;

    public MeterKeys(FileGridProperties props) {
        byte[] secret = Base64.getDecoder().decode(props.security().meterMasterKey().trim());
        if (secret.length < 32) {
            throw new IllegalStateException("METER_MASTER_KEY trebuie sa aiba cel putin 32 bytes (base64).");
        }
        this.masterKey = new SecretKeySpec(secret, ALGORITHM);
    }

    public String derive(String meterId) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(masterKey);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(meterId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nu pot calcula cheia contorului", e);
        }
    }

    public boolean matches(String meterId, String presentedKey) {
        if (meterId == null || presentedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(derive(meterId).getBytes(StandardCharsets.US_ASCII),
                presentedKey.getBytes(StandardCharsets.US_ASCII));
    }
}
