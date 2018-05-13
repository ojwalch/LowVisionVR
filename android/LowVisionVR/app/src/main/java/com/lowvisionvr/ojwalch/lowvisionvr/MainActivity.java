package com.lowvisionvr.ojwalch.lowvisionvr;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.renderscript.RenderScript;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity
        implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {

    private static final String TAG = "LowVisionVR";

    private TextureView preview_left;
    private TextureView preview_right;

    private SurfaceTexture surface;
    private int surfaceWidth;
    private int surfaceHeight;

    private CameraWizard wizard;
    private Camera camera;
    private Size cameraSize;

    private TextureView overlay_left;
    private TextureView overlay_right;

    private static final int STATE_OFF = 0;
    private static final int STATE_PREVIEW = 1;
    private int state = STATE_OFF;
    private boolean isProcessing = false;

    private float lastX = 0;
    private float lastY = 0;

    // Local variables for amount of zoom
    int mZoomFactor = 0;
    int mMaxZoomFactor = 0;
    private int mUpButtonClicks = 0;
    private int mNumLevels = 5;


    private int mDownButtonClicks = 0;
    private int mNumOptions = 10;
    private int mNumParamOptions = 3;


    // TODO: Find a more efficient way than this to do left and right processing
    private Filter filter_left;
    private Filter filter_right;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getGameControllerIds();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);


        preview_left = (TextureView) findViewById(R.id.preview_left);
        overlay_left = (TextureView) findViewById(R.id.overlay_left);
        preview_right = (TextureView) findViewById(R.id.preview_right);
        overlay_right = (TextureView) findViewById(R.id.overlay_right);

        filter_left = new Filter(RenderScript.create(this));
        filter_right = new Filter(RenderScript.create(this));

        preview_left.setSurfaceTextureListener(this);
        preview_right.setSurfaceTextureListener(this);

        overlay_left.setSurfaceTextureListener(filter_left);
        overlay_right.setSurfaceTextureListener(filter_right);

        preview_left.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                //   filter.toggleBlending();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        setUpCamera();
    }

    @Override
    public void onPause() {
        super.onPause();
        shutdownCamera();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture s,
                                          int width, int height) {
        surface = s;
        surfaceWidth = width;
        surfaceHeight = height;
        if (camera != null) {
            startPreview();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Invoked every time there's a new Camera preview frame
    }

    private void setUpCamera() {
        shutdownCamera();

        wizard = CameraWizard.getBackFacingCamera();
        wizard.setOrientation(getWindowManager().getDefaultDisplay());
        wizard.setAutofocus();
        wizard.setFps();

        camera = wizard.getCamera();
        // Zoom all the way out; get max zoom
        mMaxZoomFactor = camera.getParameters().getMaxZoom();


        if (surface != null) {
            startPreview();
        }
    }

    private void shutdownCamera() {
        if (camera != null) {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            camera.release();
            camera = null;
            state = STATE_OFF;
        }
    }

    private void startPreview() {
        if (state != STATE_OFF) {
            // Stop for a while to drain callbacks
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            state = STATE_OFF;
            Handler h = new Handler();
            Runnable mDelayedPreview = new Runnable() {
                @Override
                public void run() {
                    startPreview();
                }
            };
            h.postDelayed(mDelayedPreview, 300);
            return;
        }
        state = STATE_PREVIEW;

        cameraSize = wizard.setPreviewSize(surfaceWidth, surfaceHeight);

        Matrix transform = new Matrix();
        float widthRatio = cameraSize.width / (float) surfaceWidth;
        float heightRatio = cameraSize.height / (float) surfaceHeight;

        transform.setScale(1, heightRatio / widthRatio);
        transform.postTranslate(0,
                surfaceHeight * (1 - heightRatio / widthRatio) / 2);

        preview_left.setTransform(transform);
        overlay_left.setTransform(transform);

        preview_right.setTransform(transform);
        overlay_right.setTransform(transform);

        camera.setPreviewCallbackWithBuffer(this);
        int expectedBytes = cameraSize.width * cameraSize.height *
                ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;

        Log.d("Expected Bytes", "" + expectedBytes);

        for (int i = 0; i < 8; i++) {
            camera.addCallbackBuffer(new byte[expectedBytes]);
        }

        try {
            camera.setPreviewTexture(surface);
            camera.startPreview();
        } catch (Exception e) {
            // Something bad happened
            Log.e(TAG, "Unable to start up preview");
        }

    }

    private class ProcessPreviewDataTask extends AsyncTask<byte[], Void, Boolean> {
        @Override
        protected Boolean doInBackground(byte[]... datas) {
            byte[] data = datas[0];

            filter_left.execute(data);
            camera.addCallbackBuffer(data);


            filter_right.execute(data);
            camera.addCallbackBuffer(data);

            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            isProcessing = false;
            overlay_left.invalidate();
            overlay_right.invalidate();

        }

    }

    @Override
    public void onPreviewFrame(byte[] data, Camera c) {
        if (isProcessing || state != STATE_PREVIEW) {
            camera.addCallbackBuffer(data);
            return;
        }
        if (data == null) {
            return;
        }
        isProcessing = true;

        if (filter_left == null
                || cameraSize.width != filter_left.getWidth()
                || cameraSize.height != filter_left.getHeight()) {

            filter_left.reset(cameraSize.width, cameraSize.height);
        }

        if (filter_right == null
                || cameraSize.width != filter_right.getWidth()
                || cameraSize.height != filter_right.getHeight()) {

            filter_right.reset(cameraSize.width, cameraSize.height);
        }

        Log.d("Size of data", "" + data.length);
        new ProcessPreviewDataTask().execute(data);
    }


    // TODO: Clean up game controller ID code
    public ArrayList getGameControllerIds() {
        ArrayList gameControllerDeviceIds = new ArrayList();
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice dev = InputDevice.getDevice(deviceId);
            int sources = dev.getSources();

            // Verify that the device has gamepad buttons, control sticks, or both.
            if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                    || ((sources & InputDevice.SOURCE_JOYSTICK)
                    == InputDevice.SOURCE_JOYSTICK)) {
                // This device is a game controller. Store its device ID.
                if (!gameControllerDeviceIds.contains(deviceId)) {
                    gameControllerDeviceIds.add(deviceId);
                    Log.d("Device",""+deviceId);
                }
            }
        }
        return gameControllerDeviceIds;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {

        // Check that the event came from a game controller
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) ==
                InputDevice.SOURCE_JOYSTICK &&
                event.getAction() == MotionEvent.ACTION_MOVE) {

            // Process all historical movement samples in the batch
            final int historySize = event.getHistorySize();

            // Process the movements starting from the
            // earliest historical position in the batch
            for (int i = 0; i < historySize; i++) {
                // Process the event at historical position i
                processJoystickInput(event, i);
            }

            // Process the current movement sample in the batch (position -1)
            processJoystickInput(event, -1);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }



    private void processJoystickInput(MotionEvent event,
                                      int historyPos) {

        InputDevice mInputDevice = event.getDevice();


        float newX = Math.round(event.getAxisValue(MotionEvent.AXIS_X));
        float newY = Math.round(event.getAxisValue(MotionEvent.AXIS_Y));

        if(newX != lastX || newY != lastY) { // if something has changed
            lastX = newX;
            lastY = newY;
            if(newY > 0 || newX > 0) {
                mDownButtonClicks--;
                // mDownButtonClicks =  (mDownButtonClicks + mNumParamOptions) % mNumParamOptions;
                if(mDownButtonClicks >= mNumParamOptions){
                    mDownButtonClicks = mNumParamOptions - 1;
                }
                if(mDownButtonClicks < 0){
                    mDownButtonClicks = 0;
                }

                int mCurrentClicks = mDownButtonClicks;

                filter_left.setParam(mCurrentClicks);
                filter_right.setParam(mCurrentClicks);

                int upButtonValue = mUpButtonClicks % mNumLevels;

                if (upButtonValue == 0) { // Uncomment to only allow in normal mode
                    Camera.Parameters cameraParameters = camera.getParameters();
                    float zoomFraction = (float)mCurrentClicks/(float)mNumParamOptions;

                    cameraParameters.setZoom((int)(zoomFraction * (float) mMaxZoomFactor));
                    camera.setParameters(cameraParameters);
                }
            }
            if(newY < 0 || newX < 0){
                mDownButtonClicks++;
                // mDownButtonClicks =  (mDownButtonClicks + mNumParamOptions) % mNumParamOptions;
                if(mDownButtonClicks >= mNumParamOptions){
                    mDownButtonClicks = mNumParamOptions - 1;
                }
                if(mDownButtonClicks < 0){
                    mDownButtonClicks = 0;
                }

                int mCurrentClicks = mDownButtonClicks;

                filter_left.setParam(mCurrentClicks);
                filter_right.setParam(mCurrentClicks);

                int upButtonValue = mUpButtonClicks % mNumLevels;

                if (upButtonValue == 0) { // Uncomment to only allow in normal mode
                    Camera.Parameters cameraParameters = camera.getParameters();
                    float zoomFraction = (float)mCurrentClicks/(float)mNumParamOptions;
                    cameraParameters.setZoom((int) (zoomFraction * (float) mMaxZoomFactor));
                    camera.setParameters(cameraParameters);
                }

            }

        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean handled = false;
        int upButtonValue = 0;

        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD)
                == InputDevice.SOURCE_GAMEPAD) {
            if (event.getRepeatCount() == 0) {

                switch (keyCode) {
                    case KeyEvent.KEYCODE_BUTTON_A:
                        Log.d("Key","A");

                        mUpButtonClicks++;
                        mUpButtonClicks = mUpButtonClicks % mNumLevels;

                        upButtonValue =  mUpButtonClicks % mNumLevels;

                        if(upButtonValue == 0){
                             Toast.makeText(this, "Normal Mode", Toast.LENGTH_SHORT).show();
                             filter_left.setMode(0);
                             filter_right.setMode(0);
                            Camera.Parameters cameraParameters = camera.getParameters();
                            cameraParameters.setZoom(0);
                            camera.setParameters(cameraParameters);
                            mNumParamOptions = 10;

                        }else if (upButtonValue == 1){
                             //  Toast.makeText(this,"Edge-enhanced Mode",Toast.LENGTH_SHORT).show();
                            filter_left.setMode(1);
                            filter_right.setMode(1);

                            Camera.Parameters cameraParameters = camera.getParameters();
                            cameraParameters.setZoom(0);
                            mNumParamOptions = 3;
                            camera.setParameters(cameraParameters);
                        }else if (upButtonValue == 2){
                            //  Toast.makeText(this,"Warp center Mode",Toast.LENGTH_SHORT).show();
                            mNumParamOptions = 3;

                            filter_left.setMode(2);
                            filter_right.setMode(2);

                        }else if (upButtonValue == 3){
                            //  Toast.makeText(this,"Periphery Remove Mode",Toast.LENGTH_SHORT).show();
                            mNumParamOptions = 3;

                            filter_left.setMode(3);
                            filter_right.setMode(3);

                        }else if (upButtonValue == 4){
                            //  Toast.makeText(this,"Periphery Remove Mode",Toast.LENGTH_SHORT).show();
                            mNumParamOptions = 3;

                            filter_left.setMode(4);
                            filter_right.setMode(4);

                        }
                        return true;

                    default:
                        return true;
                        //    return super.dispatchKeyEvent(event);
                }
            }

            if (handled) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // Handle volume control inputs
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int upButtonValue = 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                mUpButtonClicks++;
                upButtonValue = ((int)Math.floor(mUpButtonClicks /2)) % mNumLevels;

                if(upButtonValue == 0){
                    // Toast.makeText(this, "Normal Mode", Toast.LENGTH_SHORT).show();
                    filter_left.setMode(0);
                    filter_right.setMode(0);

                    Camera.Parameters cameraParameters = camera.getParameters();
                    cameraParameters.setZoom(0);
                    camera.setParameters(cameraParameters);
                }else if (upButtonValue == 1){
                    //   Toast.makeText(this,"Edge-enhanced Mode",Toast.LENGTH_SHORT).show();
                    filter_left.setMode(1);
                    filter_right.setMode(1);

                    Camera.Parameters cameraParameters = camera.getParameters();
                    cameraParameters.setZoom(0);
                    camera.setParameters(cameraParameters);
                }else if (upButtonValue == 2){
                    //  Toast.makeText(this,"Warp center Mode",Toast.LENGTH_SHORT).show();
                    filter_left.setMode(2);
                    filter_right.setMode(2);

                }else if (upButtonValue == 3){
                    //  Toast.makeText(this,"Periphery Remove Mode",Toast.LENGTH_SHORT).show();
                    filter_left.setMode(3);
                    filter_right.setMode(3);

                }else if (upButtonValue == 4){
                    //  Toast.makeText(this,"Periphery Remove Mode",Toast.LENGTH_SHORT).show();
                    filter_left.setMode(4);
                    filter_right.setMode(4);

                }
                return true;

            case KeyEvent.KEYCODE_VOLUME_DOWN:
                mDownButtonClicks++;
                filter_left.setParam(mDownButtonClicks % mNumParamOptions);
                filter_right.setParam(mDownButtonClicks % mNumParamOptions);

                int mCurrentClicks = mDownButtonClicks % mNumOptions;
                upButtonValue = ((int)Math.floor(mUpButtonClicks /2)) % mNumLevels;

                if(upButtonValue == 0){ // Uncomment to only allow in normal mode
                    Camera.Parameters cameraParameters = camera.getParameters();
                    cameraParameters.setZoom((int) ( (float)(mCurrentClicks + 1 ) * (float)mMaxZoomFactor/((float)mNumOptions)));
                    camera.setParameters(cameraParameters);
                }

                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
