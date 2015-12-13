package it.sephiroth.android.library.tooltip;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.DimenRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.util.Log.DEBUG;
import static android.util.Log.ERROR;
import static android.util.Log.INFO;
import static android.util.Log.VERBOSE;
import static android.util.Log.WARN;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static it.sephiroth.android.library.tooltip.Tooltip.Gravity.BOTTOM;
import static it.sephiroth.android.library.tooltip.Tooltip.Gravity.CENTER;
import static it.sephiroth.android.library.tooltip.Tooltip.Gravity.LEFT;
import static it.sephiroth.android.library.tooltip.Tooltip.Gravity.RIGHT;
import static it.sephiroth.android.library.tooltip.Tooltip.Gravity.TOP;
import static it.sephiroth.android.library.tooltip.Utils.log;

/**
 * Created by Alessandro Crugnola on 12/12/15.
 * alessandro.crugnola@gmail.com
 */
public class Tooltip {

    public static boolean DBG = true;

    public static TooltipView make(Context context, Builder builder) {
        TooltipViewImpl view = new TooltipViewImpl(context, builder);
        return view;
    }

    static class TooltipViewImpl extends ViewGroup implements TooltipView {
        public static final int TOLERANCE_VALUE = 10;
        private static final String TAG = "TooltipView";
        private static final List<Gravity> gravities = new ArrayList<>(Arrays.asList(LEFT, RIGHT, TOP, BOTTOM, CENTER));
        private final List<Gravity> viewGravities = new ArrayList<>(gravities);
        private final long mShowDelay;
        private final int mTextAppearance;
        private final int mToolTipId;
        private final Rect mDrawRect;
        private final long mShowDuration;
        private final ClosePolicy mClosePolicy;
        private final Point mPoint;
        private final int mTextResId;
        private final int mTopRule;
        private final int mMaxWidth;
        private final boolean mHideArrow;
        private final long mActivateDelay;
        private final boolean mRestrict;
        private final long mFadeDuration;
        private final TooltipTextDrawable mDrawable;
        private final Rect mTempRect = new Rect();
        private final int[] mTempLocation = new int[2];
        private final Handler mHandler = new Handler();
        private final Rect mScreenRect = new Rect();
        private final Point mTmpPoint = new Point();
        private final Rect mHitRect = new Rect();
        private Callback mCallback;
        private int[] mOldLocation;
        private Gravity mGravity;
        private Animator mShowAnimation;
        private boolean mShowing;
        private WeakReference<View> mViewAnchor;
        private boolean mAttached;
        private boolean mInitialized;
        private boolean mActivated;
        private int mPadding;
        private CharSequence mText;
        private Rect mViewRect;
        private View mView;
        private TooltipOverlay mViewOverlay;
        private TextView mTextView;
        private int mSizeTolerance;
        private Animator mAnimator;
        private AnimationBuilder mFloatingAnimation;
        private boolean mAlreadyCheck;

        Runnable hideRunnable = () -> onClose(false, false, false);

        Runnable activateRunnable = () -> mActivated = true;

        private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!mAttached) {
                    log(TAG, WARN, "[%d] onPreDraw. not attached", mToolTipId);
                    removePreDrawObserver(null);
                    return true;
                }

