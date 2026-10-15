/**
 * ============================================================
 *  互联通道封装（手环端 · 可替换）
 * ============================================================
 *
 *  职责：
 *    1. 通过 @system.interconnect（小米互联蓝牙通道）与手机端 App 建立连接；
 *    2. 向上层提供与具体通道无关的 send(cmd, arg) / onMessage(cb) 接口；
 *    3. 当 interconnect 不可用（模拟器 / SDK 未提供 / 未配对 / 未登录小米账号）时，
 *       自动降级为「本地模拟通道」，保证工程在任意环境下都能跑通交互逻辑。
 *
 *  ★ 可替换点 ★
 *    - 若你的 Vela SDK 版本 interconnect API 名称不同（如 connect/send/onMessage 等），
 *      只需修改本文件中标注 [ADAPTER] 的三处即可，上层协议与页面代码无需改动。
 *    - 若后续改用 BLE GATT 直连，可新增一个 transport-ble.js 实现同样接口，
 *      在 app.ux 中替换初始化对象即可。
 */

import protocol from './protocol'
import logger from './logger'

// ------------------------------------------------------------------
// 通道状态枚举
// ------------------------------------------------------------------
export const STATE = {
  DISCONNECTED: 0,  // 未连接
  CONNECTING: 1,    // 连接中
  CONNECTED: 2      // 已连接
}

// 注册到小米互联的服务名（手机端需使用同名服务与之握手）
const SERVICE_NAME = 'com.marvis.bandremote'

/**
 * ★ 构建标识，会显示在手环连接页上。
 *   用途：排查时一眼确认「手上装的到底是哪一版」——此前反复出现
 *   "我改了但用户装的还是旧版"的错位，白跑很多轮。改这里必须同步 +1。
 */
export const BUILD_TAG = 'r12'

/**
 * 本版包含的能力清单（同样显示在页面上，便于核对）。
 */
export const BUILD_FEATURES = [
  'require 加载系统模块',
  'init 后立即探活（不等 onopen）',
  'data 用 {frame} 对象包装（对齐 bandqq）',
  'conn 能力 dump（判断实例是否有效）',
  '发送失败参数全 dump'
]

/** 把回调参数原样转成可读字符串（不猜结构，便于在页面上直接看） */
function dumpArgs(args) {
  return (args || []).map((a) => {
    if (a === null) return 'null'
    if (a === undefined) return 'undefined'
    if (typeof a === 'object') {
      try {
        return JSON.stringify(a)
      } catch (e) {
        return String(a)
      }
    }
    return String(a)
  }).join(' | ')
}

// ★★ 互联模块句柄。
//   bandqq（同为小米手环9 + AstroBox + 蓝牙互联，已验证可行）在 api.js 里明确注释：
//   「使用 Vela 快应用支持的 require() 方式加载系统模块」。
//   我们此前改成「页面 import 后注入」，但 import 拿到的是默认导出包装对象，
//   require 拿到的是模块本体，两者 instance() 的行为可能不同 ——
//   这正是手环端"能建实例却发送失败、始终未连接"的最大可疑点。
//   故恢复以 require 为主，页面注入仅作兜底。
let interconnect = null
let systemInterconnect = null

/**
 * 页面注入的互联模块（兜底路径，正常应走 resolveInterconnect 的 require）
 */
export function setInterconnectModule(mod) {
  if (mod) {
    interconnect = mod
    logger.log('互联模块已由页面注入（兜底）')
  }
}

/**
 * 解析互联模块：优先 require（与 bandqq 一致），失败再退回页面注入。
 */
function resolveInterconnect() {
  if (systemInterconnect) {
    return systemInterconnect
  }
  try {
    systemInterconnect = require('@system.interconnect')
    if (systemInterconnect) {
      logger.log('互联模块：require 获取成功')
      return systemInterconnect
    }
  } catch (e) {
    logger.warn('require @system.interconnect 失败', e)
  }
  if (interconnect) {
    logger.log('互联模块：回退用页面注入的对象')
    return interconnect
  }
  return null
}

