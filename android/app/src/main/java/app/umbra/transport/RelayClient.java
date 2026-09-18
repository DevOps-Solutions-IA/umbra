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
    private final BooleanSupplier permitted;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile HttpsURLConnection active;
    private volatile boolean closed;
    public RelayClient(String address) throws Exception { this(address, () -> true); }
    public RelayClient(String address, BooleanSupplier permitted) throws Exception { base = validate(address); this.permitted = permitted; }
    public static String validate(String address) throws Exception {
        URI uri = new URI(address.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null ||
            uri.getQuery() != null || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalArgumentException("Introduce solo https://dominio, sin usuario ni ruta");
        if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException("Puerto inválido");
        return uri.toString().replaceAll("/+$", "");
    }
    private void allowed() throws IOException {
        if (closed || !permitted.getAsBoolean()) throw new IOException("Conexión cancelada por la política local");
    }
    private JSONObject request(String method, String path, String token, JSONObject body) throws Exception {
        allowed();
        HttpsURLConnection connection = (HttpsURLConnection) new URI(base + path).toURL().openConnection();
        synchronized (this) {
            allowed(); if (active != null) throw new IOException("Relay client already in use"); active = connection;
        }
        ScheduledFuture<?> deadline = null;
        try {
            deadline = timer.schedule(connection::disconnect, 20, TimeUnit.SECONDS);
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
                    try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                } finally { java.util.Arrays.fill(bytes, (byte) 0); }
            }
            allowed(); int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("Servidor rechazó la operación (HTTP " + status + ")");
            if (status == 204) return new JSONObject();
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
                allowed(); return Wire.parse(output.toByteArray(), 5_100_000);
            }
        } finally {
            if (deadline != null) deadline.cancel(false);
            connection.disconnect(); synchronized (this) { if (active == connection) active = null; }
        }
    }
    public void register(JSONObject profile, String invitation) throws Exception {
        request("POST", "/v1/boxes", null, new JSONObject().put("id", profile.getString("box"))
            .put("read_token", profile.getString("read")).put("write_token", profile.getString("write")).put("invitation", invitation.trim()));
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
    @Override public synchronized void close() {
        closed = true; HttpsURLConnection c = active; active = null;
        if (c != null) c.disconnect(); timer.shutdownNow();
    }
}
