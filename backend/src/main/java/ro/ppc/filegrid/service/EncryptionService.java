package ro.ppc.filegrid.service;

import org.springframework.stereotype.Component;
import ro.ppc.filegrid.config.CategoryPolicy;
import ro.ppc.filegrid.config.FileGridProperties;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class EncryptionService {

    public static final int TAG_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    public static final String TEXT_PREFIX = "enc1:";
    private static final String TOKEN_PREFIX = "t";

    private final SecretKey key;
    private final SecretKey textKey;
    private final SecretKey tokenKey;
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
            this.textKey = null;
            this.tokenKey = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("FILEGRID_ENCRYPTION_KEY trebuie sa aiba exact 32 bytes (AES-256), are " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.textKey = new SecretKeySpec(derive(raw, "filegrid-catalog-text"), "AES");
        this.tokenKey = new SecretKeySpec(derive(raw, "filegrid-catalog-search"), "HmacSHA256");
    }

    public String sealText(String plain) {
        if (plain == null) {
            return null;
        }
        if (textKey == null) {
            throw new IllegalStateException("Cheia de criptare nu e configurata");
        }
        try {
            byte[] iv = newIv();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, textKey, new GCMParameterSpec(TAG_BYTES * 8, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return TEXT_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Nu pot cripta textul", e);
        }
    }

    public String openText(String stored) {
        if (stored == null || !stored.startsWith(TEXT_PREFIX)) {
            return stored;
        }
        if (textKey == null) {
            return "[criptat]";
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(TEXT_PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, textKey, new GCMParameterSpec(TAG_BYTES * 8, in, 0, IV_BYTES));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return "[text indisponibil]";
        }
    }

    public String searchTokens(String... values) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null) {
                tokens.addAll(queryTokens(value));
            }
        }
        return tokens.isEmpty() ? null : " " + String.join(" ", tokens) + " ";
    }

    public List<String> queryTokens(String text) {
        if (tokenKey == null || text == null) {
            return List.of();
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        if (normalized.length() < 3) {
            tokens.add(token(normalized));
        } else {
            for (int i = 0; i + 3 <= normalized.length(); i++) {
                tokens.add(token(normalized.substring(i, i + 3)));
            }
        }
        return List.copyOf(tokens);
    }

    private String token(String gram) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(tokenKey);
            byte[] digest = mac.doFinal(gram.getBytes(StandardCharsets.UTF_8));
            return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 11)
                    .replace('_', '.');
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] derive(byte[] master, String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(purpose.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] newIv() {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        return iv;
    }

    public byte[] partIv(byte[] baseIv, int partNumber) {
        byte[] iv = baseIv.clone();
        iv[IV_BYTES - 4] = (byte) (partNumber >>> 24);
        iv[IV_BYTES - 3] = (byte) (partNumber >>> 16);
        iv[IV_BYTES - 2] = (byte) (partNumber >>> 8);
        iv[IV_BYTES - 1] = (byte) partNumber;
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