class Transport {
  constructor() {
    this.state = STATE.DISCONNECTED
    this.mock = false                 // 是否处于本地模拟模式
    this.inited = false               // 是否已完成初始化（保证 init 幂等）
    this.conn = null                  // 小米互联连接实例（instance() 返回值）
    this.seq = 0                      // 指令自增序号
    this.listeners = []               // 状态变化监听
    this.messageHandlers = []         // 收到手机端下发消息的监听
    this.pending = {}                 // seq -> 回调，等待 ACK
    this.heartbeatTimer = null
    this.lastError = ''
    // ★ 诊断信息：手环端日志在本机完全读不到，只能把这些值渲染到页面上给人看。
    this.diag = {
      ready: '',      // getReadyState 返回的状态
      apkStatus: '',  // getApkStatus()：手机端 App 状态
      code: '',       // diagnosis() 返回码：0=OK / 204=超时 / 1001=对端未安装
      msg: '',        // 最近一次诊断/错误的文字
      capability: ''  // conn 实例上的可用方法列表（判断 instance() 是否有效）
    }
  }

  // ---------------- 对外：状态 ----------------

  getState() {
    return this.state
  }

  /**
   * 状态文本，页面直接展示
   */
  getStateText() {
    if (this.mock) {
      return '本地模拟'
    }
    switch (this.state) {
      case STATE.CONNECTED: return '已连接'
      case STATE.CONNECTING: return '连接中'
      default: return '未连接'
    }
  }

  /**
   * 是否可真实下发（未连接时为 false，页面可提示用户）
   */
  isOnline() {
    return this.state === STATE.CONNECTED
  }

  onStateChange(cb) {
    if (typeof cb === 'function') {
      this.listeners.push(cb)
    }
  }

  onMessage(cb) {
    if (typeof cb === 'function') {
      this.messageHandlers.push(cb)
    }
  }

  emitState() {
    const text = this.getStateText()
    this.listeners.forEach((cb) => {
      try {
        cb(this.state, text, this.mock)
      } catch (e) {
        logger.error('状态监听回调异常', e)
      }
    })
  }

  // ---------------- 对外：初始化 / 释放 ----------------

