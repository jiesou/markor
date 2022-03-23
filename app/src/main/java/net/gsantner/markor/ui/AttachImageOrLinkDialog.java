package net.gsantner.markor.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.arch.core.util.Function;
import android.content.BroadcastReceiver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.text.Editable;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import net.gsantner.markor.R;
import net.gsantner.markor.format.TextFormat;
import net.gsantner.markor.format.markdown.MarkdownHighlighterPattern;
import net.gsantner.markor.ui.hleditor.HighlightingEditor;
import net.gsantner.markor.util.AppSettings;
import net.gsantner.markor.util.ShareUtil;
import net.gsantner.opoc.ui.AudioRecordOmDialog;
import net.gsantner.opoc.ui.FilesystemViewerData;
import net.gsantner.opoc.util.Callback;
import net.gsantner.opoc.util.FileUtils;
import net.gsantner.opoc.util.GashMap;

import org.apache.commons.io.FilenameUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.regex.Matcher;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@SuppressWarnings({"UnusedReturnValue", "WeakerAccess"})
public class AttachImageOrLinkDialog {
    public final static int IMAGE_ACTION = 2, FILE_OR_LINK_ACTION = 3, AUDIO_ACTION = 4;

    @SuppressWarnings("RedundantCast")
    public static Dialog showInsertImageOrLinkDialog(final int action, final int textFormatId, final Activity activity, final HighlightingEditor _hlEditor, final File currentWorkingFile) {
        final AppSettings _appSettings = new AppSettings(activity);
        final android.support.v7.app.AlertDialog.Builder builder = new android.support.v7.app.AlertDialog.Builder(activity);
        final View view = activity.getLayoutInflater().inflate(R.layout.select_path_dialog, (ViewGroup) null);
        final EditText inputPathName = view.findViewById(R.id.ui__select_path_dialog__name);
        final EditText inputPathUrl = view.findViewById(R.id.ui__select_path_dialog__url);
        final Button buttonBrowseFilesystem = view.findViewById(R.id.ui__select_path_dialog__browse_filesystem);
        final Button buttonPictureGallery = view.findViewById(R.id.ui__select_path_dialog__gallery_picture);
        final Button buttonPictureCamera = view.findViewById(R.id.ui__select_path_dialog__camera_picture);
        final Button buttonPictureEdit = view.findViewById(R.id.ui__select_path_dialog__edit_picture);
        final Button buttonAudioRecord = view.findViewById(R.id.ui__select_path_dialog__record_audio);

        final int startCursorPos = _hlEditor.getSelectionStart();
        buttonAudioRecord.setVisibility(action == AUDIO_ACTION ? View.VISIBLE : View.GONE);
        buttonPictureCamera.setVisibility(action == IMAGE_ACTION ? View.VISIBLE : View.GONE);
        buttonPictureGallery.setVisibility(action == IMAGE_ACTION ? View.VISIBLE : View.GONE);
        buttonPictureEdit.setVisibility(action == IMAGE_ACTION ? View.VISIBLE : View.GONE);
        final int actionTitle;
        final String formatTemplate;
        switch (action) {
            default:
            case FILE_OR_LINK_ACTION: {
                actionTitle = R.string.insert_link;
                formatTemplate = new GashMap<Integer, String>().load(
                        TextFormat.FORMAT_MARKDOWN, "[{{ template.title }}]({{ template.link }})",
                        TextFormat.FORMAT_ZIMWIKI, "[[{{ template.link }}|{{ template.title }}]]"
                ).getOrDefault(textFormatId, "<a href='{{ template.link }}'>{{ template.title }}</a>");
                break;
            }
            case IMAGE_ACTION: {
                actionTitle = R.string.insert_image;
                formatTemplate = new GashMap<Integer, String>().load(
                        TextFormat.FORMAT_MARKDOWN, "![{{ template.title }}]({{ template.link }})",
                        TextFormat.FORMAT_ZIMWIKI, "{{{{ template.link }}}}"
                ).getOrDefault(textFormatId, "<img style='width:auto;max-height: 256px;' alt='{{ template.title }}' src='{{ template.link }}' />");
                break;
            }
            case AUDIO_ACTION: {
                formatTemplate = "<audio src='{{ template.link }}' controls><a href='{{ template.link }}'>{{ template.title }}</a></audio>";
                actionTitle = R.string.audio;
                break;
            }

        }

        // Extract filepath if using Markdown
        if (textFormatId == TextFormat.FORMAT_MARKDOWN) {
            if (_hlEditor.hasSelection()) {
                String selected_text = "";
                try {
                    selected_text = _hlEditor.getText().subSequence(_hlEditor.getSelectionStart(), _hlEditor.getSelectionEnd()).toString();
                } catch (Exception ignored) {
                }
                inputPathName.setText(selected_text);
            } else if (_hlEditor.getText().toString().isEmpty()) {
                inputPathName.setText("");
            } else {
                final Editable contentText = _hlEditor.getText();
                int lineStartidx = Math.max(startCursorPos, 0);
                int lineEndidx = Math.min(startCursorPos, contentText.length() - 1);
                lineStartidx = Math.min(lineEndidx, lineStartidx);
                for (; lineStartidx > 0; lineStartidx--) {
                    if (contentText.charAt(lineStartidx) == '\n') {
                        break;
                    }
                }
                for (; lineEndidx < contentText.length(); lineEndidx++) {
                    if (contentText.charAt(lineEndidx) == '\n') {
                        break;
                    }
                }

                final String line = contentText.subSequence(lineStartidx, lineEndidx).toString();
                Matcher m = (action == FILE_OR_LINK_ACTION ? MarkdownHighlighterPattern.ACTION_LINK_PATTERN : MarkdownHighlighterPattern.ACTION_IMAGE_PATTERN).pattern.matcher(line);
                if (m.find() && startCursorPos > lineStartidx + m.start() && startCursorPos < m.end() + lineStartidx) {
                    int stat = lineStartidx + m.start();
                    int en = lineStartidx + m.end();
                    _hlEditor.setSelection(stat, en);
                    inputPathName.setText(m.group(1));
                    inputPathUrl.setText((m.group(2)));
                }
            }
        }


        // Inserts path relative if inside savedir, else absolute. asks to copy file if not in savedir
        final FilesystemViewerData.SelectionListener fsListener = new FilesystemViewerData.SelectionListenerAdapter() {
            @SuppressWarnings("AlibabaAvoidManuallyCreateThread")
            @SuppressLint("SetTextI18n")
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onFsViewerSelected(final String request, final File file, final Integer lineNumber) {
                final String saveDir = _appSettings.getNotebookDirectoryAsStr();
                String text = null;
                boolean isInSaveDir = file.getAbsolutePath().startsWith(saveDir) && currentWorkingFile.getAbsolutePath().startsWith(saveDir);
                boolean isInCurrentDir = currentWorkingFile.getAbsolutePath().startsWith(file.getParentFile().getAbsolutePath());
                if ("use_image_host".equals(request)) {
                    text = activity.getString(R.string.processing);
                    new Thread(() -> {
                        try {
                            // make
                            String repoAndBranch = _appSettings.getImageHostRepo();
                            String repo = repoAndBranch.substring(0, repoAndBranch.lastIndexOf("@"));
                            String branch = repoAndBranch.substring(repoAndBranch.lastIndexOf("@") + 1);
                            String fullFileName = file.getName();
                            String fileName = FilenameUtils.getBaseName(fullFileName);
                            String fileExtension = FilenameUtils.getExtension(fullFileName);
                            // image compression
                            int compressionRate = _appSettings.getImageCompressionRate();
                            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
                            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                            Bitmap.CompressFormat compressFormat;
                            switch (fileExtension) {
                                case "jpg":
                                case "jpeg":
                                    compressFormat = Bitmap.CompressFormat.JPEG;
                                    break;
                                case "webp":
                                    compressFormat = Bitmap.CompressFormat.WEBP;
                                    break;
                                default:
                                    compressFormat = Bitmap.CompressFormat.PNG;
                            }
                            bitmap.compress(compressFormat, compressionRate, byteArrayOutputStream);
                            byteArrayOutputStream.flush();
                            byteArrayOutputStream.close();
                            String imageBase64 = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT);
                            // make fileLocation
                            @SuppressLint("SimpleDateFormat") SimpleDateFormat timeFormat = new SimpleDateFormat(_appSettings.getImageHostFileLocation());
                            String fileLocation = timeFormat.format(new Date())
                                    .replace("{name}", fileName)
                                    + "." + fileExtension;
                            JSONObject iJsonObj = new JSONObject();
                            OkHttpClient client = new OkHttpClient().newBuilder().build();
                            String imageHostCustomOutput = _appSettings.getImageHostCustomOutput();
                            String imageHostUse = _appSettings.getImageHostUse();
                            if ("0".equals(imageHostUse)) {
                                // Github doc https://docs.github.com/cn/rest/reference/repos#create-or-update-file-contents
                                iJsonObj.put("message", "MarkorUpload: " + fileName);
                                iJsonObj.put("content", imageBase64);
                                RequestBody iBody = RequestBody.create(JSON.toJSONString(iJsonObj), MediaType.parse("application/json; charset=utf-8"));
                                // make body
                                Request httpRequest = new Request.Builder()
                                        .url(String.format("https://api.github.com/repos/%s/contents/%s", repo, fileLocation))
                                        .addHeader("Authorization", "token " + _appSettings.getImageHostToken())
                                        .addHeader("branch", branch)
                                        .put(iBody)
                                        .build();
                                try (Response response = client.newCall(httpRequest).execute()) {
                                    JSONObject oJsonObj = JSONObject.parseObject(Objects.requireNonNull(response.body()).string());
                                    activity.runOnUiThread(() -> {
                                        String oUrl;
                                        try {
                                            if ("".equals(imageHostCustomOutput)) {
                                                oUrl = URLDecoder.decode(oJsonObj.getJSONObject("content").getString("download_url"), "UTF-8");
                                            } else {
                                                oUrl = imageHostCustomOutput
                                                        .replace("{repo}", repo)
                                                        .replace("{branch}", branch)
                                                        .replace("{location}", URLDecoder.decode(oJsonObj.getJSONObject("content").getString("path"), "UTF-8"));
                                            }
                                            inputPathUrl.setText(oUrl);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            inputPathUrl.setText(activity.getString(R.string.upload_failed) + "API " + oJsonObj.getString("message"));
                                        }
                                    });
                                } catch (Exception e) {
                                    // catch http and json2obj err
                                    e.printStackTrace();
                                    activity.runOnUiThread(() -> inputPathUrl.setText(activity.getString(R.string.upload_failed) + "HTTP/JSON " + e));
                                }
                            } else {
                                // Gitlab doc https://docs.gitlab.com/ee/api/repository_files.html#create-new-file-in-repository
                                iJsonObj.put("commit_message", "MarkorUpload: " + file.getName());
                                iJsonObj.put("branch", branch);
                                iJsonObj.put("encoding", "base64");
                                iJsonObj.put("content", imageBase64);
                                RequestBody inBody = RequestBody.create(JSON.toJSONString(iJsonObj), MediaType.parse("application/json; charset=utf-8"));
                                // make body
                                Request httpRequest = new Request.Builder()
                                        .url(String.format("https://gitlab.com/api/v4/projects/%s/repository/files/%s", URLEncoder.encode(repo, "UTF-8"), URLEncoder.encode(fileLocation, "UTF-8")))
                                        .addHeader("PRIVATE-TOKEN", _appSettings.getImageHostToken())
                                        .post(inBody)
                                        .build();
                                try (Response response = client.newCall(httpRequest).execute()) {
                                    JSONObject oJsonObj = JSONObject.parseObject(Objects.requireNonNull(response.body()).string());
                                    String oBranch = oJsonObj.getString("branch");
                                    String oFileLocation = URLDecoder.decode(oJsonObj.getString("file_path"), "UTF-8");
                                    activity.runOnUiThread(() -> {
                                        String oUrl;
                                        try {
                                            if ("".equals(imageHostCustomOutput)) {
                                                oUrl = String.format("https://gitlab.com/%s/-/raw/%s/%s", repo, oBranch, oFileLocation);
                                            } else {
                                                oUrl = imageHostCustomOutput
                                                        .replace("{repo}", repo)
                                                        .replace("{branch}", oBranch)
                                                        .replace("{location}", oFileLocation);
                                            }
                                            inputPathUrl.setText(oUrl);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            inputPathUrl.setText(activity.getString(R.string.upload_failed) + "API " + oJsonObj.getString("error"));
                                        }
                                    });
                                } catch (Exception e) {
                                    // catch http and json2obj err
                                    e.printStackTrace();
                                    activity.runOnUiThread(() -> inputPathUrl.setText(activity.getString(R.string.upload_failed) + "HTTP/JSON " + e));
                                }
                            }
                        } catch (Exception e) {
                            // catch all make err
                            e.printStackTrace();
                            activity.runOnUiThread(() -> inputPathUrl.setText(activity.getString(R.string.upload_failed) + "PROCESS " + e));
                        }
                    }).start();
                } else if (isInCurrentDir || isInSaveDir) {
                    text = FileUtils.relativePath(currentWorkingFile, file);
                } else if ("abs_if_not_relative".equals(request)) {
                    text = file.getAbsolutePath();
                } else {
                    String filename = file.getName();
                    if ("audio_record_om_dialog".equals(request)) {
                        filename = AudioRecordOmDialog.generateFilename(file).getName();
                    }
                    File targetCopy = new File(currentWorkingFile.getParentFile(), filename);
                    showCopyFileToDirDialog(activity, file, targetCopy, false, (noUseImageHost, cbRestValTargetFile) -> {
                                if (noUseImageHost) {
                                    onFsViewerSelected("abs_if_not_relative", cbRestValTargetFile, null);
                                } else {
                                    onFsViewerSelected("use_image_host", cbRestValTargetFile, null);
                                }
                            }
                    );
                }
                if (text == null) {
                    text = file.getAbsolutePath();
                }

                inputPathUrl.setText(text);

                if (inputPathName.getText().toString().isEmpty()) {
                    text = file.getName();
                    text = text.contains(".") ? text.substring(0, text.lastIndexOf('.')) : text;
                    inputPathName.setText(text);
                }
                text = inputPathUrl.getText().toString();
                try {
                    if (text.startsWith("../assets/") && currentWorkingFile.getParentFile().getName().equals("_posts")) {
                        text = "{{ site.baseurl }}" + text.substring(2);
                        inputPathUrl.setText(text);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFsViewerConfig(FilesystemViewerData.Options dopt) {
                if (currentWorkingFile != null) {
                    dopt.rootFolder = currentWorkingFile.getParentFile();
                }
            }
        };

        // Request camera / gallery picture button handling
        final ShareUtil shu = new ShareUtil(activity);
        final BroadcastReceiver lbr = shu.receiveResultFromLocalBroadcast((intent, lbr_ref) -> {
                    fsListener.onFsViewerSelected("pic", new File(intent.getStringExtra(ShareUtil.EXTRA_FILEPATH)), null);
                },
                false, ShareUtil.REQUEST_CAMERA_PICTURE + "", ShareUtil.REQUEST_PICK_PICTURE + "");
        final File targetFolder = currentWorkingFile != null ? currentWorkingFile.getParentFile() : _appSettings.getNotebookDirectory();
        buttonPictureCamera.setOnClickListener(button -> shu.requestCameraPicture(targetFolder));
        buttonPictureGallery.setOnClickListener(button -> shu.requestGalleryPicture());

        buttonBrowseFilesystem.setOnClickListener(button -> {
            if (activity instanceof AppCompatActivity) {
                AppCompatActivity a = (AppCompatActivity) activity;
                Function<File, Boolean> f = action == AUDIO_ACTION ? FilesystemViewerCreator.IsMimeAudio : (action == FILE_OR_LINK_ACTION ? null : FilesystemViewerCreator.IsMimeImage);
                FilesystemViewerCreator.showFileDialog(fsListener, a.getSupportFragmentManager(), activity, f);
            }
        });

        // Audio Record -> fs listener with arg file,"audio_record"
        buttonAudioRecord.setOnClickListener(v -> AudioRecordOmDialog.showAudioRecordDialog(activity, R.string.record_audio, cbValAudioRecordFilepath -> fsListener.onFsViewerSelected("audio_record_om_dialog", cbValAudioRecordFilepath, null)));

        buttonPictureEdit.setOnClickListener(v -> {
            String filepath = inputPathUrl.getText().toString().replace("%20", " ");
            if (!filepath.startsWith("/")) {
                filepath = new File(currentWorkingFile.getParent(), filepath).getAbsolutePath();
            }
            File file = new File(filepath);
            if (file.exists() && file.isFile()) {
                shu.requestPictureEdit(file);
            }
        });

        builder.setView(view)
                .setTitle(actionTitle)
                .setOnDismissListener(dialog -> LocalBroadcastManager.getInstance(activity).unregisterReceiver(lbr))
                .setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
                    if (_hlEditor.hasSelection()) {
                        _hlEditor.setSelection(startCursorPos);
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, id) -> {
                    try {
                        String title = inputPathName.getText().toString().replace(")", "\\)");
                        String url = inputPathUrl.getText().toString().trim().replace(")", "\\)").replace(" ", "%20");  // Workaround for parser - cannot deal with spaces and have other entities problems
                        url = url.replace("{{%20site.baseurl%20}}", "{{ site.baseurl }}"); // Disable space encoding for Jekyll
                        String newText = formatTemplate.replace("{{ template.title }}", title).replace("{{ template.link }}", url);
                        if (textFormatId == TextFormat.FORMAT_ZIMWIKI && newText.endsWith("|]]")) {
                            newText = newText.replaceFirst("\\|]]$", "]]");
                        }
                        if (_hlEditor.hasSelection()) {
                            _hlEditor.getText().replace(_hlEditor.getSelectionStart(), _hlEditor.getSelectionEnd(), newText);
                            _hlEditor.setSelection(_hlEditor.getSelectionStart());
                        } else {
                            _hlEditor.getText().insert(_hlEditor.getSelectionStart(), newText);
                        }
                    } catch (Exception ignored) {
                    }
                });
        return builder.show();
    }

    public static Dialog showCopyFileToDirDialog(final Activity activity, final File srcFile, final File tarFile, boolean disableCancel, final Callback.a2<Boolean, File> copyFileFinishedCallback) {
        final Callback.a1<File> copyToDirInvocation = cbValTargetFile -> {
            if (cbValTargetFile == null) {
                copyFileFinishedCallback.callback(false, srcFile);
            } else {
                new ShareUtil(activity).writeFile(cbValTargetFile, false, (wfCbValOpened, wfCbValStream) -> {
                    if (wfCbValOpened && FileUtils.copyFile(srcFile, wfCbValStream)) {
                        copyFileFinishedCallback.callback(true, cbValTargetFile);
                    }
                });
            }
        };

        final File tarFileInAssetsDir = new File(new AppSettings(activity).getNotebookDirectory(), tarFile.getName());


        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity)
                .setTitle(R.string.copy_file)
                .setMessage(R.string.file_not_in_current_folder_do_copy__appspecific)
                .setPositiveButton(R.string.current, (dialogInterface, which) -> copyToDirInvocation.callback(tarFile))
                .setNeutralButton(R.string.notebook, (dialogInterface, which) -> copyToDirInvocation.callback(tarFileInAssetsDir))
                .setNegativeButton(R.string.image_host, (dialogInterface, which) -> copyToDirInvocation.callback(null));
        if (disableCancel) {
            dialogBuilder.setCancelable(false);
        }
        return dialogBuilder.show();
    }
}
