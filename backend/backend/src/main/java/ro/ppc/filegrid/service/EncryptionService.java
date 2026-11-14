package ro.ppc.filegrid.service;

import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class EncryptionService {

    public static final int TAG_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(FileGridProperties props) {
        String base64 = props.encryption() == null ? null : props.encryption().key();
        boolean required = props.policies().values().stream().anyMatch(CategoryPolicy::encryptAtRest);
        if (base64 == null || base64.isBlank()) {
            if (required) {
                throw new IllegalStateException("O politica cere encrypt-at-rest, dar FILEGRID_ENCRYPTION_KEY nu e setata. "
                        + "Ruleaza scriptul start (genereaza .env) sau seteaza o cheie base64 de 32 bytes.");
            }
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("FILEGRID_ENCRYPTION_KEY trebuie sa aiba exact 32 bytes (AES-256), are " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] newIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    public InputStream encrypt(InputStream plain, byte[] iv) {
        return new CipherInputStream(plain, cipher(Cipher.ENCRYPT_MODE, iv));
    }

    public InputStream decrypt(InputStream encrypted, byte[] iv) {
        return new CipherInputStream(encrypted, cipher(Cipher.DECRYPT_MODE, iv));
    }

    private Cipher cipher(int mode, byte[] iv) {
        if (key == null) {
            throw new IllegalStateException("Cheia de criptare nu e configurata");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nu pot initializa AES-GCM", e);
        }
    }
}
