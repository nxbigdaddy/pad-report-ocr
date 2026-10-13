package com.padc.tabledemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_PICK = 1002;
    private static final int NAVY = Color.rgb(10, 35, 58);
    private static final int BLUE = Color.rgb(20, 112, 190);
    private static final int BLUE_PRESSED = Color.rgb(13, 83, 146);
    private static final int TEXT = Color.rgb(25, 42, 58);
    private static final int MUTED = Color.rgb(99, 116, 132);
    private static final int BORDER = Color.rgb(218, 227, 235);

    private EditText serverInput;
    private ImageView preview;
    private TextView previewPlaceholder;
    private ProgressBar progress;
    private TextView statusView;
    private TextView resultView;
    private ScrollView resultScroll;
    private TextView resultModeChip;
    private Button uploadButton;
    private LinearLayout mainContent;
    private LinearLayout leftPanel;
    private LinearLayout rightPanel;
    private Uri selectedImage;
    private Uri pendingCameraImage;
    private String summaryResult = "暂无识别结果\n\n拍照或选择图片后，点击“上传识别”。";
    private String rawResult = "";
    private boolean showingRawResult = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        acceptSharedImage(getIntent());
        checkServer();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyResponsiveLayout();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSharedImage(intent);
    }

    private void acceptSharedImage(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        Uri image = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (image != null) {
            useImage(image);
        }
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(239, 244, 248));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(22), dp(14), dp(22), dp(14));
        header.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{NAVY, Color.rgb(18, 78, 113)}));

        TextView brand = new TextView(this);
        brand.setText("PAD VISION  ·  现场数据采集");
        brand.setTextSize(11);
        brand.setLetterSpacing(0.12f);
        brand.setTextColor(Color.rgb(139, 211, 235));
        header.addView(brand, matchWrap());

        TextView title = new TextView(this);
        title.setText("生产日报表智能识别");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, dp(2), 0, 0);
        header.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("拍摄纸质表格，自动校正并生成结构化数据");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.rgb(205, 226, 239));
        subtitle.setPadding(0, dp(3), 0, 0);
        header.addView(subtitle, matchWrap());
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainContent = new LinearLayout(this);
        mainContent.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(mainContent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        leftPanel = createCard();
        TextView captureTitle = sectionTitle("采集与预览");
        leftPanel.addView(captureTitle, matchWrap());
        TextView captureHint = sectionHint("保持纸张完整入镜，避免反光和手指遮挡");
        leftPanel.addView(captureHint, matchWrap());

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(rounded(Color.rgb(245, 248, 251), 14, BORDER));
        previewFrame.setClipToOutline(true);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        previewFrame.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        previewPlaceholder = new TextView(this);
        previewPlaceholder.setText("＋\n等待拍摄表格");
        previewPlaceholder.setGravity(Gravity.CENTER);
        previewPlaceholder.setTextSize(17);
        previewPlaceholder.setTextColor(Color.rgb(126, 145, 160));
        previewPlaceholder.setLineSpacing(dp(3), 1f);
        previewFrame.addView(previewPlaceholder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.setMargins(0, dp(12), 0, dp(12));
        leftPanel.addView(previewFrame, previewParams);

        LinearLayout actionRow = buttonRow();
        Button capture = createButton("拍照", false);
        capture.setOnClickListener(v -> capturePhoto());
        actionRow.addView(capture, weightedButton(1f));
        Button pick = createButton("相册", false);
        pick.setOnClickListener(v -> pickImage());
        actionRow.addView(pick, weightedButton(1f));
        uploadButton = createButton("上传识别", true);
        uploadButton.setEnabled(false);
        uploadButton.setOnClickListener(v -> uploadAndRecognize());
        actionRow.addView(uploadButton, weightedButton(1.25f));
        leftPanel.addView(actionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        rightPanel = createCard();
        LinearLayout taskHeader = new LinearLayout(this);
        taskHeader.setOrientation(LinearLayout.HORIZONTAL);
        taskHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView taskTitle = sectionTitle("识别任务");
        taskHeader.addView(taskTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button historyButton = createButton("历史记录", false);
        historyButton.setTextSize(11);
        historyButton.setOnClickListener(v -> showHistory());
        taskHeader.addView(historyButton, new LinearLayout.LayoutParams(dp(92), dp(34)));
        rightPanel.addView(taskHeader, matchWrap());

        TextView serverLabel = sectionHint("服务地址");
        serverLabel.setPadding(0, dp(10), 0, dp(5));
        rightPanel.addView(serverLabel, matchWrap());

        LinearLayout serverRow = new LinearLayout(this);
        serverRow.setOrientation(LinearLayout.HORIZONTAL);
        serverRow.setGravity(Gravity.CENTER_VERTICAL);
        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setText(getSharedPreferences("pad_demo", MODE_PRIVATE)
                .getString("server_url", "http://127.0.0.1:8000"));
        serverInput.setHint("服务器地址");
        serverInput.setTextSize(14);
        serverInput.setTextColor(TEXT);
        serverInput.setHintTextColor(MUTED);
        serverInput.setPadding(dp(12), 0, dp(12), 0);
        serverInput.setBackground(rounded(Color.rgb(247, 249, 252), 10, BORDER));
        serverRow.addView(serverInput, new LinearLayout.LayoutParams(0, dp(46), 1f));
        Button test = createButton("检测", false);
        test.setOnClickListener(v -> checkServer());
        LinearLayout.LayoutParams testParams = new LinearLayout.LayoutParams(dp(86), dp(46));
        testParams.setMargins(dp(8), 0, 0, 0);
        serverRow.addView(test, testParams);
        rightPanel.addView(serverRow, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        progress.getIndeterminateDrawable().setTint(BLUE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.setMargins(0, dp(10), 0, dp(4));
        rightPanel.addView(progress, progressParams);

        statusView = new TextView(this);
        statusView.setText("正在检查服务器……");
        statusView.setTextSize(14);
        statusView.setTextColor(Color.rgb(28, 91, 140));
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        statusView.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusView.setBackground(rounded(Color.rgb(232, 244, 253), 10, Color.rgb(183, 218, 240)));
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.setMargins(0, dp(6), 0, dp(12));
        rightPanel.addView(statusView, statusParams);

        LinearLayout resultHeader = new LinearLayout(this);
        resultHeader.setOrientation(LinearLayout.HORIZONTAL);
        resultHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView resultTitle = sectionTitle("结构化结果");
        resultHeader.addView(resultTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        resultModeChip = smallChip("查看 JSON");
        resultModeChip.setClickable(true);
        resultModeChip.setFocusable(true);
        resultModeChip.setOnClickListener(v -> toggleResultMode());
        resultHeader.addView(resultModeChip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28)));
        rightPanel.addView(resultHeader, matchWrap());

        resultView = new TextView(this);
        resultView.setText(summaryResult);
        resultView.setTextSize(13);
        resultView.setTextIsSelectable(true);
        resultView.setTypeface(Typeface.MONOSPACE);
        resultView.setTextColor(Color.rgb(51, 69, 84));
        resultView.setLineSpacing(dp(2), 1.08f);

        resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(true);
        resultScroll.setBackground(rounded(Color.rgb(247, 249, 252), 12, BORDER));
        resultScroll.setPadding(dp(12), dp(12), dp(12), dp(12));
        resultScroll.addView(resultView, matchWrap());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.setMargins(0, dp(8), 0, 0);
        rightPanel.addView(resultScroll, scrollParams);

        mainContent.addView(leftPanel);
        mainContent.addView(rightPanel);
        applyResponsiveLayout();

        return root;
    }

    private void capturePhoto() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,
                "PAD_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PADTableDemo");
        }
        pendingCameraImage = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (pendingCameraImage == null) {
            setStatus("无法创建照片文件。", true);
            return;
        }

        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraImage);
        intent.setClipData(ClipData.newRawUri("PADTableDemo capture", pendingCameraImage));
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            getContentResolver().delete(pendingCameraImage, null, null);
            pendingCameraImage = null;
            setStatus("平板上没有可用的相机应用。", true);
            return;
        }
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && pendingCameraImage != null) {
                useImage(pendingCameraImage);
            } else if (pendingCameraImage != null) {
                getContentResolver().delete(pendingCameraImage, null, null);
            }
            pendingCameraImage = null;
        } else if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri image = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(image, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // The URI remains valid for this activity even if persistence is not supported.
            }
            useImage(image);
        }
    }

    private void useImage(Uri image) {
        selectedImage = image;
        uploadButton.setEnabled(true);
        previewPlaceholder.setVisibility(View.GONE);
        rawResult = "";
        summaryResult = "等待识别\n\n新图片已准备好，点击“上传识别”。";
        showingRawResult = false;
        updateResultDisplay();
        setStatus("图片已准备好，可以上传识别。", false);
        new Thread(() -> {
            Bitmap bitmap = decodePreview(image, 1400, 1000);
            runOnUiThread(() -> preview.setImageBitmap(bitmap));
        }).start();
    }

    private Bitmap decodePreview(Uri uri, int requestedWidth, int requestedHeight) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > requestedWidth * 2 || bounds.outHeight / sample > requestedHeight * 2) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Exception error) {
            runOnUiThread(() -> setStatus("图片预览失败：" + error.getMessage(), true));
            return null;
        }
    }

    private void checkServer() {
        setBusy(true, "正在连接服务器……");
        final String baseUrl = ApiClient.normalized(serverInput.getText().toString());
        saveServerUrl(baseUrl);
        new Thread(() -> {
            try {
                JSONObject health = ApiClient.health(baseUrl);
                boolean modelConfigured = health.optBoolean("model_configured", false);
                runOnUiThread(() -> {
                    if (selectedImage != null) {
                        setBusy(false, "图片已准备好，可以上传识别。");
                    } else {
                        setBusy(false, modelConfigured
                                ? "服务器连接正常，视觉模型已配置。"
                                : "服务器连接正常；当前为图像处理 Demo 模式。"
                        );
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false, "服务器连接失败：" + error.getMessage(), true));
            }
        }).start();
    }

    private void uploadAndRecognize() {
        if (selectedImage == null) {
            setStatus("请先拍照或选择图片。", true);
            return;
        }
        setBusy(true, "正在上传图片……");
        uploadButton.setEnabled(false);
        final String baseUrl = ApiClient.normalized(serverInput.getText().toString());
        saveServerUrl(baseUrl);
        new Thread(() -> {
            try {
                JSONObject created = ApiClient.createTask(baseUrl, getContentResolver(), selectedImage);
                String taskId = created.getString("task_id");
                showStatusFromWorker("上传完成，正在校正和识别……");
                JSONObject task = waitForTask(baseUrl, taskId);
                String status = task.optString("status");
                if ("failed".equals(status)) {
                    throw new IllegalStateException(task.optString("error", "识别任务失败"));
                }
                String pretty = task.toString(2);
                String processedImageUrl = task.optString("processed_image_url", "");
                Bitmap corrected = null;
                if (!processedImageUrl.isEmpty()) {
                    byte[] imageBytes = ApiClient.download(baseUrl + processedImageUrl);
                    corrected = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                }
                Bitmap finalCorrected = corrected;
                String finalPretty = pretty;
                String finalSummary = formatTaskSummary(task);
                String qualityStatus = getQualityStatus(task);
                runOnUiThread(() -> {
                    rawResult = finalPretty;
                    summaryResult = finalSummary;
                    showingRawResult = false;
                    updateResultDisplay();
                    if (finalCorrected != null) {
                        preview.setImageBitmap(finalCorrected);
                    }
                    if ("poor".equals(qualityStatus)) {
                        setBusy(false, "图片质量未通过，请重新拍摄。", true);
                    } else if ("warning".equals(qualityStatus)) {
                        setBusy(false, "处理完成，但图片质量需要注意。", false);
                    } else {
                        setBusy(false, "处理完成。上方显示校正后的表格，下方为结构化结果。");
                    }
                    uploadButton.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    rawResult = "";
                    summaryResult = "处理失败\n\n" + error.getMessage();
                    showingRawResult = false;
                    updateResultDisplay();
                    setBusy(false, "上传或识别失败：" + error.getMessage(), true);
                    uploadButton.setEnabled(true);
                });
            }
        }).start();
    }

    private void saveServerUrl(String baseUrl) {
        getSharedPreferences("pad_demo", MODE_PRIVATE)
                .edit().putString("server_url", baseUrl).apply();
    }

    private void toggleResultMode() {
        if (rawResult.isEmpty()) {
            setStatus("当前还没有可查看的 JSON 结果。", false);
            return;
        }
        showingRawResult = !showingRawResult;
        updateResultDisplay();
    }

    private void updateResultDisplay() {
        resultView.setTypeface(showingRawResult ? Typeface.MONOSPACE : Typeface.DEFAULT);
        resultView.setText(showingRawResult ? rawResult : summaryResult);
        resultModeChip.setText(showingRawResult ? "查看摘要" : "查看 JSON");
        resultView.clearFocus();
        resultScroll.clearFocus();
        resultScroll.scrollTo(0, 0);
        resultScroll.post(() -> resultScroll.scrollTo(0, 0));
    }

    private String formatTaskSummary(JSONObject task) {
        StringBuilder text = new StringBuilder();
        String status = task.optString("status", "unknown");
        text.append("任务状态    ").append("completed".equals(status) ? "已完成" : status).append('\n');
        String createdAt = task.optString("created_at", "");
        if (!createdAt.isEmpty()) {
            text.append("创建时间    ").append(createdAt.replace('T', ' '));
            if (createdAt.length() > 19) {
                text.setLength(text.length() - (createdAt.length() - 19));
            }
            text.append('\n');
        }

        JSONObject result = task.optJSONObject("result");
        if (result == null) {
            String error = task.optString("error", "暂无处理结果");
            return text.append("\n").append(error).toString();
        }
        String mode = result.optString("mode", "");
        String modeLabel = "vision_model".equals(mode) ? "视觉大模型"
                : "quality_rejected".equals(mode) ? "质量检查未通过"
                : "图像处理 Demo";
        text.append("识别模式    ")
                .append(modeLabel)
                .append('\n');

        JSONObject processing = result.optJSONObject("image_processing");
        if (processing != null) {
            text.append("纸张定位    ")
                    .append(processing.optBoolean("document_detected") ? "成功" : "未检测到")
                    .append('\n');
            JSONObject quality = processing.optJSONObject("quality");
            if (quality != null) {
                String qualityStatus = quality.optString("status", "unknown");
                String qualityLabel = "good".equals(qualityStatus) ? "良好"
                        : "warning".equals(qualityStatus) ? "需注意" : "较差";
                text.append("图像质量    ").append(quality.optInt("score", 0))
                        .append(" / 100（").append(qualityLabel).append("）\n");
                JSONObject table = quality.optJSONObject("table");
                if (table != null) {
                    text.append("表格结构    ");
                    if (table.optBoolean("detected")) {
                        text.append(table.optInt("rows")).append(" 行 × ")
                                .append(table.optInt("columns")).append(" 列");
                    } else {
                        text.append("未检测到完整网格");
                    }
                    text.append('\n');
                }
                appendWarnings(text, quality.optJSONArray("warnings"));
            }
        }

        JSONObject document = result.optJSONObject("document");
        if (document != null) {
            JSONArray rows = document.optJSONArray("rows");
            text.append("识别记录    ").append(rows == null ? 0 : rows.length()).append(" 行\n");
            appendRecognizedRows(text, rows);
            appendWarnings(text, document.optJSONArray("warnings"));
        }
        return text.toString().trim();
    }

    private void appendRecognizedRows(StringBuilder text, JSONArray rows) {
        if (rows == null || rows.length() == 0) {
            return;
        }
        text.append("\n识别明细\n");
        int visibleRows = Math.min(rows.length(), 30);
        for (int index = 0; index < visibleRows; index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null) {
                continue;
            }
            String completion = fieldValue(row, "completion_status");
            if ("completed".equals(completion)) {
                completion = "已完成";
            } else if ("not_completed".equals(completion)) {
                completion = "未完成";
            } else if ("uncertain".equals(completion)) {
                completion = "待确认";
            }
            text.append(String.format(Locale.CHINA, "%02d  %s  %s  %s  ×%s  %s",
                    row.optInt("row_number", index + 1),
                    fieldValue(row, "project_no"),
                    fieldValue(row, "product_name"),
                    fieldValue(row, "date"),
                    fieldValue(row, "quantity"),
                    completion));
            if (row.optBoolean("review_required", false)) {
                text.append("  [需复核]");
            }
            text.append('\n');
        }
        if (rows.length() > visibleRows) {
            text.append("……其余 ").append(rows.length() - visibleRows).append(" 行请查看 JSON\n");
        }
    }

    private String fieldValue(JSONObject row, String name) {
        JSONObject field = row.optJSONObject(name);
        if (field == null) {
            return "—";
        }
        Object value = field.opt("value");
        if (value != null && value != JSONObject.NULL && !String.valueOf(value).isEmpty()) {
            return String.valueOf(value);
        }
        String raw = field.optString("raw", "");
        return raw.isEmpty() ? "—" : raw;
    }

    private String getQualityStatus(JSONObject task) {
        JSONObject result = task.optJSONObject("result");
        JSONObject processing = result == null ? null : result.optJSONObject("image_processing");
        JSONObject quality = processing == null ? null : processing.optJSONObject("quality");
        return quality == null ? "unknown" : quality.optString("status", "unknown");
    }

    private void appendWarnings(StringBuilder text, JSONArray warnings) {
        if (warnings == null || warnings.length() == 0) {
            return;
        }
        text.append("\n提示\n");
        for (int index = 0; index < warnings.length(); index++) {
            text.append("  • ").append(warnings.optString(index)).append('\n');
        }
    }

    private void showHistory() {
        final String baseUrl = ApiClient.normalized(serverInput.getText().toString());
        saveServerUrl(baseUrl);
        setBusy(true, "正在加载历史任务……");
        new Thread(() -> {
            try {
                JSONArray tasks = ApiClient.listTasks(baseUrl).getJSONArray("tasks");
                String[] labels = new String[tasks.length()];
                for (int index = 0; index < tasks.length(); index++) {
                    JSONObject task = tasks.getJSONObject(index);
                    String time = task.optString("created_at", "").replace('T', ' ');
                    if (time.length() > 19) {
                        time = time.substring(0, 19);
                    }
                    labels[index] = time + "    " + task.optString("status", "unknown");
                }
                runOnUiThread(() -> {
                    setBusy(false, "历史任务已加载。", false);
                    if (tasks.length() == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle("历史任务")
                                .setMessage("目前还没有上传记录。")
                                .setPositiveButton("知道了", null)
                                .show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("最近任务")
                            .setItems(labels, (dialog, which) -> loadHistoricalTask(tasks.optJSONObject(which), baseUrl))
                            .setNegativeButton("关闭", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false, "历史任务加载失败：" + error.getMessage(), true));
            }
        }).start();
    }

    private void loadHistoricalTask(JSONObject task, String baseUrl) {
        if (task == null) {
            return;
        }
        setBusy(true, "正在打开历史任务……");
        new Thread(() -> {
            Bitmap processedImage = null;
            try {
                String imageUrl = task.optString("processed_image_url", "");
                if (!imageUrl.isEmpty()) {
                    byte[] imageBytes = ApiClient.download(baseUrl + imageUrl);
                    processedImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                }
                String pretty = task.toString(2);
                String summary = formatTaskSummary(task);
                Bitmap finalImage = processedImage;
                runOnUiThread(() -> {
                    rawResult = pretty;
                    summaryResult = summary;
                    showingRawResult = false;
                    updateResultDisplay();
                    if (finalImage != null) {
                        previewPlaceholder.setVisibility(View.GONE);
                        preview.setImageBitmap(finalImage);
                    }
                    setBusy(false, "历史任务已打开。", false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> setBusy(false, "历史任务打开失败：" + error.getMessage(), true));
            }
        }).start();
    }

    private JSONObject waitForTask(String baseUrl, String taskId) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            JSONObject task = ApiClient.getTask(baseUrl, taskId);
            String status = task.optString("status");
            if ("completed".equals(status) || "failed".equals(status)) {
                return task;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("等待识别结果超时");
    }

    private void showStatusFromWorker(String message) {
        runOnUiThread(() -> setStatus(message, false));
    }

    private void setBusy(boolean busy, String message) {
        setBusy(busy, message, false);
    }

    private void setBusy(boolean busy, String message, boolean error) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        serverInput.setEnabled(!busy);
        setStatus(message, error);
    }

    private void setStatus(String message, boolean error) {
        statusView.setText(message);
        if (error) {
            statusView.setTextColor(Color.rgb(166, 36, 46));
            statusView.setBackground(rounded(
                    Color.rgb(254, 238, 239), 10, Color.rgb(244, 191, 196)));
        } else if (message.contains("注意") || message.contains("建议")) {
            statusView.setTextColor(Color.rgb(145, 91, 18));
            statusView.setBackground(rounded(
                    Color.rgb(255, 247, 224), 10, Color.rgb(239, 211, 145)));
        } else if (message.contains("正常") || message.contains("完成") || message.contains("准备好")) {
            statusView.setTextColor(Color.rgb(31, 112, 76));
            statusView.setBackground(rounded(
                    Color.rgb(234, 248, 240), 10, Color.rgb(183, 226, 202)));
        } else {
            statusView.setTextColor(Color.rgb(28, 91, 140));
            statusView.setBackground(rounded(
                    Color.rgb(232, 244, 253), 10, Color.rgb(183, 218, 240)));
        }
    }

    private void applyResponsiveLayout() {
        if (mainContent == null || leftPanel == null || rightPanel == null) {
            return;
        }
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mainContent.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (landscape) {
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f);
            rightParams.setMargins(dp(12), 0, 0, 0);
            leftPanel.setLayoutParams(leftParams);
            rightPanel.setLayoutParams(rightParams);
        } else {
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.15f);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.85f);
            rightParams.setMargins(0, dp(12), 0, 0);
            leftPanel.setLayoutParams(leftParams);
            rightPanel.setLayoutParams(rightParams);
        }
        mainContent.requestLayout();
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(rounded(Color.WHITE, 16, Color.rgb(226, 233, 239)));
        card.setElevation(dp(3));
        card.setClipToOutline(false);
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(19);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(TEXT);
        return view;
    }

    private TextView sectionHint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(MUTED);
        view.setPadding(0, dp(3), 0, 0);
        return view;
    }

    private TextView smallChip(String text) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(10);
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setTextColor(Color.rgb(45, 103, 144));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setBackground(rounded(Color.rgb(234, 244, 251), 14, Color.rgb(202, 226, 241)));
        return chip;
    }

    private GradientDrawable rounded(int fillColor, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private StateListDrawable buttonBackground(boolean primary) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, rounded(
                Color.rgb(203, 213, 222), 11, Color.rgb(203, 213, 222)));
        states.addState(new int[]{android.R.attr.state_pressed}, primary
                ? rounded(BLUE_PRESSED, 11, BLUE_PRESSED)
                : rounded(Color.rgb(229, 240, 248), 11, Color.rgb(154, 196, 224)));
        states.addState(new int[]{}, primary
                ? rounded(BLUE, 11, BLUE)
                : rounded(Color.WHITE, 11, Color.rgb(170, 202, 224)));
        return states;
    }

    private Button createButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(32, 99, 148));
        button.setBackground(buttonBackground(primary));
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setStateListAnimator(null);
        button.setElevation(0);
        return button;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weightedButton(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