  /**
   * 初始化通道。失败自动降级模拟。
   * 幂等：重复调用不会重复注册（页面切换可能多次触发）
   */
  init() {
    if (this.inited) {
      logger.log('通道已初始化，跳过重复 init')
      return
    }

    // ★ 优先 require（与 bandqq 一致），失败再退回页面 import 注入的对象
    const mod = resolveInterconnect()
    if (!mod) {
      // ★ 注意：这里刻意【不】置 inited —— 模块没到位时允许后续页面再次重试
      this.enterMock('interconnect 模块不可用')
      return
    }
    this.inited = true
    this.state = STATE.CONNECTING
    this.emitState()

    try {
      // ★ 官方 API（以官方互联 demo 的 index.ux 为基准）：
      //   interconnect.instance() 无参数，返回连接实例 conn；
      //   conn 上挂 onmessage / onopen / onclose / onerror；
      //   用 conn.send({ data }) 下发，用 conn.getReadyState({success}) 查询连断（1 连 / 2 断）。
      //   旧实现把 instance 当作「带 name/success/fail 的注册调用」，且把 send/onmessage
      //   挂在模块对象上 —— 这两处错误导致永远进入本地模拟，这就是「能打开不能遥控」的根因。
      this.conn = mod.instance()

      // ★★ 探测 conn 的真实能力：把可用方法名列到页面上。
      //   此前 conn 上连 diagnosis 都没有，强烈怀疑 instance() 返回的不是有效实例
      //   （可能拿错了模块对象 / 需要参数 / 需要先完成某步）。 dump 出来一看便知。
      this.probeConnCapability()

      this.conn.onmessage = (res) => {
        const raw = res && (res.data || res.message) ? (res.data || res.message) : res
        this.handleIncoming(raw)
      }
      this.conn.onopen = () => {
        logger.log('interconnect 通道已打开')
        this.markConnected('onopen')
      }
      this.conn.onclose = (res) => {
        // ★ 记录关闭原因：之前只改状态不留原因，导致"手环连不上"完全无法定位
        const reason = res && (res.data || res.message || res.reason)
        logger.warn('interconnect 通道已关闭', JSON.stringify(res))
        this.state = STATE.DISCONNECTED
        this.diag.msg = '通道关闭' + (reason ? '：' + reason : '')
        this.stopHeartbeat()
        this.emitState()
        // 关掉后立刻诊断一次，把原因码抓到手
        setTimeout(() => this.runDiagnosis(), 500)
      }
      this.conn.onerror = (res) => {
        logger.warn('interconnect 通道错误', JSON.stringify(res))
        this.diag.msg = '错误 ' + ((res && (res.data || res.message)) || (res && res.code) || '')
        this.emitState()
      }

      // 主动查询一次当前连接状态，兜底 onopen 不触发的情况
      if (typeof this.conn.getReadyState === 'function') {
        this.conn.getReadyState({
          success: (res) => {
            this.diag.ready = String((res && res.status) !== undefined ? res.status : '')
            if (res && res.status === 1) {
              logger.log('getReadyState 已连接')
              this.markConnected('getReadyState')
            } else {
              logger.log('getReadyState 未连接 status=' + (res && res.status))
            }
          },
          fail: (res, code) => {
            logger.warn('getReadyState 失败 code=' + code)
            this.diag.msg = 'getReadyState 失败 code=' + code
            this.emitState()
          }
        })
      }

      // 抓取手机端 App 状态（同步）
      if (typeof this.conn.getApkStatus === 'function') {
        try {
          this.diag.apkStatus = String(this.conn.getApkStatus())
          logger.log('getApkStatus =', this.diag.apkStatus)
        } catch (e) {
          logger.warn('getApkStatus 异常', e)
        }
      }

      // ★ 连接诊断：返回码是定位"手环连不上手机"的唯一线索
      //   0=正常 / 204=超时 / 1001=对端应用未安装（其余按 SDK 定义）
      this.runDiagnosis()

      // [ADAPTER 3/3] 兼容旧式事件监听（个别固件版本）
      if (typeof interconnect.on === 'function') {
        interconnect.on('message', (data) => this.handleIncoming(data))
      }

      // ★★ 主动探活发送（最关键的一步，见 startProbing 的说明）
      this.startProbing()

      // ★ 自动重试重建实例：官方 demo 在 onShow() 里每次都重新 instance()，
      //   说明单次 instance() 并不保证拿到可用实例（互联服务可能晚于应用就绪）。
      //   我们原来 init() 幂等、只创建一次，一旦首次失败就永远卡在"连接中"。
      this.startAutoRetry()
    } catch (e) {
      logger.error('初始化 interconnect 异常', e)
      this.enterMock('初始化异常')
    }
  }

  /**
   * ★★★ 主动探活：不看当前是否「已连接」，无条件周期发送 PING。
   *
   * 为什么必须有（本项目的关键根因之一）：
   *   参考已验证可行的 bandqq（同为小米手环9 + AstroBox 安装 + 蓝牙互联），
   *   它在 app.onCreate 里 init() 之后【立刻连续 send()】，完全不等 onopen；
   *   且 waitReady() 只有 10s 超时，超时后照样发。
   *   → 小米互联通道表现为「首次发送才激活」：一直不发，onopen 可能永远不触发。
   *
   *   我们原来的逻辑是「等 onopen / getReadyState=1 → 置 CONNECTED → 才允许发」，
   *   而 CONNECTED 又依赖 onopen，形成死锁：永远不发 → 永远连不上 → 永远卡在"连接中"。
   *
   * 闭环：手环发 PING → 手机收到首帧置 CONNECTED 并回 PONG → 手环收到 PONG 置 CONNECTED。
   */
  startProbing() {
    this.stopProbing()
    let n = 0
    const shoot = () => {
      if (this.mock || !this.conn) {
        return false
      }
      n += 1
      this.sendRaw(protocol.encode(protocol.CMD.PING, this.seq, ''))
      logger.log('探活发送 #' + n)
      return true
    }
    shoot() // 立刻先发一帧，不等任何连接确认
    this.probeTimer = setInterval(() => {
      if (this.state === STATE.CONNECTED) {
        this.stopProbing() // 已连上，交给常规 20s 心跳
        return
      }
      if (n >= 20) {
        this.stopProbing()
        this.diag.msg = '探活 20 次无回应'
        this.emitState()
        return
      }
      shoot()
    }, 3000)
  }

