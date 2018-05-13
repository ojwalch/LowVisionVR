package com.lowvisionvr.ojwalch.lowvisionvr;

/* This code modified from https://github.com/arekolek/OnionCamera */

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Script.LaunchOptions;
import android.renderscript.ScriptIntrinsicHistogram;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

public class Filter implements TextureView.SurfaceTextureListener {
    private int mWidth;
    private int mHeight;
    private int mSize;
    private RenderScript mRS;
    private Allocation mAllocationIn;
    private Allocation mAllocationOut;
    private ScriptC_filters mEffects;
    private boolean mHaveSurface;
    private SurfaceTexture mSurface;
    private LaunchOptions sc;
    private Surface mSurf;

    private int mode = 0;
    private int param = 0;

    public Filter(RenderScript rs) {
        mRS = rs;
        mEffects = new ScriptC_filters(mRS);
    }

    private void setupSurface() {
        if (mSurface != null) {
            if (mAllocationOut != null) {
                Surface surface = new Surface(mSurface);

                mAllocationOut.setSurface(surface);
            }
            mHaveSurface = true;
        } else {
            mHaveSurface = false;
        }
    }

    private static void ioSendOutput(Allocation allocation) {
        try {
            Allocation.class.getMethod("ioSendOutput").invoke(allocation);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }
    }

    public void setMode(int m){
        mode = m;
    }


    public void setParam(int p){
        mEffects.invoke_set_param(p);

    }

    public void reset(int width, int height) {
        if (mAllocationOut != null) {
            mAllocationOut.destroy();
        }

        mWidth = width;
        mHeight = height;
        mSize = width * height;

        Type.Builder tb;

        tb = new Type.Builder(mRS, Element.U8(mRS)).setX(mWidth).setY(mHeight*3/2);
        mAllocationIn = Allocation.createTyped(mRS, tb.create(), Allocation.USAGE_SCRIPT);

        tb = new Type.Builder(mRS, Element.RGBA_8888(mRS)).setX(mWidth).setY(mHeight);
        mAllocationOut = Allocation.createTyped(mRS, tb.create(), Allocation.USAGE_SCRIPT |
                Allocation.USAGE_IO_OUTPUT);

        setupSurface();

        mEffects.invoke_set_input(mAllocationIn);
        mEffects.invoke_set_height(mHeight);
        mEffects.invoke_set_width(mWidth);

        sc = new LaunchOptions();
        sc.setX(2, mWidth - 3);
        sc.setY(2, mHeight - 3);

    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }


    // Run the image processing
    // TODO: Replace individual convolution files with a single function
    public void execute(byte[] yuv) {
        if (mHaveSurface) {
            mAllocationIn.copyFrom(yuv);

            switch(mode){
                case 0:
                    mEffects.forEach_getValueAtPoint(mAllocationOut,sc);
                    break;
                case 1:
                    mEffects.forEach_edgeKernel(mAllocationOut,sc);
                    break;
                case 2:
                    mEffects.forEach_centerKernel(mAllocationOut,sc);
                    break;
                case 3:
                    mEffects.forEach_warpKernel(mAllocationOut,sc);
                    break;
                case 4:
                    mEffects.forEach_peripheryKernel(mAllocationOut,sc);
                    break;
                default:
                    mEffects.forEach_getValueAtPoint(mAllocationOut,sc);

            }

            // hidden API
            mAllocationOut.ioSend();
            //  ioSendOutput(mAllocationOut);
        }
    }


    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mSurface = surface;
        setupSurface();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        mSurface = surface;
        setupSurface();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mSurface = null;
        setupSurface();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private static void setSurfaceTexture(Allocation allocation, SurfaceTexture surface) {
        try {
            Allocation.class.getMethod("setSurfaceTexture",
                    SurfaceTexture.class).invoke(allocation, surface);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }
    }

}
