package com.padc.tabledemo;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ApiClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 130_000;

    private ApiClient() {
    }

    static JSONObject health(String baseUrl) throws Exception {
        return new JSONObject(request("GET", normalized(baseUrl) + "/api/health", null, null));
    }

    static JSONObject createTask(String baseUrl, ContentResolver resolver, Uri imageUri) throws Exception {
        String boundary = "PadDemo-" + UUID.randomUUID();
        HttpURLConnection connection = open(normalized(baseUrl) + "/api/v1/tasks", "POST");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        String mimeType = resolver.getType(imageUri);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            mimeType = "image/jpeg";
        }
        try (OutputStream output = connection.getOutputStream();
             InputStream input = new BufferedInputStream(resolver.openInputStream(imageUri))) {
            if (input == null) {
                throw new IOException("无法打开所选图片");
            }
            writeUtf8(output, "--" + boundary + "\r\n");
            writeUtf8(output, "Content-Disposition: form-data; name=\"file\"; filename=\"capture.jpg\"\r\n");
            writeUtf8(output, "Content-Type: " + mimeType + "\r\n\r\n");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            writeUtf8(output, "\r\n--" + boundary + "--\r\n");
        }
        return new JSONObject(readResponse(connection));
    }

    static JSONObject getTask(String baseUrl, String taskId) throws Exception {
        return new JSONObject(request("GET", normalized(baseUrl) + "/api/v1/tasks/" + taskId, null, null));
    }

    static JSONObject listTasks(String baseUrl) throws Exception {
        return new JSONObject(request("GET", normalized(baseUrl) + "/api/v1/tasks?limit=20", null, null));
    }

    static byte[] download(String absoluteUrl) throws Exception {
        HttpURLConnection connection = open(absoluteUrl, "GET");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("下载校正图片失败，HTTP " + status);
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static String request(String method, String url, byte[] body, String contentType) throws Exception {
        HttpURLConnection connection = open(url, method);
        if (body != null) {
            connection.setDoOutput(true);
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }
        return readResponse(connection);
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static String readResponse(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readText(stream);
            if (status < 200 || status >= 300) {
                throw new IOException("服务器返回 HTTP " + status + ": " + response);
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static String readText(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    static String normalized(String baseUrl) {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
