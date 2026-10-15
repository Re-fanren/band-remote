/**
 * ============================================================
 *  手势识别模块（手环端）
 * ============================================================
 *
 *  输入：touchstart / touchmove / touchend 的触点坐标
 *  输出：SWIPE_UP / SWIPE_DOWN / SWIPE_LEFT / SWIPE_RIGHT / TAP / LONG_PRESS
 *
 *  判定规则（胶囊屏 192×490，纵向为主）：
 *    - 位移超过 MIN_DISTANCE 且主轴位移占优（> 1.2 倍次轴）→ 判定为对应方向滑动
 *    - 位移小于 TAP_DISTANCE 且时长 < TAP_DURATION  → 判定为点击
 *    - 位移小、时长超过 LONG_PRESS_DURATION        → 判定为长按
 *
 *  鼠标模式（CURSOR_MOVE）另用「增量上报」接口：
 *    getPendingDelta() 取「自上次上报以来」的位移，配合 advanceReported()
 *    推进上报基准点，保证滑动全程的位移一帧不漏、也不重复上报。
 */

// 触发滑动所需的最小位移（px）
export const MIN_DISTANCE = 36
// 判定为点击的最大位移（px）
export const TAP_DISTANCE = 16
// 点击最长时长（ms）
export const TAP_DURATION = 300
// 长按最短时长（ms）
export const LONG_PRESS_DURATION = 650

class GestureDetector {
  constructor() {
    this.reset()
  }

  reset() {
    this.startX = 0
    this.startY = 0
    this.lastX = 0
    this.lastY = 0
    // 「已上报基准点」：鼠标模式下用于计算增量位移，避免同一段位移重复上报
    this.reportedX = 0
    this.reportedY = 0
    this.startTime = 0
    this.tracking = false
  }

  /**
   * 手指按下
   */
  onStart(x, y) {
    this.startX = x
    this.startY = y
    this.lastX = x
    this.lastY = y
    this.reportedX = x
    this.reportedY = y
    this.startTime = Date.now()
    this.tracking = true
  }

  /**
   * 手指移动：仅记录，用于实时指示
   */
  onMove(x, y) {
    if (!this.tracking) {
      return
    }
    this.lastX = x
    this.lastY = y
  }

  /**
   * 手指抬起，返回识别结果
   * @returns {{type:string, dx:number, dy:number, duration:number}}
   */
  onEnd(x, y) {
    if (!this.tracking) {
      return { type: 'NONE', dx: 0, dy: 0, duration: 0 }
    }
    const endX = (x === undefined || x === null) ? this.lastX : x
    const endY = (y === undefined || y === null) ? this.lastY : y

    const dx = endX - this.startX
    const dy = endY - this.startY
    const duration = Date.now() - this.startTime
    const absX = Math.abs(dx)
    const absY = Math.abs(dy)
    const distance = Math.sqrt(dx * dx + dy * dy)

    this.tracking = false

    // 1) 滑动：位移达标且主轴占优
    if (distance >= MIN_DISTANCE) {
      if (absY >= absX * 1.2) {
        return { type: dy < 0 ? 'SWIPE_UP' : 'SWIPE_DOWN', dx: dx, dy: dy, duration: duration }
      }
      if (absX >= absY * 1.2) {
        return { type: dx < 0 ? 'SWIPE_LEFT' : 'SWIPE_RIGHT', dx: dx, dy: dy, duration: duration }
      }
      // 斜向：按位移更大的一轴兜底
      if (absY >= absX) {
        return { type: dy < 0 ? 'SWIPE_UP' : 'SWIPE_DOWN', dx: dx, dy: dy, duration: duration }
      }
      return { type: dx < 0 ? 'SWIPE_LEFT' : 'SWIPE_RIGHT', dx: dx, dy: dy, duration: duration }
    }

    // 2) 长按
    if (duration >= LONG_PRESS_DURATION) {
      return { type: 'LONG_PRESS', dx: dx, dy: dy, duration: duration }
    }

    // 3) 点击
    if (distance <= TAP_DISTANCE && duration < TAP_DURATION) {
      return { type: 'TAP', dx: dx, dy: dy, duration: duration }
    }

    return { type: 'NONE', dx: dx, dy: dy, duration: duration }
  }

  /**
   * 自上次上报以来、尚未上报的位移增量（鼠标模式使用）
   * 单位为手环手势区像素，可能为负；不足 1px 的余量会留到下次一并上报。
   * @returns {{dx:number, dy:number}}
   */
  getPendingDelta() {
    return {
      dx: this.lastX - this.reportedX,
      dy: this.lastY - this.reportedY
    }
  }

  /**
   * 已把「上报基准点」前移 (dx, dy)。
   * 调用方发送的是取整后的增量，因此这里按实际发送的整数前移，
   * 未被发送的小数余量自动留待下一次上报，位移不会丢失。
   */
  advanceReported(dx, dy) {
    this.reportedX += dx
    this.reportedY += dy
  }
}

export default GestureDetector
