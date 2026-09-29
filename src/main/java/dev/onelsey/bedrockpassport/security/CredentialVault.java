package dev.onelsey.bedrockpassport.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

public final class CredentialVault {
    public static final String FORMAT = "aes-gcm-v1";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    public CredentialVault(Path keyFile) throws IOException {
        this.key = new SecretKeySpec(loadOrCreateKey(keyFile), "AES");
    }

    public String encrypt(String plaintext, String xuid, UUID javaUuid) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(xuid, javaUuid));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] packed = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, packed, 0, nonce.length);
        System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
        return Base64.getEncoder().encodeToString(packed);
    }

    public String decrypt(String encoded, String xuid, UUID javaUuid) throws GeneralSecurityException {
        byte[] packed = Base64.getDecoder().decode(encoded);
        if (packed.length <= NONCE_BYTES) {
            throw new GeneralSecurityException("Encrypted credential payload is truncated");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        byte[] encrypted = new byte[packed.length - NONCE_BYTES];
        System.arraycopy(packed, 0, nonce, 0, nonce.length);
        System.arraycopy(packed, nonce.length, encrypted, 0, encrypted.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(xuid, javaUuid));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static byte[] aad(String xuid, UUID javaUuid) {
        return ("BedrockPassport\0" + xuid + "\0" + javaUuid).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] loadOrCreateKey(Path keyFile) throws IOException {
        Files.createDirectories(keyFile.getParent());

        if (!Files.exists(keyFile)) {
            byte[] key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);
            String encoded = Base64.getEncoder().encodeToString(key) + System.lineSeparator();
            try {
                Files.writeString(
                        keyFile,
                        encoded,
                        StandardCharsets.US_ASCII,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                restrictPermissions(keyFile);
                return key;
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException exception) {
            throw new IOException("BedrockPassport credential key is not valid Base64", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IOException("BedrockPassport credential key must be exactly " + KEY_BYTES + " bytes");
        }
        restrictPermissions(keyFile);
        return decoded;
    }

    private static void restrictPermissions(Path keyFile) {
        try {
            Files.setPosixFilePermissions(
                    keyFile,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
