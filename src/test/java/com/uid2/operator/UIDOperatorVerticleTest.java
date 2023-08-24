package com.uid2.operator;

import com.uid2.operator.model.*;
import com.uid2.operator.model.IdentityScope;
import com.uid2.operator.monitoring.IStatsCollectorQueue;
import com.uid2.operator.monitoring.TokenResponseStatsCollector;
import com.uid2.operator.service.*;
import com.uid2.operator.util.PrivacyBits;
import com.uid2.operator.util.Tuple;
import com.uid2.operator.vertx.OperatorDisableHandler;
import com.uid2.shared.ApplicationVersion;
import com.uid2.shared.IClock;
import com.uid2.shared.attest.AttestationTokenRetriever;
import com.uid2.shared.attest.UidCoreClient;
import com.uid2.shared.cloud.CloudUtils;
import com.uid2.shared.Utils;
import com.uid2.shared.encryption.AesGcm;
import com.uid2.shared.encryption.Random;
import com.uid2.shared.model.*;
import com.uid2.operator.store.*;
import com.uid2.operator.vertx.UIDOperatorVerticle;
import com.uid2.shared.auth.ClientKey;
import com.uid2.shared.auth.Role;
import com.uid2.shared.store.*;
import com.uid2.shared.store.ACLMode.MissingAclMode;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.*;

import static com.uid2.operator.ClientSideTokenGenerateTestUtil.decrypt;
import static com.uid2.operator.service.EncodingUtils.getSha256;
import static com.uid2.operator.service.V2RequestUtil.V2_REQUEST_TIMESTAMP_DRIFT_THRESHOLD_IN_MINUTES;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.kms.endpoints.internal.Value;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;


@ExtendWith(VertxExtension.class)
public class UIDOperatorVerticleTest {
    private AutoCloseable mocks;
    @Mock
    private ISiteStore siteProvider;
    @Mock
    private IClientKeyProvider clientKeyProvider;
    @Mock
    private IClientSideKeypairStore clientSideKeypairProvider;
    @Mock
    private IClientSideKeypairStore.IClientSideKeypairStoreSnapshot clientSideKeypairSnapshot;
    @Mock
    private IKeyStore keyStore;
    @Mock
    private IKeyStore.IKeyStoreSnapshot keyStoreSnapshot;
    @Mock
    private IKeyAclProvider keyAclProvider;
    @Mock
    private IKeysAclSnapshot keyAclProviderSnapshot;
    @Mock
    private ISaltProvider saltProvider;
    @Mock
    private ISaltProvider.ISaltSnapshot saltProviderSnapshot;
    @Mock
    private IOptOutStore optOutStore;
    @Mock
    private Clock clock;
    @Mock
    private HttpClient httpClient;
    @Mock
    IClock mockIClock;
    private SimpleMeterRegistry registry;

    private static final String firstLevelSalt = "first-level-salt";
    private static final SaltEntry rotatingSalt123 = new SaltEntry(123, "hashed123", 0, "salt123");
    private static final Duration identityExpiresAfter = Duration.ofMinutes(10);
    private static final Duration refreshExpiresAfter = Duration.ofMinutes(15);
    private static final Duration refreshIdentityAfter = Duration.ofMinutes(5);
    private static final byte[] clientSecret = Random.getRandomKeyBytes();
    private static final String clientSideTokenGenerateSubscriptionId = "4WvryDGbR5";
    private static final String clientSideTokenGeneratePublicKey = "UID2-X-T-MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsziOqRXZ7II0uJusaMxxCxlxgj8el/MUYLFMtWfB71Q3G1juyrAnzyqruNiPPnIuTETfFOridglP9UQNlwzNQg==";
    private static final String clientSideTokenGeneratePrivateKey = "UID2-Y-T-MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCBop1Dw/IwDcstgicr/3tDoyR3OIpgAWgw8mD6oTO+1ug==";
    private static final int clientSideTokenGenerateSiteId = 123;
    private AttestationTokenRetriever fakeAttestationTokenRetriever ;
    private UidCoreClient fakeCoreClient;

    private OperatorDisableHandler operatorDisableHandler;

    private ExtendedUIDOperatorVerticle uidOperatorVerticle;

    @Mock
    private IStatsCollectorQueue statsCollectorQueue;

    public UIDOperatorVerticleTest() {
    }

    @BeforeEach
    void deployVerticle(Vertx vertx, VertxTestContext testContext, TestInfo testInfo) {
        mocks = MockitoAnnotations.openMocks(this);
        when(keyStore.getSnapshot()).thenReturn(keyStoreSnapshot);
        when(keyAclProvider.getSnapshot()).thenReturn(keyAclProviderSnapshot);
        when(saltProvider.getSnapshot(any())).thenReturn(saltProviderSnapshot);
        when(clock.instant()).thenAnswer(i -> Instant.now());

        this.operatorDisableHandler = new OperatorDisableHandler(Duration.ofHours(24), clock);
        this.fakeAttestationTokenRetriever = new AttestationTokenRetriever(vertx, null, null, new ApplicationVersion("test", "test"), null, operatorDisableHandler::handleResponseStatus, mockIClock, null, null);
        this.fakeCoreClient = new UidCoreClient("dummyToken", CloudUtils.defaultProxy, false, fakeAttestationTokenRetriever, null);

        final JsonObject config = new JsonObject();
        config.put(UIDOperatorService.IDENTITY_TOKEN_EXPIRES_AFTER_SECONDS, identityExpiresAfter.toMillis() / 1000);
        config.put(UIDOperatorService.REFRESH_TOKEN_EXPIRES_AFTER_SECONDS, refreshExpiresAfter.toMillis() / 1000);
        config.put(UIDOperatorService.REFRESH_IDENTITY_TOKEN_AFTER_SECONDS, refreshIdentityAfter.toMillis() / 1000);
        config.put(Const.Config.FailureShutdownWaitHoursProp, 24);
        if(testInfo.getDisplayName().equals("cstgNoPhoneSupport(Vertx, VertxTestContext)")) {
            config.put("enable_phone_support", false);
        }

        setupConfig(config);

        this.uidOperatorVerticle = new ExtendedUIDOperatorVerticle(config, siteProvider, clientKeyProvider, clientSideKeypairProvider, keyStore, keyAclProvider, saltProvider, optOutStore, clock, statsCollectorQueue);

        uidOperatorVerticle.setDisableHandler(this.operatorDisableHandler);

        vertx.deployVerticle(uidOperatorVerticle, testContext.succeeding(id -> testContext.completeNow()));

        registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
    }

    @AfterEach
    void teardown() throws Exception {
        Metrics.globalRegistry.remove(registry);
        mocks.close();
    }

    public void setupConfig(JsonObject config) {
        config.put("identity_scope", getIdentityScope().toString());
        config.put("advertising_token_v3", getTokenVersion() == TokenVersion.V3);
        config.put("advertising_token_v4", getTokenVersion() == TokenVersion.V4);
        config.put("identity_v3", useIdentityV3());
        config.put("client_side_token_generate", true);
        //still required these 2 for domain name check in getDomainNameListForClientSideTokenGenerate
        config.put("client_side_token_generate_test_subscription_id", "4WvryDGbR5");
        //not required any more
//        config.put("client_side_token_generate_test_private_key", clientSideTokenGeneratePrivateKey);
//        config.put("client_side_token_generate_test_site_id", 123);
    }

    private static byte[] makeAesKey(String prefix) {
        return String.format("%1$16s", prefix).getBytes();
    }

    private void addEncryptionKeys(EncryptionKey... keys) {
        when(keyStoreSnapshot.getActiveKeySet()).thenReturn(Arrays.asList(keys));
    }

    protected void fakeAuth(int siteId, Role... roles) {
        ClientKey clientKey = new ClientKey("test-key", Utils.toBase64String(clientSecret))
            .withSiteId(siteId).withRoles(roles).withContact("test-contact");
        when(clientKeyProvider.get(any())).thenReturn(clientKey);
        when(clientKeyProvider.getClientKey(any())).thenReturn(clientKey);
    }

