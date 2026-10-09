package org.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;

final class TestAuthKeys {
    private static final Path DIRECTORY = create();

    private TestAuthKeys() {}

    static void register(DynamicPropertyRegistry properties) {
        properties.add("auth.private-key-file", () -> DIRECTORY.resolve("private.pem").toString());
        properties.add("auth.public-key-file", () -> DIRECTORY.resolve("public.pem").toString());
    }

    private static Path create() {
        try {
            Path directory = Files.createTempDirectory("byteme-test-auth-keys");
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            Path privateKey = directory.resolve("private.pem");
            Files.writeString(privateKey, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(privateKey, PosixFilePermissions.fromString("rw-------"));
            }
            Files.writeString(directory.resolve("public.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(privateKey);
                    Files.deleteIfExists(directory.resolve("public.pem"));
                    Files.deleteIfExists(directory);
                } catch (Exception ignored) {
                    // Test-only temporary keys are outside the repository.
                }
            }));
            return directory;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String pem(String name, byte[] bytes) {
        return "-----BEGIN " + name + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + name + "-----\n";
    }
}
