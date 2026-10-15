/**
 * ============================================================
 *  双端通信协议 —— 指令常量与编解码（手环端）
 * ============================================================
 *
 * 帧格式（UTF-8 文本，行分隔）：
 *
 *      BR1|<CMD>|<SEQ>|<ARG>\n
 *
 *   BR1    ：协议魔数 + 版本号（BandRemote v1）
 *   CMD    ：指令字，见下方 CMD 常量（大写英文）
 *   SEQ    ：自增序号，用于 ACK 回执匹配
 *   ARG    ：可选参数（音量步长、坐标比例等），无参时留空
 *
 * 示例：
 *      BR1|SWIPE_UP|12|\n
 *      BR1|VOLUME_UP|13|up\n
 *      BR1|CLICK|14|n:0.512300,0.412300\n
 *      BR1|ACK|12|OK\n
 *
 * 详细指令字对照表见工程根目录 docs/PROTOCOL.md
 */

// 协议魔数与版本
export const MAGIC = 'BR1'
// 字段分隔符
export const SEP = '|'
// 帧结束符
export const EOL = '\n'

/**
 * 指令字常量
 * 命名规则：动词_宾语，全大写
 */
export const CMD = {
  // ---- 手势滑屏 ----
  SWIPE_UP: 'SWIPE_UP',        // 上滑
  SWIPE_DOWN: 'SWIPE_DOWN',    // 下滑
  SWIPE_LEFT: 'SWIPE_LEFT',    // 左滑
  SWIPE_RIGHT: 'SWIPE_RIGHT',  // 右滑

  // ---- 系统导航 ----
  BACK: 'BACK',                // 返回
  HOME: 'HOME',                // 返回桌面
  RECENTS: 'RECENTS',          // 多任务（最近任务）
  LOCK: 'LOCK',                // 锁屏

  // ---- 音量 ----
  VOLUME_UP: 'VOLUME_UP',      // 音量 +
  VOLUME_DOWN: 'VOLUME_DOWN',  // 音量 -
  VOLUME_MUTE: 'VOLUME_MUTE',  // 静音切换

  // ---- 指针 / 鼠标模式 ----
  CURSOR_ON: 'CURSOR_ON',      // 打开鼠标模式（手机端显示虚拟指针）
  CURSOR_OFF: 'CURSOR_OFF',    // 关闭鼠标模式
  CURSOR_MOVE: 'CURSOR_MOVE',  // 指针移动，ARG = "dx,dy"（相对上次上报的增量，单位手环手势区像素，可负）
  CLICK: 'CLICK',              // 单击，ARG = "n:nx,ny"（归一化 0~1，6 位小数）/ "x,y"（历史绝对像素）/ ""（鼠标模式：指针当前位置）

  // ---- 业务 ----
  APP_LAUNCH: 'APP_LAUNCH',    // 启动自定义快捷应用，ARG = 包名
  NOTIFY_DISMISS: 'NOTIFY_DISMISS', // 通知栏（骨架实现为下拉通知栏，见 docs/PROTOCOL.md）

  // ---- 链路控制 ----
  PING: 'PING',                // 心跳
  PONG: 'PONG',                // 心跳应答
  ACK: 'ACK',                  // 指令回执，ARG = OK / ERR:xxx
  LOG: 'LOG'                   // 调试日志透传
}

/**
 * 指令 -> 中文说明对照表
 * 用于手环端 UI 提示与调试输出，同时作为协议文档的单一数据源
 */
export const CMD_TEXT = {
  SWIPE_UP: '上滑',
  SWIPE_DOWN: '下滑',
  SWIPE_LEFT: '左滑',
  SWIPE_RIGHT: '右滑',
  BACK: '返回',
  HOME: '返回桌面',
  RECENTS: '多任务',
  LOCK: '锁屏',
  VOLUME_UP: '音量+',
  VOLUME_DOWN: '音量-',
  VOLUME_MUTE: '静音',
  CURSOR_ON: '鼠标模式开',
  CURSOR_OFF: '鼠标模式关',
  CURSOR_MOVE: '指针移动',
  CLICK: '单击',
  APP_LAUNCH: '启动应用',
  NOTIFY_DISMISS: '通知栏',
  PING: '心跳',
  PONG: '心跳应答',
  ACK: '回执',
  LOG: '日志'
}

/**
 * 组装一帧
 * @param {string} cmd 指令字
 * @param {number} seq 序号
 * @param {string} [arg] 参数
 * @returns {string} 完整帧字符串
 */
export function encode(cmd, seq, arg) {
  const a = (arg === undefined || arg === null) ? '' : String(arg)
  return MAGIC + SEP + cmd + SEP + seq + SEP + a + EOL
}

/**
 * 解析一帧
 * @param {string} raw 原始帧字符串
 * @returns {Object|null} { magic, cmd, seq, arg, text } 解析失败返回 null
 */
