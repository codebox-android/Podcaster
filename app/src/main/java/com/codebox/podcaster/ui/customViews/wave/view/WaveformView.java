/*
 * Copyright (C) 2008 Google Inc.
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

package com.codebox.podcaster.ui.customViews.wave.view;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.codebox.podcaster.R;
import com.codebox.podcaster.storage.db.app.segment.Flag;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;

/**
 * WaveformView is an Android view that displays a visual representation
 * of an audio waveform.  It retrieves the frame gains from a CheapSoundFile
 * object and recomputes the shape contour at several zoom levels.
 * <p>
 * This class doesn't handle selection or any of the touch interactions
 * directly, so it exposes a listener interface.  The class that embeds
 * this view should add itself as a listener and make the view scroll
 * and respond to other events appropriately.
 * <p>
 * WaveformView doesn't actually handle selection, but it will just display
 * the selected part of the waveform in a different color.
 */
public class WaveformView extends View {
    private static final String TAG = "WaveformView";
    private final Bitmap mFlagBitMap;
    private final int flagsHeight;


    // Colors
    private Paint mGridPaint;
    private Paint mSelectedLinePaint;
    private Paint mUnselectedLinePaint;
    private Paint mUnselectedBkgndLinePaint;
    private Paint mBorderLinePaint;
    private Paint mPlaybackLinePaint;
    private Paint mTimecodePaint;
    private Paint mFlagStrokePaint;
    private Paint mTopGradientPaint;
    private Paint mBottomGradientPaint;
    private Paint mSelectionStartEndPaint;


    private SoundFile mSoundFile;
    private int[] mLenByZoomLevel;
    private double[][] mValuesByZoomLevel;
    private double[] mZoomFactorByZoomLevel;
    private int[] mHeightsAtThisZoomLevel;
    private int mZoomLevel;
    private int mNumZoomLevels;
    private int mSampleRate;
    private int mSamplesPerFrame;
    private int mOffset;
    private int mSelectionStart;
    private int mSelectionEnd;
    private int mPlaybackPos;
    private float mDensity;
    private int textSize;
    private float mInitialScaleSpan;
    private WaveformListener mListener;
    private GestureDetector mGestureDetector;
    private ScaleGestureDetector mScaleGestureDetector;
    private boolean mInitialized;
    private HashSet<Integer> flags;
    private float topBottomGradientOffset;

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // We don't want keys, the markers get these
        setFocusable(false);

        Resources res = getResources();
        mGridPaint = new Paint();
        mGridPaint.setAntiAlias(false);
        mGridPaint.setColor(res.getColor(R.color.grid_line));

        mSelectedLinePaint = new Paint();
        mSelectedLinePaint.setAntiAlias(false);
        mSelectedLinePaint.setColor(res.getColor(R.color.waveform_selected));

        mUnselectedLinePaint = new Paint();
        mUnselectedLinePaint.setAntiAlias(false);
        mUnselectedLinePaint.setColor(res.getColor(R.color.waveform_unselected));
        mUnselectedBkgndLinePaint = new Paint();
        mUnselectedBkgndLinePaint.setAntiAlias(false);
        mUnselectedBkgndLinePaint.setColor(res.getColor(R.color.waveform_unselected_bkgnd_overlay));
        mBorderLinePaint = new Paint();
        mBorderLinePaint.setAntiAlias(true);
        mBorderLinePaint.setStrokeWidth(1.5f);
        mBorderLinePaint.setPathEffect(new DashPathEffect(new float[]{3.0f, 2.0f}, 0.0f));
        mBorderLinePaint.setColor(res.getColor(R.color.selection_border));
        mPlaybackLinePaint = new Paint();
        mPlaybackLinePaint.setAntiAlias(false);
        mPlaybackLinePaint.setColor(res.getColor(R.color.playback_indicator));

        mTimecodePaint = new Paint();
        mTimecodePaint.setAntiAlias(true);
        mTimecodePaint.setColor(res.getColor(R.color.timecode));
        mTimecodePaint.setShadowLayer(2, 1, 1, res.getColor(R.color.timecode_shadow));

        mFlagStrokePaint = new Paint();
        mFlagStrokePaint.setColor(res.getColor(R.color.colorOnBackground));
        mFlagStrokePaint.setStrokeWidth(1);
        mFlagStrokePaint.setStyle(Paint.Style.STROKE);
        mFlagStrokePaint.setPathEffect(new DashPathEffect(new float[]{10f, 20f}, 0f));


