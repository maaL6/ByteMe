package org.example;

import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.example.auth.domain.account.*;
import org.example.auth.domain.exception.*;
import org.example.auth.service.port.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@org.springframework.test.context.ActiveProfiles("auth")
@org.springframework.context.annotation.Import(AuthIntegrationTest.OtherModuleController.class)
@SpringBootTest @AutoConfigureMockMvc(print=MockMvcPrint.NONE) @Testcontainers
class AuthIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("auth_integration").withInitScript("db/schema.sql");
    static final Path KEYS;
    static {
        try {
            KEYS=Files.createTempDirectory("sa-auth-test-keys");
            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
            Files.writeString(KEYS.resolve("private.pem"),pem("PRIVATE KEY",pair.getPrivate().getEncoded()));
            if (Files.getFileStore(KEYS).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(KEYS.resolve("private.pem"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            Files.writeString(KEYS.resolve("public.pem"),pem("PUBLIC KEY",pair.getPublic().getEncoded()));
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static String pem(String name,byte[] bytes) { return "-----BEGIN "+name+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+name+"-----\n"; }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);
        r.add("spring.datasource.password",MYSQL::getPassword);
        r.add("auth.private-key-file",()->KEYS.resolve("private.pem").toString());
        r.add("auth.public-key-file",()->KEYS.resolve("public.pem").toString());
    }
    @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @Autowired JwtDecoder decoder;@Autowired AccountRepository repository;@Autowired PasswordHasher passwords;
    static boolean seeded;
    @BeforeEach void seed() {
        if(!seeded) { new ResourceDatabasePopulator(new ClassPathResource("db/seed.sql")).execute(Objects.requireNonNull(db.getDataSource()));seeded=true; }
    }
    Map<String,Object> registration(String email,String phone) {
        return new LinkedHashMap<>(Map.of("fullName","  Khách tổng hợp  ","email",email,"phone",phone,
                                      "password","Sample-Test-Password","confirmPassword","Sample-Test-Password"));
    }
    org.springframework.test.web.servlet.MvcResult send(String path,Map<String,Object> body,int expected) throws Exception {
        var result=http.perform(post(path).contentType("application/json").content(json.writeValueAsBytes(body))).andReturn();
        assertEquals(expected,result.getResponse().getStatus());assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));
        return result;
    }
    @Test void registerThenLoginAgainstRealMysql() throws Exception {
        var created=send("/api/auth/register",registration("  NEW@integration.example  ","0911111101"),201);
        var account=json.readTree(created.getResponse().getContentAsString());
        assertEquals("new@integration.example",account.path("email").asText());assertEquals("CUSTOMER",account.path("role").asText());
        assertEquals("ACTIVE",account.path("status").asText());assertFalse(account.has("passwordHash"));assertFalse(account.has("password"));
        String hash=db.queryForObject("SELECT password_hash FROM users WHERE email=?",String.class,"new@integration.example");
        assertTrue(passwords.matches("Sample-Test-Password",hash));
        var response=send("/api/auth/token",Map.of("email","NEW@integration.example","password","Sample-Test-Password"),200);
        var payload=json.readTree(response.getResponse().getContentAsString());
        assertEquals(5400,payload.path("expiresIn").asLong());
        var jwt=decoder.decode(payload.path("accessToken").asText());
        assertEquals(account.path("id").asText(),jwt.getSubject());assertEquals("CUSTOMER",jwt.getClaimAsString("role"));
        assertEquals(5400,jwt.getExpiresAt().getEpochSecond()-jwt.getIssuedAt().getEpochSecond());
    }
    @Test void seedRolesAndAccountStatuses() throws Exception {
        for(long id:new long[]{1,2,4}) {
            String email=db.queryForObject("SELECT email FROM users WHERE id=?",String.class,id);
            var response=send("/api/auth/token",Map.of("email",email,"password","ByteMeDemo!2026"),200);
            var body=json.readTree(response.getResponse().getContentAsString());
            assertEquals(Long.toString(id),decoder.decode(body.path("accessToken").asText()).getSubject());
            assertEquals(db.queryForObject("SELECT role FROM users WHERE id=?",String.class,id),body.path("user").path("role").asText());
        }
        for(long id:new long[]{7,8}) {
            String email=db.queryForObject("SELECT email FROM users WHERE id=?",String.class,id);
            send("/api/auth/token",Map.of("email",email,"password","wrong"),401);
            send("/api/auth/token",Map.of("email",email,"password","ByteMeDemo!2026"),403);
        }
        send("/api/auth/token",Map.of("email","missing@integration.example","password","wrong"),401);
    }
    @Test void validationDoesNotCreatePrivilegedOrMalformedAccounts() throws Exception {
        var request=registration("validation@integration.example","0911111102");
        request.put("role","ADMIN");send("/api/auth/register",request,400);request.remove("role");
        request.put("fullName",123);send("/api/auth/register",request,400);request.put("fullName","valid");
        request.put("confirmPassword","different");send("/api/auth/register",request,400);
        request.put("password","ế".repeat(25));request.put("confirmPassword","ế".repeat(25));send("/api/auth/register",request,400);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,"validation@integration.example"));
        assertEquals(400,http.perform(post("/api/auth/register").contentType("application/json").content("{bad")).andReturn().getResponse().getStatus());
        var unsupported=http.perform(post("/api/auth/register").contentType("text/plain").content("test")).andReturn().getResponse();
        assertEquals(415,unsupported.getStatus());
        assertEquals("UNSUPPORTED_MEDIA_TYPE",json.readTree(unsupported.getContentAsString()).path("code").asText());
        assertEquals("no-store",unsupported.getHeader("Cache-Control"));
    }
    @Test void duplicateEmailAndPhoneAre409() throws Exception {
        send("/api/auth/register",registration("duplicate@integration.example","0911111103"),201);
        send("/api/auth/register",registration("DUPLICATE@integration.example","0911111104"),409);
        send("/api/auth/register",registration("other@integration.example","0911111103"),409);
    }
    @Test void databaseUniqueProtectsConcurrentInserts() throws Exception {
        var input=new NewCustomer("Race sample","race@integration.example","0911111105",passwords.hash("Sample-Test-Password"));
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> insert=()->{gate.await();try { repository.createCustomer(input);return true; }catch(DuplicateAccountException e) { return false; }};
            var a=pool.submit(insert);var b=pool.submit(insert);gate.countDown();
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,input.email()));
    }
    @Test void loginEmailQuotaAndPublicBearerBehaviour() throws Exception {
        for(int i=0;i<10;i++)send("/api/auth/token",Map.of("email","quota@integration.example","password","wrong"),401);
        var limited=send("/api/auth/token",Map.of("email","quota@integration.example","password","wrong"),429);
        assertTrue(Long.parseLong(limited.getResponse().getHeader("Retry-After"))>0);
        var body=registration("public@integration.example","0911111106");
        assertEquals(201,http.perform(post("/api/auth/register").header("Authorization","Bearer broken")
            .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus());
    }
    @Test void protectedUnknownRouteRejectsInvalidJwt() throws Exception {
        assertEquals(401,http.perform(get("/api/cart")).andReturn().getResponse().getStatus());
        assertEquals(401,http.perform(get("/api/cart").header("Authorization","Bearer broken")).andReturn().getResponse().getStatus());
    }
    @Autowired TokenIssuer tokens;
    @org.springframework.web.bind.annotation.RestController
    static class OtherModuleController {
        record Input(String value) {}
        @org.springframework.web.bind.annotation.PostMapping("/scope-check/echo")
        Input echo(@org.springframework.web.bind.annotation.RequestBody Input input) { return input; }
        @org.springframework.web.bind.annotation.GetMapping("/scope-check/error")
        String fail() { throw new IllegalStateException("synthetic module error"); }
    }
    @Test void strictJsonAndErrorAdviceAreLimitedToAuth() throws Exception {
        String token=tokens.issue(new org.example.shared.api.AuthenticatedUser("scope-user",
                org.example.shared.api.Role.CUSTOMER)).value();
        assertEquals(200,http.perform(post("/scope-check/echo").header("Authorization","Bearer "+token)
                .contentType("application/json").content("{\"value\":123,\"extra\":true}"))
                .andReturn().getResponse().getStatus());
        assertThrows(jakarta.servlet.ServletException.class,()->http.perform(get("/scope-check/error")
                .header("Authorization","Bearer "+token)));
        assertEquals(400,http.perform(post("/api/auth/token").contentType("application/json")
                .content("{\"email\":\"valid@scope.example\",\"password\":\"x\"} {}"))
                .andReturn().getResponse().getStatus());
    }
    @AfterAll static void cleanupKeys() throws Exception {
        Files.deleteIfExists(KEYS.resolve("private.pem"));Files.deleteIfExists(KEYS.resolve("public.pem"));Files.deleteIfExists(KEYS);
    }
}