                if (null != mViewAnchor && mAttached) {
                    View view = mViewAnchor.get();
                    if (null != view) {
                        view.getLocationOnScreen(mTempLocation);

                        if (mOldLocation == null) {
                            mOldLocation = new int[]{mTempLocation[0], mTempLocation[1]};
                        }

                        if (mOldLocation[0] != mTempLocation[0] || mOldLocation[1] != mTempLocation[1]) {
                            mView.setTranslationX(mTempLocation[0] - mOldLocation[0] + mView.getTranslationX());
                            mView.setTranslationY(mTempLocation[1] - mOldLocation[1] + mView.getTranslationY());

                            if (null != mViewOverlay) {
                                mViewOverlay.setTranslationX(mTempLocation[0] - mOldLocation[0] + mViewOverlay.getTranslationX());
                                mViewOverlay.setTranslationY(mTempLocation[1] - mOldLocation[1] + mViewOverlay.getTranslationY());
                            }
                        }

                        mOldLocation[0] = mTempLocation[0];
                        mOldLocation[1] = mTempLocation[1];
                    }
                }
                return true;
            }
        };

        private final ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (!mAttached) {
                    log(TAG, WARN, "[%d] onGlobalLayout. removeListeners", mToolTipId);
                    removeGlobalLayoutObserver(null);
                    return;
                }

                if (null != mViewAnchor) {
                    View view = mViewAnchor.get();

                    if (null != view) {
                        view.getHitRect(mTempRect);
                        view.getLocationOnScreen(mTempLocation);

                        if (DBG) {
                            log(TAG, INFO, "[%d] onGlobalLayout(dirty: %b)", mToolTipId, view.isDirty());
                            log(TAG, VERBOSE, "[%d] hitRect: %s, old: %s", mToolTipId, mTempRect, mHitRect);
                        }

                        if (!mTempRect.equals(mHitRect)) {
                            mHitRect.set(mTempRect);

                            mTempRect.offsetTo(mTempLocation[0], mTempLocation[1]);
                            mViewRect.set(mTempRect);
                            calculatePositions();
                        }
                    } else {
                        if (DBG) {
                            log(TAG, WARN, "[%d] view is null", mToolTipId);
                        }
                    }
                }
            }
        };

        private final OnAttachStateChangeListener mAttachedStateListener = new OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(final View v) {
                // setVisibility(VISIBLE);
            }

            @Override
            @TargetApi(17)
            public void onViewDetachedFromWindow(final View v) {
                log(TAG, INFO, "[%d] onViewDetachedFromWindow", mToolTipId);
                removeViewListeners(v);

                if (!mAttached) {
                    log(TAG, WARN, "[%d] not attached", mToolTipId);
                    return;
                }

                Activity activity = (Activity) getContext();
                if (null != activity) {
                    if (activity.isFinishing()) {
                        log(TAG, WARN, "[%d] skipped because activity is finishing...", mToolTipId);
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) {
                        return;
                    }
                    onClose(false, false, true);
                }
            }
        };

        @Override
        public void show() {
            if (getParent() == null) {
                final Activity act = Utils.getActivity(getContext());
                LayoutParams params = new LayoutParams(MATCH_PARENT, MATCH_PARENT);
                ViewGroup rootView = (ViewGroup) (act.getWindow().getDecorView());
                rootView.addView(this, params);
            }
        }

        public TooltipViewImpl(Context context, final Builder builder) {
            super(context);

            TypedArray theme =
                    context.getTheme().obtainStyledAttributes(null, R.styleable.TooltipLayout, builder.defStyleAttr, builder.defStyleRes);
            this.mPadding = theme.getDimensionPixelSize(R.styleable.TooltipLayout_ttlm_padding, 30);
            this.mTextAppearance = theme.getResourceId(R.styleable.TooltipLayout_android_textAppearance, 0);
            int overlayStyle = theme.getResourceId(R.styleable.TooltipLayout_ttlm_overlayStyle, R.style.ToolTipOverlayDefaultStyle);
            theme.recycle();

            this.mToolTipId = builder.id;
            this.mText = builder.text;
            this.mGravity = builder.gravity;
            this.mTextResId = builder.textResId;
            this.mMaxWidth = builder.maxWidth;
            this.mTopRule = builder.actionbarSize;
            this.mClosePolicy = builder.closePolicy;
            this.mShowDuration = builder.showDuration;
            this.mShowDelay = builder.showDelay;
            this.mHideArrow = builder.hideArrow;
            this.mActivateDelay = builder.activateDelay;
            this.mRestrict = builder.restrictToScreenEdges;
            this.mFadeDuration = builder.fadeDuration;
            this.mCallback = builder.closeCallback;
            this.mFloatingAnimation = builder.floatingAnimation;
            this.mSizeTolerance = (int) (context.getResources().getDisplayMetrics().density * TOLERANCE_VALUE);

            setClipChildren(false);
            setClipToPadding(false);

            if (null != builder.point) {
                this.mPoint = new Point(builder.point);
                this.mPoint.y += mTopRule;
            } else {
                this.mPoint = null;
            }

            this.mDrawRect = new Rect();

            if (null != builder.view) {
                mViewRect = new Rect();

                builder.view.getHitRect(mHitRect);
                builder.view.getLocationOnScreen(mTempLocation);

                mViewRect.set(mHitRect);
                mViewRect.offsetTo(mTempLocation[0], mTempLocation[1]);

                mViewAnchor = new WeakReference<>(builder.view);

                if (builder.view.getViewTreeObserver().isAlive()) {
                    builder.view.getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayoutListener);
                    builder.view.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
                    builder.view.addOnAttachStateChangeListener(mAttachedStateListener);
                }
            }

            if (builder.overlay) {
                mViewOverlay = new TooltipOverlay(getContext(), null, 0, overlayStyle);
                mViewOverlay.setAdjustViewBounds(true);
                mViewOverlay.setLayoutParams(new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            }

            if (!builder.isCustomView) {
                this.mDrawable = new TooltipTextDrawable(context, builder);
            } else {
                this.mDrawable = null;
            }
            setVisibility(INVISIBLE);
        }

        @SuppressWarnings("unused")
        private static boolean rectEqualsSize(@NonNull final Rect rect1, @NonNull final Rect rect2) {
            return rect1.width() == rect2.width() && rect1.height() == rect2.height();
        }

        @Override
        public int getTooltipId() {
            return mToolTipId;
        }

        @SuppressWarnings("unused")
        public boolean isShowing() {
            return mShowing;
        }

        void removeFromParent() {
            log(TAG, INFO, "[%d] removeFromParent", mToolTipId);
            ViewParent parent = getParent();
            removeCallbacks();

            if (null != parent) {
                ((ViewGroup) parent).removeView(TooltipViewImpl.this);

                if (null != mShowAnimation && mShowAnimation.isStarted()) {
                    mShowAnimation.cancel();
                }
            }
        }

        private void removeCallbacks() {
            mHandler.removeCallbacks(hideRunnable);
            mHandler.removeCallbacks(activateRunnable);
        }

        @Override
        protected void onAttachedToWindow() {
            log(TAG, INFO, "[%d] onAttachedToWindow", mToolTipId);
            super.onAttachedToWindow();
            mAttached = true;
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            android.view.Display display = wm.getDefaultDisplay();
            display.getRectSize(mScreenRect);
            initializeView();
            showInternal();
        }

        @Override
        protected void onDetachedFromWindow() {
            log(TAG, INFO, "[%d] onDetachedFromWindow", mToolTipId);
            removeListeners();
            stopFloatingAnimations();
            mAttached = false;
            mViewAnchor = null;
            super.onDetachedFromWindow();
        }

        @Override
        protected void onLayout(final boolean changed, final int l, final int t, final int r, final int b) {
            log(TAG, INFO, "[%d] onLayout(%b, %d, %d, %d, %d)", mToolTipId, changed, l, t, r, b);

            if (null != mView) {
                mView.layout(mView.getLeft(), mView.getTop(), mView.getMeasuredWidth(), mView.getMeasuredHeight());
                Log.d(TAG, "view.width: " + mView.getMeasuredWidth());
            }

            if (null != mViewOverlay) {
                mViewOverlay.layout(
                        mViewOverlay.getLeft(),
                        mViewOverlay.getTop(),
                        mViewOverlay.getMeasuredWidth(),
                        mViewOverlay.getMeasuredHeight());
            }

            if (changed) {
                if (mViewAnchor != null) {
                    View view = mViewAnchor.get();
                    if (null != view) {
                        view.getHitRect(mTempRect);
                        view.getLocationOnScreen(mTempLocation);
                        mTempRect.offsetTo(mTempLocation[0], mTempLocation[1]);
                        mViewRect.set(mTempRect);
                    }
                }
                calculatePositions();
            }
        }

        private void removeListeners() {
            mCallback = null;

            if (null != mViewAnchor) {
                View view = mViewAnchor.get();
                removeViewListeners(view);
            }
        }

        private void stopFloatingAnimations() {
            log(TAG, DEBUG, "stopFloatingAnimations");
            if (null != mAnimator) {
                mAnimator.cancel();
            }
        }

        private void removeViewListeners(final View view) {
            log(TAG, INFO, "[%d] removeListeners", mToolTipId);
            removeGlobalLayoutObserver(view);
            removePreDrawObserver(view);
            removeOnAttachStateObserver(view);
        }

        private void removeGlobalLayoutObserver(@Nullable View view) {
            if (null == view && null != mViewAnchor) {
                view = mViewAnchor.get();
            }
            if (null != view && view.getViewTreeObserver().isAlive()) {
                if (Build.VERSION.SDK_INT >= 16) {
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
                } else {
                    view.getViewTreeObserver().removeGlobalOnLayoutListener(mGlobalLayoutListener);
                }
            } else {
                log(TAG, ERROR, "[%d] removeGlobalLayoutObserver failed", mToolTipId);
            }
        }

        private void removePreDrawObserver(@Nullable View view) {
            if (null == view && null != mViewAnchor) {
                view = mViewAnchor.get();
            }
            if (null != view && view.getViewTreeObserver().isAlive()) {
                view.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            } else {
                log(TAG, ERROR, "[%d] removePreDrawObserver failed", mToolTipId);
            }
        }

        private void removeOnAttachStateObserver(@Nullable View view) {
            if (null == view && null != mViewAnchor) {
                view = mViewAnchor.get();
            }
            if (null != view) {
                view.removeOnAttachStateChangeListener(mAttachedStateListener);
            } else {
                log(TAG, ERROR, "[%d] removeOnAttachStateObserver failed", mToolTipId);
            }
        }

        private void initializeView() {
            if (!isAttached() || mInitialized) {
                return;
            }
            mInitialized = true;

            log(TAG, VERBOSE, "[%d] initializeView", mToolTipId);

            LayoutParams params = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            mView = LayoutInflater.from(getContext()).inflate(mTextResId, this, false);
            mView.setLayoutParams(params);

            mTextView = (TextView) mView.findViewById(android.R.id.text1);
            mTextView.setText(Html.fromHtml((String) this.mText));
            if (mMaxWidth > -1) {
                mTextView.setMaxWidth(mMaxWidth);
                log(TAG, VERBOSE, "[%d] maxWidth: %d", mToolTipId, mMaxWidth);
            }

            if (0 != mTextAppearance) {
                mTextView.setTextAppearance(getContext(), mTextAppearance);
            }

            if (null != mDrawable) {
                mTextView.setBackgroundDrawable(mDrawable);
                if (mHideArrow) {
                    mTextView.setPadding(mPadding / 2, mPadding / 2, mPadding / 2, mPadding / 2);
                } else {
                    mTextView.setPadding(mPadding, mPadding, mPadding, mPadding);
                }
            }

            this.addView(mView);

            if (null != mViewOverlay) {
                this.addView(mViewOverlay);
            }
        }

        private void showInternal() {
            log(TAG, INFO, "[%d] show", mToolTipId);
            if (!isAttached()) {
                log(TAG, ERROR, "[%d] not attached!", mToolTipId);
                return;
            }
            fadeIn(mFadeDuration);
        }

        @Override
        public void hide() {
            hide(mFadeDuration);
        }

        private void hide(long fadeDuration) {
            log(TAG, INFO, "[%d] hide(%d)", mToolTipId, fadeDuration);

            if (!isAttached()) {
                return;
            }
            fadeOut(fadeDuration);
        }

        protected void fadeOut(long fadeDuration) {
            if (!isAttached() || !mShowing) {
                return;
            }

            log(TAG, INFO, "[%d] fadeOut(%d)", mToolTipId, fadeDuration);

            if (null != mShowAnimation) {
                mShowAnimation.cancel();
            }

            mShowing = false;

            if (fadeDuration > 0) {
                float alpha = getAlpha();
                mShowAnimation = ObjectAnimator.ofFloat(this, "alpha", alpha, 0);
                mShowAnimation.setDuration(fadeDuration);
                mShowAnimation.addListener(
                        new Animator.AnimatorListener() {
                            boolean cancelled;

                            @Override
                            public void onAnimationStart(final Animator animation) {
                                cancelled = false;
                            }

                            @Override
                            public void onAnimationEnd(final Animator animation) {
                                log(TAG, VERBOSE, "[%d] fade-out onAnimationEnd, cancelled: %b", mToolTipId, cancelled);
                                if (cancelled) {
                                    return;
                                }

                                // hide completed
                                if (null != mCallback) {
                                    mCallback.onTooltipHidden(TooltipViewImpl.this);
                                }

                                remove();
                                mShowAnimation = null;
                            }

                            @Override
                            public void onAnimationCancel(final Animator animation) {
                                log(TAG, VERBOSE, "[%d] fade-out onAnimationCancel", mToolTipId);
                                cancelled = true;
                            }

                            @Override
                            public void onAnimationRepeat(final Animator animation) {

                            }
                        });
                mShowAnimation.start();
            } else {
                setVisibility(View.INVISIBLE);
                remove();
            }
        }

        @Override
        public void remove() {
            log(TAG, INFO, "[%d] remove()", mToolTipId);
            if (isAttached()) {
                removeFromParent();
            }
        }

        @Override
        public void setOffsetX(int x) {
            mView.setTranslationX(x - mViewRect.left + mDrawRect.left);
        }

        @Override
        public void setOffsetY(int y) {
            mView.setTranslationY(y - mViewRect.top + mDrawRect.top);
        }

        @Override
        public void offsetTo(final int x, final int y) {
            mView.setTranslationX(x - mViewRect.left + mDrawRect.left);
            mView.setTranslationY(y - mViewRect.top + mDrawRect.top);
        }

        @Override
        public boolean isAttached() {
            return mAttached;
        }

        protected void fadeIn(final long fadeDuration) {
            if (mShowing) {
                return;
            }

            if (null != mShowAnimation) {
                mShowAnimation.cancel();
            }

            log(TAG, INFO, "[%d] fadeIn", mToolTipId);

            mShowing = true;

            if (fadeDuration > 0) {
                mShowAnimation = ObjectAnimator.ofFloat(this, "alpha", 0, 1);
                mShowAnimation.setDuration(fadeDuration);
                if (this.mShowDelay > 0) {
                    mShowAnimation.setStartDelay(this.mShowDelay);
                }
                mShowAnimation.addListener(
                        new Animator.AnimatorListener() {
                            boolean cancelled;

                            @Override
                            public void onAnimationStart(final Animator animation) {
                                log(TAG, VERBOSE, "[%d] fade-in onAnimationStart", mToolTipId);
                                setVisibility(View.VISIBLE);
                                cancelled = false;
                            }

                            @Override
                            public void onAnimationEnd(final Animator animation) {
                                log(TAG, VERBOSE, "[%d] fade-in onAnimationEnd, cancelled: %b", mToolTipId, cancelled);

                                if (!cancelled) {
                                    if (null != mCallback) {
                                        mCallback.onTooltipShown(TooltipViewImpl.this);
                                    }

                                    postActivate(mActivateDelay);
                                }
                            }

                            @Override
                            public void onAnimationCancel(final Animator animation) {
                                log(TAG, VERBOSE, "[%d] fade-in onAnimationCancel", mToolTipId);
                                cancelled = true;
                            }

                            @Override
                            public void onAnimationRepeat(final Animator animation) {

                            }
                        });
                mShowAnimation.start();
            } else {
                setVisibility(View.VISIBLE);
                //            mTooltipListener.onShowCompleted(TooltipView.this);
                if (!mActivated) {
                    postActivate(mActivateDelay);
                }
            }

            if (mShowDuration > 0) {
                mHandler.removeCallbacks(hideRunnable);
                mHandler.postDelayed(hideRunnable, mShowDuration);
            }
        }

        void postActivate(long ms) {
            log(TAG, VERBOSE, "[%d] postActivate: %d", mToolTipId, ms);
            if (ms > 0) {
                if (isAttached()) {
                    mHandler.postDelayed(activateRunnable, ms);
                }
            } else {
                mActivated = true;
            }
        }

        private void calculatePositions() {
            calculatePositions(mRestrict);
        }

        private void calculatePositions(boolean restrict) {
            viewGravities.clear();
            viewGravities.addAll(gravities);
            viewGravities.remove(mGravity);
            viewGravities.add(0, mGravity);
            calculatePositions(viewGravities, restrict);
        }

        private void calculatePositions(List<Gravity> gravities, final boolean checkEdges) {
            if (!isAttached()) {
                return;
            }

            // failed to display the tooltip due to
            // something wrong with its dimensions or
            // the target position..
            if (gravities.size() < 1) {
                if (null != mCallback) {
                    mCallback.onTooltipFailed(this);
                }
                setVisibility(View.GONE);
                return;
            }

            Gravity gravity = gravities.remove(0);

            if (DBG) {
                log(TAG, DEBUG, "[%s] calculatePositions. gravity: %s, gravities: %d, restrict: %b", mToolTipId, gravity, gravities.size(), checkEdges);
            }

            int statusbarHeight = mScreenRect.top;

            final int overlayWidth;
            final int overlayHeight;

            if (null != mViewOverlay && gravity != CENTER) {
                overlayWidth = mViewOverlay.getWidth() / 2;
                overlayHeight = mViewOverlay.getHeight() / 2;
            } else {
                overlayWidth = 0;
                overlayHeight = 0;
            }

            log(TAG, VERBOSE, "overlaySize: %d, %d", overlayWidth, overlayHeight);

            if (mViewRect == null) {
                mViewRect = new Rect();
                mViewRect.set(mPoint.x, mPoint.y + statusbarHeight, mPoint.x, mPoint.y + statusbarHeight);
            }

            final int screenTop = mScreenRect.top + mTopRule;

            int width = mView.getWidth();
            int height = mView.getHeight();

            // get the destination mPoint

            if (gravity == BOTTOM) {
                mDrawRect.set(
                        mViewRect.centerX() - width / 2,
                        mViewRect.bottom,
                        mViewRect.centerX() + width / 2,
                        mViewRect.bottom + height
                );

                if (mViewRect.height() / 2 < overlayHeight) {
                    mDrawRect.offset(0, overlayHeight);
                }

                if (checkEdges && !rectContainsRectWithTolerance(mScreenRect, mDrawRect, mSizeTolerance)) {
                    if (mDrawRect.right > mScreenRect.right) {
                        mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                    } else if (mDrawRect.left < mScreenRect.left) {
                        mDrawRect.offset(-mDrawRect.left, 0);
                    }
                    if (mDrawRect.bottom > mScreenRect.bottom) {
                        // this means there's no enough space!
                        calculatePositions(gravities, checkEdges);
                        return;
                    } else if (mDrawRect.top < screenTop) {
                        mDrawRect.offset(0, screenTop - mDrawRect.top);
                    }
                }
            } else if (gravity == TOP) {
                mDrawRect.set(
                        mViewRect.centerX() - width / 2,
                        mViewRect.top - height,
                        mViewRect.centerX() + width / 2,
                        mViewRect.top
                );

                if ((mViewRect.height() / 2) < overlayHeight) {
                    mDrawRect.offset(0, -(overlayHeight - (mViewRect.height() / 2)));
                }

                if (checkEdges && !rectContainsRectWithTolerance(mScreenRect, mDrawRect, mSizeTolerance)) {
                    if (mDrawRect.right > mScreenRect.right) {
                        mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                    } else if (mDrawRect.left < mScreenRect.left) {
                        mDrawRect.offset(-mDrawRect.left, 0);
                    }
                    if (mDrawRect.top < screenTop) {
                        // this means there's no enough space!
                        calculatePositions(gravities, checkEdges);
                        return;
                    } else if (mDrawRect.bottom > mScreenRect.bottom) {
                        mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                    }
                }
            } else if (gravity == RIGHT) {
                mDrawRect.set(
                        mViewRect.right,
                        mViewRect.centerY() - height / 2,
                        mViewRect.right + width,
                        mViewRect.centerY() + height / 2
                );

                if ((mViewRect.width() / 2) < overlayWidth) {
                    mDrawRect.offset(overlayWidth - mViewRect.width() / 2, 0);
                }

                if (checkEdges && !rectContainsRectWithTolerance(mScreenRect, mDrawRect, mSizeTolerance)) {
                    if (mDrawRect.bottom > mScreenRect.bottom) {
                        mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                    } else if (mDrawRect.top < screenTop) {
                        mDrawRect.offset(0, screenTop - mDrawRect.top);
                    }
                    if (mDrawRect.right > mScreenRect.right) {
                        // this means there's no enough space!
                        calculatePositions(gravities, checkEdges);
                        return;
                    } else if (mDrawRect.left < mScreenRect.left) {
                        mDrawRect.offset(mScreenRect.left - mDrawRect.left, 0);
                    }
                }
            } else if (gravity == LEFT) {
                mDrawRect.set(
                        mViewRect.left - width,
                        mViewRect.centerY() - height / 2,
                        mViewRect.left,
                        mViewRect.centerY() + height / 2
                );

                if ((mViewRect.width() / 2) < overlayWidth) {
                    mDrawRect.offset(-(overlayWidth - (mViewRect.width() / 2)), 0);
                }

                if (checkEdges && !rectContainsRectWithTolerance(mScreenRect, mDrawRect, mSizeTolerance)) {
                    if (mDrawRect.bottom > mScreenRect.bottom) {
                        mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                    } else if (mDrawRect.top < screenTop) {
                        mDrawRect.offset(0, screenTop - mDrawRect.top);
                    }
                    if (mDrawRect.left < mScreenRect.left) {
                        // this means there's no enough space!
                        calculatePositions(gravities, checkEdges);
                        return;
                    } else if (mDrawRect.right > mScreenRect.right) {
                        mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                    }
                }
            } else if (gravity == CENTER) {
                mDrawRect.set(
                        mViewRect.centerX() - width / 2,
                        mViewRect.centerY() - height / 2,
                        mViewRect.centerX() + width / 2,
                        mViewRect.centerY() + height / 2
                );

                if (checkEdges && !rectContainsRectWithTolerance(mScreenRect, mDrawRect, mSizeTolerance)) {
                    if (mDrawRect.bottom > mScreenRect.bottom) {
                        mDrawRect.offset(0, mScreenRect.bottom - mDrawRect.bottom);
                    } else if (mDrawRect.top < screenTop) {
                        mDrawRect.offset(0, screenTop - mDrawRect.top);
                    }
                    if (mDrawRect.right > mScreenRect.right) {
                        mDrawRect.offset(mScreenRect.right - mDrawRect.right, 0);
                    } else if (mDrawRect.left < mScreenRect.left) {
                        mDrawRect.offset(mScreenRect.left - mDrawRect.left, 0);
                    }
                }
            }

            if (DBG) {
                log(TAG, VERBOSE, "[%d] mScreenRect: %s, mTopRule: %d, statusBar: %d",
                        mToolTipId,
                        mScreenRect,
                        mTopRule,
                        statusbarHeight);
                log(TAG, VERBOSE, "[%d] mDrawRect: %s", mToolTipId, mDrawRect);
                log(TAG, VERBOSE, "[%d] mViewRect: %s", mToolTipId, mViewRect);
            }

            if (gravity != mGravity) {
                log(TAG, ERROR, "gravity changed from %s to %s", mGravity, gravity);

                mGravity = gravity;

                if (gravity == CENTER && null != mViewOverlay) {
                    log(TAG, VERBOSE, "remove overlay");
                    removeView(mViewOverlay);
                    mViewOverlay = null;
                }
            }


            if (null != mViewOverlay) {
                mViewOverlay.setTranslationX(mViewRect.centerX() - mViewOverlay.getWidth() / 2);
                mViewOverlay.setTranslationY(mViewRect.centerY() - mViewOverlay.getHeight() / 2);
            }

            // translate the text view
            mView.setTranslationX(mDrawRect.left);
            mView.setTranslationY(mDrawRect.top);

            if (null != mDrawable) {
                getAnchorPoint(gravity, mTmpPoint);
                mDrawable.setAnchor(gravity, mHideArrow ? 0 : mPadding / 2, mHideArrow ? null : mTmpPoint);
            }

            if (!mAlreadyCheck) {
                mAlreadyCheck = true;
                startFloatingAnimations();
            }
        }

        private void startFloatingAnimations() {
            if (mTextView == mView || null == mFloatingAnimation) return;

            final float endValue = mFloatingAnimation.radius;
            final long duration = mFloatingAnimation.duration;

            final int direction;

            if (mFloatingAnimation.direction == 0) {
                direction = mGravity == TOP || mGravity == BOTTOM ? 2 : 1;
            } else {
                direction = mFloatingAnimation.direction;
            }

            final String property = direction == 2 ? "translationY" : "translationX";
            ValueAnimator anim1 = ObjectAnimator.ofFloat(mTextView, property, -endValue, endValue);
            anim1.setDuration(duration);
            anim1.setInterpolator(new AccelerateDecelerateInterpolator());

            ValueAnimator anim2 = ObjectAnimator.ofFloat(mTextView, property, endValue, -endValue);
            anim2.setDuration(duration);
            anim2.setInterpolator(new AccelerateDecelerateInterpolator());

            AnimatorSet set = new AnimatorSet();
            set.playSequentially(anim1, anim2);
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (isAttached()) {
                        log(TAG, VERBOSE, "animation restart");
                        animation.start();
                    }
                }
            });
            mAnimator = set;
            mAnimator.start();
        }

        static boolean rectContainsRectWithTolerance(@NonNull final Rect parentRect, @NonNull final Rect childRect, final int t) {
            return parentRect.contains(childRect.left + t, childRect.top + t, childRect.right - t, childRect.bottom - t);
        }

        void getAnchorPoint(final Gravity gravity, Point outPoint) {

            if (gravity == BOTTOM) {
                outPoint.x = mViewRect.centerX();
                outPoint.y = mViewRect.bottom;
            } else if (gravity == TOP) {
                outPoint.x = mViewRect.centerX();
                outPoint.y = mViewRect.top;
            } else if (gravity == RIGHT) {
                outPoint.x = mViewRect.right;
                outPoint.y = mViewRect.centerY();
            } else if (gravity == LEFT) {
                outPoint.x = mViewRect.left;
                outPoint.y = mViewRect.centerY();
            } else if (this.mGravity == CENTER) {
                outPoint.x = mViewRect.centerX();
                outPoint.y = mViewRect.centerY();
            }

            outPoint.x -= mDrawRect.left;
            outPoint.y -= mDrawRect.top;

            if (!mHideArrow) {
                if (gravity == LEFT || gravity == RIGHT) {
                    outPoint.y -= mPadding / 2;
                } else if (gravity == TOP || gravity == BOTTOM) {
                    outPoint.x -= mPadding / 2;
                }
            }
        }

        void setText(final CharSequence text) {
            this.mText = text;
            if (null != mTextView) {
                mTextView.setText(Html.fromHtml((String) text));
            }
        }

        @Override
        public boolean onTouchEvent(@NonNull final MotionEvent event) {
            if (!mAttached || !mShowing || !isShown()) {
                return false;
            }

            final int action = event.getActionMasked();

            log(TAG, INFO, "[%d] onTouchEvent: %d, active: %b", mToolTipId, action, mActivated);

            if (mClosePolicy != ClosePolicy.None) {

                if (!mActivated) {
                    log(TAG, WARN, "[%d] not yet activated...", mToolTipId);
                    return true;
                }

                if (action == MotionEvent.ACTION_DOWN) {

                    Rect outRect = new Rect();
                    mView.getGlobalVisibleRect(outRect);
                    log(TAG, VERBOSE, "[%d] text rect: %s", mToolTipId, outRect);

                    boolean containsTouch = outRect.contains((int) event.getX(), (int) event.getY());

                    if (null != mViewOverlay) {
                        mViewOverlay.getGlobalVisibleRect(outRect);
                        containsTouch |= outRect.contains((int) event.getX(), (int) event.getY());
                        log(TAG, VERBOSE, "[%d] overlay rect: %s", mToolTipId, outRect);
                    }

                    if (DBG) {
                        log(TAG, VERBOSE, "[%d] containsTouch: %b", mToolTipId, containsTouch);
                        log(TAG, VERBOSE, "[%d] mDrawRect: %s, point: %g, %g", mToolTipId, mDrawRect, event.getX(), event.getY());
                        log(
                                TAG,
                                VERBOSE, "[%d] real drawing rect: %s, contains: %b", mToolTipId, outRect,
                                outRect.contains((int) event.getX(), (int) event.getY())
                        );
                    }

                    switch (mClosePolicy) {
                        case TouchInside:
                        case TouchInsideExclusive:
                            if (containsTouch) {
                                onClose(true, true, false);
                                return mClosePolicy == ClosePolicy.TouchInsideExclusive;
                            }
                            return mClosePolicy == ClosePolicy.TouchInsideExclusive;
                        case TouchAnyWhere:
                        case TouchAnyWhereExclusive:
                            onClose(true, containsTouch, false);
                            return mClosePolicy == ClosePolicy.TouchAnyWhereExclusive || containsTouch;
                        case None:
                            break;
                    }
                }
            }

            return false;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            if (!mAttached) {
                return;
            }
            super.onDraw(canvas);
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);

            int myWidth = 0;
            int myHeight = 0;

            final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            int heightSize = MeasureSpec.getSize(heightMeasureSpec);

            // Record our dimensions if they are known;
            if (widthMode != MeasureSpec.UNSPECIFIED) {
                myWidth = widthSize;
            }

            if (heightMode != MeasureSpec.UNSPECIFIED) {
                myHeight = heightSize;
            }

            log(TAG, VERBOSE, "[%d] onMeasure myWidth: %d, myHeight: %d", mToolTipId, myWidth, myHeight);

            if (null != mView) {
                if (mView.getVisibility() != GONE) {
                    int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(myWidth, MeasureSpec.AT_MOST);
                    int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(myHeight, MeasureSpec.AT_MOST);
                    mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                } else {
                    myWidth = 0;
                    myHeight = 0;
                }
            }

            if (null != mViewOverlay && mViewOverlay.getVisibility() != GONE) {

                final int childWidthMeasureSpec;
                final int childHeightMeasureSpec;
                //                final View view = null != mViewAnchor ? mViewAnchor.get() : null;
                LayoutParams params = mViewOverlay.getLayoutParams();
                //                childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(params.width, MeasureSpec.EXACTLY);
                //                childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(params.height, MeasureSpec.EXACTLY);

                childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST);
                childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.AT_MOST);
                mViewOverlay.measure(childWidthMeasureSpec, childHeightMeasureSpec);

                //                if (null != view) {
                //                    widthSize = view.getMeasuredWidth();
                //                    heightSize = view.getMeasuredHeight();
                //                    childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY);
                //                    childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY);
                //                } else {
                //                }

                //                mViewOverlay.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }

            setMeasuredDimension(myWidth, myHeight);
        }

        private void onClose(boolean fromUser, boolean containsTouch, boolean immediate) {
            log(TAG, INFO, "[%d] onClose. fromUser: %b, containsTouch: %b, immediate: %b",
                    mToolTipId,
                    fromUser,
                    containsTouch,
                    immediate
            );

            if (!isAttached()) {
                return;
            }

            if (null != mCallback) {
                mCallback.onTooltipClose(this, fromUser, containsTouch);
            }

            hide(immediate ? 0 : mFadeDuration);
        }
    }

    public interface TooltipView {
        void show();

        void hide();

        void remove();

        int getTooltipId();

        void setOffsetX(int x);

        void setOffsetY(int y);

        void offsetTo(int x, int y);

        boolean isAttached();

        boolean isShown();

        void requestLayout();
    }

    public interface Callback {
        /**
         * Tooltip is being closed
         *
         * @param tooltip       the tooltip being closed
         * @param fromUser      true if the close operation started from a user click
         * @param containsTouch true if the original touch came from inside the tooltip
         */
        void onTooltipClose(final TooltipView tooltip, final boolean fromUser, final boolean containsTouch);

        /**
         * Tooltip failed to show (not enough space)
         */
        void onTooltipFailed(final TooltipView view);

        void onTooltipShown(final TooltipView view);

        void onTooltipHidden(final TooltipView view);
    }

    public enum ClosePolicy {
        /**
         * tooltip will hide when touching it, or after the specified delay.
         * If delay is '0' the tooltip will never hide until clicked
         */
        TouchInside,
        /**
         * tooltip will hide when touching it, or after the specified delay.
         * If delay is '0' the tooltip will never hide until clicked.
         * In exclusive mode all touches will be consumed by the tooltip itself
         */
        TouchInsideExclusive,

        /**
         * tooltip will hide when user touches anywhere the screen, or after the specified delay.
         * If delay is '0' the tooltip will never hide until clicked
         */
        TouchAnyWhere,
        /**
         * tooltip will hide when user touches anywhere the screen, or after the specified delay.
         * If delay is '0' the tooltip will never hide until clicked.
         * Touch will be consumed in any case.
         */
        TouchAnyWhereExclusive,
        /**
         * tooltip is hidden only after the specified delay
         */
        None
    }

    public enum Gravity {
        LEFT, RIGHT, TOP, BOTTOM, CENTER
    }

    public static final class AnimationBuilder {
        int radius;
        int direction;
        long duration;
        boolean completed;

        public AnimationBuilder() {
            radius = 8;
            direction = 0;
            duration = 400;
        }

        public AnimationBuilder setRadius(int value) {
            throwIfCompleted();
            this.radius = value;
            return this;
        }

        /**
         * @param value 0 for auto, 1 horizontal, 2 vertical
         * @return
         */
        public AnimationBuilder setDirection(int value) {
            throwIfCompleted();
            this.direction = value;
            return this;
        }

        public AnimationBuilder setDuration(long value) {
            throwIfCompleted();
            this.duration = value;
            return this;
        }

        private void throwIfCompleted() {
            if (completed) {
                throw new IllegalStateException("Builder cannot be modified");
            }
        }

        public AnimationBuilder build() {
            throwIfCompleted();
            completed = true;
            return this;
        }
    }

    public static final class Builder {
        int id;
        CharSequence text;
        View view;
        Gravity gravity;
        int actionbarSize = 0;
        int textResId = R.layout.tooltip_textview;
        ClosePolicy closePolicy;
        long showDuration;
        Point point;
        long showDelay = 0;
        boolean hideArrow;
        int maxWidth = -1;
        int defStyleRes = R.style.ToolTipLayoutDefaultStyle;
        int defStyleAttr = R.attr.ttlm_defaultStyle;
        long activateDelay = 0;
        boolean isCustomView;
        boolean restrictToScreenEdges = true;
        long fadeDuration = 200;
        Callback closeCallback;
        boolean completed;
        boolean overlay = true;
        AnimationBuilder floatingAnimation;

        public Builder(int id) {
            this.id = id;
        }

        private void throwIfCompleted() {
            if (completed) {
                throw new IllegalStateException("Builder cannot be modified");
            }
        }

        /**
         * Use a custom View for the tooltip. Note that the custom view
         * must include a TextView which id is `@android:id/text1`.<br />
         * Moreover, when using a custom view, the anchor arrow will not be shown
         *
         * @param resId              the custom layout view.
         * @param replace_background if true the custom view's background won't be replaced
         * @return the builder for chaining.
         */
        public Builder withCustomView(int resId, boolean replace_background) {
            this.textResId = resId;
            this.isCustomView = replace_background;
            return this;
        }

        public Builder withCustomView(int resId) {
            throwIfCompleted();
            return withCustomView(resId, true);
        }

        public Builder withStyleId(int styleId) {
            throwIfCompleted();
            this.defStyleAttr = 0;
            this.defStyleRes = styleId;
            return this;
        }

        public Builder fitToScreen(boolean value) {
            throwIfCompleted();
            restrictToScreenEdges = value;
            return this;
        }

        public Builder fadeDuration(long ms) {
            throwIfCompleted();
            fadeDuration = ms;
            return this;
        }

        public Builder withCallback(Callback callback) {
            throwIfCompleted();
            this.closeCallback = callback;
            return this;
        }

        public Builder text(Resources res, @StringRes int resId) {
            return text(res.getString(resId));
        }

        public Builder text(CharSequence text) {
            throwIfCompleted();
            this.text = text;
            return this;
        }

        public Builder maxWidth(Resources res, @DimenRes int dimension) {
            return maxWidth(res.getDimensionPixelSize(dimension));
        }

        public Builder maxWidth(int maxWidth) {
            throwIfCompleted();
            this.maxWidth = maxWidth;
            return this;
        }

        public Builder floatingAnimation(AnimationBuilder builder) {
            throwIfCompleted();
            this.floatingAnimation = builder;
            return this;
        }

        /**
         * Enable/disable the default overlay view
         *
         * @param value false to disable the overlay view. True by default
         */
        public Builder withOverlay(boolean value) {
            throwIfCompleted();
            this.overlay = value;
            return this;
        }

        public Builder anchor(View view, Gravity gravity) {
            throwIfCompleted();
            this.point = null;
            this.view = view;
            this.gravity = gravity;
            return this;
        }

        public Builder anchor(final Point point, final Gravity gravity) {
            throwIfCompleted();
            this.view = null;
            this.point = new Point(point);
            this.gravity = gravity;
            return this;
        }

        /**
         * @deprecated use {#withArrow} instead
         */
        @Deprecated
        public Builder toggleArrow(boolean show) {
            return withArrow(show);
        }

        /**
         * Hide/Show the tooltip arrow (trueby default)
         *
         * @param show true to show the arrow, false to hide it
         * @return the builder for chaining.
         */
        public Builder withArrow(boolean show) {
            throwIfCompleted();
            this.hideArrow = !show;
            return this;
        }

        public Builder actionBarSize(final int actionBarSize) {
            throwIfCompleted();
            this.actionbarSize = actionBarSize;
            return this;
        }

        public Builder actionBarSize(Resources resources, int resId) {
            return actionBarSize(resources.getDimensionPixelSize(resId));
        }

        public Builder closePolicy(ClosePolicy policy, long milliseconds) {
            throwIfCompleted();
            this.closePolicy = policy;
            this.showDuration = milliseconds;
            return this;
        }

        public Builder activateDelay(long ms) {
            throwIfCompleted();
            this.activateDelay = ms;
            return this;
        }

        public Builder showDelay(long ms) {
            throwIfCompleted();
            this.showDelay = ms;
            return this;
        }

        public Builder build() {
            throwIfCompleted();
            if (floatingAnimation != null) {
                if (!floatingAnimation.completed) {
                    throw new IllegalStateException("Builder not closed");
                }
            }
            completed = true;
            overlay = overlay && gravity != CENTER;
            return this;
        }
    }
}
