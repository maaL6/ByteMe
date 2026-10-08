package org.example.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import com.nimbusds.jose.jwk.RSAKey;
import org.example.platform.security.LocalRsaTokenIssuer;
import org.example.shared.api.AuthenticatedUser;
import org.example.shared.api.Role;

/** Chỉ CLI local; không có endpoint cấp role, không thay thế đăng nhập thật. */
public final class LocalTokenCli {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Truyền đường dẫn thư mục .local");
        Path folder = Path.of(args[0]);
        var factory = KeyFactory.getInstance("RSA");
        var privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(readPem(folder.resolve("private.pem"))));
        var publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(readPem(folder.resolve("public.pem"))));
        var key = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        var issuer = new LocalRsaTokenIssuer(key, Clock.systemUTC(), 5400);
        var fixtures = Map.of("customer-a", Role.CUSTOMER, "customer-b", Role.CUSTOMER,
                              "staff-a", Role.EMPLOYEE, "admin-a", Role.ADMIN);
        for (var entry : fixtures.entrySet()) {
            var token = issuer.issue(new AuthenticatedUser(entry.getKey(), entry.getValue()));
            Path file = folder.resolve(entry.getKey() + ".token");
            if (!Files.exists(file)) Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(file, token.value());
        }
        System.out.println("Đã tạo 4 JWT local trong .local; không in token vào log.");
    }
    private static byte[] readPem(Path file) throws Exception {
        String pem = Files.readString(file).replaceAll("-----[^\\n]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(pem);
    }
}