  stopProbing() {
    if (this.probeTimer) {
      clearInterval(this.probeTimer)
      this.probeTimer = null
    }
  }

  /**
   * 列出 conn 实例上可用的方法名，写入 diag 供页面显示。
   * ★ 用途：判断 instance() 返回的是否为有效实例。
   *   若方法列表为空或明显不含 send / onmessage 支持，说明拿到的对象不对。
   */
  probeConnCapability() {
    try {
      const names = []
      let o = this.conn
      let depth = 0
      while (o && o !== Object.prototype && depth < 6) {
        Object.getOwnPropertyNames(o).forEach((n) => {
          if (names.indexOf(n) < 0) {
            let t = null
            try {
              t = typeof this.conn[n]
            } catch (e) {
              t = '?'
            }
            if (t === 'function' || t === 'object') {
              names.push(n + (t === 'function' ? '()' : ''))
            }
          }
        })
        o = Object.getPrototypeOf(o)
        depth += 1
      }
      this.diag.capability = names.join(' ')
      logger.log('conn 能力：' + this.diag.capability)
      this.emitState()
    } catch (e) {
      logger.error('探测 conn 能力失败', e)
      this.diag.capability = '探测失败'
    }
  }

  /**
   * 未连上时周期性重建互联实例（最多 5 次）。
   * 参考官方 demo：页面每次 onShow 都会重新 instance()。
   */
  startAutoRetry() {
    this.stopAutoRetry()
    let n = 0
    this.autoRetryTimer = setInterval(() => {
      if (this.state === STATE.CONNECTED) {
        this.stopAutoRetry()
        return
      }
      n += 1
      if (n > 5) {
        this.stopAutoRetry()
        this.diag.msg = '已重试 5 次仍未连上'
        this.emitState()
        return
      }
      logger.log('自动重试：第 ' + n + ' 次重建互联实例')
      this.diag.msg = '重试中 ' + n + '/5'
      this.emitState()
      try {
        // ★ 传 true：保留重试定时器。否则 release() 会把重试本身也停掉，只能重试一次。
        this.release(true)
        this.init()
      } catch (e) {
        logger.error('重试异常', e)
      }
    }, 3000)
  }

  stopAutoRetry() {
    if (this.autoRetryTimer) {
      clearInterval(this.autoRetryTimer)
      this.autoRetryTimer = null
    }
  }

  /**
   * 释放资源
   * @param {boolean} [keepRetry=false] 为 true 时保留自动重试定时器（供 startAutoRetry 调用）
   */
  release(keepRetry) {
    this.stopHeartbeat()
    this.stopProbing()
    if (!keepRetry) {
      this.stopAutoRetry()
    }
    try {
      if (this.conn && typeof this.conn.release === 'function') {
        this.conn.release()
      }
    } catch (e) {
      logger.warn('release 异常', e)
    }
    this.conn = null
    this.state = STATE.DISCONNECTED
    this.inited = false
    this.mock = false
    this.emitState()
  }

  // ---------------- 对外：发送 ----------------

  /**
   * 下发一条指令
   * @param {string} cmd  protocol.CMD 中的指令字
   * @param {string} [arg] 参数
   * @param {Function} [cb] ACK 回调 (ok, arg)
   * @returns {number} 本次使用的 seq
   */
  send(cmd, arg, cb) {
    this.seq += 1
    const seq = this.seq
    const frame = protocol.encode(cmd, seq, arg)

    if (cb) {
      this.pending[seq] = cb
      // 3s 未收到 ACK 视为超时
      setTimeout(() => {
        if (this.pending[seq]) {
          const fn = this.pending[seq]
          delete this.pending[seq]
          fn(false, 'TIMEOUT')
        }
      }, 3000)
    }

    // ★ 仅当明确处于模拟模式、或连接实例尚未建立时才走模拟；
    //   不再以 isOnline()（要求 state==CONNECTED）作为门槛 —— 因为 onopen
    //   可能晚于用户首次点击到达，而此时 conn 已可下发。
    if (this.mock || !this.conn) {
      logger.log('[MOCK TX]', frame.replace(protocol.EOL, ''))
      return seq
    }

    try {
      this.conn.send({
        // ★ 与 sendRaw 一致：data 用对象包装（对齐 bandqq），不要直接传字符串
        data: { frame: frame },
        success: () => logger.log('[TX OK]', cmd, seq),
        fail: (...args) => {
          logger.warn('[TX FAIL]', cmd, seq, args)
          this.diag.msg = '发送失败 args=[' + dumpArgs(args) + ']'
          this.onLinkLost()
        }
      })
    } catch (e) {
      logger.error('发送异常', e)
      this.diag.msg = '发送异常 ' + (e && e.message ? e.message : String(e))
      this.onLinkLost()
    }
    return seq
  }

