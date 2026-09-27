package app.umbra.transport;

import app.umbra.core.Bytes;
import app.umbra.protocol.Wire;
import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;

/** Untrusted HTTPS courier; cancellation on lock and no redirects or plaintext fallback. */
public final class RelayClient implements AutoCloseable {
    private final String base;
    private final app.umbra.connectivity.ConnectivityService.Lease network;
    private final BooleanSupplier permitted;
    private final app.umbra.admission.AdmissionService admission;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.atomic.AtomicReference<HttpsURLConnection> active=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.ArrayDeque<app.umbra.admission.AdmissionChallenge> challenges=new java.util.ArrayDeque<>();
    public RelayClient(String address) throws Exception { this(address, () -> true); }
    public RelayClient(String address, BooleanSupplier permitted) throws Exception { this(address,permitted,null); }
    public RelayClient(String address, BooleanSupplier permitted, app.umbra.admission.AdmissionService admission) throws Exception {
        base=validate(address); this.permitted=permitted; this.admission=admission;
        network=admission==null?null:admission.connectivity().networkLease(base);
        if(network!=null) network.attach(this::close);
    }
    public static String validate(String address) throws Exception {
        URI uri = new URI(address.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
            uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Introduce solo https://dominio, sin usuario ni ruta");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("Puerto inválido");
        return uri.toString().replaceAll("/+$", "");
    }
    private void allowed() throws IOException {
        if(network==null) throw new IOException("Explicit connectivity consent required");
        network.checkEpoch();
        if (closed.get() || !permitted.getAsBoolean()) throw new IOException("Conexión cancelada por la política local");
    }
    private static final class HttpFailure extends IOException {
        private static final long serialVersionUID=1L;
        final boolean freshChallenge;
        HttpFailure(int status,boolean freshChallenge) {
            super("Servidor rechazó la operación (HTTP "+status+")"); this.freshChallenge=freshChallenge;
        }
    }
    @FunctionalInterface interface Authorization { void check() throws Exception; }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }
    /** A fresh verifier timestamp may precede the next local wall-clock second. Wait boundedly;
     * never accept a future/expired proof, extend its TTL, hold a DB transaction or acquire a new lease. */
    static void awaitChallengeStart(long issued,java.util.function.LongSupplier wall,
            java.util.function.LongSupplier monotonic,Authorization authorization,Pause pause) throws Exception {
        long began=monotonic.getAsLong();
        while(wall.getAsLong()<issued) {
            authorization.check();
            if(issued-wall.getAsLong()>2 || monotonic.getAsLong()-began>=2_500_000_000L)
                throw new SecurityException("Admission challenge clock mismatch");
            pause.sleep(50);
        }
        authorization.check();
    }
    private JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
        return request(method, path, token, body, () -> {});
    }
    private JSONObject request(String method, String path, String token, JSONObject body, Authorization authorization) throws Exception {
        return admittedRequest(method,path,token,body,authorization,false);
    }
    private JSONObject admittedRequest(String method,String path,String token,JSONObject body,Authorization authorization,boolean renewed) throws Exception {
        allowed(); authorization.check();
        if(admission==null) throw new SecurityException("Admission provisioning required");
        var lease=admission.authorization();
        var credential=admission.requireAdmission();
        JSONObject frozen=body==null?null:new JSONObject(body.toString());
        byte[] raw=frozen==null?new byte[0]:Bytes.utf8(frozen.toString());
        String operation;
        try {
            operation=Bytes.sha256(Bytes.utf8("UMBRA-ADMISSION-HTTP-1\n"+method+"\n"+
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(Bytes.utf8(path))+"\n"+
                Bytes.sha256(raw)+"\n"+Bytes.sha256(Bytes.utf8(token==null?"":"Bearer "+token))+"\n"));
        } finally { java.util.Arrays.fill(raw,(byte)0); }
        Authorization check=() -> { lease.run(); authorization.check(); };
        app.umbra.admission.AdmissionChallenge challenge;
        synchronized(challenges) {
            challenges.removeIf(c -> c.expiresAt()<=Bytes.now() || !c.credentialId().equals(credential.credentialId()));
            if(challenges.isEmpty()) {
                JSONObject response=requestRaw("POST","/v1/admission/challenge-batch",null,
                    new JSONObject().put("wire",credential.wire()),check,java.util.Map.of());
                Wire.fields(response,"challenges");
                var batch=response.getJSONArray("challenges");
                if(batch.length()!=8) throw new SecurityException("Invalid admission challenge batch");
                java.util.HashSet<String> nonces=new java.util.HashSet<>();
                for(int i=0;i<batch.length();i++) {
                    var candidate=app.umbra.admission.AdmissionChallenge.decode(batch.getString(i));
                    if(!candidate.credentialId().equals(credential.credentialId()) || !nonces.add(candidate.nonce()))
                        throw new SecurityException("Invalid admission challenge batch");
                    challenges.add(candidate);
                }
            }
            // Retire before attempting the request, including on failure. Never replay a possession proof.
            challenge=challenges.removeFirst().forOperation(operation);
        }
        try {
            awaitChallengeStart(challenge.issuedAt(),Bytes::now,System::nanoTime,() -> { allowed(); check.check(); },Thread::sleep);
            String proof=admission.prove(challenge,Bytes.sha256(Bytes.utf8(base)),operation);
            return requestRaw(method,path,token,frozen,check,java.util.Map.of("X-Umbra-Credential",credential.wire(),
                "X-Umbra-Challenge",challenge.encode(),"X-Umbra-Proof",proof));
        } catch(app.umbra.admission.AdmissionChallenge.Expired expired) {
            if(renewed) throw expired;
            synchronized(challenges) { challenges.clear(); }
            return admittedRequest(method,path,token,frozen,check,true);
        } catch(HttpFailure failure) {
            if(!failure.freshChallenge || renewed) throw failure;
            synchronized(challenges) { challenges.clear(); }
            // One bounded fresh proof after verifier restart/expiry, with the original lease
            // and frozen ciphertext. Business/capability failures do not trigger this path.
            return admittedRequest(method,path,token,frozen,check,true);
        }
    }
    /** Public configuration retrieval never installs or changes the pinned authority. */
    public String publicRealm() throws Exception {
        JSONObject response=requestRaw("GET","/v1/admission/realm",null,null,() -> {},java.util.Map.of());
        Wire.fields(response,"realm");
        return app.umbra.admission.RealmConfig.decode(Wire.string(response,"realm",256)).encode();
    }
    public JSONObject submitAdmissionRequest(String wire) throws Exception {
        return requestRaw("POST","/v1/admission/requests",null,new JSONObject().put("wire",wire),() -> {},java.util.Map.of());
    }
    public JSONObject publishAdmissionCredential(String wire) throws Exception {
        return requestRaw("POST","/v1/admission/credentials",null,new JSONObject().put("wire",wire),() -> {},java.util.Map.of());
    }
    public JSONObject publishAdmissionRenewal(app.umbra.admission.AdmissionService.Renewal renewal) throws Exception {
        return requestRaw("POST","/v1/admission/renewals",null,new JSONObject().put("credential",renewal.credential().wire())
            .put("revocation",renewal.revocation().wire()),() -> {},java.util.Map.of());
    }
    public JSONObject publishAdmissionRejection(String wire) throws Exception {
        return requestRaw("POST","/v1/admission/rejections",null,new JSONObject().put("wire",wire),() -> {},java.util.Map.of());
    }
    public JSONObject admissionResult() throws Exception {
        if(admission==null) throw new SecurityException("Admission provisioning required");
        var requestLease=admission.requestAuthorization();
        var pending=admission.pendingRequest();
        JSONObject challengeResponse=requestRaw("POST","/v1/admission/result-challenge",null,
            new JSONObject().put("wire",pending.wire()),() -> requestLease.run(),java.util.Map.of());
        Wire.fields(challengeResponse,"challenge");
        var challenge=app.umbra.admission.AdmissionChallenge.decode(Wire.string(challengeResponse,"challenge",2048));
        String proof=admission.proveRequestResult(challenge,Bytes.sha256(Bytes.utf8(base)));
        // A current private-key operation is rechecked before the result request; the response
        // contains only public signed decisions and cannot unlock or auto-admit the device.
        return requestRaw("POST","/v1/admission/result",null,new JSONObject().put("wire",pending.wire())
            .put("challenge",challenge.encode()).put("proof",proof),
            () -> { requestLease.run(); admission.proveRequestResult(challenge,Bytes.sha256(Bytes.utf8(base))); },java.util.Map.of());
    }
    public JSONObject publishAdmissionRevocation(String wire) throws Exception {
        return requestRaw("POST","/v1/admission/revocations",null,new JSONObject().put("wire",wire),() -> {},java.util.Map.of());
    }
    private JSONObject requestRaw(String method,String path,String token,JSONObject body,Authorization authorization,
                                  java.util.Map<String,String> admissionHeaders) throws Exception {
        allowed(); network.check(); authorization.check();
        HttpsURLConnection connection = (HttpsURLConnection) new URI(base + path).toURL().openConnection();
        if(!active.compareAndSet(null,connection)) { connection.disconnect(); throw new IOException("Relay client already in use"); }
        ScheduledFuture<?> deadline = null;
        try {
            allowed();
            deadline = timer.schedule(connection::disconnect, 20, TimeUnit.SECONDS);
            for(var header:admissionHeaders.entrySet()) connection.setRequestProperty(header.getKey(),header.getValue());
            connection.setRequestMethod(method); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000); connection.setReadTimeout(10_000);
            connection.setUseCaches(false); connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (token != null) {
                if (!token.matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("Invalid capability");
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] bytes = Bytes.utf8(body.toString());
                try {
                    if (bytes.length > 1_000_000) throw new IOException("Solicitud demasiado grande");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length); allowed();
                    try (OutputStream output = connection.getOutputStream()) { allowed(); authorization.check(); output.write(bytes); }
                } finally { java.util.Arrays.fill(bytes, (byte) 0); }
            }
            allowed(); authorization.check(); int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpFailure(status,status==403 && "fresh-challenge".equals(connection.getHeaderField("X-Umbra-Admission-Retry")));
            if (status == 204) { network.check(); authorization.check(); return new JSONObject(); }
            String type = connection.getContentType(), encoding = connection.getContentEncoding();
            if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json") ||
                (encoding != null && !encoding.equalsIgnoreCase("identity"))) throw new IOException("Formato de respuesta no permitido");
            if (connection.getContentLengthLong() > 5_100_000) throw new IOException("Respuesta demasiado grande");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192]; int length;
                while ((length = input.read(chunk)) != -1) {
                    allowed(); if (output.size() + length > 5_100_000) throw new IOException("Respuesta demasiado grande");
                    output.write(chunk, 0, length);
                }
                allowed(); network.check(); authorization.check(); return Wire.parse(output.toByteArray(), 5_100_000);
            }
        } catch(IOException failure) {
            if(!(failure instanceof HttpFailure)) network.failed();
            throw failure;
        } finally {
            if (deadline != null) deadline.cancel(false);
            connection.disconnect(); active.compareAndSet(connection,null);
        }
    }
    public void register(JSONObject profile, String invitation) throws Exception {
        request("POST", "/v1/boxes", null, new JSONObject().put("id", profile.getString("box"))
            .put("read_token", profile.getString("read")).put("write_token", profile.getString("write")).put("invitation", invitation.trim()));
    }
    public void registerPairing(JSONObject profile, JSONObject registration) throws Exception {
        JSONObject response = request("POST", "/v1/boxes/" + Wire.uuid(profile.getString("box")) + "/pairing-invites",
            profile.getString("read"), registration);
        Wire.fields(response, "registered");
        if (!Boolean.TRUE.equals(response.get("registered"))) throw new SecurityException("Invalid pairing response");
    }
    public void claimPairing(String invite, String requestPayload) throws Exception {
        String id = app.umbra.pairing.PairingService.invitationId(invite);
        JSONObject response = request("POST", "/v1/pairing-invites/" + id + "/claim",
            app.umbra.pairing.PairingService.consumeToken(invite),
            new JSONObject().put("request_hash", Bytes.sha256(Bytes.utf8(requestPayload))));
        Wire.fields(response, "claimed");
        if (!Boolean.TRUE.equals(response.get("claimed"))) throw new SecurityException("Invalid pairing response");
    }
    public void revokePairing(String id, String capability) throws Exception {
        request("DELETE", "/v1/pairing-invites/" + app.umbra.pairing.PairingService.token(id), capability, null);
    }
    public void delegateDeviceRevocation(JSONObject profile, String capability) throws Exception {
        JSONObject result = request("PUT", "/v1/boxes/" + Wire.uuid(profile.getString("box")) + "/revocation", profile.getString("read"),
            new JSONObject().put("token", app.umbra.pairing.PairingService.token(capability)));
        Wire.fields(result, "delegated");
        if (!Boolean.TRUE.equals(result.get("delegated"))) throw new SecurityException("Invalid device delegation response");
    }
    public void revokeDevice(String box, String capability) throws Exception {
        request("DELETE", "/v1/boxes/" + Wire.uuid(box) + "/revocation", capability, null);
    }
    public void sendAuthorized(app.umbra.crypto.Engine engine, JSONObject card, JSONObject envelope) throws Exception {
        var authorization = engine.deliveryAuthorization(envelope);
        request("PUT", "/v1/boxes/" + Wire.uuid(card.getString("box")) + "/messages/" + Wire.uuid(envelope.getString("id")),
            card.getString("write"), envelope, () -> authorization.run());
    }
    public void send(JSONObject card, JSONObject envelope) throws Exception {
        request("PUT", "/v1/boxes/" + Wire.uuid(card.getString("box")) + "/messages/" + Wire.uuid(envelope.getString("id")), card.getString("write"), envelope);
    }
    public JSONObject poll(JSONObject profile, long after) throws Exception {
        if (after < 0) throw new IllegalArgumentException("Invalid cursor");
        JSONObject response = request("GET", "/v1/boxes/" + Wire.uuid(profile.getString("box")) + "/messages?after=" + after, profile.getString("read"), null);
        Wire.fields(response, "messages", "next_cursor", "more");
        if (response.getJSONArray("messages").length() > 5 || Wire.integer(response, "next_cursor") < after ||
            !(response.get("more") instanceof Boolean) ||
            (response.getBoolean("more") && response.getLong("next_cursor") == after))
            throw new SecurityException("Invalid relay pagination");
        return response;
    }
    public void acknowledge(JSONObject profile, String id) throws Exception {
        request("DELETE", "/v1/boxes/" + Wire.uuid(profile.getString("box")) + "/messages/" + Wire.uuid(id), profile.getString("read"), null);
    }
    public void unregister(JSONObject profile) throws Exception {
        request("DELETE", "/v1/boxes/" + Wire.uuid(profile.getString("box")), profile.getString("read"), null);
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        HttpsURLConnection c = active.getAndSet(null);
        if (c != null) c.disconnect(); timer.shutdownNow();
        if(network!=null) network.close();
    }
}
