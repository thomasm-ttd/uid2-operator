package com.uid2.operator.vertx;

import com.uid2.operator.model.IdentityScope;
import com.uid2.operator.model.KeyManager;
import com.uid2.operator.monitoring.TokenResponseStatsCollector;
import com.uid2.operator.service.EncodingUtils;
import com.uid2.operator.service.ResponseUtil;
import com.uid2.operator.service.V2RequestUtil;
import com.uid2.shared.Utils;
import com.uid2.shared.auth.ClientKey;
import com.uid2.shared.encryption.AesGcm;
import com.uid2.shared.middleware.AuthMiddleware;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.function.Function;

public class V2PayloadHandler {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(V2PayloadHandler.class);

    private KeyManager keyManager;

    private Boolean enableEncryption;

    private IdentityScope identityScope;

    private TokenResponseStatsCollector tokenResponseStatsCollector;

    public V2PayloadHandler(KeyManager keyManager, Boolean enableEncryption, IdentityScope identityScope) {
        this.keyManager = keyManager;
        this.enableEncryption = enableEncryption;
        this.identityScope = identityScope;
    }

    public void handle(RoutingContext rc, Handler<RoutingContext> apiHandler) {
        if (!enableEncryption) {
            passThrough(rc, apiHandler);
            return;
        }

        V2RequestUtil.V2Request request = V2RequestUtil.parseRequest(rc.body().asString(), AuthMiddleware.getAuthClient(ClientKey.class, rc));
        if (!request.isValid()) {
            ResponseUtil.ClientError(rc, request.errorMessage);
            return;
        }
        rc.data().put("request", request.payload);

        apiHandler.handle(rc);

        handleResponse(rc, request);
    }

    public void handleAsync(RoutingContext rc, Function<RoutingContext, Future> apiHandler) {
        if (!enableEncryption) {
            apiHandler.apply(rc);
            return;
        }

        V2RequestUtil.V2Request request = V2RequestUtil.parseRequest(rc.body().asString(), AuthMiddleware.getAuthClient(ClientKey.class, rc));
        if (!request.isValid()) {
            ResponseUtil.ClientError(rc, request.errorMessage);
            return;
        }
        rc.data().put("request", request.payload);

        apiHandler.apply(rc).onComplete(ar -> {
            handleResponse(rc, request);
        });
    }

    public void handleTokenGenerate(RoutingContext rc, Handler<RoutingContext> apiHandler) {
        if (!enableEncryption) {
            passThrough(rc, apiHandler);
            return;
        }

        V2RequestUtil.V2Request request = V2RequestUtil.parseRequest(rc.body().asString(), AuthMiddleware.getAuthClient(ClientKey.class, rc));
        if (!request.isValid()) {
            ResponseUtil.ClientError(rc, request.errorMessage);
            // TODO
            return;
        }
        rc.data().put("request", request.payload);

        apiHandler.handle(rc);

        if (rc.response().getStatusCode() != 200) {
            return;
        }

        try {
            JsonObject respJson = (JsonObject) rc.data().get("response");

            // DevNote: 200 does not guarantee a token.
            if (respJson.getString("status").equals(UIDOperatorVerticle.ResponseStatus.Success) && respJson.containsKey("body")) {
                V2RequestUtil.handleRefreshTokenInResponseBody(respJson.getJsonObject("body"), this.keyManager, this.identityScope);
            }

            writeResponse(rc, request.nonce, respJson, request.encryptionKey);
        }
        catch (Exception ex){
            LOGGER.error("Failed to generate token", ex);
            ResponseUtil.Error(UIDOperatorVerticle.ResponseStatus.GenericError, 500, rc, "");
        }
    }