        mFlagBitMap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_flag_grey);

        mSelectionStartEndPaint = new Paint();
        mSelectionStartEndPaint.setColor(res.getColor(R.color.selection_start_end_color));
        mSelectionStartEndPaint.setStrokeWidth(5);
        mSelectionStartEndPaint.setStyle(Paint.Style.STROKE);
        mSelectionStartEndPaint.setPathEffect(new DashPathEffect(new float[]{20f, 10f}, 0f));


        mGestureDetector = new GestureDetector(
                context,
                new GestureDetector.SimpleOnGestureListener() {
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        mListener.waveformFling(vx);
                        return true;
                    }
                }
        );

        mScaleGestureDetector = new ScaleGestureDetector(
                context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        Log.v("Ringdroid", "ScaleBegin " + d.getCurrentSpanX());
                        mInitialScaleSpan = Math.abs(d.getCurrentSpanX());
                        return true;
                    }

                    public boolean onScale(ScaleGestureDetector d) {
                        float scale = Math.abs(d.getCurrentSpanX());
                        Log.v("Ringdroid", "Scale " + (scale - mInitialScaleSpan));
                        if (scale - mInitialScaleSpan > 40) {
                            mListener.waveformZoomIn();
                            mInitialScaleSpan = scale;
                        }
                        if (scale - mInitialScaleSpan < -40) {
                            mListener.waveformZoomOut();
                            mInitialScaleSpan = scale;
                        }
                        return true;
                    }

                    public void onScaleEnd(ScaleGestureDetector d) {
                        Log.v("Ringdroid", "ScaleEnd " + d.getCurrentSpanX());
                    }
                }
        );

        mSoundFile = null;
        mLenByZoomLevel = null;
        mValuesByZoomLevel = null;
        mHeightsAtThisZoomLevel = null;
        mOffset = 0;
        mPlaybackPos = -1;
        mSelectionStart = 0;
        mSelectionEnd = 0;
        mDensity = 1.0f;
        textSize = 12;
        topBottomGradientOffset = 0.2f;
        flagsHeight = (int) (mFlagBitMap.getHeight() * 2);
        mInitialized = false;
    }


    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScaleGestureDetector.onTouchEvent(event);
        if (mGestureDetector.onTouchEvent(event)) {
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mListener.waveformTouchStart(event.getX());
                break;
            case MotionEvent.ACTION_MOVE:
                mListener.waveformTouchMove(event.getX());
                break;
            case MotionEvent.ACTION_UP:
                mListener.waveformTouchEnd();
                break;
        }
        return true;
    }

    public boolean hasSoundFile() {
        return mSoundFile != null;
    }

    public void setSoundFile(SoundFile soundFile) {
        mSoundFile = soundFile;
        mSampleRate = mSoundFile.getSampleRate();
        mSamplesPerFrame = mSoundFile.getSamplesPerFrame();
        computeDoublesForAllZoomLevels();
        mHeightsAtThisZoomLevel = null;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    public int getZoomLevel() {
        return mZoomLevel;
    }

    public void setZoomLevel(int zoomLevel) {
        while (mZoomLevel > zoomLevel) {
            zoomIn();
        }
        while (mZoomLevel < zoomLevel) {
            zoomOut();
        }
    }

    public boolean canZoomIn() {
        return (mZoomLevel > 0);
    }

    public void zoomIn() {
        if (canZoomIn()) {
            mZoomLevel--;
            mSelectionStart *= 2;
            mSelectionEnd *= 2;
            mHeightsAtThisZoomLevel = null;
            int offsetCenter = mOffset + getMeasuredWidth() / 2;
            offsetCenter *= 2;
            mOffset = offsetCenter - getMeasuredWidth() / 2;
            if (mOffset < 0)
                mOffset = 0;
            invalidate();
        }
    }

    public boolean canZoomOut() {
        return (mZoomLevel < mNumZoomLevels - 1);
    }

    public void zoomOut() {
        if (canZoomOut()) {
            mZoomLevel++;
            mSelectionStart /= 2;
            mSelectionEnd /= 2;
            int offsetCenter = mOffset + getMeasuredWidth() / 2;
            offsetCenter /= 2;
            mOffset = offsetCenter - getMeasuredWidth() / 2;
            if (mOffset < 0)
                mOffset = 0;
            mHeightsAtThisZoomLevel = null;
            invalidate();
        }
    }

    public int maxPos() {
        return mLenByZoomLevel[mZoomLevel];
    }

    public int secondsToFrames(double seconds) {
        return (int) (1.0 * seconds * mSampleRate / mSamplesPerFrame + 0.5);
    }

    public int secondsToPixels(double seconds) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (int) (z * seconds * mSampleRate / mSamplesPerFrame + 0.5);
    }

    public double pixelsToSeconds(int pixels) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (pixels * (double) mSamplesPerFrame / (mSampleRate * z));
    }

    public int millisecsToPixels(int msecs) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (int) ((msecs * 1.0 * mSampleRate * z) /
                (1000.0 * mSamplesPerFrame) + 0.5);
    }

    public int pixelsToMillisecs(int pixels) {
        double z = mZoomFactorByZoomLevel[mZoomLevel];
        return (int) (pixels * (1000.0 * mSamplesPerFrame) /
                (mSampleRate * z) + 0.5);
    }

    public void setParameters(int start, int end, int offset) {
        mSelectionStart = start;
        mSelectionEnd = end;
        mOffset = offset;
    }

    public int getStart() {
        return mSelectionStart;
    }

    public int getEnd() {
        return mSelectionEnd;
    }

    public int getOffset() {
        return mOffset;
    }

    public void setPlayback(int pos) {
        mPlaybackPos = pos;
    }

    public void setListener(WaveformListener listener) {
        mListener = listener;
    }

    public void setFlags(@NotNull List<Flag> flagList) {


        this.flags = new HashSet<>();
        for (Flag flag : flagList) {
            flags.add(flag.getSecondsAfterRecording());
        }
    }

    public void recomputeHeights(float density) {
        mHeightsAtThisZoomLevel = null;
        mDensity = density;
        mTimecodePaint.setTextSize((int) (textSize * density));

        invalidate();
    }

    protected void drawWaveformLine(Canvas canvas,
                                    int x, int y0, int y1,
                                    Paint paint) {
        canvas.drawLine(x, y0, x, y1, paint);
    }


    private Paint initGradientPaint(int[] gradientColors, int y0, int y1) {

        Paint paint = new Paint();

        LinearGradient topGradient = new LinearGradient(0, y0, 0, y1, gradientColors, null, Shader.TileMode.CLAMP);
        paint.setShader(topGradient);

        return paint;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mSoundFile == null)
            return;

        int measuredWidth = getMeasuredWidth();
        int measuredHeight = getMeasuredHeight();

        int bottomOfGradient = (int) (measuredHeight - (textSize * mDensity)); // or topOfText
        int gradientHeight = (int) ((bottomOfGradient - flagsHeight) * topBottomGradientOffset);
        int topAfterGradient = flagsHeight + gradientHeight;
        int waveMaxHeight = bottomOfGradient - flagsHeight - (2 * gradientHeight);

        if (mHeightsAtThisZoomLevel == null)
            computeIntsForThisZoomLevel(waveMaxHeight);


        int start = mOffset;
        int width = mHeightsAtThisZoomLevel.length - start;

        if (width > measuredWidth)
            width = measuredWidth;


        drawFlags(canvas, width);
        drawTopBottomGradient(canvas, flagsHeight, measuredWidth, bottomOfGradient, gradientHeight);
        drawTimeAtStartAndEnd(canvas, width, measuredWidth, measuredHeight);
        drawWaveForm(canvas, start, width, topAfterGradient, waveMaxHeight);
        drawSelection(canvas, width, start, flagsHeight, bottomOfGradient);
        drawPlaybackLine(canvas, width, start, flagsHeight, bottomOfGradient);


        if (mListener != null) {
            mListener.waveformDraw();
        }

    }

    private void drawPlaybackLine(Canvas canvas, int width, int start, int top, int bottom) {
        for (int i = 0; i < width; i++) {
            if (i + start == mPlaybackPos) {
                canvas.drawLine(i, top, i, bottom, mPlaybackLinePaint);
            }
        }
    }

    private void drawSelection(Canvas canvas, int width, int start, int top, int bottom) {

        /*if (mSelectionStart == -1 || mSelectionEnd == -1){
            mSelectionStart = width / 3;
            mSelectionEnd = width / 2;
        }*/

        for (int i = 0; i < width; i++) {

            int point = i + start;

            if (point == mSelectionStart || point == mSelectionEnd) {
                canvas.drawLine(i, top, i, bottom, mSelectionStartEndPaint);
            }

            if (point > mSelectionStart && point < mSelectionEnd) {
                canvas.drawLine(i, top, i, bottom, mSelectedLinePaint);
            }
        }
    }

    private void drawWaveForm(Canvas canvas, int start, int width, int top, int waveMaxHeight) {

        int ctr = waveMaxHeight / 2;
        for (int i = 0; i < width; i++) {
            drawWaveformLine(
                    canvas, i,
                    top + ctr - mHeightsAtThisZoomLevel[start + i],
                    top + ctr + 1 + mHeightsAtThisZoomLevel[start + i],
                    mUnselectedLinePaint);
        }
    }

    private void drawTimeAtStartAndEnd(Canvas canvas, int width, int right, int bottom) {
        double onePixelInSecs = pixelsToSeconds(1);
        double fractionalSecs = mOffset * onePixelInSecs;

        int startTime = (int) (fractionalSecs + onePixelInSecs);
        int endTime = (int) (fractionalSecs + (onePixelInSecs * width));

        String startTimeStr = getTimeString(startTime);
        String endTimeStr = getTimeString(endTime);

        float textMargin = 10;
        float endOffset = (float) (mTimecodePaint.measureText(endTimeStr));


        canvas.drawText(startTimeStr,
                0 + textMargin,
                bottom,
                mTimecodePaint);

        canvas.drawText(endTimeStr,
                right - endOffset - textMargin,
                bottom,
                mTimecodePaint);
    }

    private String getTimeString(int time) {
        String timecodeMinutes = "" + (time / 60);
        String timecodeSeconds = "" + (time % 60);
        if ((time % 60) < 10) {
            timecodeSeconds = "0" + timecodeSeconds;
        }
        return timecodeMinutes + ":" + timecodeSeconds;
    }

    private void drawFlags(Canvas canvas, int width) {

        double onePixelInSecs = pixelsToSeconds(1);
        double fractionalSecs = mOffset * onePixelInSecs;


        int lastIntegerSecs = (int) fractionalSecs;

        int i = 0;
        int integerSecs;
        if (flags != null && !flags.isEmpty()) {
            while (i < width) {
                i++;
                fractionalSecs += onePixelInSecs;
                integerSecs = (int) fractionalSecs;

                if (integerSecs != lastIntegerSecs && flags.contains(integerSecs)) {
                    lastIntegerSecs = integerSecs;

                    int offset = mFlagBitMap.getWidth() / 2;

                    canvas.drawBitmap(mFlagBitMap, i - offset, 0, null);

                }
            }
        }
    }

    private void drawTopBottomGradient(Canvas canvas, int top, int right, int bottom, int gradientHeight) {

        if (mTopGradientPaint == null || mBottomGradientPaint == null) {

            Resources res = getResources();
            int[] gradientColors = {
                    res.getColor(R.color.wave_gradient_1),
                    res.getColor(R.color.wave_gradient_2),
                    res.getColor(R.color.wave_gradient_3),
            };

            mTopGradientPaint = initGradientPaint(gradientColors, top, top + gradientHeight);
            mBottomGradientPaint = initGradientPaint(gradientColors, bottom, bottom - gradientHeight);
        }

        float x0 = 0;
        float y0 = top;

        float x1 = right;
        float y1 = top + gradientHeight;


        canvas.drawRect(x0, y0, x1, y1, mTopGradientPaint);

        x0 = 0;
        y0 = bottom - gradientHeight;

        x1 = right;
        y1 = bottom;

        canvas.drawRect(x0, y0, x1, y1, mBottomGradientPaint);
    }

    /**
     * Called once when a new sound file is added
     */
    private void computeDoublesForAllZoomLevels() {
        int numFrames = mSoundFile.getNumFrames();
        int[] frameGains = mSoundFile.getFrameGains();
        double[] smoothedGains = new double[numFrames];
        if (numFrames == 1) {
            smoothedGains[0] = frameGains[0];
        } else if (numFrames == 2) {
            smoothedGains[0] = frameGains[0];
            smoothedGains[1] = frameGains[1];
        } else if (numFrames > 2) {
            smoothedGains[0] = (double) (
                    (frameGains[0] / 2.0) +
                            (frameGains[1] / 2.0));
            for (int i = 1; i < numFrames - 1; i++) {
                smoothedGains[i] = (double) (
                        (frameGains[i - 1] / 3.0) +
                                (frameGains[i] / 3.0) +
                                (frameGains[i + 1] / 3.0));
            }
            smoothedGains[numFrames - 1] = (double) (
                    (frameGains[numFrames - 2] / 2.0) +
                            (frameGains[numFrames - 1] / 2.0));
        }

        // Make sure the range is no more than 0 - 255
        double maxGain = 1.0;
        for (int i = 0; i < numFrames; i++) {
            if (smoothedGains[i] > maxGain) {
                maxGain = smoothedGains[i];
            }
        }
        double scaleFactor = 1.0;
        if (maxGain > 255.0) {
            scaleFactor = 255 / maxGain;
        }

        // Build histogram of 256 bins and figure out the new scaled max
        maxGain = 0;
        int gainHist[] = new int[256];
        for (int i = 0; i < numFrames; i++) {
            int smoothedGain = (int) (smoothedGains[i] * scaleFactor);
            if (smoothedGain < 0)
                smoothedGain = 0;
            if (smoothedGain > 255)
                smoothedGain = 255;

            if (smoothedGain > maxGain)
                maxGain = smoothedGain;

            gainHist[smoothedGain]++;
        }

        // Re-calibrate the min to be 5%
        double minGain = 0;
        int sum = 0;
        while (minGain < 255 && sum < numFrames / 20) {
            sum += gainHist[(int) minGain];
            minGain++;
        }

        // Re-calibrate the max to be 99%
        sum = 0;
        while (maxGain > 2 && sum < numFrames / 100) {
            sum += gainHist[(int) maxGain];
            maxGain--;
        }

        // Compute the heights
        double[] heights = new double[numFrames];
        double range = maxGain - minGain;
        for (int i = 0; i < numFrames; i++) {
            double value = (smoothedGains[i] * scaleFactor - minGain) / range;
            if (value < 0.0)
                value = 0.0;
            if (value > 1.0)
                value = 1.0;
            heights[i] = value * value;
        }

        mNumZoomLevels = 5;
        mLenByZoomLevel = new int[5];
        mZoomFactorByZoomLevel = new double[5];
        mValuesByZoomLevel = new double[5][];

        // Level 0 is doubled, with interpolated values
        mLenByZoomLevel[0] = numFrames * 2;
        mZoomFactorByZoomLevel[0] = 2.0;
        mValuesByZoomLevel[0] = new double[mLenByZoomLevel[0]];
        if (numFrames > 0) {
            mValuesByZoomLevel[0][0] = 0.5 * heights[0];
            mValuesByZoomLevel[0][1] = heights[0];
        }
        for (int i = 1; i < numFrames; i++) {
            mValuesByZoomLevel[0][2 * i] = 0.5 * (heights[i - 1] + heights[i]);
            mValuesByZoomLevel[0][2 * i + 1] = heights[i];
        }

        // Level 1 is normal
        mLenByZoomLevel[1] = numFrames;
        mValuesByZoomLevel[1] = new double[mLenByZoomLevel[1]];
        mZoomFactorByZoomLevel[1] = 1.0;
        for (int i = 0; i < mLenByZoomLevel[1]; i++) {
            mValuesByZoomLevel[1][i] = heights[i];
        }

        // 3 more levels are each halved
        for (int j = 2; j < 5; j++) {
            mLenByZoomLevel[j] = mLenByZoomLevel[j - 1] / 2;
            mValuesByZoomLevel[j] = new double[mLenByZoomLevel[j]];
            mZoomFactorByZoomLevel[j] = mZoomFactorByZoomLevel[j - 1] / 2.0;
            for (int i = 0; i < mLenByZoomLevel[j]; i++) {
                mValuesByZoomLevel[j][i] =
                        0.5 * (mValuesByZoomLevel[j - 1][2 * i] +
                                mValuesByZoomLevel[j - 1][2 * i + 1]);
            }
        }

        if (numFrames > 5000) {
            mZoomLevel = 3;
        } else if (numFrames > 1000) {
            mZoomLevel = 2;
        } else if (numFrames > 300) {
            mZoomLevel = 1;
        } else {
            mZoomLevel = 0;
        }

        mInitialized = true;
    }


    /**
     * Called the first time we need to draw when the zoom level has changed
     * or the screen is resized
     */
    private void computeIntsForThisZoomLevel(int waveMaxHeight) {
        int halfHeight = (waveMaxHeight / 2) - 1;
        mHeightsAtThisZoomLevel = new int[mLenByZoomLevel[mZoomLevel]];
        for (int i = 0; i < mLenByZoomLevel[mZoomLevel]; i++) {
            mHeightsAtThisZoomLevel[i] =
                    (int) (mValuesByZoomLevel[mZoomLevel][i] * halfHeight);
        }
    }


    public interface WaveformListener {
        public void waveformTouchStart(float x);

        public void waveformTouchMove(float x);

        public void waveformTouchEnd();

        public void waveformFling(float x);

        public void waveformDraw();

        public void waveformZoomIn();

        public void waveformZoomOut();
    }
}