    private void clearAuth() {
        when(clientKeyProvider.get(any())).thenReturn(null);
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String getUrlForEndpoint(String endpoint) {
        return String.format("http://127.0.0.1:%d/%s", Const.Port.ServicePortForOperator + Utils.getPortOffset(), endpoint);
    }

    private void send(String apiVersion, Vertx vertx, String endpoint, boolean isV1Get, String v1GetParam, JsonObject postPayload, int expectedHttpCode, Handler<JsonObject> handler) {
        if (apiVersion.equals("v2")) {
            ClientKey ck = (ClientKey) clientKeyProvider.get("");

            long nonce = new BigInteger(Random.getBytes(8)).longValue();

            postV2(ck, vertx, endpoint, postPayload, nonce, null, ar -> {
                assertTrue(ar.succeeded());
                assertEquals(expectedHttpCode, ar.result().statusCode());

                if (ar.result().statusCode() == 200) {
                    byte[] decrypted = AesGcm.decrypt(Utils.decodeBase64String(ar.result().bodyAsString()), 0, ck.getSecretBytes());
                    assertArrayEquals(Buffer.buffer().appendLong(nonce).getBytes(), Buffer.buffer(decrypted).slice(8, 16).getBytes());

                    JsonObject respJson = new JsonObject(new String(decrypted, 16, decrypted.length - 16, StandardCharsets.UTF_8));

                    handler.handle(respJson);
                } else {
                    handler.handle(tryParseResponse(ar.result()));
                }
            });
        } else if (isV1Get) {
            get(vertx, endpoint + (v1GetParam != null ? "?" + v1GetParam : ""), ar -> {
                assertTrue(ar.succeeded());
                assertEquals(expectedHttpCode, ar.result().statusCode());
                handler.handle(tryParseResponse(ar.result()));
            });
        } else {
            post(vertx, endpoint, postPayload, ar -> {
                assertTrue(ar.succeeded());
                assertEquals(expectedHttpCode, ar.result().statusCode());
                handler.handle(tryParseResponse(ar.result()));
            });
        }
    }

    protected void sendTokenGenerate(String apiVersion, Vertx vertx, String v1GetParam, JsonObject v2PostPayload, int expectedHttpCode,
                                     Handler<JsonObject> handler) {
        sendTokenGenerate(apiVersion, vertx, v1GetParam, v2PostPayload, expectedHttpCode, null, handler);
    }


    private void sendTokenGenerate(String apiVersion, Vertx vertx, String v1GetParam, JsonObject v2PostPayload, int expectedHttpCode, String referer, Handler<JsonObject> handler) {
        if (apiVersion.equals("v2")) {
            ClientKey ck = (ClientKey) clientKeyProvider.get("");

            long nonce = new BigInteger(Random.getBytes(8)).longValue();

            addAdditionalTokenGenerateParams(v2PostPayload);

            postV2(ck, vertx, apiVersion + "/token/generate", v2PostPayload, nonce, referer, ar -> {
                assertTrue(ar.succeeded());
                assertEquals(expectedHttpCode, ar.result().statusCode());

                if (ar.result().statusCode() == 200) {
                    byte[] decrypted = AesGcm.decrypt(Utils.decodeBase64String(ar.result().bodyAsString()), 0, ck.getSecretBytes());

                    assertArrayEquals(Buffer.buffer().appendLong(nonce).getBytes(), Buffer.buffer(decrypted).slice(8, 16).getBytes());

                    JsonObject respJson = new JsonObject(new String(decrypted, 16, decrypted.length - 16, StandardCharsets.UTF_8));

                    decodeV2RefreshToken(respJson);

                    handler.handle(respJson);
                } else {
                    handler.handle(tryParseResponse(ar.result()));
                }
            });
        } else {
            get(vertx, apiVersion + "/token/generate" + (v1GetParam != null ? "?" + v1GetParam : ""), ar -> {
                assertTrue(ar.succeeded());
                assertEquals(expectedHttpCode, ar.result().statusCode());
                handler.handle(tryParseResponse(ar.result()));
            });
        }
    }

    private void sendTokenRefresh(String apiVersion, Vertx vertx, String refreshToken, String v2RefreshDecryptSecret, int expectedHttpCode,
                                  Handler<JsonObject> handler) {
        if (apiVersion.equals("v2")) {
            WebClient client = WebClient.create(vertx);
            client.postAbs(getUrlForEndpoint("v2/token/refresh"))
                .putHeader("content-type", "text/plain")
                .sendBuffer(Buffer.buffer(refreshToken.getBytes(StandardCharsets.UTF_8)), ar -> {
                    assertTrue(ar.succeeded());
                    assertEquals(expectedHttpCode, ar.result().statusCode());

                    if (ar.result().statusCode() == 200 && v2RefreshDecryptSecret != null) {
                        byte[] decrypted = AesGcm.decrypt(Utils.decodeBase64String(ar.result().bodyAsString()), 0, Utils.decodeBase64String(v2RefreshDecryptSecret));
                        JsonObject respJson = new JsonObject(new String(decrypted, StandardCharsets.UTF_8));

                        if (respJson.getString("status").equals("success"))
                            decodeV2RefreshToken(respJson);

                        handler.handle(respJson);
                    } else {
                        handler.handle(tryParseResponse(ar.result()));
                    }
                });
        } else {
            get(vertx, "v1/token/refresh?refresh_token=" + urlEncode(refreshToken), ar -> {
                assertTrue(ar.succeeded());
                HttpResponse<Buffer> response = ar.result();
                assertEquals(expectedHttpCode, response.statusCode());
                JsonObject json = response.bodyAsJsonObject();
                handler.handle(json);
            });
        }
    }

    private void decodeV2RefreshToken(JsonObject respJson) {
        if (respJson.containsKey("body")) {
            JsonObject bodyJson = respJson.getJsonObject("body");

            byte[] tokenBytes = Utils.decodeBase64String(bodyJson.getString("refresh_token"));
            EncryptionKey refreshKey = keyStore.getSnapshot().getKey(Buffer.buffer(tokenBytes).getInt(1));

            byte[] decrypted = AesGcm.decrypt(tokenBytes, 5, refreshKey);
            JsonObject tokenKeyJson = new JsonObject(new String(decrypted));

            String refreshToken = tokenKeyJson.getString("refresh_token");
            bodyJson.put("decrypted_refresh_token", refreshToken);
        }
    }

    private JsonObject tryParseResponse(HttpResponse<Buffer> resp) {
        try {
            return resp.bodyAsJsonObject();
        } catch (Exception ex) {
            return null;
        }
    }

    private void get(Vertx vertx, String endpoint, Handler<AsyncResult<HttpResponse<Buffer>>> handler) {
        WebClient client = WebClient.create(vertx);
        ClientKey ck = clientKeyProvider.getClientKey("");
        HttpRequest<Buffer> req = client.getAbs(getUrlForEndpoint(endpoint));
        if (ck != null)
            req.putHeader("Authorization", "Bearer " + ck.getKey());
        req.send(handler);
    }

    private void post(Vertx vertx, String endpoint, JsonObject body, Handler<AsyncResult<HttpResponse<Buffer>>> handler) {
        WebClient client = WebClient.create(vertx);
        ClientKey ck = clientKeyProvider.getClientKey("");
        HttpRequest<Buffer> req = client.postAbs(getUrlForEndpoint(endpoint));
        if (ck != null)
            req.putHeader("Authorization", "Bearer " + ck.getKey());
        req.sendJsonObject(body, handler);
    }

    private void postV2(ClientKey ck, Vertx vertx, String endpoint, JsonObject body, long nonce, String referer, Handler<AsyncResult<HttpResponse<Buffer>>> handler) {
        WebClient client = WebClient.create(vertx);

        Buffer b = Buffer.buffer();
        b.appendLong(Instant.now().toEpochMilli());
        b.appendLong(nonce);

        if (body != null)
            b.appendBytes(body.encode().getBytes(StandardCharsets.UTF_8));

        Buffer bufBody = Buffer.buffer();
        bufBody.appendByte((byte) 1);
        if (ck != null){
            bufBody.appendBytes(AesGcm.encrypt(b.getBytes(), ck.getSecretBytes()));
        }

        final String apiKey = ck == null ? "" : ck.getKey();
        HttpRequest<Buffer> request = client.postAbs(getUrlForEndpoint(endpoint))
            .putHeader("Authorization", "Bearer " + apiKey)
            .putHeader("content-type", "text/plain");
        if (referer != null) {
            request.putHeader("Referer", referer);
        }
        request.sendBuffer(Buffer.buffer(Utils.toBase64String(bufBody.getBytes()).getBytes(StandardCharsets.UTF_8)), handler);
    }

    private void checkEncryptionKeysResponse(JsonObject response, EncryptionKey... expectedKeys) {
        assertEquals("success", response.getString("status"));
        final JsonArray responseKeys = response.getJsonArray("body");
        assertNotNull(responseKeys);
        assertEquals(expectedKeys.length, responseKeys.size());
        for (int i = 0; i < expectedKeys.length; ++i) {
            EncryptionKey expectedKey = expectedKeys[i];
            JsonObject actualKey = responseKeys.getJsonObject(i);
            assertEquals(expectedKey.getId(), actualKey.getInteger("id"));
            assertArrayEquals(expectedKey.getKeyBytes(), actualKey.getBinary("secret"));
            assertEquals(expectedKey.getCreated().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("created")));
            assertEquals(expectedKey.getActivates().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("activates")));
            assertEquals(expectedKey.getExpires().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("expires")));
            assertEquals(expectedKey.getSiteId(), actualKey.getInteger("site_id"));
        }
    }

    private void checkEncryptionKeysSharing(JsonObject response, int siteId, EncryptionKey... expectedKeys) {
        assertEquals("success", response.getString("status"));
        final JsonArray responseKeys = response.getJsonObject("body").getJsonArray("keys");
        assertNotNull(responseKeys);
        assertEquals(expectedKeys.length, responseKeys.size());
        for (int i = 0; i < expectedKeys.length; ++i) {
            EncryptionKey expectedKey = expectedKeys[i];
            JsonObject actualKey = responseKeys.getJsonObject(i);
            assertEquals(expectedKey.getId(), actualKey.getInteger("id"));
            assertArrayEquals(expectedKey.getKeyBytes(), actualKey.getBinary("secret"));
            assertEquals(expectedKey.getCreated().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("created")));
            assertEquals(expectedKey.getActivates().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("activates")));
            assertEquals(expectedKey.getExpires().truncatedTo(ChronoUnit.SECONDS), Instant.ofEpochSecond(actualKey.getLong("expires")));
            // This is TEMPORARY until while keyset_id is hard coded
            if(expectedKey.getSiteId() == siteId){
                assertEquals(99999, actualKey.getInteger("keyset_id"));
            }
            else if(expectedKey.getSiteId() == -1) {
                assertEquals(1, actualKey.getInteger("keyset_id"));
            }
            else {
                assertNull(actualKey.getInteger("keyset_id"));
            }
        }
    }

    private void checkIdentityMapResponse(JsonObject response, String... expectedIdentifiers) {
        assertEquals("success", response.getString("status"));
        JsonObject body = response.getJsonObject("body");
        JsonArray mapped = body.getJsonArray("mapped");
        assertNotNull(mapped);
        assertEquals(expectedIdentifiers.length, mapped.size());
        for (int i = 0; i < expectedIdentifiers.length; ++i) {
            String expectedIdentifier = expectedIdentifiers[i];
            JsonObject actualMap = mapped.getJsonObject(i);
            assertEquals(expectedIdentifier, actualMap.getString("identifier"));
            assertFalse(actualMap.getString("advertising_id").isEmpty());
            assertFalse(actualMap.getString("bucket_id").isEmpty());
        }
    }

    protected void setupSalts() {
        when(saltProviderSnapshot.getFirstLevelSalt()).thenReturn(firstLevelSalt);
        when(saltProviderSnapshot.getRotatingSalt(any())).thenReturn(rotatingSalt123);
    }

    protected void setupKeys() {
        EncryptionKey masterKey = new EncryptionKey(101, makeAesKey("masterKey"), Instant.now().minusSeconds(7), Instant.now(), Instant.now().plusSeconds(10), -1);
        EncryptionKey siteKey = new EncryptionKey(102, makeAesKey("siteKey"), Instant.now().minusSeconds(7), Instant.now(), Instant.now().plusSeconds(10), Const.Data.AdvertisingTokenSiteId);
        EncryptionKey refreshKey = new EncryptionKey(103, makeAesKey("refreshKey"), Instant.now().minusSeconds(7), Instant.now(), Instant.now().plusSeconds(10), -2);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);
        when(keyStoreSnapshot.getMasterKey(any())).thenReturn(masterKey);
        when(keyStoreSnapshot.getRefreshKey(any())).thenReturn(refreshKey);
        when(keyStoreSnapshot.getActiveSiteKey(eq(Const.Data.AdvertisingTokenSiteId), any())).thenReturn(siteKey);
        when(keyStoreSnapshot.getKey(101)).thenReturn(masterKey);
        when(keyStoreSnapshot.getKey(102)).thenReturn(siteKey);
        when(keyStoreSnapshot.getKey(103)).thenReturn(refreshKey);
        when(keyStoreSnapshot.getActiveKeySet()).thenReturn(Arrays.asList(masterKey, siteKey, refreshKey));
        when(clientSideKeypairSnapshot.getKeypair(clientSideTokenGenerateSubscriptionId)).thenReturn(
                new ClientSideKeypair(clientSideTokenGenerateSubscriptionId, clientSideTokenGeneratePublicKey, clientSideTokenGeneratePrivateKey, clientSideTokenGenerateSiteId,
                        clientSideTokenGenerateSubscriptionId, Instant.now(), false));
    }

    protected void setupSiteKey(int siteId, int keyId) {
        EncryptionKey siteKey = new EncryptionKey(keyId, makeAesKey("siteKey" + siteId), Instant.now().minusSeconds(7), Instant.now(), Instant.now().plusSeconds(10), siteId);
        when(keyStoreSnapshot.getActiveSiteKey(eq(siteId), any())).thenReturn(siteKey);
        when(keyStoreSnapshot.getKey(keyId)).thenReturn(siteKey);
    }

    private void generateTokens(String apiVersion, Vertx vertx, String inputType, String input, Handler<JsonObject> handler) {
        String v1Param = inputType + "=" + urlEncode(input);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put(inputType, input);

        sendTokenGenerate(apiVersion, vertx, v1Param, v2Payload, 200, handler);
    }

    private static void assertEqualsClose(Instant expected, Instant actual, int withinSeconds) {
        assertTrue(expected.minusSeconds(withinSeconds).isBefore(actual));
        assertTrue(expected.plusSeconds(withinSeconds).isAfter(actual));
    }

    private void assertTokenStatusMetrics(Integer siteId, TokenResponseStatsCollector.Endpoint endpoint, TokenResponseStatsCollector.ResponseStatus responseStatus) {
        assertEquals(1, Metrics.globalRegistry
                .get("uid2.token_response_status_count")
                .tag("site_id", String.valueOf(siteId))
                .tag("token_endpoint", String.valueOf(endpoint))
                .tag("token_response_status", String.valueOf(responseStatus))
                .counter().count());
    }

    private byte[] getAdvertisingIdFromIdentity(IdentityType identityType, String identityString, String firstLevelSalt, String rotatingSalt) {
        return getRawUid(identityType, identityString, firstLevelSalt, rotatingSalt, getIdentityScope(), useIdentityV3());
    }

    private static byte[] getRawUid(IdentityType identityType, String identityString, String firstLevelSalt, String rotatingSalt, IdentityScope identityScope, boolean useIdentityV3) {
        return !useIdentityV3
                ? TokenUtils.getAdvertisingIdV2FromIdentity(identityString, firstLevelSalt, rotatingSalt)
                : TokenUtils.getAdvertisingIdV3FromIdentity(identityScope, identityType, identityString, firstLevelSalt, rotatingSalt);
    }

    public static byte[] getRawUid(IdentityType identityType, String identityString, IdentityScope identityScope, boolean useIdentityV3) {
        return !useIdentityV3
                ? TokenUtils.getAdvertisingIdV2FromIdentity(identityString, firstLevelSalt, rotatingSalt123.getSalt())
                : TokenUtils.getAdvertisingIdV3FromIdentity(identityScope, identityType, identityString, firstLevelSalt, rotatingSalt123.getSalt());
    }



    private byte[] getAdvertisingIdFromIdentityHash(IdentityType identityType, String identityString, String firstLevelSalt, String rotatingSalt) {
        return !useIdentityV3()
                ? TokenUtils.getAdvertisingIdV2FromIdentityHash(identityString, firstLevelSalt, rotatingSalt)
                : TokenUtils.getAdvertisingIdV3FromIdentityHash(getIdentityScope(), identityType, identityString, firstLevelSalt, rotatingSalt);
    }

    protected TokenVersion getTokenVersion() {return TokenVersion.V2;}

    final boolean useIdentityV3() { return getTokenVersion() != TokenVersion.V2; }
    protected IdentityScope getIdentityScope() { return IdentityScope.UID2; }
    protected void addAdditionalTokenGenerateParams(JsonObject payload) {}

    @Test
    void verticleDeployed(Vertx vertx, VertxTestContext testContext) {
        testContext.completeNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void keyLatestNoAcl(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        fakeAuth(205, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
            new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 201),
            new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);

        send(apiVersion, vertx, apiVersion + "/key/latest", true, null, null, 200, respJson -> {
            checkEncryptionKeysResponse(respJson, encryptionKeys);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void keyLatestWithAcl(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        fakeAuth(205, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
            new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 201),
            new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).then((i) -> {
            return i.getArgument(1, EncryptionKey.class).getId() > 101;
        });

        send(apiVersion, vertx, apiVersion + "/key/latest", true, null, null, 200, respJson -> {
            checkEncryptionKeysResponse(respJson, Arrays.copyOfRange(encryptionKeys, 1, 2));
            testContext.completeNow();
        });
    }

    @Test
    void keySharingCorrectIDS(Vertx vertx, VertxTestContext testContext) {
        String apiVersion = "v2";
        int siteId = 4;
        fakeAuth(siteId, Role.SHARER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(6, "sharingkey6".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 42),
                new EncryptionKey(12, "sharingkey12".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 43),
                new EncryptionKey(13, "sharingkey13".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 44),
                new EncryptionKey(14, "sharingkey14".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 45),
        };
        addEncryptionKeys(encryptionKeys);

        //Creates a subset of all IDs
        EncryptionKey[] expectedKeys = Arrays.copyOfRange(encryptionKeys, 1, 3);

        // This sets ACL that the client can only access the calling keys
        for (EncryptionKey expectedKey : expectedKeys) {
            when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(expectedKey))).thenReturn(true);
        }

        send(apiVersion, vertx, apiVersion + "/key/sharing", true, null, null, 200, respJson -> {
            System.out.println(respJson);
            assertEquals(siteId, respJson.getJsonObject("body").getInteger("caller_site_id"));
            assertEquals(1, respJson.getJsonObject("body").getInteger("master_keyset_id"));
            assertEquals(99999, respJson.getJsonObject("body").getInteger("default_keyset_id"));
            testContext.completeNow();
        });
    }

    @Test
    void keySharingCorrectFiltering(Vertx vertx, VertxTestContext testContext) {
        //Call should return
        // all keys they have access in ACL
        // The master key -1
        //Call Should not return
        // The master key -2
        // The publisher General 2
        // Any other key without an ACL
        String apiVersion = "v2";
        int siteId = 4;
        fakeAuth(siteId, Role.SHARER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(6, "sharingkey6".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 42),
                new EncryptionKey(12, "sharingkey12".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 43),
                new EncryptionKey(13, "sharingkey13".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 44),
                new EncryptionKey(14, "sharingkey14".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 45),
                new EncryptionKey(3, "masterKey".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), -1),
                new EncryptionKey(42, "masterKey2".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), -2),
                new EncryptionKey(6, "clientsKey".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 4),
                new EncryptionKey(5, "publisherMaster".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 2),
                new EncryptionKey(9, "key with no ACL".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 2),
        };
        addEncryptionKeys(encryptionKeys);

        //Creates a subset of all IDs
        EncryptionKey[] expectedKeyACL = Arrays.copyOfRange(encryptionKeys, 1, 3);

        // This sets ACL that the client can only access the calling keys
        for (EncryptionKey expectedKey : expectedKeyACL) {
            when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(expectedKey), any())).thenReturn(true);
        }

        when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(encryptionKeys[4]), any())).thenReturn(true);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(encryptionKeys[5]), any())).thenReturn(true);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(encryptionKeys[6]), any())).thenReturn(true);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(encryptionKeys[7]), eq(MissingAclMode.DENY_ALL))).thenReturn(false);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(encryptionKeys[7]), eq(MissingAclMode.ALLOW_ALL))).thenReturn(true);

        //Add the expected Default keys to the expected Keys from filtering
        EncryptionKey[] expectedKeysDefault = new EncryptionKey[]{encryptionKeys[4], encryptionKeys[6]};
        EncryptionKey[] expectedKeys = new EncryptionKey[expectedKeyACL.length + expectedKeysDefault.length];
        System.arraycopy(expectedKeyACL, 0, expectedKeys, 0, expectedKeyACL.length);
        System.arraycopy(expectedKeysDefault, 0, expectedKeys, expectedKeyACL.length, expectedKeysDefault.length);


        send(apiVersion, vertx, apiVersion + "/key/sharing", true, null, null, 200, respJson -> {
            System.out.println(respJson);
            checkEncryptionKeysSharing(respJson, siteId, expectedKeys);
            testContext.completeNow();
        });
    }

    @Test
    void keySharingReturnsMasterAndSite(Vertx vertx, VertxTestContext testContext) {
        String apiVersion = "v2";
        int siteId = 4;
        fakeAuth(siteId, Role.SHARER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(1, "master key".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), -1),
                new EncryptionKey(4, "site key".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 4),
        };
        addEncryptionKeys(encryptionKeys);

        //Creates a subset of all IDs
        // This sets ACL that the client can only access the calling keys
        for (EncryptionKey expectedKey : encryptionKeys) {
            //returning false to prove that function always returns above keys
            when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(expectedKey), any())).thenReturn(false);
        }

        send(apiVersion, vertx, apiVersion + "/key/sharing", true, null, null, 200, respJson -> {
            System.out.println(respJson);
            checkEncryptionKeysSharing(respJson, siteId, encryptionKeys);
            testContext.completeNow();
        });
    }

    @Test
    void keySharingIDREADER(Vertx vertx, VertxTestContext testContext) {
        String apiVersion = "v2";
        int siteId = 4;
        fakeAuth(siteId, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
                new EncryptionKey(1, "master key".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), -1),
                new EncryptionKey(4, "site key".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 4),
                new EncryptionKey(2, "no acl key".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 2),
        };
        addEncryptionKeys(encryptionKeys);

        //Creates a subset of all IDs
        // This sets ACL that the client can only access the calling keys
        for (EncryptionKey expectedKey : encryptionKeys) {
            //returning false to prove that function always returns above keys
            when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(expectedKey), eq(MissingAclMode.ALLOW_ALL))).thenReturn(true);
            when(keyAclProviderSnapshot.canClientAccessKey(any(), eq(expectedKey), eq(MissingAclMode.DENY_ALL))).thenReturn(false);
        }

        send(apiVersion, vertx, apiVersion + "/key/sharing", true, null, null, 200, respJson -> {
            System.out.println(respJson);
            checkEncryptionKeysSharing(respJson, siteId, encryptionKeys);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void keyLatestClientBelongsToReservedSiteId(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        fakeAuth(Const.Data.AdvertisingTokenSiteId, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
            new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 201),
            new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);

        send(apiVersion, vertx, apiVersion + "/key/latest", true, null, null, 401, respJson -> testContext.completeNow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void keyLatestHideRefreshKey(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        fakeAuth(205, Role.ID_READER);
        EncryptionKey[] encryptionKeys = {
            new EncryptionKey(101, "key101".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), -1),
            new EncryptionKey(102, "key102".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), -2),
            new EncryptionKey(103, "key103".getBytes(), Instant.now(), Instant.now(), Instant.now().plusSeconds(10), 202),
        };
        addEncryptionKeys(encryptionKeys);
        when(keyAclProviderSnapshot.canClientAccessKey(any(), any())).thenReturn(true);

        send(apiVersion, vertx, apiVersion + "/key/latest", true, null, null, 200, respJson -> {
            checkEncryptionKeysResponse(respJson,
                Arrays.stream(encryptionKeys).filter(k -> k.getSiteId() != -2).toArray(EncryptionKey[]::new));
            testContext.completeNow();
        });
    }


    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateBothEmailAndHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        final String emailHash = TokenUtils.getIdentityHashString(emailAddress);
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "email=" + emailAddress + "&email_hash=" + urlEncode(emailHash);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("email", emailAddress);
        v2Payload.put("email_hash", emailHash);

        sendTokenGenerate(apiVersion, vertx,
            v1Param, v2Payload, 400,
            json -> {
                assertFalse(json.containsKey("body"));

                assertEquals("client_error", json.getString("status"));
                testContext.completeNow();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateNoEmailOrHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        sendTokenGenerate(apiVersion, vertx,
            "", null, 400,
            json -> {
                assertFalse(json.containsKey("body"));
                assertEquals("client_error", json.getString("status"));
                testContext.completeNow();
            });
    }

    private void assertStatsCollector(String path, String referer, String apiContact, Integer siteId) {
        final ArgumentCaptor<StatsCollectorMessageItem> messageCaptor = ArgumentCaptor.forClass(StatsCollectorMessageItem.class);
        verify(statsCollectorQueue).enqueue(any(), messageCaptor.capture());

        final StatsCollectorMessageItem messageItem = messageCaptor.getValue();
        assertEquals(path, messageItem.getPath());
        assertEquals(apiContact, messageItem.getApiContact());
        assertEquals(referer, messageItem.getReferer());
        assertEquals(siteId, messageItem.getSiteId());
    }

    private AdvertisingToken validateAndGetToken(EncryptedTokenEncoder encoder, JsonObject body, IdentityType identityType) { //See UID2-79+Token+and+ID+format+v3
        final String advertisingTokenString = body.getString("advertising_token");
        validateAdvertisingToken(advertisingTokenString, getTokenVersion(), getIdentityScope(), identityType);
        return encoder.decodeAdvertisingToken(advertisingTokenString);
    }

    public static void validateAdvertisingToken(String advertisingTokenString, TokenVersion tokenVersion, IdentityScope identityScope, IdentityType identityType) {
        if (tokenVersion == TokenVersion.V2) {
            assertEquals("Ag", advertisingTokenString.substring(0, 2));
        } else {
            String firstChar = advertisingTokenString.substring(0, 1);
            if (identityScope == IdentityScope.UID2) {
                assertEquals(identityType == IdentityType.Email ? "A" : "B", firstChar);
            } else {
                assertEquals(identityType == IdentityType.Email ? "E" : "F", firstChar);
            }

            String secondChar = advertisingTokenString.substring(1, 2);
            if (tokenVersion == TokenVersion.V3) {
                assertEquals("3", secondChar);
            } else {
                assertEquals("4", secondChar);

                //No URL-unfriendly characters allowed:
                assertEquals(-1, advertisingTokenString.indexOf('='));
                assertEquals(-1, advertisingTokenString.indexOf('+'));
                assertEquals(-1, advertisingTokenString.indexOf('/'));
            }
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateForEmail(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "email=" + emailAddress;
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("email", emailAddress);

        sendTokenGenerate(apiVersion, vertx,
            v1Param, v2Payload, 200,
            json -> {
                assertEquals("success", json.getString("status"));
                JsonObject body = json.getJsonObject("body");
                assertNotNull(body);
                EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

                AdvertisingToken advertisingToken = validateAndGetToken(encoder, body, IdentityType.Email);

                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
                assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
                assertArrayEquals(getAdvertisingIdFromIdentity(IdentityType.Email, emailAddress, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

                RefreshToken refreshToken = encoder.decodeRefreshToken(body.getString(apiVersion.equals("v2")? "decrypted_refresh_token" :  "refresh_token"));
                assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
                assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentity(emailAddress, firstLevelSalt), refreshToken.userIdentity.id);

                assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("identity_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_from")), 10);

                assertStatsCollector("/" + apiVersion + "/token/generate", null, "test-contact", clientSiteId);

                testContext.completeNow();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateForEmailHash(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailHash = TokenUtils.getIdentityHashString("test@uid2.com");
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "email_hash=" + urlEncode(emailHash);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("email_hash", emailHash);

        sendTokenGenerate(apiVersion, vertx,
            v1Param, v2Payload, 200,
            json -> {
                assertEquals("success", json.getString("status"));
                JsonObject body = json.getJsonObject("body");
                assertNotNull(body);
                EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

                AdvertisingToken advertisingToken = validateAndGetToken(encoder, body, IdentityType.Email);

                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
                assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
                assertArrayEquals(getAdvertisingIdFromIdentityHash(IdentityType.Email, emailHash, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

                RefreshToken refreshToken = encoder.decodeRefreshToken(apiVersion.equals("v2") ? body.getString("decrypted_refresh_token") : body.getString("refresh_token"));
                assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
                assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentityHash(emailHash, firstLevelSalt), refreshToken.userIdentity.id);

                assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("identity_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_from")), 10);

                testContext.completeNow();
            });
    }


    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenRefresh(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "email", emailAddress, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            assertNotNull(bodyJson);

            String genRefreshToken = bodyJson.getString("refresh_token");

            when(this.optOutStore.getLatestEntry(any())).thenReturn(null);

            sendTokenRefresh(apiVersion, vertx, genRefreshToken, bodyJson.getString("refresh_response_key"), 200, refreshRespJson ->
            {
                assertEquals("success", refreshRespJson.getString("status"));
                JsonObject refreshBody = refreshRespJson.getJsonObject("body");
                assertNotNull(refreshBody);
                EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

                AdvertisingToken advertisingToken = validateAndGetToken(encoder, refreshBody, IdentityType.Email);

                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
                assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
                assertArrayEquals(getAdvertisingIdFromIdentity(IdentityType.Email, emailAddress, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

                String refreshTokenStringNew = refreshBody.getString(apiVersion.equals("v2") ? "decrypted_refresh_token" : "refresh_token");
                assertNotEquals(genRefreshToken, refreshTokenStringNew);
                RefreshToken refreshToken = encoder.decodeRefreshToken(refreshTokenStringNew);
                assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
                assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentity(emailAddress, firstLevelSalt), refreshToken.userIdentity.id);

                assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("identity_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("refresh_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("refresh_from")), 10);

                assertTokenStatusMetrics(
                        clientSiteId,
                        apiVersion.equals("v1") ? TokenResponseStatsCollector.Endpoint.GenerateV1 : TokenResponseStatsCollector.Endpoint.GenerateV2,
                        TokenResponseStatsCollector.ResponseStatus.Success);
                assertTokenStatusMetrics(
                        clientSiteId,
                        apiVersion.equals("v1") ? TokenResponseStatsCollector.Endpoint.RefreshV1 : TokenResponseStatsCollector.Endpoint.RefreshV2,
                        TokenResponseStatsCollector.ResponseStatus.Success);

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenValidateWithEmail_Match(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = UIDOperatorVerticle.ValidationInputEmail;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "email", emailAddress, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject genBody = genRespJson.getJsonObject("body");
            assertNotNull(genBody);

            String advertisingTokenString = genBody.getString("advertising_token");

            String v1Param = "token=" + urlEncode(advertisingTokenString) + "&email=" + emailAddress;
            JsonObject v2Payload = new JsonObject();
            v2Payload.put("token", advertisingTokenString);
            v2Payload.put("email", emailAddress);

            send(apiVersion, vertx, apiVersion + "/token/validate", true, v1Param, v2Payload, 200, json -> {
                assertTrue(json.getBoolean("body"));
                assertEquals("success", json.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenValidateWithEmailHash_Match(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = UIDOperatorVerticle.ValidationInputEmail;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "email", emailAddress, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject genBody = genRespJson.getJsonObject("body");
            assertNotNull(genBody);

            String advertisingTokenString = genBody.getString("advertising_token");

            String v1Param = "token=" + urlEncode(advertisingTokenString) + "&email_hash=" + urlEncode(EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputEmailHash));
            JsonObject v2Payload = new JsonObject();
            v2Payload.put("token", advertisingTokenString);
            v2Payload.put("email_hash", EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputEmailHash));

            send(apiVersion, vertx, apiVersion + "/token/validate", true, v1Param, v2Payload, 200, json -> {
                assertTrue(json.getBoolean("body"));
                assertEquals("success", json.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenValidateWithBothEmailAndEmailHash(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = UIDOperatorVerticle.ValidationInputEmail;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "email", emailAddress, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject genBody = genRespJson.getJsonObject("body");
            assertNotNull(genBody);

            String advertisingTokenString = genBody.getString("advertising_token");

            String v1Param = "token=" + urlEncode(advertisingTokenString) + "&email=" + emailAddress + "&email_hash=" + urlEncode(EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputEmailHash));
            JsonObject v2Payload = new JsonObject();
            v2Payload.put("token", advertisingTokenString);
            v2Payload.put("email", emailAddress);
            v2Payload.put("email_hash", emailAddress);

            send(apiVersion, vertx, apiVersion + "/token/validate", true, v1Param, v2Payload, 400, json -> {
                assertFalse(json.containsKey("body"));
                assertEquals("client_error", json.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateUsingCustomSiteKey(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final int siteKeyId = 1201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();
        setupSiteKey(clientSiteId, siteKeyId);

        String v1Param = "email=" + emailAddress;
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("email", emailAddress);

        sendTokenGenerate(apiVersion, vertx, v1Param, v2Payload, 200, json -> {
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);
            EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

            AdvertisingToken advertisingToken = validateAndGetToken(encoder, body, IdentityType.Email);
            verify(keyStoreSnapshot).getKey(eq(siteKeyId));
            verify(keyStoreSnapshot, times(0)).getKey(eq(Const.Data.AdvertisingTokenSiteId));
            assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
            assertArrayEquals(getAdvertisingIdFromIdentity(IdentityType.Email, emailAddress, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

            RefreshToken refreshToken = encoder.decodeRefreshToken(body.getString(apiVersion.equals("v2") ? "decrypted_refresh_token" : "refresh_token"));
            assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
            assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentity(emailAddress, firstLevelSalt), refreshToken.userIdentity.id);

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshNoToken(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        sendTokenRefresh(apiVersion, vertx, "", "", 400, json -> {
            assertEquals("invalid_token", json.getString("status"));
            assertTokenStatusMetrics(
                    clientSiteId,
                    apiVersion.equals("v1") ? TokenResponseStatsCollector.Endpoint.RefreshV1 : TokenResponseStatsCollector.Endpoint.RefreshV2,
                    TokenResponseStatsCollector.ResponseStatus.InvalidToken);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshInvalidTokenAuthenticated(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);

        sendTokenRefresh(apiVersion, vertx, "abcd", "", 400, json -> {
            assertEquals("invalid_token", json.getString("status"));
            assertTokenStatusMetrics(
                    clientSiteId,
                    apiVersion.equals("v1") ? TokenResponseStatsCollector.Endpoint.RefreshV1 : TokenResponseStatsCollector.Endpoint.RefreshV2,
                    TokenResponseStatsCollector.ResponseStatus.InvalidToken);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshInvalidTokenUnauthenticated(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        sendTokenRefresh(apiVersion, vertx, "abcd", "", 400, json -> {
            assertEquals("error", json.getString("status"));
            testContext.completeNow();
        });
    }

    private void generateRefreshToken(String apiVersion, Vertx vertx, String identityType, String identity, int siteId, Handler<JsonObject> handler) {
        fakeAuth(siteId, Role.GENERATOR);
        setupSalts();
        setupKeys();
        generateTokens(apiVersion, vertx, identityType, identity, handler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void captureDurationsBetweenRefresh(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        final String emailAddress = "test@uid2.com";
        generateRefreshToken(apiVersion, vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");
            when(clock.instant()).thenAnswer(i -> Instant.now().plusSeconds(300));

            sendTokenRefresh(apiVersion, vertx, refreshToken, bodyJson.getString("refresh_response_key"), 200, refreshRespJson-> {
                assertEquals("success", refreshRespJson.getString("status"));
                assertEquals(300, Metrics.globalRegistry
                        .get("uid2.token_refresh_duration_seconds")
                        .tag("api_contact", "test-contact")
                        .tag("site_id", String.valueOf(clientSiteId))
                        .summary().mean());

                assertEquals(1, Metrics.globalRegistry
                        .get("uid2.advertising_token_expired_on_refresh")
                        .tag("site_id", String.valueOf(clientSiteId))
                        .tag("is_expired", "false")
                        .counter().count());

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void captureExpiredAdvertisingTokenStatus(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        final String emailAddress = "test@uid2.com";
        generateRefreshToken(apiVersion, vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");
            when(clock.instant()).thenAnswer(i -> Instant.now().plusSeconds(identityExpiresAfter.toSeconds() + 1));

            sendTokenRefresh(apiVersion, vertx, refreshToken, bodyJson.getString("refresh_response_key"), 200, refreshRespJson-> {
                assertEquals("success", refreshRespJson.getString("status"));

                assertEquals(1, Metrics.globalRegistry
                        .get("uid2.advertising_token_expired_on_refresh")
                        .tag("site_id", String.valueOf(clientSiteId))
                        .tag("is_expired", "true")
                        .counter().count());

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshExpiredTokenAuthenticated(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        final String emailAddress = "test@uid2.com";
        generateRefreshToken(apiVersion, vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");
            when(clock.instant()).thenAnswer(i -> Instant.now().plusMillis(refreshExpiresAfter.toMillis()).plusSeconds(60));

            sendTokenRefresh(apiVersion, vertx, refreshToken, bodyJson.getString("refresh_response_key"), 400, refreshRespJson-> {
                assertEquals("expired_token", refreshRespJson.getString("status"));
                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshExpiredTokenUnauthenticated(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";

        generateRefreshToken(apiVersion, vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            String refreshToken = genRespJson.getJsonObject("body").getString("refresh_token");
            clearAuth();
            when(clock.instant()).thenAnswer(i -> Instant.now().plusMillis(refreshExpiresAfter.toMillis()).plusSeconds(60));

            sendTokenRefresh(apiVersion, vertx, refreshToken, "", 400, refreshRespJson-> {
                assertEquals("error", refreshRespJson.getString("status"));
                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshOptOut(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        generateRefreshToken(apiVersion, vertx, "email", emailAddress, clientSiteId, genRespJson  -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");

            when(this.optOutStore.getLatestEntry(any())).thenReturn(Instant.now());

            sendTokenRefresh(apiVersion, vertx, refreshToken, bodyJson.getString("refresh_response_key"), 200, refreshRespJson -> {
                assertEquals("optout", refreshRespJson.getString("status"));
                assertTokenStatusMetrics(
                        clientSiteId,
                        apiVersion.equals("v1") ? TokenResponseStatsCollector.Endpoint.RefreshV1 : TokenResponseStatsCollector.Endpoint.RefreshV2,
                        TokenResponseStatsCollector.ResponseStatus.OptOut);
                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshOptOutBeforeLogin(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        generateRefreshToken(apiVersion, vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");
            String refreshTokenDecryptSecret = bodyJson.getString("refresh_response_key");

            when(this.optOutStore.getLatestEntry(any())).thenReturn(Instant.now().minusSeconds(10));

            sendTokenRefresh(apiVersion, vertx, refreshToken, refreshTokenDecryptSecret, 200, refreshRespJson -> {
                assertEquals("success", refreshRespJson.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @Test
    void v2HandleV1RefreshToken(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(201, Role.GENERATOR);
        final String emailAddress = "test@uid2.com";

        generateRefreshToken("v1", vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");

            sendTokenRefresh("v2", vertx, refreshToken, null, 200, refreshRespJson -> {
                assertEquals("success", refreshRespJson.getString("status"));

                JsonObject refreshBodyJson = refreshRespJson.getJsonObject("body");
                assertNotNull(refreshBodyJson.getString("refresh_response_key"));

                decodeV2RefreshToken(refreshRespJson);

                testContext.completeNow();
            });
        });
    }

    @Test
    void v1HandleV2RefreshToken(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(201, Role.GENERATOR);
        final String emailAddress = "test@uid2.com";

        generateRefreshToken("v2", vertx, "email", emailAddress, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");

            sendTokenRefresh("v1", vertx, refreshToken, null, 200, refreshRespJson -> {
                assertEquals("success", refreshRespJson.getString("status"));
                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenValidateWithEmail_Mismatch(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = UIDOperatorVerticle.ValidationInputEmail;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        send(apiVersion, vertx, apiVersion + "/token/validate", true,
            "token=abcdef&email=" + emailAddress,
            new JsonObject().put("token", "abcdef").put("email", emailAddress),
            200,
            respJson -> {
                assertFalse(respJson.getBoolean("body"));
                assertEquals("success", respJson.getString("status"));

                testContext.completeNow();
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenValidateWithEmailHash_Mismatch(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        send(apiVersion, vertx, apiVersion + "/token/validate", true,
            "token=abcdef&email_hash=" + urlEncode(EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputEmailHash)),
            new JsonObject().put("token", "abcdef").put("email_hash", EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputEmailHash)),
            200,
            respJson -> {
                assertFalse(respJson.getBoolean("body"));
                assertEquals("success", respJson.getString("status"));

                testContext.completeNow();
            });
    }

    @Test
    void identityMapBothEmailAndHashSpecified(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        final String emailHash = TokenUtils.getIdentityHashString(emailAddress);
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map?email=" + emailAddress + "&email_hash=" + urlEncode(emailHash), ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(400, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @Test
    void identityMapNoEmailOrHashSpecified(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map", ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(400, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @Test
    void identityMapForEmail(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map?email=" + emailAddress, ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(200, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);

            assertEquals(emailAddress, body.getString("identifier"));
            assertFalse(body.getString("advertising_id").isEmpty());
            assertFalse(body.getString("bucket_id").isEmpty());

            testContext.completeNow();
        });
    }

    @Test
    void identityMapForEmailHash(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailHash = TokenUtils.getIdentityHashString("test@uid2.com");
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map?email_hash=" + urlEncode(emailHash), ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(200, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);

            assertEquals(emailHash, body.getString("identifier"));
            assertFalse(body.getString("advertising_id").isEmpty());
            assertFalse(body.getString("bucket_id").isEmpty());

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchBothEmailAndHashEmpty(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        JsonArray emailHashes = new JsonArray();
        req.put("email", emails);
        req.put("email_hash", emailHashes);

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, respJson -> {
            checkIdentityMapResponse(respJson);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchBothEmailAndHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        JsonArray emailHashes = new JsonArray();
        req.put("email", emails);
        req.put("email_hash", emailHashes);

        emails.add("test1@uid2.com");
        emailHashes.add(TokenUtils.getIdentityHashString("test2@uid2.com"));

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 400, respJson -> {
            assertFalse(respJson.containsKey("body"));
            assertEquals("client_error", respJson.getString("status"));
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchNoEmailOrHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 400, json -> {
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchEmails(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        req.put("email", emails);

        emails.add("test1@uid2.com");
        emails.add("test2@uid2.com");

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json, "test1@uid2.com", "test2@uid2.com");
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchEmailHashes(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray hashes = new JsonArray();
        req.put("email_hash", hashes);
        final String[] email_hashes = {
            TokenUtils.getIdentityHashString("test1@uid2.com"),
            TokenUtils.getIdentityHashString("test2@uid2.com"),
        };

        for (String email_hash : email_hashes) {
            hashes.add(email_hash);
        }

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json, email_hashes);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchEmailsOneEmailInvalid(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        req.put("email", emails);

        emails.add("test1@uid2.com");
        emails.add("bogus");
        emails.add("test2@uid2.com");

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json, "test1@uid2.com", "test2@uid2.com");
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchEmailsNoEmails(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        req.put("email", emails);

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchRequestTooLarge(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        req.put("email", emails);

        final String email = "test@uid2.com";
        for (long requestSize = 0; requestSize < UIDOperatorVerticle.MAX_REQUEST_BODY_SIZE; requestSize += email.length()) {
            emails.add(email);
        }

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 413, json -> testContext.completeNow());
    }

    @Test
    void LogoutV2(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.OPTOUT);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        req.put("email", "test@uid2.com");

        doAnswer(invocation -> {
            Handler<AsyncResult<Instant>> handler = invocation.getArgument(2);
            handler.handle(Future.succeededFuture(Instant.now()));
            return null;
        }).when(this.optOutStore).addEntry(any(), any(), any());

        send("v2", vertx, "v2/token/logout", false, null, req, 200, respJson -> {
            assertEquals("success", respJson.getString("status"));
            assertEquals("OK", respJson.getJsonObject("body").getString("optout"));
            testContext.completeNow();
        });
    }

    @Test
    void disableOnDeauthorization(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        get(vertx, "v1/token/generate?email=test@uid2.com", ar -> {
            // Request should succeed before revoking auth
            assertEquals(200, ar.result().statusCode());

            // Revoke auth
            this.operatorDisableHandler.handleResponseStatus(401);

            // Request should fail after revoking auth
            get(vertx, "v1/token/generate?email=test@uid2.com", ar1 -> {
                assertEquals(503, ar1.result().statusCode());
                testContext.completeNow();

                // Recovered
                this.operatorDisableHandler.handleResponseStatus(200);
                get(vertx, "v1/token/generate?email=test@uid2.com", ar2 -> {
                    assertEquals(200, ar2.result().statusCode());
                    testContext.completeNow();
                });
            });
        });
    }

    @Test
    void disableOnFailure(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        // Verify success before revoking auth
        get(vertx, "v1/token/generate?email=test@uid2.com", ar -> {
            assertEquals(200, ar.result().statusCode());

            // Failure starts
            this.operatorDisableHandler.handleResponseStatus(500);

            // Can server before waiting period passes
            when(clock.instant()).thenAnswer(i -> Instant.now().plus(12, ChronoUnit.HOURS));
            this.operatorDisableHandler.handleResponseStatus(500);
            get(vertx, "v1/token/generate?email=test@uid2.com", ar1 -> {
                assertEquals(200, ar1.result().statusCode());

                // Can't serve after waiting period passes
                when(clock.instant()).thenAnswer(i -> Instant.now().plus(24, ChronoUnit.HOURS));
                this.operatorDisableHandler.handleResponseStatus(500);
                get(vertx, "v1/token/generate?email=test@uid2.com", ar2 -> {
                    assertEquals(503, ar2.result().statusCode());

                    // Recovered
                    this.operatorDisableHandler.handleResponseStatus(200);
                    get(vertx, "v1/token/generate?email=test@uid2.com", ar3 -> {
                        assertEquals(200, ar3.result().statusCode());
                        testContext.completeNow();
                    });
                });
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateBothPhoneAndHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        final String phoneHash = TokenUtils.getIdentityHashString(phone);
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "phone=" + urlEncode(phone) + "&phone_hash=" + urlEncode(phoneHash);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("phone", phone);
        v2Payload.put("phone_hash", phoneHash);

        send(apiVersion, vertx, apiVersion + "/token/generate", true, v1Param, v2Payload, 400, json -> {
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateBothPhoneAndEmailSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "phone=" + urlEncode(phone) + "&email=" + emailAddress;
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("phone", phone);
        v2Payload.put("email", emailAddress);

        send(apiVersion, vertx, apiVersion + "/token/generate", true, v1Param, v2Payload, 400, json -> {
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateBothPhoneHashAndEmailHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        final String phoneHash = TokenUtils.getIdentityHashString(phone);
        final String emailAddress = "test@uid2.com";
        final String emailHash = TokenUtils.getIdentityHashString(emailAddress);
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "phone_hash=" + urlEncode(phoneHash) + "&email_hash=" + urlEncode(emailHash);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("phone_hash", phoneHash);
        v2Payload.put("email_hash", emailHash);

        send(apiVersion, vertx, apiVersion + "/token/generate", true, v1Param, v2Payload, 400, json -> {
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateForPhone(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "phone=" + urlEncode(phone);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("phone", phone);

        sendTokenGenerate(apiVersion, vertx, v1Param, v2Payload, 200, json -> {
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);
            EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

            AdvertisingToken advertisingToken = validateAndGetToken(encoder, body, IdentityType.Phone);

            assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
            assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
            assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
            assertArrayEquals(getAdvertisingIdFromIdentity(IdentityType.Phone, phone, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

            RefreshToken refreshToken = encoder.decodeRefreshToken(body.getString(apiVersion.equals("v2") ? "decrypted_refresh_token" : "refresh_token"));
            assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
            assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentity(phone, firstLevelSalt), refreshToken.userIdentity.id);

            assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("identity_expires")), 10);
            assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_expires")), 10);
            assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_from")), 10);

            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateForPhoneHash(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        final String phoneHash = TokenUtils.getIdentityHashString(phone);
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        String v1Param = "phone_hash=" + urlEncode(phoneHash);
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("phone_hash", phoneHash);

        sendTokenGenerate(apiVersion, vertx, v1Param, v2Payload, 200, json -> {
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);
            EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

            AdvertisingToken advertisingToken = validateAndGetToken(encoder, body, IdentityType.Phone);

            assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
            assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
            assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
            assertArrayEquals(getAdvertisingIdFromIdentity(IdentityType.Phone, phone, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

            RefreshToken refreshToken = encoder.decodeRefreshToken(body.getString(apiVersion.equals("v2") ? "decrypted_refresh_token" : "refresh_token"));
            assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
            assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentity(phone, firstLevelSalt), refreshToken.userIdentity.id);

            assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("identity_expires")), 10);
            assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_expires")), 10);
            assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(body.getLong("refresh_from")), 10);

            testContext.completeNow();
        });
    }


    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenRefreshForPhone(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "phone", phone, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            assertNotNull(bodyJson);

            String genRefreshToken = bodyJson.getString("refresh_token");

            when(this.optOutStore.getLatestEntry(any())).thenReturn(null);

            sendTokenRefresh(apiVersion, vertx, genRefreshToken, bodyJson.getString("refresh_response_key"), 200, refreshRespJson ->
            {
                assertEquals("success", refreshRespJson.getString("status"));
                JsonObject refreshBody = refreshRespJson.getJsonObject("body");
                assertNotNull(refreshBody);
                EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

                AdvertisingToken advertisingToken = validateAndGetToken(encoder, refreshBody, IdentityType.Phone);

                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
                assertFalse(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
                assertEquals(clientSiteId, advertisingToken.publisherIdentity.siteId);
                assertArrayEquals(getAdvertisingIdFromIdentity(IdentityType.Phone, phone, firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);

                String refreshTokenStringNew = refreshBody.getString(apiVersion.equals("v2") ? "decrypted_refresh_token" : "refresh_token");
                assertNotEquals(genRefreshToken, refreshTokenStringNew);
                RefreshToken refreshToken = encoder.decodeRefreshToken(refreshTokenStringNew);
                assertEquals(clientSiteId, refreshToken.publisherIdentity.siteId);
                assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentity(phone, firstLevelSalt), refreshToken.userIdentity.id);

                assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("identity_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("refresh_expires")), 10);
                assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("refresh_from")), 10);

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenValidateWithPhone_Match(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = UIDOperatorVerticle.ValidationInputPhone;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "phone", phone, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject genBody = genRespJson.getJsonObject("body");
            assertNotNull(genBody);

            String advertisingTokenString = genBody.getString("advertising_token");

            String v1Param = "token=" + urlEncode(advertisingTokenString) + "&phone=" + urlEncode(phone);
            JsonObject v2Payload = new JsonObject();
            v2Payload.put("token", advertisingTokenString);
            v2Payload.put("phone", phone);

            send(apiVersion, vertx, apiVersion + "/token/validate", true, v1Param, v2Payload, 200, json -> {
                assertTrue(json.getBoolean("body"));
                assertEquals("success", json.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenValidateWithPhoneHash_Match(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = UIDOperatorVerticle.ValidationInputPhone;
        final String phoneHash = EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputPhoneHash);
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "phone", phone, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject genBody = genRespJson.getJsonObject("body");
            assertNotNull(genBody);

            String advertisingTokenString = genBody.getString("advertising_token");

            String v1Param = "token=" + urlEncode(advertisingTokenString) + "&phone_hash=" + urlEncode(phoneHash);
            JsonObject v2Payload = new JsonObject();
            v2Payload.put("token", advertisingTokenString);
            v2Payload.put("phone_hash", phoneHash);

            send(apiVersion, vertx, apiVersion + "/token/validate", true, v1Param, v2Payload, 200, json -> {
                assertTrue(json.getBoolean("body"));
                assertEquals("success", json.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenGenerateThenValidateWithBothPhoneAndPhoneHash(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = UIDOperatorVerticle.ValidationInputPhone;
        final String phoneHash = EncodingUtils.toBase64String(UIDOperatorVerticle.ValidationInputEmailHash);
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        generateTokens(apiVersion, vertx, "phone", phone, genRespJson -> {
            assertEquals("success", genRespJson.getString("status"));
            JsonObject genBody = genRespJson.getJsonObject("body");
            assertNotNull(genBody);

            String advertisingTokenString = genBody.getString("advertising_token");

            String v1Param = "token=" + urlEncode(advertisingTokenString) + "&phone=" + urlEncode(phone) + "&phone_hash=" + urlEncode(phoneHash);
            JsonObject v2Payload = new JsonObject();
            v2Payload.put("token", advertisingTokenString);
            v2Payload.put("phone", phone);
            v2Payload.put("phone_hash", phoneHash);

            send(apiVersion, vertx, apiVersion + "/token/validate", true, v1Param, v2Payload, 400, json -> {
                assertFalse(json.containsKey("body"));
                assertEquals("client_error", json.getString("status"));

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshOptOutForPhone(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        generateRefreshToken(apiVersion, vertx, "phone", phone, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");

            when(this.optOutStore.getLatestEntry(any())).thenReturn(Instant.now());

            get(vertx, "v1/token/refresh?refresh_token=" + urlEncode(refreshToken), ar -> {
                assertTrue(ar.succeeded());
                HttpResponse<Buffer> response = ar.result();
                assertEquals(200, response.statusCode());
                JsonObject json = response.bodyAsJsonObject();
                assertEquals("optout", json.getString("status"));
                assertTokenStatusMetrics(clientSiteId, TokenResponseStatsCollector.Endpoint.RefreshV1, TokenResponseStatsCollector.ResponseStatus.OptOut);

                testContext.completeNow();
            });
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void tokenRefreshOptOutBeforeLoginForPhone(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        generateRefreshToken(apiVersion, vertx, "phone", phone, clientSiteId, genRespJson -> {
            JsonObject bodyJson = genRespJson.getJsonObject("body");
            String refreshToken = bodyJson.getString("refresh_token");

            when(this.optOutStore.getLatestEntry(any())).thenReturn(Instant.now().minusSeconds(10));

            get(vertx, "v1/token/refresh?refresh_token=" + urlEncode(refreshToken), ar -> {
                assertTrue(ar.succeeded());
                HttpResponse<Buffer> response = ar.result();
                assertEquals(200, response.statusCode());
                JsonObject json = response.bodyAsJsonObject();
                assertEquals("success", json.getString("status"));

                testContext.completeNow();
            });
        });
    }


    @Test
    void identityMapBothPhoneAndHashSpecified(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        final String phoneHash = TokenUtils.getIdentityHashString(phone);
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map?phone=" + urlEncode(phone) + "&phone_hash=" + urlEncode(phoneHash), ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(400, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertFalse(json.containsKey("body"));
            assertEquals("client_error", json.getString("status"));

            testContext.completeNow();
        });
    }

    @Test
    void identityMapForPhone(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map?phone=" + urlEncode(phone), ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(200, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);

            assertEquals(phone, body.getString("identifier"));
            assertFalse(body.getString("advertising_id").isEmpty());
            assertFalse(body.getString("bucket_id").isEmpty());

            testContext.completeNow();
        });
    }

    @Test
    void identityMapForPhoneHash(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String phone = "+15555555555";
        final String phonneHash = TokenUtils.getIdentityHashString(phone);
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();
        get(vertx, "v1/identity/map?phone_hash=" + urlEncode(phonneHash), ar -> {
            assertTrue(ar.succeeded());
            HttpResponse<Buffer> response = ar.result();
            assertEquals(200, response.statusCode());
            JsonObject json = response.bodyAsJsonObject();
            assertEquals("success", json.getString("status"));
            JsonObject body = json.getJsonObject("body");
            assertNotNull(body);

            assertEquals(phonneHash, body.getString("identifier"));
            assertFalse(body.getString("advertising_id").isEmpty());
            assertFalse(body.getString("bucket_id").isEmpty());

            testContext.completeNow();
        });
    }
    @Test void sendInformationToStatsCollector(Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        final String emailAddress = "test@uid2.com";
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        vertx.eventBus().consumer(Const.Config.StatsCollectorEventBus, message -> {
            String expected = "{\"path\":\"/v1/token/generate\",\"referer\":null,\"apiContact\":null,\"siteId\":201}";
            assertSame(message.body().toString(), expected);
        });

        get(vertx, "v1/token/generate?email=" + emailAddress, ar -> {
            verify(statsCollectorQueue, times(1)).enqueue(any(), any());
            testContext.completeNow();
        });
    }



    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchBothPhoneAndHashEmpty(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray phones = new JsonArray();
        JsonArray phoneHashes = new JsonArray();
        req.put("phone", phones);
        req.put("phone_hash", phoneHashes);

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, respJson -> {
            checkIdentityMapResponse(respJson);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchBothPhoneAndHashSpecified(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray phones = new JsonArray();
        JsonArray phoneHashes = new JsonArray();
        req.put("phone", phones);
        req.put("phone_hash", phoneHashes);

        phones.add("+15555555555");
        phoneHashes.add(TokenUtils.getIdentityHashString("+15555555555"));

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 400, respJson -> {
            assertFalse(respJson.containsKey("body"));
            assertEquals("client_error", respJson.getString("status"));
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchPhones(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray phones = new JsonArray();
        req.put("phone", phones);

        phones.add("+15555555555");
        phones.add("+15555555556");

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json, "+15555555555", "+15555555556");
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchPhoneHashes(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray hashes = new JsonArray();
        req.put("phone_hash", hashes);
        final String[] email_hashes = {
                TokenUtils.getIdentityHashString("+15555555555"),
                TokenUtils.getIdentityHashString("+15555555556"),
        };

        for (String email_hash : email_hashes) {
            hashes.add(email_hash);
        }

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json, email_hashes);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchPhonesOnePhoneInvalid(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray phones = new JsonArray();
        req.put("phone", phones);

        phones.add("+15555555555");
        phones.add("bogus");
        phones.add("+15555555556");

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json, "+15555555555", "+15555555556");
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchPhonesNoPhones(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray phones = new JsonArray();
        req.put("phone", phones);

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            checkIdentityMapResponse(json);
            testContext.completeNow();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapBatchRequestTooLargeForPhone(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        JsonObject req = new JsonObject();
        JsonArray phones = new JsonArray();
        req.put("phone", phones);

        final String phone = "+15555555555";
        for (long requestSize = 0; requestSize < UIDOperatorVerticle.MAX_REQUEST_BODY_SIZE; requestSize += phone.length()) {
            phones.add(phone);
        }

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 413, json -> testContext.completeNow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v2"})
    void tokenGenerateRespectOptOutOption(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.GENERATOR);
        setupSalts();
        setupKeys();

        // the clock value shouldn't matter here
        when(optOutStore.getLatestEntry(any(UserIdentity.class)))
                .thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));

        JsonObject req = new JsonObject();
        req.put("email", "random-optout-user@email.io");
        req.put("policy", 1);

        // for EUID
        addAdditionalTokenGenerateParams(req);

        send(apiVersion, vertx, apiVersion + "/token/generate", false, null, req, 200, json -> {
            try {
                Assertions.assertEquals(UIDOperatorVerticle.ResponseStatus.OptOut, json.getString("status"));
                Assertions.assertNull(json.getJsonObject("body"));
                assertTokenStatusMetrics(clientSiteId, TokenResponseStatsCollector.Endpoint.GenerateV2, TokenResponseStatsCollector.ResponseStatus.OptOut);
                testContext.completeNow();
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapDefaultOption(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        // the clock value shouldn't matter here
        when(optOutStore.getLatestEntry(any(UserIdentity.class)))
                .thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        emails.add("random-optout-user@email.io");
        req.put("email", emails);

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            try {
                Assertions.assertTrue(json.getJsonObject("body").getJsonArray("unmapped") == null ||
                        json.getJsonObject("body").getJsonArray("unmapped").isEmpty());
                Assertions.assertEquals(1, json.getJsonObject("body").getJsonArray("mapped").size());
                Assertions.assertEquals("random-optout-user@email.io", json.getJsonObject("body").getJsonArray("mapped").getJsonObject(0).getString("identifier"));
                testContext.completeNow();
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void identityMapRespectOptOutOption(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final int clientSiteId = 201;
        fakeAuth(clientSiteId, Role.MAPPER);
        setupSalts();
        setupKeys();

        // the clock value shouldn't matter here
        when(optOutStore.getLatestEntry(any(UserIdentity.class)))
                .thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));

        JsonObject req = new JsonObject();
        JsonArray emails = new JsonArray();
        emails.add("random-optout-user@email.io");
        req.put("email", emails);
        req.put("policy", 1);

        send(apiVersion, vertx, apiVersion + "/identity/map", false, null, req, 200, json -> {
            try {
                Assertions.assertTrue(json.getJsonObject("body").getJsonArray("mapped").isEmpty());
                Assertions.assertEquals(1, json.getJsonObject("body").getJsonArray("unmapped").size());
                Assertions.assertEquals("random-optout-user@email.io", json.getJsonObject("body").getJsonArray("unmapped").getJsonObject(0).getString("identifier"));
                Assertions.assertEquals("optout", json.getJsonObject("body").getJsonArray("unmapped").getJsonObject(0).getString("reason"));
                testContext.completeNow();
            } catch (Exception e) {
                testContext.failNow(e);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void requestWithoutClientKeyOrReferer(String apiVersion, Vertx vertx, VertxTestContext testContext) {
        final String emailAddress = "test@uid2.com";
        setupSalts();
        setupKeys();

        String v1Param = "email=" + emailAddress;
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("email", emailAddress);

        sendTokenGenerate(apiVersion, vertx,
                v1Param, v2Payload, 401,
                json -> {
                    assertEquals("unauthorized", json.getString("status"));

                    assertStatsCollector("/" + apiVersion + "/token/generate", null, null, null);

                    testContext.completeNow();
                });
    }

    @Test
    void requestWithReferer(Vertx vertx, VertxTestContext testContext) {
        final String emailAddress = "test@uid2.com";
        setupSalts();
        setupKeys();

        String v1Param = "email=" + emailAddress;
        JsonObject v2Payload = new JsonObject();
        v2Payload.put("email", emailAddress);

        sendTokenGenerate("v2", vertx,
                v1Param, v2Payload, 401, "test-referer",
                json -> {
                    assertEquals("unauthorized", json.getString("status"));

                    assertStatsCollector("/v2/token/generate", "test-referer", null, null);

                    testContext.completeNow();
                });
    }

    private void postCstg(Vertx vertx, String endpoint, String httpOriginHeader, JsonObject body, Handler<AsyncResult<HttpResponse<Buffer>>> handler) {
        WebClient client = WebClient.create(vertx);
        HttpRequest<Buffer> req = client.postAbs(getUrlForEndpoint(endpoint));
        req.putHeader("origin", httpOriginHeader);
        req.sendJsonObject(body, handler);
    }

    private void sendCstg(Vertx vertx, String endpoint, String httpOriginHeader, JsonObject postPayload, SecretKey secretKey, int expectedHttpCode, Handler<JsonObject> handler) {
        postCstg(vertx, endpoint, httpOriginHeader, postPayload, ar -> {
            assertTrue(ar.succeeded());
            assertEquals(expectedHttpCode, ar.result().statusCode());

            // successful response is encrypted
            if (ar.result().statusCode() == 200) {
                byte[] decrypted = decrypt(Utils.decodeBase64String(ar.result().bodyAsString()), 0, secretKey.getEncoded());
                JsonObject respJson = new JsonObject(new String(decrypted, 0, decrypted.length - 0, StandardCharsets.UTF_8));
                handler.handle(respJson);
            } else { //errors is in plain text
                handler.handle(tryParseResponse(ar.result()));
            }
        });
    }

    private void setupCstgBackend(String... domainNames)
    {
        //must match up to whatever getPrivateKeyForClientSideTokenGenerate returns for now
        final int clientSiteId = clientSideTokenGenerateSiteId;
        final int siteKeyId = 1201;
        setupSalts();
        setupKeys();
        setupSiteKey(clientSiteId, siteKeyId);
        ClientSideKeypair keypair = new ClientSideKeypair(clientSideTokenGenerateSubscriptionId, clientSideTokenGeneratePublicKey, clientSideTokenGeneratePrivateKey, clientSideTokenGenerateSiteId, "", Instant.now(), false);
        HashMap<String, ClientSideKeypair> keypairMap = new HashMap<>(){{put(clientSideTokenGenerateSubscriptionId, keypair);}};
        HashMap<Integer, List<ClientSideKeypair>> siteKeypairMap = new HashMap<>(){{put(clientSideTokenGenerateSiteId, List.of(keypair));}};
        ClientSideKeypairStoreSnapshot snapshot = new ClientSideKeypairStoreSnapshot(keypairMap, siteKeypairMap);
        when(clientSideKeypairProvider.getSnapshot()).thenReturn(snapshot);

        when(siteProvider.getSite(eq(clientSiteId))).thenReturn(new Site(clientSiteId, "test", true, new HashSet<>(List.of(domainNames))));

    }

    //if no identity is provided will get an error
    @Test
    void cstgNoIdentityHashProvided(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");
        Tuple.Tuple2<JsonObject, SecretKey> data = createClientSideTokenGenerateRequestWithNoPayload(Instant.now().toEpochMilli());
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertFalse(respJson.containsKey("body"));
                    assertEquals("please provide exactly one of: email_hash, phone_hash", respJson.getString("message"));
                    assertEquals(UIDOperatorVerticle.ResponseStatus.ClientError, respJson.getString("status"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.MissingParams);
                    testContext.completeNow();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://blahblah.com", "http://local1host:8080"})
    void cstgDomainNameCheckFails(String httpOrigin, Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend();
        Tuple.Tuple2<JsonObject, SecretKey> data = createClientSideTokenGenerateRequest(IdentityType.Email, "random@unifiedid.com", Instant.now().toEpochMilli());
        sendCstg(vertx,
                "v2/token/client-generate",
                httpOrigin,
                data.getItem1(),
                data.getItem2(),
                403,
                respJson -> {
                    assertFalse(respJson.containsKey("body"));
                    assertEquals("unexpected http origin", respJson.getString("message"));
                    assertEquals("invalid_http_origin", respJson.getString("status"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.InvalidHttpOrigin);
                    testContext.completeNow();
                    testContext.completeNow();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://cstg.co.uk", "https://cstg2.com", "http://localhost:8080"})
    void cstgDomainNameCheckPasses(String httpOrigin, Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk", "cstg2.com", "localhost");
        Tuple.Tuple2<JsonObject, SecretKey> data = createClientSideTokenGenerateRequest(IdentityType.Email, "random@unifiedid.com", Instant.now().toEpochMilli());
        sendCstg(vertx,
                "v2/token/client-generate",
                httpOrigin,
                data.getItem1(),
                data.getItem2(),
                200,
                respJson -> {
                    assertTrue(respJson.containsKey("body"));
                    assertEquals("success", respJson.getString("status"));
                    testContext.completeNow();
                });
    }

    @Test
    void cstgNoBody(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        postCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                null,
                ar -> {
                    JsonObject response = ar.result().bodyAsJsonObject();
                    assertEquals("client_error", response.getString("status"));
                    assertEquals("json payload expected but not found", response.getString("message"));
                    assertTokenStatusMetrics(
                            0,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.MissingParams);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgBadTimestamp(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().minus(V2_REQUEST_TIMESTAMP_DRIFT_THRESHOLD_IN_MINUTES, ChronoUnit.MINUTES).toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("error", respJson.getString("status"));
                    assertEquals("invalid timestamp: request too old or client time drift", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            0,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadTimestamp);
                    testContext.completeNow();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload", "iv", "subscription_id", "public_key"})
    void cstgMissingRequiredField(String testField, Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        requestJson.remove(testField);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("required parameters: payload, iv, subscription_id, public_key", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            0,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.MissingParams);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgBadPublicKey(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend();

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "bad-key");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", "4WvryDGbR5");

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("bad public key", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            0,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadPublicKey);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgBadSubscriptionId(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", "bad");

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                401,
                respJson -> {
                    assertEquals("unauthorized", respJson.getString("status"));
                    assertEquals("bad subscription_id", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            0,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadSubscriptionId);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgBadIvNotBase64(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", "............");
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("bad iv", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadIV);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgBadIvIncorrectLength(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", "aa");
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("bad iv", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadIV);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgBadEncryptedPayload(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        IdentityType identityType = IdentityType.Email;
        String rawId = "random@unifiedid.com";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", "not-encrypted");
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("payload decryption failed", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadPayload);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgInvalidEncryptedPayloadJson(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt("not-json".getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("encrypted payload contains invalid json", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadPayload);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgPhoneAndEmailProvided(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("email_hash", getSha256("random@unifiedid.com"));
        identityPayload.put("phone_hash", getSha256("+10001110000"));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("please provide exactly one of: email_hash, phone_hash", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadPayload);
                    testContext.completeNow();
                });
    }

    @Test
    void cstgNoPhoneSupport(Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");

        String rawId = "+10001110000";

        JsonObject identityPayload = new JsonObject();
        identityPayload.put("phone_hash", getSha256(rawId));

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final long timestamp = Instant.now().toEpochMilli();
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        Tuple.Tuple2<JsonObject, SecretKey> data = new Tuple.Tuple2<>(requestJson, secretKey);
        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                400,
                respJson -> {
                    assertEquals("client_error", respJson.getString("status"));
                    assertEquals("phone support not enabled", respJson.getString("message"));
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.BadPayload);
                    testContext.completeNow();
                });
    }

    private Tuple.Tuple2<JsonObject, SecretKey> createClientSideTokenGenerateRequestWithPayload(JsonObject identityPayload, long timestamp) throws NoSuchAlgorithmException, InvalidKeyException {

        final KeyFactory kf = KeyFactory.getInstance("EC");
        final PublicKey serverPublicKey = ClientSideTokenGenerateTestUtil.stringToPublicKey(clientSideTokenGeneratePublicKey, kf);
        final PrivateKey clientPrivateKey = ClientSideTokenGenerateTestUtil.stringToPrivateKey("MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCDsqxZicsGytVqN2HZqNDHtV422Lxio8m1vlflq4Jb47Q==", kf);
        final SecretKey secretKey = ClientSideTokenGenerateTestUtil.deriveKey(serverPublicKey, clientPrivateKey);

        final byte[] iv = Random.getBytes(12);
        final byte[] aad = new JsonArray(List.of(timestamp)).toBuffer().getBytes();
        byte[] payloadBytes = ClientSideTokenGenerateTestUtil.encrypt(identityPayload.toString().getBytes(), secretKey.getEncoded(), iv, aad);
        final String payload = EncodingUtils.toBase64String(payloadBytes);

        JsonObject requestJson = new JsonObject();
        requestJson.put("payload", payload);
        requestJson.put("iv", EncodingUtils.toBase64String(iv));
        requestJson.put("public_key", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE92+xlW2eIrXsDzV4cSfldDKxLXHsMmjLIqpdwOqJ29pWTNnZMaY2ycZHFpxbp6UlQ6vVSpKwImTKr3uikm9yCw==");
        requestJson.put("timestamp", timestamp);
        requestJson.put("subscription_id", clientSideTokenGenerateSubscriptionId);

        return new Tuple.Tuple2<>(requestJson, secretKey);
    }

    private Tuple.Tuple2<JsonObject, SecretKey> createClientSideTokenGenerateRequest(IdentityType identityType, String rawId, long timestamp) throws NoSuchAlgorithmException, InvalidKeyException {

        JsonObject identity = new JsonObject();

        if(identityType == IdentityType.Email) {
            identity.put("email_hash", getSha256(rawId));
        }
        else if(identityType == IdentityType.Phone) {
            identity.put("phone_hash", getSha256(rawId));
        }
        else { //can't be other types
            assertFalse(true);
        }
        return createClientSideTokenGenerateRequestWithPayload(identity, timestamp);
    }

    private Tuple.Tuple2<JsonObject, SecretKey> createClientSideTokenGenerateRequestWithNoPayload(long timestamp) throws NoSuchAlgorithmException, InvalidKeyException {
        JsonObject identity = new JsonObject();
        return createClientSideTokenGenerateRequestWithPayload(identity, timestamp);
    }


    // tests for opted out user should lead to generating ad tokens with the default optout identity
    // tests for opted in user should lead to generating ad tokens that never match the default optout identity
    // tests for all email/phone combos
    @ParameterizedTest
    @CsvSource({"true,abc@abc.com,Email,optout@unifiedid.com",
            "true,+61400000000,Phone,+00000000001",
            "false,abc@abc.com,Email,optout@unifiedid.com",
            "false,+61400000000,Phone,+00000000001"})
    void cstgOptedOutTest(boolean optOutExpected, String id, IdentityType identityType, String expectedOptedOutIdentity,
                      Vertx vertx, VertxTestContext testContext) throws NoSuchAlgorithmException, InvalidKeyException {
        setupCstgBackend("cstg.co.uk");
        Tuple.Tuple2<JsonObject, SecretKey> data = createClientSideTokenGenerateRequest(identityType, id, Instant.now().toEpochMilli());
        if(optOutExpected)
        {
            when(optOutStore.getLatestEntry(any(UserIdentity.class)))
                    .thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));
        }
        else { //not expectedOptedOut
            when(optOutStore.getLatestEntry(any(UserIdentity.class)))
                    .thenReturn(null);
        }

        sendCstg(vertx,
                "v2/token/client-generate",
                "https://cstg.co.uk",
                data.getItem1(),
                data.getItem2(),
                200,
                respJson -> {
                    JsonObject genBody = respJson.getJsonObject("body");
                    assertNotNull(genBody);

                    decodeV2RefreshToken(respJson);
                    EncryptedTokenEncoder encoder = new EncryptedTokenEncoder(keyStore);

                    AdvertisingToken advertisingToken = validateAndGetToken(encoder, genBody, identityType);
                    assertEquals(clientSideTokenGenerateSiteId, advertisingToken.publisherIdentity.siteId);

                    assertTrue(PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenGenerated());
                    assertEquals(optOutExpected, PrivacyBits.fromInt(advertisingToken.userIdentity.privacyBits).isClientSideTokenOptedOut());

                    if(identityType == IdentityType.Email) {
                        assertArrayEquals(getAdvertisingIdFromIdentityHash(IdentityType.Email,
                                TokenUtils.getIdentityHashString(optOutExpected? expectedOptedOutIdentity : id),
                                firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);
                    }
                    else if(identityType == IdentityType.Phone) {
                        assertArrayEquals(getAdvertisingIdFromIdentityHash(IdentityType.Phone,
                                TokenUtils.getIdentityHashString(optOutExpected? expectedOptedOutIdentity : id),
                                firstLevelSalt, rotatingSalt123.getSalt()), advertisingToken.userIdentity.id);
                    }
                    else { //should never happen
                        assertFalse(true);
                    }

                    RefreshToken refreshToken = encoder.decodeRefreshToken(genBody.getString("decrypted_refresh_token"));
                    assertEquals(clientSideTokenGenerateSiteId, refreshToken.publisherIdentity.siteId);
                    assertTrue(PrivacyBits.fromInt(refreshToken.userIdentity.privacyBits).isClientSideTokenGenerated());

                    if(optOutExpected) {
                        assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentityHash(getSha256(expectedOptedOutIdentity), firstLevelSalt), refreshToken.userIdentity.id);
                        assertTrue(PrivacyBits.fromInt(refreshToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
                    }
                    else {
                        assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentityHash(getSha256(id), firstLevelSalt), refreshToken.userIdentity.id);
                        assertFalse(PrivacyBits.fromInt(refreshToken.userIdentity.privacyBits).isClientSideTokenOptedOut());
                    }

                    assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(genBody.getLong("identity_expires")), 10);
                    assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(genBody.getLong("refresh_expires")), 10);
                    assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(genBody.getLong("refresh_from")), 10);


                    String advertisingTokenString = genBody.getString("advertising_token");

                    InputUtil.InputVal input;
                    if(identityType == IdentityType.Email) {
                        input = InputUtil.InputVal.validEmail(expectedOptedOutIdentity, expectedOptedOutIdentity);
                    }
                    else if(identityType == IdentityType.Phone) {
                        input = InputUtil.InputVal.validPhone(expectedOptedOutIdentity, expectedOptedOutIdentity);
                    }
                    else { //should never happen
                        input = null;
                        assertFalse(true);
                    }

                    final Instant now = Instant.now();
                    final String token = advertisingTokenString;
                    final boolean matchedOptedOutIdentity = this.uidOperatorVerticle.getIdService().advertisingTokenMatches(token, input.toUserIdentity(getIdentityScope(), 0, now), now);

                    assertEquals(optOutExpected, matchedOptedOutIdentity);
                    assertTokenStatusMetrics(
                            clientSideTokenGenerateSiteId,
                            TokenResponseStatsCollector.Endpoint.ClientSideTokenGenerateV2,
                            TokenResponseStatsCollector.ResponseStatus.Success);

                    String genRefreshToken = genBody.getString("refresh_token");
                    //test a subsequent refresh from this cstg call and see if it still works
                    sendTokenRefresh("v2", vertx, genRefreshToken, genBody.getString("refresh_response_key"), 200, refreshRespJson ->
                    {
                        assertEquals("success", refreshRespJson.getString("status"));
                        JsonObject refreshBody = refreshRespJson.getJsonObject("body");
                        assertNotNull(refreshBody);
                        EncryptedTokenEncoder encoder2 = new EncryptedTokenEncoder(keyStore);

                        //make sure the new advertising token from refresh looks right
                        AdvertisingToken adTokenFromRefresh = validateAndGetToken(encoder2, refreshBody, identityType);
                        assertEquals(clientSideTokenGenerateSiteId, adTokenFromRefresh.publisherIdentity.siteId);

                        String refreshTokenStringNew = refreshBody.getString("decrypted_refresh_token");
                        assertNotEquals(genRefreshToken, refreshTokenStringNew);
                        RefreshToken refreshTokenAfterRefresh = encoder.decodeRefreshToken(refreshTokenStringNew);
                        assertEquals(clientSideTokenGenerateSiteId, refreshTokenAfterRefresh.publisherIdentity.siteId);

                        if(optOutExpected) {
                            assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentityHash(getSha256(expectedOptedOutIdentity), firstLevelSalt), refreshTokenAfterRefresh.userIdentity.id);
                        }
                        else {
                            assertArrayEquals(TokenUtils.getFirstLevelHashFromIdentityHash(getSha256(id), firstLevelSalt), refreshTokenAfterRefresh.userIdentity.id);
                        }

                        if(identityType == IdentityType.Email) {
                            assertArrayEquals(getAdvertisingIdFromIdentityHash(IdentityType.Email,
                                    TokenUtils.getIdentityHashString(optOutExpected? expectedOptedOutIdentity : id),
                                    firstLevelSalt, rotatingSalt123.getSalt()), adTokenFromRefresh.userIdentity.id);
                        }
                        else if(identityType == IdentityType.Phone) {
                            assertArrayEquals(getAdvertisingIdFromIdentityHash(IdentityType.Phone,
                                    TokenUtils.getIdentityHashString(optOutExpected? expectedOptedOutIdentity : id),
                                    firstLevelSalt, rotatingSalt123.getSalt()), adTokenFromRefresh.userIdentity.id);

                        }
                        else { //should never happen
                            assertFalse(true);
                        }

                        assertEqualsClose(Instant.now().plusMillis(identityExpiresAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("identity_expires")), 10);
                        assertEqualsClose(Instant.now().plusMillis(refreshExpiresAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("refresh_expires")), 10);
                        assertEqualsClose(Instant.now().plusMillis(refreshIdentityAfter.toMillis()), Instant.ofEpochMilli(refreshBody.getLong("refresh_from")), 10);


                        assertTokenStatusMetrics(
                                clientSideTokenGenerateSiteId,
                                TokenResponseStatsCollector.Endpoint.RefreshV2,
                                TokenResponseStatsCollector.ResponseStatus.Success);


                        //check the CSTG-related privacy bits are still set correctly
                        assertTrue(PrivacyBits.fromInt(adTokenFromRefresh.userIdentity.privacyBits).isClientSideTokenGenerated());
                        assertTrue(PrivacyBits.fromInt(refreshTokenAfterRefresh.userIdentity.privacyBits).isClientSideTokenGenerated());
                        assertEquals(optOutExpected, PrivacyBits.fromInt(adTokenFromRefresh.userIdentity.privacyBits).isClientSideTokenOptedOut());
                        assertEquals(optOutExpected, PrivacyBits.fromInt(refreshTokenAfterRefresh.userIdentity.privacyBits).isClientSideTokenOptedOut());

                        testContext.completeNow();
                    });
                });
    }



}