  /**
   * 直接下发一条**已编码**的帧，不重新分配 SEQ。
   *
   * ★ 为什么需要它：PONG 这类应答帧必须沿用对端 PING 的 SEQ（协议 2.6 / 3.2），
   *   而 send() 一定会自增新 SEQ，二者不可互相替代。
   *
   * ★★ data 必须用对象 { frame } 包装，不能直接传字符串：
   *   参考已验证可行的 bandqq，它传的是 payload 对象；我们原来直接传字符串，
   *   实测 fail 回调被触发、且 code=undefined（本固件上 fail 的参数结构与
   *   官方 demo 的 fail(res, code) 不一致）。故改为对象并对齐。
   *
   * @param {string} frame 已由 protocol.encode 生成的完整帧（含换行）
   * @returns {boolean} 是否真实下发（模拟模式返回 false）
   */
  sendRaw(frame) {
    if (this.mock || !this.conn) {
      logger.log('[MOCK TX]', String(frame).replace(protocol.EOL, ''))
      return false
    }
    try {
      this.conn.send({
        data: { frame: frame },
        success: () => logger.log('[TX OK]', String(frame).replace(protocol.EOL, '')),
        // ★ 不猜参数结构：官方 demo 写作 fail(res, code)，但实测本固件上 code 恒 undefined，
        //   说明签名可能是 fail(res) / fail(code) / fail(res, code) 之一。
        //   直接把全部参数 dump 到页面，避免再次靠猜。
        fail: (...args) => {
          logger.warn('[TX FAIL]', args)
          this.diag.msg = '发送失败 args=[' + dumpArgs(args) + ']'
          this.onLinkLost()
        }
      })
      return true
    } catch (e) {
      logger.error('发送异常', e)
      this.diag.msg = '发送异常 ' + (e && e.message ? e.message : String(e))
      this.onLinkLost()
      return false
    }
  }

  // ---------------- 内部 ----------------

  /**
   * 处理手机端下发（ACK / PONG / LOG 等）
   */
  handleIncoming(data) {
    let raw = ''
    if (typeof data === 'string') {
      raw = data
    } else if (data && typeof data.data === 'string') {
      raw = data.data
    } else if (data && typeof data.message === 'string') {
      raw = data.message
    }
    if (!raw) {
      return
    }

    // 兼容单帧与多帧粘连（按 \n 拆）
    const lines = raw.split(/\n+/).filter((s) => s.length > 0)
    lines.forEach((line) => {
      const frame = protocol.decode(line)
      if (!frame) {
        return
      }
      logger.log('[RX]', frame.cmd, frame.seq, frame.arg)

      if (frame.cmd === protocol.CMD.ACK) {
        const fn = this.pending[frame.seq]
        if (fn) {
          delete this.pending[frame.seq]
          fn(frame.arg === 'OK', frame.arg)
        }
        return
      }
      if (frame.cmd === protocol.CMD.PONG) {
        // ★ 手机端应答了我们的探活 → 双向通道打通，据此置为已连接
        //   （不依赖 onopen，摆脱"等 onopen 才发、不发又等不到 onopen"的死锁）
        this.markConnected('pong')
        return
      }
      if (frame.cmd === protocol.CMD.PING) {
        // 手机端心跳，回 PONG。
        // ★ 协议要求：BR1|PONG|<与 PING 相同的 SEQ>|<空 ARG>（docs/PROTOCOL.md 2.6 / 3.2）。
        //   不能直接 send(PONG, String(frame.seq)) —— send() 会自增一个新 SEQ，
        //   并把原 SEQ 当成 ARG 塞进帧里，对端按 SEQ 配对时永远匹配不上。
        this.sendRaw(protocol.encode(protocol.CMD.PONG, frame.seq, ''))
        return
      }

      // 其余指令透传给页面
      this.messageHandlers.forEach((cb) => {
        try {
          cb(frame)
        } catch (e) {
          logger.error('消息回调异常', e)
        }
      })
    })
  }

