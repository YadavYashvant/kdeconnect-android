/*
 * SPDX-FileCopyrightText: 2014 Ahmed I. Khalil <ahmedibrahimkhali@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.Plugins.MousePadPlugin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.kde.kdeconnect.KdeConnect;
import org.kde.kdeconnect.UserInterface.PluginSettingsActivity;
import org.kde.kdeconnect_tp.R;

import java.util.Objects;

public class MousePadActivity
        extends AppCompatActivity
        implements GestureDetector.OnGestureListener,
        GestureDetector.OnDoubleTapListener,
        MousePadGestureDetector.OnGestureListener,
        SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private String deviceId;

    private final static float MinDistanceToSendScroll = 2.5f; // touch gesture scroll
    private final static float MinDistanceToSendGenericScroll = 0.1f; // real mouse scroll wheel event
    private final static float StandardDpi = 240.0f; // = hdpi

    private float mPrevX;
    private float mPrevY;
    private float mCurrentX;
    private float mCurrentY;
    private float mCurrentSensitivity;
    private float displayDpiMultiplier;
    private int scrollDirection = 1;
    private double scrollCoefficient = 1.0;
    private boolean allowGyro = false;
    private boolean gyroEnabled = false;
    private int gyroscopeSensitivity = 100;
    private boolean isScrolling = false;
    private float accumulatedDistanceY = 0;

    private GestureDetector mDetector;
    private SensorManager mSensorManager;
    private MousePadGestureDetector mMousePadGestureDetector;
    private PointerAccelerationProfile mPointerAccelerationProfile;

    private PointerAccelerationProfile.MouseDelta mouseDelta; // to be reused on every touch move event

    private KeyListenerView keyListenerView;

    private SharedPreferences prefs = null;

    private boolean prefsApplied = false;

    enum ClickType {
        LEFT, RIGHT, MIDDLE, NONE;

        static ClickType fromString(String s) {
            switch (s) {
                case "left":
                    return LEFT;
                case "right":
                    return RIGHT;
                case "middle":
                    return MIDDLE;
                default:
                    return NONE;
            }
        }
    }

    private ClickType singleTapAction, doubleTapAction, tripleTapAction;

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] values = event.values;

        float X = -values[2] * 70 * (gyroscopeSensitivity/100.0f);
        float Y = -values[0] * 70 * (gyroscopeSensitivity/100.0f);

        if (X < 0.25 && X > -0.25) {
            X = 0;
        } else {
            X = X * (gyroscopeSensitivity/100.0f);
        }

        if (Y < 0.25 && Y > -0.25) {
            Y = 0;
        } else {
            Y = Y * (gyroscopeSensitivity/100.0f);
        }

        final float nX = X;
        final float nY = Y;

        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return;
        }
        plugin.sendMouseDelta(nX, nY);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mousepad);

        setSupportActionBar(findViewById(R.id.toolbar));
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        findViewById(R.id.mouse_click_left).setOnClickListener(v -> sendLeftClick());
        findViewById(R.id.mouse_click_middle).setOnClickListener(v -> sendMiddleClick());
        findViewById(R.id.mouse_click_right).setOnClickListener(v -> sendRightClick());

        deviceId = getIntent().getStringExtra("deviceId");

        getWindow().getDecorView().setHapticFeedbackEnabled(true);

        mDetector = new GestureDetector(this, this);
        mMousePadGestureDetector = new MousePadGestureDetector(this);
        mDetector.setOnDoubleTapListener(this);
        mSensorManager = ContextCompat.getSystemService(this, SensorManager.class);

        keyListenerView = findViewById(R.id.keyListener);
        keyListenerView.setDeviceId(deviceId);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.registerOnSharedPreferenceChangeListener(this);

        applyPrefs();

        //Technically xdpi and ydpi should be handled separately,
        //but since ydpi is usually almost equal to xdpi, only xdpi is used for the multiplier.
        displayDpiMultiplier = StandardDpi / getResources().getDisplayMetrics().xdpi;

        final View decorView = getWindow().getDecorView();
        decorView.setOnSystemUiVisibilityChangeListener(visibility -> {
            if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {

                int fullscreenType = 0;

                fullscreenType |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                fullscreenType |= View.SYSTEM_UI_FLAG_FULLSCREEN;
                fullscreenType |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

                getWindow().getDecorView().setSystemUiVisibility(fullscreenType);
            }
        });
    }

    @Override
    protected void onResume() {
        applyPrefs();

        if (allowGyro && !gyroEnabled) {
            mSensorManager.registerListener(this, mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_GAME);
            gyroEnabled = true;
        }

        invalidateMenu();

        super.onResume();
    }

    @Override
    protected void onPause() {
        if (gyroEnabled) {
            mSensorManager.unregisterListener(this);
            gyroEnabled = false;
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (gyroEnabled) {
            mSensorManager.unregisterListener(this);
            gyroEnabled = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_mousepad, menu);

        boolean mouseButtonsEnabled = prefs
                .getBoolean(getString(R.string.mousepad_mouse_buttons_enabled_pref), true);
        menu.findItem(R.id.menu_right_click).setVisible(!mouseButtonsEnabled);
        menu.findItem(R.id.menu_middle_click).setVisible(!mouseButtonsEnabled);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_right_click) {
            sendRightClick();
            return true;
        } else if (id == R.id.menu_middle_click) {
            sendMiddleClick();
            return true;
        } else if (id == R.id.menu_open_mousepad_settings) {
            Intent intent = new Intent(this, PluginSettingsActivity.class)
                    .putExtra(PluginSettingsActivity.EXTRA_DEVICE_ID, deviceId)
                    .putExtra(PluginSettingsActivity.EXTRA_PLUGIN_KEY, MousePadPlugin.class.getSimpleName());
            startActivity(intent);
            return true;
        } else if (id == R.id.menu_show_keyboard) {
            MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
            if (plugin == null) {
                finish();
                return true;
            }
            if (plugin.isKeyboardEnabled()) {
                showKeyboard();
            } else {
                Toast toast = Toast.makeText(this, R.string.mousepad_keyboard_input_not_supported, Toast.LENGTH_SHORT);
                toast.show();
            }
            return true;
        } else if (id == R.id.menu_open_compose_send) {
            MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
            if (plugin == null) {
                finish();
                return true;
            }
            if (plugin.isKeyboardEnabled()) {
                showCompose();
            } else {
                Toast toast = Toast.makeText(this, R.string.mousepad_keyboard_input_not_supported, Toast.LENGTH_SHORT);
                toast.show();
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mMousePadGestureDetector.onTouchEvent(event)) {
            return true;
        }
        if (mDetector.onTouchEvent(event)) {
            return true;
        }

        int actionType = event.getAction();

        if (isScrolling) {
            if (actionType == MotionEvent.ACTION_UP) {
                isScrolling = false;
            } else {
                return false;

            }
        }

        switch (actionType) {
            case MotionEvent.ACTION_DOWN:
                mPrevX = event.getX();
                mPrevY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                mCurrentX = event.getX();
                mCurrentY = event.getY();

                MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
                if (plugin == null) {
                    finish();
                    return true;
                }

                float deltaX = (mCurrentX - mPrevX) * displayDpiMultiplier * mCurrentSensitivity;
                float deltaY = (mCurrentY - mPrevY) * displayDpiMultiplier * mCurrentSensitivity;

                // Run the mouse delta through the pointer acceleration profile
                mPointerAccelerationProfile.touchMoved(deltaX, deltaY, event.getEventTime());
                mouseDelta = mPointerAccelerationProfile.commitAcceleratedMouseDelta(mouseDelta);

                plugin.sendMouseDelta(mouseDelta.x, mouseDelta.y);

                mPrevX = mCurrentX;
                mPrevY = mCurrentY;

                break;
        }
        return true;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent e) {
        //From GestureDetector, left empty
    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_SCROLL) {
            final float distanceY = e.getAxisValue(MotionEvent.AXIS_VSCROLL);

            accumulatedDistanceY += distanceY;

            if (accumulatedDistanceY > MinDistanceToSendGenericScroll || accumulatedDistanceY < -MinDistanceToSendGenericScroll) {
                sendScroll(accumulatedDistanceY);
                accumulatedDistanceY = 0;
            }
        }

        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, final float distanceX, final float distanceY) {
        // If only one thumb is used then cancel the scroll gesture
        if (e2.getPointerCount() <= 1) {
            return false;
        }

        isScrolling = true;

        accumulatedDistanceY += distanceY * scrollCoefficient;
        if (accumulatedDistanceY > MinDistanceToSendScroll || accumulatedDistanceY < -MinDistanceToSendScroll) {
            sendScroll(scrollDirection * accumulatedDistanceY);

            accumulatedDistanceY = 0;
        }

        return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return;
        }
        plugin.sendSingleHold();
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        switch (singleTapAction) {
            case LEFT:
                sendLeftClick();
                break;
            case RIGHT:
                sendRightClick();
                break;
            case MIDDLE:
                sendMiddleClick();
                break;
            default:
        }
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return true;
        }
        plugin.sendDoubleClick();
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onTripleFingerTap(MotionEvent ev) {
        switch (tripleTapAction) {
            case LEFT:
                sendLeftClick();
                break;
            case RIGHT:
                sendRightClick();
                break;
            case MIDDLE:
                sendMiddleClick();
                break;
            default:
        }
        return true;
    }

    @Override
    public boolean onDoubleFingerTap(MotionEvent ev) {
        switch (doubleTapAction) {
            case LEFT:
                sendLeftClick();
                break;
            case RIGHT:
                sendRightClick();
                break;
            case MIDDLE:
                sendMiddleClick();
                break;
            default:
        }
        return true;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (prefsApplied) prefsApplied = false;
    }


    private void sendLeftClick() {
        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return;
        }
        plugin.sendLeftClick();
    }

    private void sendMiddleClick() {
        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return;
        }
        plugin.sendMiddleClick();
    }

    private void sendRightClick() {
        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return;
        }
        plugin.sendRightClick();
    }

    private void sendScroll(final float y) {
        MousePadPlugin plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, MousePadPlugin.class);
        if (plugin == null) {
            finish();
            return;
        }
        plugin.sendScroll(0, y);
    }

    private void showKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(this, InputMethodManager.class);
        keyListenerView.requestFocus();
        imm.toggleSoftInputFromWindow(keyListenerView.getWindowToken(), 0, 0);
    }

    private void showCompose() {
        Intent intent = new Intent(this, ComposeSendActivity.class);
        intent.putExtra("org.kde.kdeconnect.Plugins.MousePadPlugin.deviceId", deviceId);
        startActivity(intent);
    }

    private void applyPrefs() {
        if (prefsApplied) return;

        if (prefs.getBoolean(getString(R.string.mousepad_scroll_direction), false)) {
            scrollDirection = -1;
        } else {
            scrollDirection = 1;
        }

        int scrollSensitivity = prefs.getInt(getString(R.string.mousepad_scroll_sensitivity), 100);
        if (scrollSensitivity == 0) scrollSensitivity = 1;
        scrollCoefficient = Math.pow((scrollSensitivity / 100f), 1.5);

        allowGyro = isGyroSensorAvailable() && prefs.getBoolean(getString(R.string.gyro_mouse_enabled), false);
        if (allowGyro) gyroscopeSensitivity = prefs.getInt(getString(R.string.gyro_mouse_sensitivity), 100);

        String singleTapSetting = prefs.getString(getString(R.string.mousepad_single_tap_key),
                getString(R.string.mousepad_default_single));
        String doubleTapSetting = prefs.getString(getString(R.string.mousepad_double_tap_key),
                getString(R.string.mousepad_default_double));
        String tripleTapSetting = prefs.getString(getString(R.string.mousepad_triple_tap_key),
                getString(R.string.mousepad_default_triple));
        String sensitivitySetting = prefs.getString(getString(R.string.mousepad_sensitivity_key),
                getString(R.string.mousepad_default_sensitivity));

        String accelerationProfileName = prefs.getString(getString(R.string.mousepad_acceleration_profile_key),
                getString(R.string.mousepad_default_acceleration_profile));

        mPointerAccelerationProfile = PointerAccelerationProfileFactory.getProfileWithName(accelerationProfileName);

        singleTapAction = ClickType.fromString(singleTapSetting);
        doubleTapAction = ClickType.fromString(doubleTapSetting);
        tripleTapAction = ClickType.fromString(tripleTapSetting);

        switch (sensitivitySetting) {
            case "slowest":
                mCurrentSensitivity = 0.2f;
                break;
            case "aboveSlowest":
                mCurrentSensitivity = 0.5f;
                break;
            case "default":
                mCurrentSensitivity = 1.0f;
                break;
            case "aboveDefault":
                mCurrentSensitivity = 1.5f;
                break;
            case "fastest":
                mCurrentSensitivity = 2.0f;
                break;
            default:
                mCurrentSensitivity = 1.0f;
                return;
        }

        if (prefs.getBoolean(getString(R.string.mousepad_mouse_buttons_enabled_pref), true)) {
            findViewById(R.id.mouse_buttons).setVisibility(View.VISIBLE);
        } else {
            findViewById(R.id.mouse_buttons).setVisibility(View.GONE);
        }

        prefsApplied = true;
    }

    private boolean isGyroSensorAvailable() {
        return mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    @Override
    public boolean onSupportNavigateUp() {
        super.onBackPressed();
        return true;
    }
}

