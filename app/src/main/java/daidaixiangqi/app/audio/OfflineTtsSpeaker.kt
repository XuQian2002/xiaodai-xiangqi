package daidaixiangqi.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import daidaixiangqi.app.GameController
import daidaixiangqi.app.MoveNotation
import daidaixiangqi.app.model.Board
import daidaixiangqi.app.model.Move
import daidaixiangqi.app.model.PieceColor
import daidaixiangqi.app.model.SweepCapture
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

class OfflineTtsSpeaker(context: Context) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var ready = false

    @Volatile
    private var failed = false

    @Volatile
    private var track: AudioTrack? = null

    private var enabled = true

    @Volatile
    private var pendingText: String? = null

    private val activeToken = AtomicInteger(0)

    // 单线程合成队列：合成播放都在这一个常驻线程上完成，避免二次线程调度抬高新句起播延迟；
    // 同时保证播报顺序，避免两个线程抢合成锁造成的排队抖动；推理内部线程数不受影响。
    // 线程提至 URGENT_DISPLAY 级，落子后播报优先被调度，减少与引擎搜索/UI 的争用延迟。
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            runnable.run()
        }
    }

    private val synthLock = ReentrantLock()

    /** 主线程 Handler：用于延时空闲投递补灌任务。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 空闲补灌缓存的进度下标；达到 WARMUP_TEXTS 长度即补灌完成。 */
    @Volatile
    private var prefillIndex = 0

    @Volatile
    private var prefillScheduled = false

    /**
     * 空闲补灌线程：优先级低于播报线程，只在没有真实播报时逐句合成灌缓存。
     * 与播报共用 synthLock，且合成回调里一发现新播报就中止当前句，让出锁给真实播报。
     */
    private val prefillWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }.apply { isDaemon = true }
    }

    /** 已合成语句的 PCM LRU 缓存：重复句命中即直接回放，跳过本轮模型推理。仅在持有 synthLock 时访问。 */
    private val pcmCache = object : LinkedHashMap<String, FloatArray>(PCM_CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > PCM_CACHE_CAPACITY
    }

    /** 合成线程数：按设备可用核心自适应，上限 8，避免线程超订反而互相挤占。 */
    private val synthThreads = minOf(TTS_THREADS, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))

    /** 队列中尚未开始的最新播报任务；新句到来时取消旧任务，避免上一句排队拖住新句起播。 */
    @Volatile
    private var pendingTask: Future<*>? = null

    init {
        // 引擎初始化（拷贝/加载 onnx 模型、词典、热身）放在独立后台线程，不占用播报常驻线程；
        // 否则首句落子播报会排队等待初始化完成，抬高首字开口延迟。
        Thread {
            // 加载模型与热身提至显示级优先级，让引擎更早就绪，缩短首句冷启动等待。
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            initEngine()
        }.apply { isDaemon = true }.start()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) stopPlayback()
    }

    fun isEnabled(): Boolean = enabled

    /**
     * 落子播报：构文（吃子名、将军判定、抽吃识别、记谱成句）在回调线程算好，
     * 即刻把最新任务提交到合成常驻线程；新句到来时丢弃队列里尚未开始的旧任务，
     * 避免上一句排队拖住新句出声。
     */
    fun announceMove(
        move: Move,
        boardBefore: Board,
        currentBoard: Board
    ) {
        if (!enabled || failed) return
        // 构文（吃子名、将军、抽吃识别、记谱成句）在回调线程算好，合成线程一醒来即可进入推理，
        // 不再让这些纯计算串行排在模型前向之前，抬高首句开口延迟。
        val raw = try {
            val capturedName = move.capturedPiece?.let { it.type.getDisplayName(it.color) }
            val checked = currentBoard.isInCheck(currentBoard.currentPlayer)
            val sweep = if (checked) SweepCapture.detect(currentBoard, move, move.piece.color) else null
            composeAnnouncement(
                move.piece.color, MoveNotation.format(move, boardBefore), capturedName, checked, sweep
            )
        } catch (t: Throwable) {
            Log.w(TAG, "compose announcement failed", t)
            return
        }
        val token = activeToken.incrementAndGet()
        submitLatest(token) { enqueue(token, raw) }
    }

    fun announceStatus(text: String) {
        if (!enabled || failed) return
        val token = activeToken.incrementAndGet()
        submitLatest(token) { enqueue(token, text) }
    }

    /** 取消队列中尚未开始的旧任务，提交最新任务到合成常驻线程。 */
    private fun submitLatest(token: Int, task: () -> Unit) {
        pendingTask?.cancel(false)
        pendingTask = executor.submit {
            if (token != activeToken.get()) return@submit
            task()
        }
    }

    /** 已在 TTS 合成线程内调用；入参文本在构文阶段已规范化，直接合成。 */
    private fun enqueue(token: Int, rawText: String) {
        if (!enabled || failed) return
        if (token != activeToken.get()) return
        if (!ready) {
            pendingText = rawText
            return
        }
        play(token, rawText)
    }

    private fun initEngine() {
        try {
            val modelDir = prepareModelDir()
            val vits = OfflineTtsVitsModelConfig.builder()
                .setModel(File(modelDir, MODEL_ONNX).absolutePath)
                .setLexicon(File(modelDir, LEXICON).absolutePath)
                .setTokens(File(modelDir, TOKENS).absolutePath)
                .build()

            val modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vits)
                .setNumThreads(synthThreads)
                .setDebug(false)
                .setProvider("cpu")
                .build()

            val config = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setRuleFsts(File(modelDir, PHONE_FST).absolutePath)
                .build()

            val engine = OfflineTts(config)
            tts = engine
            track = createTrack(engine.getSampleRate())
            // 只跑一句最短热身，把模型算子与推理内存拉起来即可放行播报；
            // 不再等 18 句预热全部合成完才 ready，避免开局前几秒落子被热身队列拖住。
            warmupTrack(engine.getSampleRate())
            warmupFirst(engine)
            ready = true

            pendingText?.let { enqueue(activeToken.incrementAndGet(), it) }
            pendingText = null
            Log.i(TAG, "Offline TTS ready (sampleRate=${engine.getSampleRate()})")
            // 其余常用句改到空闲时逐句补灌，有真实播报就让位。
            schedulePrefill(PREFILL_FIRST_DELAY_MS)
        } catch (t: Throwable) {
            Log.e(TAG, "Offline TTS init failed; announcing disabled for this session", t)
            failed = true
            pendingText = null
            releaseInternal()
        }
    }

    private fun prepareModelDir(): File {
        val dir = File(appContext.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        for (asset in ASSET_FILES) {
            val target = File(dir, asset)
            if (!target.exists() || target.length() == 0L) {
                appContext.assets.open("$MODEL_DIR/$asset").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
        return dir
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = if (minBuf > 0) minBuf else sampleRate * 2
        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setSampleRate(sampleRate)
            .build()

        // 优先走低延迟输出通道，把音频回放缓冲压到最小，减少落子后首块样本的开口等待；
        // 低延迟模式不可用时（个别机型/格式不支持）回退普通通道，保证能出声。
        val audioTrack = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "low-latency track unavailable, fallback to normal track", t)
            AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE)
        }
        audioTrack.play()
        return audioTrack
    }

    private fun play(token: Int, text: String) {
        val engine = tts ?: return
        if (token != activeToken.get() || !enabled) return

        // 空闲补灌线程同样会读写 pcmCache 并占用引擎，这里全程持同一把锁互斥。
        synthLock.lock()
        try {
            if (token != activeToken.get() || !enabled) return

            // 缓存命中：直接回放已合成 PCM，跳过模型推理，落子即出声。
            val cached = pcmCache[text]
            if (cached != null) {
                stopPlayback()
                val audioTrack = track ?: return
                // 分片写入，中途若被更新的句子打断则尽快退出，避免旧句整段阻塞拖住新句起播。
                var off = 0
                val chunk = 1024
                while (off < cached.size) {
                    if (token != activeToken.get() || !enabled) return
                    val n = minOf(chunk, cached.size - off)
                    audioTrack.write(cached, off, n, AudioTrack.WRITE_BLOCKING)
                    off += n
                }
                return
            }

            stopPlayback()

            val audioTrack = track ?: return
            val wroteChunk = AtomicBoolean(false)
            val streamFailed = AtomicBoolean(false)
            val chunks = ArrayList<FloatArray>()

            val config = GenerationConfig().apply {
                speed = SPEECH_RATE
                silenceScale = SILENCE_SCALE
            }

            val callback = OfflineTtsCallback { chunk ->
                when {
                    token != activeToken.get() || !enabled -> 0
                    else -> try {
                        audioTrack.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                        chunks.add(chunk.copyOf())
                        wroteChunk.set(true)
                        1
                    } catch (t: Throwable) {
                        Log.e(TAG, "stream write failed: $text", t)
                        streamFailed.set(true)
                        0
                    }
                }
            }

            var streamed = false
            try {
                engine.generateWithConfigAndCallback(text, config, callback)
                streamed = wroteChunk.get() && !streamFailed.get()
            } catch (t: Throwable) {
                Log.e(TAG, "stream generate failed, fallback to full generate: $text", t)
                streamed = false
            }

            if (token != activeToken.get() || !enabled) return
            if (streamed) {
                // 累积整句写入缓存，下次相同语句直接回放。
                if (chunks.isNotEmpty()) {
                    pcmCache[text] = mergeChunks(chunks)
                }
                return
            }

            stopPlayback()
            val audio = try {
                engine.generateWithConfig(text, config)
            } catch (t: Throwable) {
                Log.e(TAG, "Offline TTS generate failed: $text", t)
                return
            }

            if (token != activeToken.get()) return
            if (!enabled) return

            val samples = audio.samples
            if (samples.isEmpty()) return

            track?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            pcmCache[text] = samples.copyOf()
        } finally {
            synthLock.unlock()
        }
    }

    /**
     * 最小热身：只合成一句最短的「将军」，把 ONNX 算子、线程池与推理内存一次性拉起。
     * 这句合成同时灌进缓存，之后补灌队列里遇到它直接跳过。
     */
    private fun warmupFirst(engine: OfflineTts) {
        val config = GenerationConfig().apply {
            speed = SPEECH_RATE
            silenceScale = SILENCE_SCALE
        }
        try {
            val chunks = ArrayList<FloatArray>()
            val cb = OfflineTtsCallback { chunk ->
                chunks.add(chunk.copyOf())
                1
            }
            engine.generateWithConfigAndCallback(WARMUP_FIRST, config, cb)
            if (chunks.isNotEmpty()) pcmCache[WARMUP_FIRST] = mergeChunks(chunks)
        } catch (t: Throwable) {
            Log.w(TAG, "TTS first warmup skipped", t)
        }
    }

    private fun mergeChunks(chunks: List<FloatArray>): FloatArray {
        val merged = FloatArray(chunks.sumOf { it.size })
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, merged, off, c.size)
            off += c.size
        }
        return merged
    }

    /**
     * 排一次空闲补灌。投递前若已有更晚的真实播报（token 变化），这一句直接作废重排，
     * 保证补灌永远给真实播报让路。
     */
    private fun schedulePrefill(delayMs: Long) {
        if (failed || !enabled || prefillScheduled) return
        if (prefillIndex >= WARMUP_TEXTS.size) return
        prefillScheduled = true
        mainHandler.postDelayed({
            prefillScheduled = false
            if (!ready || failed || !enabled) return@postDelayed
            val idleToken = activeToken.get()
            prefillWorker.execute { prefillOne(idleToken) }
        }, delayMs)
    }

    /**
     * 补灌一句缓存。合成期间持 synthLock，但回调里每收到一块就检查是否出现新播报，
     * 一旦有新播报立即返回 0 中止当前句并释放锁，真实播报最多等一块生成的时间。
     */
    private fun prefillOne(idleToken: Int) {
        if (!ready || failed || !enabled) return
        if (idleToken != activeToken.get()) {
            schedulePrefill(PREFILL_GAP_MS)
            return
        }
        val text = WARMUP_TEXTS.getOrNull(prefillIndex) ?: return
        val engine = tts ?: return

        synthLock.lock()
        try {
            if (pcmCache.containsKey(text)) {
                // 已缓存（例如开场那句「将军」），直接跳过继续下一句。
                prefillIndex++
            } else {
                val config = GenerationConfig().apply {
                    speed = SPEECH_RATE
                    silenceScale = SILENCE_SCALE
                }
                val aborted = AtomicBoolean(false)
                val chunks = ArrayList<FloatArray>()
                val cb = OfflineTtsCallback { chunk ->
                    if (!enabled || activeToken.get() != idleToken) {
                        aborted.set(true)
                        0
                    } else {
                        chunks.add(chunk.copyOf())
                        1
                    }
                }
                val done = try {
                    engine.generateWithConfigAndCallback(text, config, cb)
                    true
                } catch (t: Throwable) {
                    Log.w(TAG, "TTS prefill skipped: $text", t)
                    false
                }
                if (done && !aborted.get()) {
                    if (chunks.isNotEmpty()) pcmCache[text] = mergeChunks(chunks)
                    prefillIndex++
                }
            }
        } finally {
            synthLock.unlock()
        }
        schedulePrefill(PREFILL_GAP_MS)
    }

    /**
     * 首局前向音频轨道写入一小段静音并清掉，走通输出通道与内部缓冲，
     * 避免首句首块样本写入时音频会话冷启动；静音不会对外发声。
     */
    private fun warmupTrack(sampleRate: Int) {
        val audioTrack = track ?: return
        val silence = FloatArray(256)
        try {
            audioTrack.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
            stopPlayback()
        } catch (_: Throwable) {
        }
    }

    private fun stopPlayback() {
        track?.let {
            try {
                // 轨道保持 PLAYING 态，仅丢弃已写入未播放的旧缓冲；避免 pause/play 状态切换抬高新句起播延迟。
                it.flush()
            } catch (_: Throwable) {
            }
        }
    }

    private fun releaseInternal() {
        pendingTask?.cancel(false)
        pendingTask = null
        pcmCache.clear()
        synthLock.lock()
        try {
            track?.let {
                try {
                    it.stop()
                    it.release()
                } catch (_: Throwable) {
                }
            }
            track = null
            tts?.release()
            tts = null
            ready = false
            pendingText = null
        } finally {
            synthLock.unlock()
        }
    }

    fun release() {
        prefillWorker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        executor.submit {
            releaseInternal()
            executor.shutdown()
        }
    }

    companion object {
        private const val TAG = "OfflineTtsSpeaker"
        /** 语速：1.60 倍，保证每个字咬得清；落子到开口的快慢靠缓存与低延迟链路，不靠拉语速。 */
        private const val SPEECH_RATE = 1.60f
        /** 合成线程数：吃满可用核心，把单句前向耗时压到最低。 */
        private const val TTS_THREADS = 8
        /** 句首尾静音比例：恢复模型原始值，保证播报呼吸自然，不再为了抢时间压低。 */
        private const val SILENCE_SCALE = 0.2f
        /** PCM 缓存句数上限：超出后淘汰最久未用的语句。容量留足，避免常用短句被挤掉。 */
        private const val PCM_CACHE_CAPACITY = 32

        /** 开场最小热身句：最短的「将军」，只为把模型算子与推理内存拉起来。 */
        private const val WARMUP_FIRST = "将军"

        /** 抛出 ready 之后首句补灌的延迟：先留一点时间给玩家第一手，避免一开屏就抢引擎。 */
        private const val PREFILL_FIRST_DELAY_MS = 600L

        /** 两句补灌之间的间隔：留出足够空窗，真实播报随时可以插进来。 */
        private const val PREFILL_GAP_MS = 250L

        private val WARMUP_TEXTS = listOf(
            "红方炮二平五",
            "黑方马八进七",
            "红方车二平五",
            "黑方炮八平五",
            "红方马二进三",
            "黑方车九平八",
            "红方相三进五",
            "黑方象七进五",
            "红方仕四进五",
            "黑方士六进五",
            "红方兵三进一",
            "黑方卒七进一",
            "将军",
            "吃马",
            "吃车",
            "吃炮",
            "不可送将",
            "保护将军"
        )

        private const val MODEL_DIR = "tts"
        private const val MODEL_ONNX = "zh_CN-chaowen-medium.onnx"
        private const val LEXICON = "lexicon.txt"
        private const val TOKENS = "tokens.txt"
        private const val PHONE_FST = "phone.fst"
        private const val MODEL_JSON = "zh_CN-chaowen-medium.onnx.json"

        private val ASSET_FILES = listOf(MODEL_ONNX, LEXICON, TOKENS, PHONE_FST, MODEL_JSON)

        private val SPEECH_REPLACEMENTS: Map<Char, Char> = buildMap {
            val chinese = "零一二三四五六七八九"
            for (i in 0..9) {
                put(('0' + i), chinese[i])
                put(('０' + i), chinese[i])
            }
            // 红方记谱大写/异体数字 → 标准简体口语数字（避免按繁体字形误读）。
            put('壹', '一')
            put('貳', '二')
            put('參', '三')
            put('叁', '三')
            put('肆', '四')
            put('伍', '五')
            put('陸', '六')
            put('陆', '六')
            put('柒', '七')
            put('捌', '八')
            put('玖', '九')
            // 棋子名与杀招名中的“车（俥/車）”按象棋传统读 jū。
            put('車', '居')
            put('车', '居')
            put('俥', '居')
            put('傌', '马')
            put('卒', '足')
            // 多音字按象棋应读声调替换为同音同调字（仅影响发音，不改界面记谱/杀招名）。
            put('相', '象')   // 红相 xiàng（词典单字误注 xiāng）
            put('肋', '类')   // 中肋 lèi（词典单字误注 lè）
            put('夹', '加')   // 夹击 jiā（词典单字误注 jiá）
        }

        /** 词级发音纠正：先于单字映射执行。 */
        private val PHRASE_REPLACEMENTS: Map<String, String> = mapOf(
            "重炮" to "虫炮"  // 重叠 chóng，非重量 zhòng
        )

        private val PAUSE_CHARS: Set<Char> = setOf(
            // 句读停顿（全角逗号“，”保留，作为终局分段的短停顿；其余标点过滤）
            '。', '.', '？', '?', '！', '!', '、', ',',
            '：', ':', '；', ';', '…', '‥', '·', '・',
            // 破折号 / 连字符
            '—', '–', '‐', '―', '－', '-',
            // 空白与换行
            ' ', '　', '\n', '\r', '\t',
            // 括号
            '（', '(', '）', ')', '〔', '〕', '【', '】', '[', ']',
            // 引号
            '“', '”', '‘', '’', '「', '」', '『', '』', '〈', '〉',
            // 其他分隔 / 装饰符号
            '|', '~', '～', '／', '/', '＼', '\\', '*', '※',
            '_', '＿', '#', '＃', '&', '＆', '%', '％', '$', '＄',
            '@', '＠', '+', '＋', '=', '＝'
        )

        fun normalizeForSpeech(text: String): String {
            var out = text
            for ((src, dst) in PHRASE_REPLACEMENTS) out = out.replace(src, dst)
            return out.map { SPEECH_REPLACEMENTS[it] ?: it }
                .filterNot { it in PAUSE_CHARS }
                .joinToString("")
        }

        /** 杀招名规范化：“将”读 jiàng；车按象棋传统读 jū（居），与棋子名一致。 */
        private fun normalizePatternName(name: String): String {
            val out = name.replace('将', '將')
            return normalizeForSpeech(out)
        }

        fun sidePrefix(color: PieceColor): String =
            if (color == PieceColor.RED) "红方" else "黑方"

        fun composeAnnouncement(
            color: PieceColor,
            notation: String,
            capturedName: String? = null,
            check: Boolean = false,
            sweepName: String? = null
        ): String {
            val movePart = normalizeForSpeech(notation).replace('将', '將')
            val capturePart = capturedName?.let { normalizeForSpeech(it) }
            val sweepPart = sweepName?.let { normalizeForSpeech(it) }
            val body = when {
                check && capturePart != null && sweepPart != null ->
                    movePart + "吃" + capturePart + "将军抽" + sweepPart
                check && capturePart != null ->
                    movePart + "吃" + capturePart + "将军"
                check && sweepPart != null ->
                    movePart + "将军抽" + sweepPart
                check -> movePart + "将军"
                capturePart != null -> movePart + "吃" + capturePart
                else -> movePart
            }
            return normalizeForSpeech((sidePrefix(color) + body).replace('将', '將'))
        }

        fun statusAnnouncement(
            result: GameController.GameResult,
            lastNotation: String? = null,
            patternName: String? = null
        ): String {
            val notation = lastNotation?.takeIf { it.isNotBlank() }
            val text = when (result) {
                is GameController.GameResult.Checkmate -> {
                    val winnerPrefix = sidePrefix(result.winner)
                    val loserPrefix = sidePrefix(result.winner.opposite())
                    if (notation != null) {
                        val body = normalizeForSpeech(notation).replace('将', '將')
                        if (patternName != null) {
                            // 杀招局：胜方 + 记谱 + 杀招绝杀连贯播报，结尾明确胜方获胜。
                            val sweep = normalizePatternName(patternName)
                            winnerPrefix + body + "，" + sweep + "绝杀，" + winnerPrefix + "获胜"
                        } else {
                            val parts = mutableListOf(winnerPrefix + body)
                            parts.add("将死")
                            parts.add(loserPrefix)
                            parts.add(winnerPrefix + "获胜")
                            parts.joinToString("，")
                        }
                    } else {
                        winnerPrefix + "获胜"
                    }
                }
                is GameController.GameResult.Stalemate -> {
                    val winnerPrefix = sidePrefix(result.winner)
                    val loserPrefix = sidePrefix(result.winner.opposite())
                    if (notation != null) {
                        val body = normalizeForSpeech(notation).replace('将', '將')
                        listOf(winnerPrefix + body, "困毙", loserPrefix, winnerPrefix + "获胜")
                            .joinToString("，")
                    } else {
                        listOf("困毙", winnerPrefix + "获胜").joinToString("，")
                    }
                }
                is GameController.GameResult.PerpetualCheck ->
                    listOf("长将判负", sidePrefix(result.winner) + "获胜").joinToString("，")
                is GameController.GameResult.PerpetualChase ->
                    listOf("长捉判负", sidePrefix(result.winner) + "获胜").joinToString("，")
                GameController.GameResult.RepetitionDraw -> "和棋"
            }
            return normalizeForSpeech(text.replace('将', '將'))
        }
    }
}
