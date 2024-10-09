package dora.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import dora.widget.swipemenu.R
import kotlin.math.abs

/**
 * 侧滑菜单。
 */
class SwipeMenuLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private var scaleTouchSlop = 0 // 为了处理单击事件的冲突
    private var maxVelocity = 0 // 计算滑动速度用
    private var pointerId = 0 // 多点触摸只算第一根手指的速度
    private var height = 0 // 自己的高度

    // 右侧菜单宽度总和(最大滑动距离)
    private var rightMenuWidths = 0

    // 滑动判定临界值（右侧菜单宽度的40%） 手指抬起时，超过了展开，没超过收起menu
    private var limit = 0
    private var contentView: View? = null // 存储contentView(第一个View)

    // private Scroller mScroller; // 以前item的滑动动画靠它做，现在用属性动画做
    // 上一次的xy
    private val lastP = PointF()

    // 仿QQ，侧滑菜单展开时，点击除侧滑菜单之外的区域，关闭侧滑菜单。
    // 增加一个布尔值变量，dispatch函数里，每次down时，为true，move时判断，如果是滑动动作，设为false。
    // 在Intercept函数的up时，判断这个变量，如果仍为true 说明是点击事件，则关闭菜单。
    private var isUnMoved = true

    // 判断手指起始落点，如果距离属于滑动了，就屏蔽一切点击事件。
    // up-down的坐标，判断是否是滑动，如果是，则屏蔽一切点击事件
    private val firstP = PointF()
    private var isUserSwiped = false
    private var velocityTracker: VelocityTracker? = null // 滑动速度变量

    /**
     * 右滑删除功能的开关,默认开。
     */
    private var isSwipeEnable = false

    /**
     * IOS的QQ式交互，默认开。
     */
    private var isIos = false
    private var iosInterceptFlag = false // IOS类型下，是否拦截事件的flag

    /**
     * 左滑右滑的开关,默认左滑打开菜单。
     */
    private var isLeftSwipe = false

    fun isSwipeEnable(): Boolean {
        return isSwipeEnable
    }

    /**
     * 设置侧滑功能开关。
     *
     * @param swipeEnable
     */
    fun setSwipeEnable(swipeEnable: Boolean) {
        isSwipeEnable = swipeEnable
    }

    fun isIos(): Boolean {
        return isIos
    }

    /**
     * 设置是否开启IOS阻塞式交互。
     *
     * @param ios
     */
    fun setIos(ios: Boolean): SwipeMenuLayout {
        isIos = ios
        return this
    }

    fun isLeftSwipe(): Boolean {
        return isLeftSwipe
    }

    /**
     * 设置是否开启左滑出菜单，设置false 为右滑出菜单。
     *
     * @param leftSwipe
     * @return
     */
    fun setLeftSwipe(leftSwipe: Boolean): SwipeMenuLayout {
        isLeftSwipe = leftSwipe
        return this
    }

    private fun init(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
        scaleTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
        maxVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
        // 初始化滑动帮助类对象
        // mScroller = new Scroller(context);

        // 右滑删除功能的开关,默认开
        isSwipeEnable = true
        // IOS的QQ式交互，默认开
        isIos = true
        // 左滑右滑的开关，默认左滑打开菜单
        isLeftSwipe = true
        val ta = context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.SwipeMenuLayout,
            defStyleAttr,
            0
        )
        val count = ta.indexCount
        for (i in 0 until count) {
            val attr = ta.getIndex(i)
            // 如果引用成AndroidLib 资源都不是常量，无法使用switch case
            if (attr == R.styleable.SwipeMenuLayout_dview_swipeEnable) {
                isSwipeEnable = ta.getBoolean(attr, true)
            } else if (attr == R.styleable.SwipeMenuLayout_dview_ios) {
                isIos = ta.getBoolean(attr, true)
            } else if (attr == R.styleable.SwipeMenuLayout_dview_leftSwipe) {
                isLeftSwipe = ta.getBoolean(attr, true)
            }
        }
        ta.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Log.d(TAG, "onMeasure() called with: " + "widthMeasureSpec = [" + widthMeasureSpec + "], heightMeasureSpec = [" + heightMeasureSpec + "]");
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        isClickable = true // 令自己可点击，从而获取触摸事件
        rightMenuWidths = 0 // 由于ViewHolder的复用机制，每次这里要手动恢复初始值
        height = 0
        var contentWidth = 0 // 适配GridLayoutManager，将以第一个子Item(即ContentItem)的宽度为控件宽度
        val childCount = childCount

        // 为了子View的高，可以matchParent(参考的FrameLayout 和LinearLayout的Horizontal)
        val measureMatchParentChildren =
            MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY
        var isNeedMeasureChildHeight = false
        for (i in 0 until childCount) {
            val childView = getChildAt(i)
            // 令每一个子View可点击，从而获取触摸事件
            childView.isClickable = true
            if (childView.visibility != GONE) {
                // 后续计划加入上滑、下滑，则将不再支持Item的margin
                measureChild(childView, widthMeasureSpec, heightMeasureSpec)
                // measureChildWithMargins(childView, widthMeasureSpec, 0, heightMeasureSpec, 0);
                val lp = childView.layoutParams as MarginLayoutParams
                height = Math.max(
                    height,
                    childView.measuredHeight /* + lp.topMargin + lp.bottomMargin*/
                )
                if (measureMatchParentChildren && lp.height == LayoutParams.MATCH_PARENT) {
                    isNeedMeasureChildHeight = true
                }
                if (i > 0) { // 第一个布局是Left item，从第二个开始才是RightMenu
                    rightMenuWidths += childView.measuredWidth
                } else {
                    contentView = childView
                    contentWidth = childView.measuredWidth
                }
            }
        }
        setMeasuredDimension(
            paddingLeft + paddingRight + contentWidth,
            height + paddingTop + paddingBottom
        ) // 宽度取第一个Item(Content)的宽度
        limit = rightMenuWidths * 4 / 10 // 滑动判断的临界值
        // Log.d(TAG, "onMeasure() called with: " + "mRightMenuWidths = [" + mRightMenuWidths);
        if (isNeedMeasureChildHeight) { // 如果子View的height有MatchParent属性的，设置子View高度
            forceUniformHeight(childCount, widthMeasureSpec)
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    /**
     * 给MatchParent的子View设置高度。
     *
     * @param count
     * @param widthMeasureSpec
     */
    private fun forceUniformHeight(count: Int, widthMeasureSpec: Int) {
        val uniformMeasureSpec = MeasureSpec.makeMeasureSpec(
            measuredHeight,
            MeasureSpec.EXACTLY
        )
        // 以父布局高度构建一个Exactly的测量参数
        for (i in 0 until count) {
            val child = getChildAt(i)
            if (child.visibility != GONE) {
                val lp = child.layoutParams as MarginLayoutParams
                if (lp.height == LayoutParams.MATCH_PARENT) {
                    // Temporarily force children to reuse their old measured width
                    val oldWidth = lp.width // measureChildWithMargins 这个函数会用到宽，所以要保存一下
                    lp.width = child.measuredWidth
                    // Remeasure with new dimensions
                    measureChildWithMargins(child, widthMeasureSpec, 0, uniformMeasureSpec, 0)
                    lp.width = oldWidth
                }
            }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // LogUtils.e(TAG, "onLayout() called with: " + "changed = [" + changed + "], l = [" + l + "], t = [" + t + "], r = [" + r + "], b = [" + b + "]");
        val childCount = childCount
        var left = 0 + paddingLeft
        var right = 0 + paddingLeft
        for (i in 0 until childCount) {
            val childView = getChildAt(i)
            if (childView.visibility != GONE) {
                if (i == 0) { // 第一个子View是内容 宽度设置为全屏
                    childView.layout(
                        left,
                        paddingTop,
                        left + childView.measuredWidth,
                        paddingTop + childView.measuredHeight
                    )
                    left += childView.measuredWidth
                } else {
                    if (isLeftSwipe) {
                        childView.layout(
                            left,
                            paddingTop,
                            left + childView.measuredWidth,
                            paddingTop + childView.measuredHeight
                        )
                        left += childView.measuredWidth
                    } else {
                        childView.layout(
                            right - childView.measuredWidth,
                            paddingTop,
                            right,
                            paddingTop + childView.measuredHeight
                        )
                        right -= childView.measuredWidth
                    }
                }
            }
        }
        // Log.d(TAG, "onLayout() called with: " + "maxScrollGap = [" + maxScrollGap + "], l = [" + l + "], t = [" + t + "], r = [" + r + "], b = [" + b + "]");
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // LogUtils.d(TAG, "dispatchTouchEvent() called with: " + "ev = [" + ev + "]");
        if (isSwipeEnable) {
            acquireVelocityTracker(ev)
            val verTracker = velocityTracker
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    isUserSwiped = false // 判断手指起始落点，如果距离属于滑动了，就屏蔽一切点击事件。
                    isUnMoved = true // 仿QQ，侧滑菜单展开时，点击内容区域，关闭侧滑菜单。
                    iosInterceptFlag = false // 每次DOWN时，默认是不拦截的
                    if (isTouching) { // 如果有别的指头摸过了，那么就return false。这样后续的move..等事件也不会再来找这个View了。
                        return false
                    } else {
                        isTouching = true // 第一个摸的指头，赶紧改变标志，宣誓主权。
                    }
                    lastP[ev.rawX] = ev.rawY
                    firstP[ev.rawX] = ev.rawY // 判断手指起始落点，如果距离属于滑动了，就屏蔽一切点击事件。

                    // 如果down，view和cacheview不一样，则立马让它还原。且把它置为null
                    if (viewCache != null) {
                        if (viewCache !== this) {
                            viewCache!!.smoothClose()
                            iosInterceptFlag =
                                isIos // IOS模式开启的话，且当前有侧滑菜单的View，且不是自己的，就该拦截事件咯。
                        }
                        // 只要有一个侧滑菜单处于打开状态， 就不给外层布局上下滑动了
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    // 求第一个触点的id， 此时可能有多个触点，但至少一个，计算滑动速率用
                    pointerId = ev.getPointerId(0)
                }

                MotionEvent.ACTION_MOVE -> {
                    // IOS模式开启的话，且当前有侧滑菜单的View，且不是自己的，就该拦截事件咯。滑动也不该出现
                    if (iosInterceptFlag) {
                        return true
                    }
                    val gap = lastP.x - ev.rawX
                    // 为了在水平滑动中禁止父类ListView等再竖直滑动
                    if (Math.abs(gap) > 10 || Math.abs(scrollX) > 10) { //2016 09 29 修改此处，使屏蔽父布局滑动更加灵敏，
                        parent.requestDisallowInterceptTouchEvent(true)
                    }
                    // 仿QQ，侧滑菜单展开时，点击内容区域，关闭侧滑菜单。begin
                    if (Math.abs(gap) > scaleTouchSlop) {
                        isUnMoved = false
                    }
                    // 仿QQ，侧滑菜单展开时，点击内容区域，关闭侧滑菜单。end
                    // 如果scroller还没有滑动结束 停止滑动动画
                    /*                    if (!mScroller.isFinished()) {
                        mScroller.abortAnimation();
                    }*/scrollBy(gap.toInt(), 0) // 滑动使用scrollBy
                    // 越界修正
                    if (isLeftSwipe) { // 左滑
                        if (scrollX < 0) {
                            scrollTo(0, 0)
                        }
                        if (scrollX > rightMenuWidths) {
                            scrollTo(rightMenuWidths, 0)
                        }
                    } else { // 右滑
                        if (scrollX < -rightMenuWidths) {
                            scrollTo(-rightMenuWidths, 0)
                        }
                        if (scrollX > 0) {
                            scrollTo(0, 0)
                        }
                    }
                    lastP[ev.rawX] = ev.rawY
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 判断手指起始落点，如果距离属于滑动了，就屏蔽一切点击事件。
                    if (abs(ev.rawX - firstP.x) > scaleTouchSlop) {
                        isUserSwiped = true
                    }

                    // IOS模式开启的话，且当前有侧滑菜单的View，且不是自己的，就该拦截事件咯。滑动也不该出现
                    if (!iosInterceptFlag) { // 且滑动了，才判断是否要收起、展开menu
                        // 求伪瞬时速度
                        verTracker!!.computeCurrentVelocity(1000, maxVelocity.toFloat())
                        val velocityX = verTracker.getXVelocity(pointerId)
                        if (abs(velocityX) > 1000) { // 滑动速度超过阈值
                            if (velocityX < -1000) {
                                if (isLeftSwipe) { // 左滑
                                    // 平滑展开Menu
                                    smoothExpand()
                                } else {
                                    // 平滑关闭Menu
                                    smoothClose()
                                }
                            } else {
                                if (isLeftSwipe) { // 左滑
                                    // 平滑关闭Menu
                                    smoothClose()
                                } else {
                                    // 平滑展开Menu
                                    smoothExpand()
                                }
                            }
                        } else {
                            if (abs(scrollX) > limit) { // 否则就判断滑动距离
                                // 平滑展开Menu
                                smoothExpand()
                            } else {
                                // 平滑关闭Menu
                                smoothClose()
                            }
                        }
                    }
                    // 释放
                    releaseVelocityTracker()
                    // LogUtils.i(TAG, "onTouch A ACTION_UP ACTION_CANCEL:velocityY:" + velocityX);
                    isTouching = false // 没有手指在摸我了
                }

                else -> {}
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Log.d(TAG, "onInterceptTouchEvent() called with: ev = [" + ev + "]");
        // 禁止侧滑时，点击事件不受干扰。
        if (isSwipeEnable) {
            when (ev.action) {
                MotionEvent.ACTION_MOVE ->                         // 屏蔽滑动时的事件
                    if (Math.abs(ev.rawX - firstP.x) > scaleTouchSlop) {
                        return true
                    }

                MotionEvent.ACTION_UP -> {
                    // 为了在侧滑时，屏蔽子View的点击事件
                    if (isLeftSwipe) {
                        if (scrollX > scaleTouchSlop) {
                            // 解决一个智障问题~ 居然不给点击侧滑菜单 我跪着谢罪
                            // 这里判断落点在内容区域屏蔽点击，内容区域外，允许传递事件继续向下的的。。。
                            if (ev.x < width - scrollX) {
                                // 仿QQ，侧滑菜单展开时，点击内容区域，关闭侧滑菜单。
                                if (isUnMoved) {
                                    smoothClose()
                                }
                                return true // true表示拦截
                            }
                        }
                    } else {
                        if (-scrollX > scaleTouchSlop) {
                            if (ev.x > -scrollX) { // 点击范围在菜单外 屏蔽
                                // 仿QQ，侧滑菜单展开时，点击内容区域，关闭侧滑菜单。
                                if (isUnMoved) {
                                    smoothClose()
                                }
                                return true
                            }
                        }
                    }
                    // 判断手指起始落点，如果距离属于滑动了，就屏蔽一切点击事件。
                    if (isUserSwiped) {
                        return true
                    }
                }
            }
            // 模仿IOS 点击其他区域关闭：
            if (iosInterceptFlag) {
                // IOS模式开启，且当前有菜单的View，且不是自己的，拦截点击事件给子View
                return true
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * 平滑展开。
     */
    private var expandAnim: ValueAnimator? = null
    private var closeAnim: ValueAnimator? = null
    private var isExpand = false // 代表当前是否是展开状态

    init {
        init(context, attrs, defStyleAttr)
    }

    fun smoothExpand() {
        // Log.d(TAG, "smoothExpand() called" + this);
        /*mScroller.startScroll(getScrollX(), 0, mRightMenuWidths - getScrollX(), 0);
        invalidate();*/
        // 展开就加入ViewCache：
        viewCache = this@SwipeMenuLayout

        // 侧滑菜单展开，屏蔽content长按
        if (null != contentView) {
            contentView!!.isLongClickable = false
        }
        cancelAnim()
        expandAnim =
            ValueAnimator.ofInt(scrollX, if (isLeftSwipe) rightMenuWidths else -rightMenuWidths)
        expandAnim!!.addUpdateListener { animation ->
            scrollTo(
                (animation.animatedValue as Int),
                0
            )
        }
        expandAnim!!.interpolator = OvershootInterpolator()
        expandAnim!!.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isExpand = true
            }
        })
        expandAnim!!.setDuration(300).start()
    }

    /**
     * 每次执行动画之前都应该先取消之前的动画。
     */
    private fun cancelAnim() {
        if (closeAnim != null && closeAnim!!.isRunning) {
            closeAnim!!.cancel()
        }
        if (expandAnim != null && expandAnim!!.isRunning) {
            expandAnim!!.cancel()
        }
    }

    /**
     * 平滑关闭。
     */
    fun smoothClose() {
        // Log.d(TAG, "smoothClose() called" + this);
        /*        mScroller.startScroll(getScrollX(), 0, -getScrollX(), 0);
        invalidate();*/
        viewCache = null

        // 侧滑菜单展开，屏蔽content长按
        if (null != contentView) {
            contentView!!.isLongClickable = true
        }
        cancelAnim()
        closeAnim = ValueAnimator.ofInt(scrollX, 0)
        closeAnim!!.addUpdateListener { animation ->
            scrollTo(
                (animation.animatedValue as Int),
                0
            )
        }
        closeAnim!!.interpolator = AccelerateInterpolator()
        closeAnim!!.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isExpand = false
            }
        })
        closeAnim!!.setDuration(300).start()
        // LogUtils.d(TAG, "smoothClose() called with:getScrollX() " + getScrollX());
    }

    /**
     * @param event 向VelocityTracker添加MotionEvent
     * @see VelocityTracker.obtain
     * @see VelocityTracker.addMovement
     */
    private fun acquireVelocityTracker(event: MotionEvent) {
        if (null == velocityTracker) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)
    }

    /**
     * 释放VelocityTracker。
     *
     * @see VelocityTracker.clear
     * @see VelocityTracker.recycle
     */
    private fun releaseVelocityTracker() {
        if (null != velocityTracker) {
            velocityTracker!!.clear()
            velocityTracker!!.recycle()
            velocityTracker = null
        }
    }

    // 每次ViewDetach的时候，判断一下 ViewCache是不是自己，如果是自己，关闭侧滑菜单，且ViewCache设置为null，
    // 理由：1 防止内存泄漏(ViewCache是一个静态变量)
    // 2 侧滑删除后自己后，这个View被Recycler回收，复用，下一个进入屏幕的View的状态应该是普通状态，而不是展开状态。
    override fun onDetachedFromWindow() {
        if (this === viewCache) {
            viewCache!!.smoothClose()
            viewCache = null
        }
        super.onDetachedFromWindow()
    }

    // 展开时，禁止长按
    override fun performLongClick(): Boolean {
        return if (abs(scrollX) > scaleTouchSlop) {
            false
        } else super.performLongClick()
    }
    // 平滑滚动 弃用 改属性动画实现
    /*    @Override
    public void computeScroll() {
        // 判断Scroller是否执行完毕：
        if (mScroller.computeScrollOffset()) {
            scrollTo(mScroller.getCurrX(), mScroller.getCurrY());
            // 通知View重绘-invalidate()->onDraw()->computeScroll()
            invalidate();
        }
    }*/
    /**
     * 快速关闭。
     * 用于 点击侧滑菜单上的选项,同时想让它快速关闭(删除 置顶)。
     * 这个方法在ListView里是必须调用的，
     * 在RecyclerView里，视情况而定，如果是mAdapter.notifyItemRemoved(pos)方法不用调用。
     */
    fun quickClose() {
        if (this === viewCache) {
            // 先取消展开动画
            cancelAnim()
            viewCache!!.scrollTo(0, 0) // 关闭
            viewCache = null
        }
    }

    companion object {

        /**
         * 存储的是当前正在展开的View。
         */
        @SuppressLint("StaticFieldLeak")
        private var viewCache: SwipeMenuLayout? = null

        /**
         * 防止多只手指一起滑我的flag 在每次down里判断，touch事件结束清空。
         */
        private var isTouching = false

        /**
         * 返回ViewCache。
         *
         * @return
         */
        fun getViewCache(): SwipeMenuLayout? {
            return viewCache
        }
    }
}