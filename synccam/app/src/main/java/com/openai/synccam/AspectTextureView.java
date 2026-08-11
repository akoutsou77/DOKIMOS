package com.openai.synccam;

import android.content.Context;
import android.view.TextureView;
import android.view.View;

/** TextureView that fits the camera stream inside the available screen without distorting its aspect ratio. */
final class AspectTextureView extends TextureView {
    private int ratioWidth;
    private int ratioHeight;

    AspectTextureView(Context context) {
        super(context);
    }

    void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (ratioWidth == width && ratioHeight == height) return;
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = View.MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = View.MeasureSpec.getSize(heightMeasureSpec);
        if (ratioWidth == 0 || ratioHeight == 0 || availableWidth == 0 || availableHeight == 0) {
            setMeasuredDimension(availableWidth, availableHeight);
            return;
        }

        long heightAtFullWidth = (long) availableWidth * ratioHeight / ratioWidth;
        if (heightAtFullWidth <= availableHeight) {
            setMeasuredDimension(availableWidth, Math.max(1, (int) heightAtFullWidth));
        } else {
            int fittedWidth = (int) ((long) availableHeight * ratioWidth / ratioHeight);
            setMeasuredDimension(Math.max(1, fittedWidth), availableHeight);
        }
    }
}