    public void handleTokenRefresh(RoutingContext rc, Handler<RoutingContext> apiHandler) {
        if (!enableEncryption) {
            passThrough(rc, apiHandler);
            return;
        }

        String bodyString = rc.body().asString();

        V2RequestUtil.V2Request request = null;
        if (bodyString != null && bodyString.length() == V2RequestUtil.V2_REFRESH_PAYLOAD_LENGTH) {
            request = V2RequestUtil.parseRefreshRequest(bodyString, this.keyManager);
            if (!request.isValid()) {
                // TODO
//                tokenResponseStatsCollector.SendErrorResponseAndRecordStats(UIDOperatorVerticle.ResponseStatus.ClientError, 400, rc, "Required Parameter Missing: exactly one of email or email_hash must be specified", siteId, TokenResponseStatsCollector.Endpoint.GenerateV0, TokenResponseStatsCollector.ResponseStatus.BadPayload);
                ResponseUtil.ClientError(rc, request.errorMessage);
                return;
            }
            rc.data().put("request", request.payload);
        }
        else {
            rc.data().put("request", bodyString);
        }

        apiHandler.handle(rc);

        if (rc.response().getStatusCode() != 200) {
            return;
        }

        try {
            JsonObject respJson = (JsonObject) rc.data().get("response");

            JsonObject bodyJson = respJson.getJsonObject("body");
            if (bodyJson != null)
                V2RequestUtil.handleRefreshTokenInResponseBody(bodyJson, this.keyManager, this.identityScope);

            if (request != null) {
                rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
                // Encrypt whole payload using key shared with client.
                byte[] encryptedResp = AesGcm.encrypt(
                    respJson.encode().getBytes(StandardCharsets.UTF_8),
                    request.encryptionKey);
                rc.response().end(Utils.toBase64String(encryptedResp));
            }
            else {
                rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(respJson.encode());
            }
        }
        catch (Exception ex){
            LOGGER.error("Failed to refresh token", ex);
            ResponseUtil.Error(UIDOperatorVerticle.ResponseStatus.GenericError, 500, rc, "");
        }
    }

//    public void SendErrorResponseAndRecordStats(String errorStatus, int statusCode, RoutingContext rc, String message, Integer siteId, TokenResponseStatsCollector.Endpoint endpoint, TokenResponseStatsCollector.ResponseStatus responseStatus)
//    {
//        if (statusCode == 400) {
//            ResponseUtil.Warning(errorStatus, statusCode, rc, message);
//        } else if (statusCode == 500) {
//            ResponseUtil.Error(errorStatus, statusCode, rc, message);
//            rc.fail(500);
//        }
//        recordTokenResponseStats(siteId, endpoint, responseStatus);
//    }
//
//    private void recordTokenResponseStats(Integer siteId, TokenResponseStatsCollector.Endpoint endpoint, TokenResponseStatsCollector.ResponseStatus responseStatus) {
//        TokenResponseStatsCollector.record(siteProvider, siteId, endpoint, responseStatus);
//    }

    private void passThrough(RoutingContext rc, Handler<RoutingContext> apiHandler) {
        rc.data().put("request", rc.body().asJsonObject());
        apiHandler.handle(rc);
        if (rc.response().getStatusCode() != 200) {
            return;
        }
        JsonObject respJson = (JsonObject) rc.data().get("response");
        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .end(respJson.encode());
    }

    private void writeResponse(RoutingContext rc, byte[] nonce, JsonObject resp, byte[] keyBytes) {
        Buffer buffer = Buffer.buffer();
        buffer.appendLong(EncodingUtils.NowUTCMillis().toEpochMilli());
        buffer.appendBytes(nonce);
        buffer.appendBytes(resp.encode().getBytes(StandardCharsets.UTF_8));

        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "text/plain");
        rc.response().end(Utils.toBase64String(AesGcm.encrypt(buffer.getBytes(), keyBytes)));
    }

    private void handleResponse(RoutingContext rc, V2RequestUtil.V2Request request) {
        if (rc.response().getStatusCode() != 200) {
            return;
        }

        try {
            JsonObject respJson = (JsonObject) rc.data().get("response");

            writeResponse(rc, request.nonce, respJson, request.encryptionKey);
        } catch (Exception ex) {
            LOGGER.error("Failed to generate response", ex);
            ResponseUtil.Error(UIDOperatorVerticle.ResponseStatus.GenericError, 500, rc, "");
        }
    }
}

