package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Encapsula o Window.Callback da activity do jogo (a instância real é a
 * classe do jogo criada via delegate de Instrumentation) para que o app
 * hospedeiro observe teclas e toques ANTES do jogo.
 *
 * Desempenho: o dispatch inteiro é um if de referência + uma chamada de
 * interface; nada é copiado, enfileirado ou postado. Teclas e toques não
 * interceptados seguem pelo caminho original (mesma thread, mesma ordem).
 */
public class WindowCallbackDispatcher implements Window.Callback {

    private final Activity mActivity;
    private final Window.Callback mBase;

    public WindowCallbackDispatcher(Activity activity, Window.Callback base) {
        this.mActivity = activity;
        this.mBase = base;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        GameExperienceBridge.Hook hook = GameExperienceBridge.hook;
        if (hook != null && hook.onGameKeyEvent(mActivity, event)) {
            return true;
        }
        return mBase.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        GameExperienceBridge.Hook hook = GameExperienceBridge.hook;
        if (hook != null && hook.onGameKeyEvent(mActivity, event)) {
            return true;
        }
        return mBase.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        GameExperienceBridge.Hook hook = GameExperienceBridge.hook;
        if (hook != null && hook.onGameTouchEvent(mActivity, event)) {
            return true;
        }
        return mBase.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        GameExperienceBridge.Hook hook = GameExperienceBridge.hook;
        if (hook != null && hook.onGameTouchEvent(mActivity, event)) {
            return true;
        }
        return mBase.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        GameExperienceBridge.Hook hook = GameExperienceBridge.hook;
        if (hook != null && hook.onGameGenericMotionEvent(mActivity, event)) {
            return true;
        }
        return mBase.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return mBase.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return mBase.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return mBase.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return mBase.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return mBase.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return mBase.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        mBase.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        mBase.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        mBase.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        mBase.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        mBase.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        mBase.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return mBase.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return mBase.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return mBase.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return mBase.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        mBase.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        mBase.onActionModeFinished(mode);
    }

    @Override
    public void onProvideKeyboardShortcuts(java.util.List<android.view.KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        mBase.onProvideKeyboardShortcuts(data, menu, deviceId);
    }
}
