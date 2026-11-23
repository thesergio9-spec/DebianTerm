package com.debianchroot.term;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Lightweight left-edge slide-out drawer without AndroidX DrawerLayout dependency.
 * Child 0: main content, Child 1: dark scrim, Child 2: left drawer panel.
 */
public final class SlideDrawerLayout extends FrameLayout {

    public interface DrawerListener {
        void onDrawerStateChanged(boolean isOpen);
    }

    private final View mScrimView;
    private final FrameLayout mDrawerPanel;
    private final int mDrawerWidthPx;
    private final int mEdgeThresholdPx;
    private final int mTouchSlop;

    private boolean mOpen = false;
    private boolean mDragging = false;
    private float mDownX;
    private float mDownY;
    private float mStartTranslationX;
    private DrawerListener mListener;

    public SlideDrawerLayout(Context context, View mainContent, View drawerContent) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        mDrawerWidthPx = Math.round(290 * density);
        mEdgeThresholdPx = Math.round(24 * density);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        addView(mainContent, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mScrimView = new View(context);
        mScrimView.setBackgroundColor(Color.argb(160, 0, 0, 0));
        mScrimView.setAlpha(0f);
        mScrimView.setVisibility(GONE);
        mScrimView.setOnClickListener(v -> closeDrawer());
        addView(mScrimView, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mDrawerPanel = new FrameLayout(context);
        mDrawerPanel.setBackgroundColor(Color.parseColor("#161B22"));
        mDrawerPanel.setElevation(8 * density);
        mDrawerPanel.addView(drawerContent, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mDrawerPanel.setTranslationX(-mDrawerWidthPx);
        mDrawerPanel.setVisibility(INVISIBLE);
        addView(mDrawerPanel, new LayoutParams(
            mDrawerWidthPx,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    public void setDrawerListener(DrawerListener listener) {
        mListener = listener;
    }

    public boolean isDrawerOpen() {
        return mOpen;
    }

    public void toggleDrawer() {
        if (mOpen) {
            closeDrawer();
        } else {
            openDrawer();
        }
    }

    public void openDrawer() {
        mOpen = true;
        mScrimView.setVisibility(VISIBLE);
        mDrawerPanel.setVisibility(VISIBLE);
        mScrimView.animate().alpha(1f).setDuration(200).start();
        mDrawerPanel.animate().translationX(0f).setDuration(200).start();
        if (mListener != null) mListener.onDrawerStateChanged(true);
    }

    public void closeDrawer() {
        mOpen = false;
        mScrimView.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            if (!mOpen) mScrimView.setVisibility(GONE);
        }).start();
        mDrawerPanel.animate().translationX(-mDrawerWidthPx).setDuration(180).withEndAction(() -> {
            if (!mOpen) mDrawerPanel.setVisibility(INVISIBLE);
        }).start();
        if (mListener != null) mListener.onDrawerStateChanged(false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDownX = ev.getX();
            mDownY = ev.getY();
            mDragging = false;
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - mDownX;
            float dy = ev.getY() - mDownY;
            if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                if (!mOpen && mDownX <= mEdgeThresholdPx && dx > 0) {
                    mDragging = true;
                    mStartTranslationX = -mDrawerWidthPx;
                    mScrimView.setVisibility(VISIBLE);
                    mDrawerPanel.setVisibility(VISIBLE);
                    return true;
                }
                if (mOpen && dx < 0) {
                    mDragging = true;
                    mStartTranslationX = 0f;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging && !mOpen) {
            return super.onTouchEvent(ev);
        }
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getX() - mDownX;
            float tx = Math.max(-mDrawerWidthPx, Math.min(0f, mStartTranslationX + dx));
            mDrawerPanel.setTranslationX(tx);
            float ratio = 1f + (tx / mDrawerWidthPx);
            mScrimView.setAlpha(Math.max(0f, Math.min(1f, ratio)));
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mDragging) {
                mDragging = false;
                float tx = mDrawerPanel.getTranslationX();
                if (tx > -mDrawerWidthPx * 0.5f) {
                    openDrawer();
                } else {
                    closeDrawer();
                }
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }
}