export function decode(raw) {
  if (!raw || typeof raw !== 'string') {
    return null
  }
  const line = raw.replace(/[\r\n]+$/, '')
  const parts = line.split(SEP)
  if (parts.length < 3) {
    return null
  }
  if (parts[0] !== MAGIC) {
    return null
  }
  const cmd = parts[1]
  const seq = parseInt(parts[2], 10)
  const arg = parts.length > 3 ? parts.slice(3).join(SEP) : ''
  return {
    magic: parts[0],
    cmd: cmd,
    seq: isNaN(seq) ? 0 : seq,
    arg: arg,
    text: CMD_TEXT[cmd] || cmd
  }
}

// ------------------------------------------------------------------
// 坐标参数编解码（CLICK / CURSOR_MOVE）
// ------------------------------------------------------------------

/** 归一化坐标参数前缀：ARG = "n:<nx>,<ny>"，nx / ny ∈ [0,1] */
export const NORMALIZED_PREFIX = 'n:'

/** 归一化坐标保留小数位（与手机端 Protocol.NORMALIZED_DIGITS 一致） */
export const NORMALIZED_DIGITS = 6

function clamp01(v) {
  const n = Number(v)
  if (isNaN(n)) {
    return 0
  }
  if (n < 0) {
    return 0
  }
  if (n > 1) {
    return 1
  }
  return n
}

/**
 * 组装归一化坐标参数（手环手势区点按位置 -> 手机屏幕比例）
 * @param {number} nx 0~1，沿手机自然竖屏的横向比例
 * @param {number} ny 0~1，沿手机自然竖屏的纵向比例
 * @returns {string} 形如 "n:0.512300,0.412300"
 */
export function encodeNormalizedPoint(nx, ny) {
  return NORMALIZED_PREFIX + clamp01(nx).toFixed(NORMALIZED_DIGITS) +
    ',' + clamp01(ny).toFixed(NORMALIZED_DIGITS)
}

/**
 * 解析坐标参数，兼容两种写法：
 *   "n:0.512300,0.412300"  归一化坐标（0~1，手环手势区点按位置）
 *   "540,1200"             历史绝对坐标（手机物理像素）
 * @param {string} arg
 * @returns {Object|null} { normalized, x, y }，解析失败返回 null
 */
export function decodePointArg(arg) {
  if (!arg || typeof arg !== 'string') {
    return null
  }
  const raw = arg.trim()
  if (!raw) {
    return null
  }
  const normalized = raw.slice(0, NORMALIZED_PREFIX.length).toLowerCase() === NORMALIZED_PREFIX
  const body = normalized ? raw.slice(NORMALIZED_PREFIX.length) : raw
  const parts = body.split(',')
  if (parts.length < 2) {
    return null
  }
  const x = parseFloat(parts[0])
  const y = parseFloat(parts[1])
  if (isNaN(x) || isNaN(y)) {
    return null
  }
  if (normalized && (x < 0 || x > 1 || y < 0 || y > 1)) {
    return null
  }
  if (!normalized && (x < 0 || y < 0)) {
    return null
  }
  return { normalized: normalized, x: x, y: y }
}

// ------------------------------------------------------------------
// 位移参数编解码（CURSOR_MOVE）
// ------------------------------------------------------------------

/**
 * 单帧位移上限（手环手势区像素），与手机端 Protocol.MAX_DELTA_STEP 一致。
 * 超过该值视为异常帧（例如手势状态错乱），手机端会直接丢弃。
 */
export const MAX_DELTA_STEP = 400

/**
 * 组装位移参数，例如 "12,-8"
 * 语义：相对「上一次上报」的增量，单位手环手势区像素，可为负。
 * @param {number} dx 横向增量
 * @param {number} dy 纵向增量
 * @returns {string} 形如 "12,-8"
 */
export function encodeDelta(dx, dy) {
  const ix = Math.round(Number(dx) || 0)
  const iy = Math.round(Number(dy) || 0)
  return ix + ',' + iy
}

/**
 * 解析位移参数 "dx,dy"
 * @param {string} arg
 * @returns {Object|null} { dx, dy }，空参 / 非法 / 全零返回 null
 */
export function decodeDelta(arg) {
  if (!arg || typeof arg !== 'string') {
    return null
  }
  const raw = arg.trim()
  if (!raw) {
    return null
  }
  const parts = raw.split(',')
  if (parts.length < 2) {
    return null
  }
  const dx = parseFloat(parts[0])
  const dy = parseFloat(parts[1])
  if (isNaN(dx) || isNaN(dy) || !isFinite(dx) || !isFinite(dy)) {
    return null
  }
  if (Math.abs(dx) > MAX_DELTA_STEP || Math.abs(dy) > MAX_DELTA_STEP) {
    return null
  }
  if (dx === 0 && dy === 0) {
    return null
  }
  return { dx: dx, dy: dy }
}

export default {
  MAGIC,
  SEP,
  EOL,
  CMD,
  CMD_TEXT,
  NORMALIZED_PREFIX,
  NORMALIZED_DIGITS,
  MAX_DELTA_STEP,
  encode,
  decode,
  encodeNormalizedPoint,
  decodePointArg,
  encodeDelta,
  decodeDelta
}
