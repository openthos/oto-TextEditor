/*
 * Copyright (C) 2016 Jecelyin Peng <jecelyin@gmail.com>
 *
 * This file is part of 920 Text Editor.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openthos.editor.widget.text;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ActionMode;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.alibaba.fastjson.JSON;

import org.openthos.common.utils.IOUtils;
import org.openthos.common.utils.L;
import org.openthos.common.utils.UIUtils;
import org.openthos.editor.Pref;
import org.openthos.editor.R;
import org.openthos.editor.ThemeList;
import org.openthos.editor.interfaces.OnMenuClickListener;
import org.openthos.editor.interfaces.OnTextChangeListener;
import org.openthos.editor.ui.MainActivity;
import org.openthos.editor.ui.dialog.TopMenuDialog;
import org.openthos.editor.view.menu.MenuFactory;
import org.openthos.editor.view.menu.MenuItemInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Jecelyin Peng <jecelyin@gmail.com>
 */
public class EditAreaView extends WebView implements SharedPreferences.OnSharedPreferenceChangeListener {
    private Pref pref;
    private ArrayList<EditorCommand> cmdQueue = new ArrayList<>();
    private boolean pageLoaded;
    private AtomicLong cmdID = new AtomicLong(0);
    private HashMap<Long, ValueCallback<String>> callbackMap;
    private ActionMode.Callback actionModeCallback;
    private ActionMode actionMode;
    private OnTextChangeListener onTextChangeListener;
    private String modeName = "Text";
    private boolean selected;
    private boolean textChanged;
    private InputConnectionHacker inputConnectionHacker;
    private String selectedText;
    private OnMenuClickListener mListener;

    private Boolean mIsShow = false;

    public EditAreaView(Context context) {
        super(context);
    }