  /**
   * 连接诊断（官方 API：conn.diagnosis({timeout, success, fail})）
   *
   * ★ 为什么必须有：手环端的日志在本机完全读不到，而"手机已连接、手环没连上"这类
   *   单向故障只能靠诊断码区分原因。结果写进 this.diag 并广播，由页面渲染出来给人看。
   *   已知返回码：0=正常 / 204=超时 / 1001=对端应用未安装。
   */
  runDiagnosis() {
    if (!this.conn || typeof this.conn.diagnosis !== 'function') {
      // ★ 接口不可用时只记日志、不覆盖 diag.msg —— 此前这里会盖掉更有用的
      //   「发送失败 args=[...]」，导致真正的错误原因传不出来。
      logger.warn('diagnosis 接口在本固件上不可用（conn 无 diagnosis 方法）')
      return
    }
    try {
      this.conn.diagnosis({
        timeout: 10000,
        success: (res) => {
          const code = res && res.status !== undefined ? res.status : res
          this.diag.code = String(code)
          this.diag.msg = '诊断码 ' + code
          logger.log('diagnosis 返回', code, JSON.stringify(res))
          // 诊断通过说明对端可达，顺手把状态拉起（并发握手帧让手机端确认）
          if (String(code) === '0') {
            this.markConnected('diagnosis')
          }
          this.emitState()
        },
        fail: (res, code) => {
          this.diag.code = String(code)
          this.diag.msg = '诊断失败 code=' + code
          logger.warn('diagnosis 失败 code=' + code, JSON.stringify(res))
          this.emitState()
        }
      })
    } catch (e) {
      logger.error('diagnosis 异常', e)
      this.diag.msg = '诊断异常'
      this.emitState()
    }
  }

  /**
   * 标记已连接，并**立刻发出一帧握手**。
   *
   * ★ 为什么必须主动发：手机端把「收到手环端第一帧」作为真正连通的判据
   *   （此前它只要 addListener 成功就显示"已连接"，那是假象 —— 手环上没装应用也会显示已连接）。
   *   不主动发的话，要等 20 秒后的第一次心跳，手机端才会变「已连接」，排查时会误以为没通。
   *
   * @param {string} source 触发来源，便于定位（onopen / getReadyState / diagnosis）
   */
  markConnected(source) {
    const was = this.state
    this.stopProbing()    // 探活已达成目的，交给常规心跳
    this.stopAutoRetry()  // 已连上，停止重建重试
    this.state = STATE.CONNECTED
    this.mock = false
    this.lastError = ''
    this.startHeartbeat()
    this.emitState()
    if (was !== STATE.CONNECTED) {
      this.sendRaw(protocol.encode(protocol.CMD.PING, this.seq, ''))
      logger.log('标记已连接（' + source + '），已发出握手帧')
    }
  }

  startHeartbeat() {
    this.stopHeartbeat()
    // 每 20s 一次心跳，维持链路活性
    this.heartbeatTimer = setInterval(() => {
      if (this.isOnline()) {
        this.send(protocol.CMD.PING, '')
      }
    }, 20000)
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  onLinkLost() {
    this.state = STATE.DISCONNECTED
    this.stopHeartbeat()
    this.emitState()
  }

  /**
   * 进入本地模拟模式
   */
  enterMock(reason) {
    this.stopAutoRetry()
    this.mock = true
    this.state = STATE.DISCONNECTED
    this.lastError = reason || ''
    logger.warn('进入本地模拟模式，原因：', reason)
    this.emitState()
  }

  /**
   * 手动重连（页面「重新连接」按钮调用）
   */
  reconnect() {
    this.release()
    this.mock = false
    this.init()
  }
}

// 单例导出
export default new Transport()