    public EditAreaView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, android.R.attr.webViewStyle);
    }

    public EditAreaView(Context context, AttributeSet attrs, int defstyle) {
        super(context, attrs, defstyle);

        if (L.debug) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                setWebContentsDebuggingEnabled(true);
            }
        }

        setLongClickable(false);
        setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {//鼠标右键
                return true;
            }
        });

        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);

        WebSettings ws = getSettings();
        ws.setDefaultZoom(WebSettings.ZoomDensity.FAR);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccess(true);
        ws.setBuiltInZoomControls(false);
        ws.setDefaultTextEncodingName("utf-8");
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(false);
        ws.setLoadWithOverviewMode(false);

        ws.setJavaScriptEnabled(true);
        ws.setAppCacheEnabled(false);
        ws.setDomStorageEnabled(true);
        ws.setAppCacheMaxSize(1024 * 1024 * 80);
        ws.setAppCachePath(context.getCacheDir().getPath());
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        addJavascriptInterface(new JavascriptApi(), "AndroidEditor");//交互用的

        setWebViewClient(new EditorViewClient());
        setWebChromeClient(new EditorViewChromeClient());

        pref = Pref.getInstance(getContext());
        ThemeList.Theme theme = pref.getThemeInfo();
        boolean isDark = false;
        if (theme != null) {
            isDark = theme.isDark;
        }

        String html = null;

        try {
            InputStream is = getContext().getAssets().open("editor.html");
            html = IOUtils.readFile(is, "utf-8");
            is.close();
        } catch (Exception e) {
            L.e(e);
            UIUtils.toast(getContext(), R.string.editor_create_unknown_exception);
            return;
        }

        if (!isDark)
            html = html.replaceAll("<\\!\\-\\-\\{DARK\\-START\\}\\-\\->[\\w\\W]+?<\\!\\-\\-\\{DARK\\-END\\}\\-\\->", "");

        loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", "file:///android_asset/");
        //fix dark theme background spark
        setBackgroundColor(Color.TRANSPARENT);

        pref.registerOnSharedPreferenceChangeListener(this);
        onSharedPreferenceChanged(null, Pref.KEY_FONT_SIZE);
        onSharedPreferenceChanged(null, Pref.KEY_CURSOR_WIDTH);
        onSharedPreferenceChanged(null, Pref.KEY_SHOW_LINE_NUMBER);
        onSharedPreferenceChanged(null, Pref.KEY_WORD_WRAP);
        onSharedPreferenceChanged(null, Pref.KEY_SHOW_WHITESPACE);
        onSharedPreferenceChanged(null, Pref.KEY_TAB_SIZE);
        onSharedPreferenceChanged(null, Pref.KEY_AUTO_INDENT);
        onSharedPreferenceChanged(null, Pref.KEY_AUTO_CAPITALIZE);
        onSharedPreferenceChanged(null, Pref.KEY_INSERT_SPACE_FOR_TAB);
        onSharedPreferenceChanged(null, Pref.KEY_THEME);
        onSharedPreferenceChanged(null, Pref.KEY_TOUCH_TO_ADJUST_TEXT_SIZE);
        enableHighlight(pref.isHighlight());
        setReadOnly(pref.isReadOnly());
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch (key) {
            case Pref.KEY_FONT_SIZE:
                execCommand(new EditorCommand.Builder("setFontSize").put("value", pref.getFontSize()).build());
                break;
            case Pref.KEY_CURSOR_WIDTH:
                execCommand(new EditorCommand.Builder("setCursorWidth").put("value", pref.getCursorThickness()).build());
                break;
            case Pref.KEY_SHOW_LINE_NUMBER:
                execCommand(new EditorCommand.Builder("setShowLineNumber").put("value", pref.isShowLineNumber()).build());
                break;
            case Pref.KEY_WORD_WRAP:
                execCommand(new EditorCommand.Builder("setWordWrap").put("value", pref.isWordWrap()).build());
                break;
            case Pref.KEY_SHOW_WHITESPACE:
                execCommand(new EditorCommand.Builder("setShowInvisible").put("value", pref.isShowWhiteSpace()).build());
                break;
            case Pref.KEY_TAB_SIZE:
                execCommand(new EditorCommand.Builder("setTabSize").put("value", pref.getTabSize()).build());
                break;
            case Pref.KEY_AUTO_INDENT:
                execCommand(new EditorCommand.Builder("setAutoIndent").put("value", pref.isAutoIndent()).build());
                break;
            case Pref.KEY_INSERT_SPACE_FOR_TAB:
                execCommand(new EditorCommand.Builder("setSpaceAsTab").put("value", pref.isInsertSpaceForTab()).build());
                break;
            case Pref.KEY_AUTO_CAPITALIZE:
                execCommand(new EditorCommand.Builder("setAutoCapitalize").put("value", pref.isAutoCapitalize()).build());
                break;
            case Pref.KEY_TOUCH_TO_ADJUST_TEXT_SIZE:
                execCommand(new EditorCommand.Builder("setZoomable").put("value", pref.isTouchScaleTextSize()).build());
                break;
            case Pref.KEY_THEME:
                ThemeList.Theme theme = pref.getThemeInfo();
                if (theme != null) {
                    execCommand(new EditorCommand.Builder("setTheme").put("value", theme.mode).build());
                }
                break;
        }
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return null;
    }

    private class JavascriptApi {

        @JavascriptInterface
        public void returnValue(long id, String value) {
            ValueCallback<String> callback = callbackMap.get(id);
            if (callback != null) {
                callback.onReceiveValue(value);
                callbackMap.remove(id);
            }
        }

        @JavascriptInterface
        public void showActionView(boolean isShow) {
            mIsShow = isShow;
        }

        @JavascriptInterface
        public void showActionMode() {
            if (actionModeCallback == null)
                return;

            post(new Runnable() {
                @Override
                public void run() {
                    if (actionMode != null) {
                        actionMode.finish();
                    }
                    //actionMode = startActionMode(actionModeCallback);
                    actionMode.finish();//禁止ActionBar弹出；
                }
            });
        }

        @JavascriptInterface
        public void hideActionMode() {
            if (actionMode != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        actionMode.finish();
                    }
                });
            }
        }

        @JavascriptInterface
        public void onSelectionChange(boolean s, String text) {
            selectedText = text;
            selected = s;
        }

        @JavascriptInterface
        public void onTextChanged(boolean s) {
            textChanged = s;

            if (onTextChangeListener != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        onTextChangeListener.onTextChanged();
                    }
                });
            }
        }

        @JavascriptInterface
        public void onModeChanged(String name) {
            modeName = name;
        }

        @JavascriptInterface
        public void onScrollStart() {
            notifySymbolBarVisibility(false);
        }

        @JavascriptInterface
        public void onScrollEnd() {
            notifySymbolBarVisibility(true);
        }

        @JavascriptInterface
        public void openFile(final String file, final int line, final int column) {
            post(new Runnable() {
                @Override
                public void run() {
                    MainActivity activity = (MainActivity) UIUtils.getActivity(EditAreaView.this);
                    if (activity != null) {
                        activity.openFile(file, null, line, column);
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean isShiftPressed() {
            return inputConnectionHacker != null && inputConnectionHacker.isShiftPressed();
        }

        @JavascriptInterface
        public boolean isAltPressed() {
            return inputConnectionHacker != null && inputConnectionHacker.isAltPressed();
        }

        @JavascriptInterface
        public boolean isCtrlPressed() {
            return inputConnectionHacker != null && inputConnectionHacker.isCtrlPressed();
        }

        @JavascriptInterface
        public void updateCursorBeforeText(String text) {
            if (inputConnectionHacker != null)
                inputConnectionHacker.cursorBeforeText = text;
        }
    }

    private void notifySymbolBarVisibility(final boolean b) {
        post(new Runnable() {
            @Override
            public void run() {
                MainActivity activity = (MainActivity) UIUtils.getActivity(EditAreaView.this);
                if (activity != null) {
                    activity.setSymbolVisibility(b);
                }
            }
        });
    }

    private class EditorViewClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            pageLoaded = true;

            for (EditorCommand command : cmdQueue) {
                execCommand(command);
            }
        }
    }

    private class EditorViewChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            L.d("CONSOLE", consoleMessage.message() + " (#" +
                    consoleMessage.lineNumber() + " " + consoleMessage.sourceId() + ")");
            return true;
        }
    }

    public void execCommand(final EditorCommand ec) {
        if (!pageLoaded) {
            cmdQueue.add(ec);
            return;
        }
        if (Looper.getMainLooper().getThread() != Thread.currentThread()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    execCommand(ec);
                }
            });
            return;
        }

        try {
            HashMap<String, Object> map = new HashMap<>();
            map.put("cmd", ec.cmd);
            map.put("data", ec.data);
            String json = JSON.toJSONString(map);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                evaluateJavascript(String.format("handleJava(%d, %s);", 0, json), ec.callback);
            } else {
                long id = cmdID.incrementAndGet();
                if (ec.callback != null) {
                    if (callbackMap == null)
                        callbackMap = new HashMap<>();
                    callbackMap.put(id, ec.callback);
                }

                loadUrl(String.format("javascript:handleJava(%d, %s);", id, json));
            }
        } catch (OutOfMemoryError e) {
            UIUtils.toast(getContext(), R.string.out_of_memory_error);
        }
    }

    public void redo() {
        execCommand(new EditorCommand.Builder("redo").build());
    }

    public void undo() {
        execCommand(new EditorCommand.Builder("undo").build());
    }

    public void canRedo(JsCallback<Boolean> callback) {
        execCommand(new EditorCommand.Builder("canRedo").callback(callback).build());
    }

    public void canUndo(JsCallback<Boolean> callback) {
        execCommand(new EditorCommand.Builder("canUndo").callback(callback).build());
    }

    public boolean copy() {
        getSelectedText(new JsCallback<String>() {
            @Override
            public void onCallback(String data) {
                setPrimaryClip(ClipData.newPlainText(null, data));
                execCommand(new EditorCommand.Builder("onCopy").build());
            }
        });

        return true;
    }

    public boolean paste() {
        ClipboardManager clipboard = (ClipboardManager) getContext().
                getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData primaryClip = clipboard.getPrimaryClip();
        if (primaryClip == null) {
            return false;
        }
        ClipData.Item item = primaryClip.getItemAt(0);

        // Gets the clipboard as text.
        CharSequence pasteData = item.getText();
        if (pasteData == null)
            return false;

        execCommand(new EditorCommand.Builder("onPaste").put("text", pasteData.toString()).build());
        return true;
    }

    public boolean cut() {
        getSelectedText(new JsCallback<String>() {
            @Override
            public void onCallback(String data) {
                setPrimaryClip(ClipData.newPlainText(null, data));
                execCommand(new EditorCommand.Builder("onCut").build());
            }
        });
        return true;
    }

    private void setPrimaryClip(ClipData clip) {
        ClipboardManager clipboard = (ClipboardManager) getContext().
                getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(clip);
    }

    public void duplication() {
        execCommand(new EditorCommand.Builder("duplication").build());
    }

    public void convertWrapCharTo(String chars) {
        execCommand(new EditorCommand.Builder("convertWrapCharTo").put("value", chars).build());
    }

    public void gotoTop() {
        execCommand(new EditorCommand.Builder("gotoTop").build());
    }

    public void gotoEnd() {
        execCommand(new EditorCommand.Builder("gotoEnd").build());
    }

    public void gotoLine(int line) {
        gotoLine(line, 0);
    }

    public void gotoLine(int line, int column) {
        execCommand(new EditorCommand.Builder("gotoLine").put("line", line).put("column", column).build());
    }

    public void setReadOnly(boolean readOnly) {
        execCommand(new EditorCommand.Builder("readOnly").put("value", readOnly).build());
    }

    public void setCustomSelectionActionModeCallback(ActionMode.Callback callback) {
        this.actionModeCallback = callback;
    }

    public boolean hasSelection() {
        return selected;
    }

    public boolean selectAll() {
        execCommand(new EditorCommand.Builder("selectAll").build());
        return true;
    }

    public void forwardLocation() {
        execCommand(new EditorCommand.Builder("forwardLocation").build());
    }

    public void backLocation() {
        execCommand(new EditorCommand.Builder("backLocation").build());
    }

    public void insertOrReplaceText(CharSequence text, boolean requireSelected) {
        execCommand(new EditorCommand.Builder("insertOrReplaceText")
                .put("text", text.toString())
                .put("requireSelected", requireSelected)
                .build());
    }

    public void hideSoftInput() {
        InputMethodManager imm = (InputMethodManager) getContext().
                getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
    }

    public void showSoftInput() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
    }

    public void addTextChangedListener(OnTextChangeListener onTextChangeListener) {
        this.onTextChangeListener = onTextChangeListener;
    }

    public void setSearchResult(String text, String find, List<HashMap<String, Object>> data) {
        execCommand(new EditorCommand.Builder("setSearchResult")
                .put("text", text)
                .put("find", find)
                .put("data", data)
                .build());
    }

    public void setText(String file, CharSequence text) {
        setText(file, 0, 0, text);
    }

    public void setText(String file, int line, int column, CharSequence text) {
        if (text == null)
            return;
        if (text.length() > pref.getHighlightSizeLimit()) {
            enableHighlight(false);
        }

        execCommand(new EditorCommand.Builder("setText")
                .put("text", text.toString())
                .put("line", line)
                .put("column", column)
                .put("file", file)
                .build());
    }

    public void getText(JsCallback<String> callback) {
        execCommand(new EditorCommand.Builder("getText").callback(callback).build());
    }

    public void getSelectedText(JsCallback<String> callback) {
        callback.onCallback(getSelectedText());
    }

    public String getSelectedText() {
        return selectedText;
    }

    /**
     * @param line        begin with 0
     * @param limitLength
     * @param callback
     */
    public void getLineText(int line, int limitLength, JsCallback<String> callback) {
        execCommand(new EditorCommand.Builder("getLineText")
                .put("line", line)
                .put("limitLength", limitLength)
                .callback(callback).build());
    }


    public void enableHighlight(boolean enable) {
        execCommand(new EditorCommand.Builder("enableHighlight").put("value", enable).build());
    }

    /**
     * @param mode [null, ace/mode/js,,,]
     */
    public void setMode(String mode) {
        execCommand(new EditorCommand.Builder("setMode").put("mode", mode).build());
    }

    public String getModeName() {
        return modeName;
    }

    public void doFind(String findText, String replaceText, boolean caseSensitive,
                       boolean wholeWordOnly, boolean regex) {
        execCommand(new EditorCommand.Builder("doFind")
                .put("findText", findText)
                .put("replaceText", replaceText)
                .put("caseSensitive", caseSensitive)
                .put("wholeWordOnly", wholeWordOnly)
                .put("regex", regex)
                .build());
    }

    public boolean isTextChanged() {
        return textChanged;
    }

    public void resetTextChange() {
        textChanged = false;
        execCommand(new EditorCommand.Builder("resetTextChange").build());
    }

    public void clearSelection() {
        execCommand(new EditorCommand.Builder("clearSelection").build());
    }

    public void getCurrentPosition(JsCallback<Integer[]> callback) {
        execCommand(new EditorCommand.Builder("getCurrentPosition")
                .callback(callback).build());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        int x = (int) event.getRawX();
        int y = (int) event.getRawY();

        if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY
                && action == MotionEvent.ACTION_DOWN) {
            final TopMenuDialog menuDialog = TopMenuDialog.getInstance(getContext());
            if (mIsShow) {
                menuDialog.showLocationDialog(MenuFactory.getInstance(getContext()
                ).getMenuItemInfos(false, null, true), x, y);
                menuDialog.setOnMenuClickListener(new OnMenuClickListener() {
                    @Override
                    public void onMenuItemClick(MenuItemInfo itemInfo) {
                        Log.i("Smaster -->", "id:::" + itemInfo.getItemId());
                        switch (itemInfo.getItemId()) {
                            case 2131755024://剪切
                                cut();
                                break;
                            case 2131755023://复制
                                copy();
                                break;
                            case 2131755041://粘贴
                                paste();
                                break;
                            case 2131755052://撤销
                                undo();
                                break;
                            case 2131755043://重做
                                redo();
                                break;
                        }
                        menuDialog.dismiss();
                    }
                });
            } else {
                menuDialog.showLocationDialog(MenuFactory.getInstance(getContext()
                ).getMenuItemInfos(false, null, false), x, y);
                menuDialog.setOnMenuClickListener(new OnMenuClickListener() {
                    @Override
                    public void onMenuItemClick(MenuItemInfo itemInfo) {
                        Log.i("Smaster-->", "id:::" + itemInfo.getItemId());
                        switch (itemInfo.getItemId()) {
                            case 2131755041://粘贴
                                paste();
                                break;
                            case 2131755048://全选
                                selectAll();
                                mIsShow = true;
                                break;
                            case 2131755052://撤销
                                undo();
                                break;
                            case 2131755043://重做
                                redo();
                                break;
                        }
                        menuDialog.dismiss();
                    }
                });
            }
        }
        return super.onTouchEvent(event);
    }

    /*@Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (ic == null)
            return null;
        inputConnectionHacker = new InputConnectionHacker(ic, this);
        return inputConnectionHacker;
    }*/
}
