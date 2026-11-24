package ysyx_26030103.cache
import chisel3._
import chisel3.util._
import _root_.ysyx_26030103.common._

/** ICacheReg 与 ICacheSRAM 的公共 core：几何、valid/tag 阵列、alias 扫描/目录、
  * PMA 判定、refill 状态机、AXI/snoop/flush 与性能计数。
  *
  * 数据阵列和读响应时序由两个存储适配通过 storage* 钩子提供：Reg 组合读并在
  * 命中拍锁存，SRAM 同步读并在下一拍旁路/锁存。两版的存储时序不在这里合并。
  */
private[cache] trait ICacheCore { this: ysyx_26030103_ICacheBase =>
  // 构造参数由两个存储适配类以 private[cache] val 提供。
  private[cache] def Enable: Boolean
  private[cache] def BlockSizeLog2: Int
  private[cache] def IndexBits: Int
  private[cache] def AddressWidth: Int
  private[cache] def DataWidth: Int
  private[cache] def VIPT: Boolean
  private[cache] def VIPTAliasSafe: Boolean
  private[cache] def VIPTAliasScanMode: String
  private[cache] def VIPTAliasScanWidth: Int
  private[cache] def VIPTAliasDirectoryEntries: Int
  private[cache] def Snoop: Boolean
  private[cache] def DMASnoop: Boolean
  private[cache] def DrainTimeout: Int
  private[cache] def PMARegions: Seq[ysyx_26030103_PMARegion]

  // 存储适配钩子。storageInit 在无 when 的上文中建立存储器/寄存器，
  // 避免 RegInit/Mem 首次创建落在条件块内。
  protected def readDataWords(blockIndex: UInt, wordOffset: UInt): UInt =
    throw new UnsupportedOperationException("当前存储适配不支持组合读")
  protected def writeDataWord(blockIndex: UInt, wordOffset: UInt, word: UInt): Unit
  protected def storageInit(): Unit = ()
  protected def storageHitResponding: Bool
  // 响应数据选择（Reg 在响应级组合读）；SRAM 由 storageRespond 在读端口后驱动。
  protected def storageResponseData(): Unit = ()
  // 存储读端口与数据锁存时序（SRAM 在 refill 启动块之后执行）。
  protected def storageRespond(): Unit = ()
  protected def storageOnAccept(): Unit = ()
  protected def storageOnAcceptPa(): Unit = ()
  protected def storageOnResolve(): Unit = ()
  protected def storageOnRecheck(): Unit = ()
  protected def storageOnRefillBeat(keywordBeat: Bool): Unit = ()
  protected def storageOnKill(): Unit = ()
  protected def storageOnFlush(): Unit = ()
  protected def storageDropStage1Hit(): Unit = ()

  // 只在真正需要 alias 维护的几何下生成扫描/目录硬件。
  private val aliasScanActive =
    VIPTAliasSafe && Enable &&
      ysyx_26030103_VIPTAliasScan.isScan(VIPTAliasScanMode)
  private val aliasAllSets =
    VIPTAliasSafe && Enable &&
      ysyx_26030103_VIPTAliasScan.isAllSets(VIPTAliasScanMode)
  private val aliasDirectoryMode =
    VIPTAliasSafe && Enable &&
      ysyx_26030103_VIPTAliasScan.isDirectory(VIPTAliasScanMode)
  // alias 模式必须比较完整物理行号：index 含 VPN 位时，PA 中与 index
  // 对应的位并不由 VA index 决定，若仍省略这些位，两个只有该位不同的
  // 物理行会在同一 set 中 false hit。页内安全 VIPT 可继续使用窄 tag。
  val tagBits =
    if (VIPTAliasSafe) AddressWidth - BlockSizeLog2
    else AddressWidth - IndexBits - BlockSizeLog2
  private def tagOf(address: UInt): UInt =
    if (VIPTAliasSafe) address(AddressWidth - 1, BlockSizeLog2)
    else address(AddressWidth - 1, IndexBits + BlockSizeLog2)
  val NumberBlocks = 1 << IndexBits
  val wordsPerBlock = 1 << (BlockSizeLog2 - 2) // 块大小 ÷ 4字节
  val wordsPerBeat = DataWidth / 32 // 每个AXI beat包含的32位字数(32位总线=1, 64位总线=2)
  val beatsPerBlock = wordsPerBlock / wordsPerBeat // 填满一条line所需的beat数
  require(wordsPerBlock % wordsPerBeat == 0,
    s"块内字数($wordsPerBlock)不能被每拍字数($wordsPerBeat)整除")
  val beatCountBits = log2Ceil(beatsPerBlock).max(1) // refill beat计数器位宽
  val busBytes = DataWidth / 8 // 一个AXI beat的字节数
  val beatSizeLog2 = log2Ceil(busBytes) // ARSIZE: 32位总线=2, 64位总线=3
  private val beatWordBits = log2Ceil(wordsPerBeat) // beat内word偏移位宽
  private def beatWordOffset(beat: UInt, wordInBeat: Int): UInt =
    if (wordsPerBeat == 1) beat
    else if (beatsPerBlock == 1) wordInBeat.U(beatWordBits.W)
    else Cat(beat, wordInBeat.U(beatWordBits.W))
  private def beatWordMask(beat: UInt): UInt =
    (0 until wordsPerBeat)
      .map(k => UIntToOH(beatWordOffset(beat, k), wordsPerBlock))
      .reduce(_ | _)
  // 扫描/目录参数只在对应模式生效，但先统一解析，非法配置在 elaboration
  // 期明确报错，不会生成“看起来是 parallel 实际退化成 serial”的硬件。
  private val scanWidth =
    ysyx_26030103_VIPTAliasScan.resolveScanWidth(
      VIPTAliasScanMode,
      VIPTAliasScanWidth,
      NumberBlocks
    )
  private val directoryEntries =
    if (VIPTAliasDirectoryEntries <= 0) NumberBlocks
    else {
      require(VIPTAliasDirectoryEntries <= NumberBlocks,
        s"VIPT alias 目录项数($VIPTAliasDirectoryEntries) 不能超过 set 数($NumberBlocks)")
      VIPTAliasDirectoryEntries
    }
  // 直接映射cache：valid/tag留在公共core，data由存储适配实现。
  // 关闭缓存时阵列只留1项占位(其余逻辑不可达), 综合会把这些触发器/组合逻辑全部剪掉
  private val arrayBlocks = if (Enable) NumberBlocks else 1
  val valid = RegInit(VecInit(Seq.fill(arrayBlocks)(false.B)))
  val tag = Reg(Vec(arrayBlocks, UInt(tagBits.W)))
  storageInit()
  // 仅 all_sets 在 refill 安装时展开全组 tag 比较；serial/parallel 由
  // 扫描引擎完成，directory 由目录完成，避免同时生成两套专用阵列。
  private def invalidateAliasCopies(aliasTag: UInt, targetIndex: UInt): Unit = {
    if (aliasAllSets) {
      for (i <- valid.indices) {
        when(valid(i) && tag(i) === aliasTag && i.U =/= targetIndex) {
          valid(i) := false.B
        }
      }
    }
  }
  // directory 模式的事件目录：只在 refill/snoop 事件拍做 CAM 比较。
  private val aliasDirectory =
    if (aliasDirectoryMode) {
      Some(
        Module(
          new ysyx_26030103_VIPTAliasDirectory(
            LineBits = AddressWidth - BlockSizeLog2,
            SetBits = IndexBits,
            NumSets = NumberBlocks,
            Entries = directoryEntries
          )
        )
      )
    } else {
      None
    }
  // 自修改代码 store snoop 的行匹配。默认几何下 tag 不含 index 位，
  // 必须同时比较 set；alias-safe 几何下 tag 是完整物理行号，可扫描所有 set。
  // directory 模式改由目录失效，不再生成 tag 扫描阵列。
  private def snoopHitVec(address: UInt): UInt =
    if (!Snoop || !Enable || aliasDirectoryMode) 0.U(arrayBlocks.W)
    else
      VecInit(valid.indices.map { set =>
        val indexMatch =
          if (VIPTAliasSafe) true.B
          else set.U === address(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
        valid(set) && tag(set) === tagOf(address) && indexMatch
      }).asUInt
  private def lineOf(address: UInt): UInt =
    address(AddressWidth - 1, BlockSizeLog2)
  private def snoopLineMatch(address: UInt): Bool =
    lineOf(address) === lineOf(io.SnoopAddress) ||
      lineOf(address) === lineOf(io.SnoopAddressHi)
  private val DMASnoopActive = DMASnoop && Enable
  // DMA 范围失效同样是“所有重叠物理行”：普通几何拼回完整行号，
  // alias-safe 几何 tag 本身就是完整行号。
  private def dmaLineOfSet(set: Int): UInt =
    if (VIPTAliasSafe) tag(set)
    else Cat(tag(set), set.U(IndexBits.W))
  private val DMAStartLine = io.DMASnoopStart(AddressWidth - 1, BlockSizeLog2)
  private val DMAEndLine = io.DMASnoopEnd(AddressWidth - 1, BlockSizeLog2)
  private def dmaLineInRange(line: UInt): Bool =
    line >= DMAStartLine && line <= DMAEndLine
  private def dmaLineMatch(address: UInt): Bool =
    dmaLineInRange(lineOf(address))
  private def DMASnoopHitVec: UInt =
    if (!DMASnoopActive) 0.U(arrayBlocks.W)
    else
      VecInit(valid.indices.map { set =>
        valid(set) && dmaLineInRange(dmaLineOfSet(set))
      }).asUInt
  val indexAddress = if (VIPT) io.FetchVaddr else io.FetchAddress
  val index = indexAddress(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  // Enable=false时阵列只有一个综合占位项。用Scala期的常量索引访问它，
  // 避免把完整的组索引接到单项Vec上产生W004；Enable=true时仍是原始索引，
  // 不在命中路径增加任何截断或选择逻辑。
  val indexSafe = if (Enable) index else 0.U(IndexBits.W)
  val blockOffset =
    if (wordsPerBlock > 1) indexAddress(BlockSizeLog2 - 1, 2)
    else 0.U
  val RequestTag = tagOf(io.FetchAddress)
  private def addressInRegion(
      address: UInt,
      region: ysyx_26030103_PMARegion
  ): Bool = {
    val extended = Cat(0.U(1.W), address)
    // 未启用 C 扩展的 RV32 每次都取一条完整的 4 字节指令。如果只检查
    // 首字节，位于非规则大小可执行区域末尾的请求就可能在 Xbar 拒绝前
    // 越过 EndExclusive。
    val lastByte = extended + 3.U((AddressWidth + 1).W)
    extended >= BigInt(region.Base).U((AddressWidth + 1).W) &&
    lastByte < BigInt(region.EndExclusive).U((AddressWidth + 1).W)
  }
  private def addressInRegions(
      address: UInt,
      regions: Seq[ysyx_26030103_PMARegion]
  ): Bool =
    regions
      .map(addressInRegion(address, _))
      .reduceOption(_ || _)
      .getOrElse(false.B)

  private val executableRegions = PMARegions.filter(_.Executable)
  private val cacheableRegions = PMARegions.filter(r => r.Executable && r.Cacheable)
  require(executableRegions.nonEmpty, "ICache至少需要一个可执行PMA区域")
  require(
    cacheableRegions.forall(r =>
      r.Size >= (1L << BlockSizeLog2) &&
        (r.Base & ((1L << BlockSizeLog2) - 1L)) == 0L &&
        (r.Size & ((1L << BlockSizeLog2) - 1L)) == 0L
    ),
    "可缓存PMA区域必须按cache line对齐且不得小于一个cache line"
  )

  // VIPT 下 PA 可能晚于 VA 到达。只接受“PA 有效且其携带的 VA 正是当前
  // 请求 VA”的翻译结果，避免把上一笔请求的旧 PA 用到新 PC 上。
  val incomingPhysicalAddressMatch =
    !VIPT.B || (io.FetchPaddrValid && io.FetchPaddrVaddr === io.FetchVaddr)
  // 可执行权限和可缓存属性是两个独立的PMA属性。非法取指在本模块
  // 直接形成instruction access fault，不允许向MMIO/CLINT/地址空洞发AR。
  val executable = addressInRegions(io.FetchAddress, executableRegions)
  val cacheable =
    if (Enable) addressInRegions(io.FetchAddress, cacheableRegions) else false.B
  // 两级流水: s1受理级(寄存请求) -> 响应级(命中按存储时序交付,缺失走refill)
  val stage1Valid = RegInit(false.B)
  val stage1Hit = Reg(Bool()) // 受理时判定: 可缓存且命中
  val stage1Ready = RegInit(false.B) // 缺失的关键词已返回(early restart)或refill已完成
  val fetchAddressReg = Reg(UInt(AddressWidth.W))
  val fetchVirtualAddressReg = Reg(UInt(AddressWidth.W))
  val fetchIndexReg = Reg(UInt(IndexBits.W))
  val fetchOffsetReg = Reg(UInt((BlockSizeLog2 - 2).W)) // 请求时块内word偏移
  // VIPT: s1 受理 VA 时可能还没有 PA。该位表示 fetch_addr_reg 已装入
  // 与本请求虚拟地址匹配的物理地址，此后才能做 tag/PMA/refill 判定。
  val stage1PhysicalAddressValid = RegInit(false.B)
  val fetchIndexSafe =
    if (Enable) fetchIndexReg else 0.U(IndexBits.W)
  val fetchOffsetSafe =
    if (wordsPerBlock > 1) fetchOffsetReg else 0.U
  val responseDataReg = Reg(UInt(32.W))
  val cacheableReg = RegInit(false.B)
  val accessFaultReg = RegInit(false.B)
  val accessFaultResponseReg = RegInit(0.U(2.W))
  // 本拍 store snoop 是否命中“当前取指/迟到的 PA”；命中时不能凭 store
  // 之前的 tag 或半填 refill 数据判定 hit，否则同一拍会把旧指令交付。
  val snoopLineHitNow =
    if (Snoop && Enable) io.SnoopValid && snoopLineMatch(io.FetchAddress)
    else false.B
  // 已被 s1 锁存、且 PA 已知的请求命中 store 行：撤回本拍响应，让它
  // 在 store 完成后的新一次 refill 中重新取指。异常响应不能撤回，否则
  // 会把不可执行区域的故障取指错误地变成一次总线读。
  // DMA 写的行失效与 CPU store snoop 使用同一套“同拍命中必须让路”的
  // 规则，但地址范围独立，两个事件可以同拍发生。
  val DMASnoopLineHitNow =
    if (DMASnoopActive) io.DMASnoopValid && dmaLineMatch(io.FetchAddress)
    else false.B
  val anySnoopLineHitNow = snoopLineHitNow || DMASnoopLineHitNow
  val snoopStage1LineHit =
    if (Snoop && Enable)
      io.SnoopValid && stage1Valid && stage1PhysicalAddressValid && !accessFaultReg &&
        snoopLineMatch(fetchAddressReg)
    else false.B
  val DMASnoopStage1LineHit =
    if (DMASnoopActive)
      io.DMASnoopValid && stage1Valid && stage1PhysicalAddressValid && !accessFaultReg &&
        dmaLineMatch(fetchAddressReg)
    else false.B
  val anySnoopStage1LineHit = snoopStage1LineHit || DMASnoopStage1LineHit
  // refill状态机(单MSHR: 同一时刻只处理一个缺失)
  val refillStates = Enum(5)
  val refillIdle = refillStates(0)
  val refillRequest = refillStates(1)
  val refillResponse = refillStates(2)
  val refillDrain = refillStates(3)
  val refillDrainFault = refillStates(4)
  val refillState = RegInit(refillIdle)
  // 排空超时（AXI要求从设备给RLAST；违规时不能永久停在排空态）。
  // 超时后转入 rf_drain_fault：继续吸收迟到拍防串扰，同时让取指以
  // 故障响应继续推进，而不是整核挂死。
  val drainTimer = RegInit(0.U(10.W))
  val drainRetryTimer = RegInit(0.U(10.W))
  val busFault = RegInit(false.B)
  // refill专属寄存器。refill期间只能受理同一line中已返回的word，
  // 未返回word和其他line在单MSHR结构下继续反压。
  val refillAddress = Reg(UInt(AddressWidth.W))
  val refillVirtualIndex = Reg(UInt(IndexBits.W))
  val refillIndex = if (VIPT) refillVirtualIndex else refillAddress(IndexBits + BlockSizeLog2 - 1, BlockSizeLog2)
  val refillIndexSafe = if (Enable) refillIndex else 0.U(IndexBits.W)
  val refillTag = tagOf(refillAddress)
  val refillOffset =
    if (wordsPerBlock > 1) refillAddress(BlockSizeLog2 - 1, 2)
    else 0.U
  val refillKeywordBeat =
    if (wordsPerBeat == 1) refillOffset
    else if (beatsPerBlock > 1)
      refillOffset(BlockSizeLog2 - 3, beatWordBits)
    else 0.U
  val nonCacheableReadWord =
    if (DataWidth == 64)
      Mux(refillAddress(2) === 1.U, io.AXI.R.RDATA(63, 32), io.AXI.R.RDATA(31, 0))
    else io.AXI.R.RDATA
  val refillCacheable = Reg(Bool())
  val refillCount = RegInit(0.U(beatCountBits.W)) // 当前正在填充第几个beat
  val refillOffsetSafe =
    if (wordsPerBlock > 1) beatWordOffset(refillCount, 0) else 0.U
  // line valid仍要等所有beat成功后才会置位。
  val refillWordValid = RegInit(0.U(wordsPerBlock.W))
  val burstMode = RegInit(false.B) // 突发传输模式标志
  // AXI规定VALID一旦对外呈现，在READY前不能撤回。该位区分
  // “刚进rf_req且本拍kill”与“ARVALID已经反压了一拍”。
  val ARExposed = RegInit(false.B)
  // 在飞refill是否仍属于当前s1里的请求: 原请求被消费/冲刷/替换后,
  // 后续R拍只写阵列, 绝不再写s1的响应寄存器(那会污染新请求)
  // s1_ready为true时响应已经对外可见，即使尚未握手也不能在反压下修改
  // payload。因此迟到的协议错误只能替换尚未可见的demand响应。
  val stage1Waits = RegInit(false.B)
  // flush(fence.i)/kill不能丢弃已被从机接受的AXI事务: 置位后继续把剩余拍排空,
  // 否则残留的R拍会被后续突发误收, 造成块内数据错位
  val discard = RegInit(false.B)
  // 一条cache line的refill错误状态。RRESP错误可能出现在关键词之后，
  // 因而不能只看最后一个beat决定valid；任意一个错误都使整条line失效。
  val refillError = RegInit(false.B)
  // 正在refill的索引不能走普通valid/tag命中；半填行只能通过
  // refill_word_valid的逐word许可通路访问。
  val refillBusy = refillState =/= refillIdle
  val underRefill = refillBusy && refillCacheable && (index === refillIndex)
  val hit =
    valid(indexSafe) && tag(indexSafe) === RequestTag && !underRefill &&
      !anySnoopLineHitNow
  val refillLineMatch =
    io.FetchAddress(AddressWidth - 1, BlockSizeLog2) ===
      refillAddress(AddressWidth - 1, BlockSizeLog2)
  val requestedWordArrived =
    if (wordsPerBlock > 1) refillWordValid(blockOffset)
    else refillWordValid(0)
  val refillReadFire = refillState === refillResponse && io.AXI.R.RVALID
  val malformedRefillBeatNow =
    refillReadFire && !io.AXI.R.RLAST &&
      (!refillCacheable || !burstMode || refillCount === (beatsPerBlock - 1).U)
  val refillErrorNow =
    refillReadFire && (io.AXI.R.RRESP =/= 0.U || malformedRefillBeatNow)
  // serial/parallel alias 扫描的启动事件：真正被接受的 cacheable refill AR。
  // 被 kill/flush 取消的错路 refill 不驱逐其它 set 的有效行。
  val refillAliasEvent = refillState === refillRequest && io.AXI.AR.ARREADY &&
    refillCacheable && !discard && !(io.kill && stage1Waits)
  // 扫描/启动拍反压新取指，确保不会在旧 alias 副本尚未清完时受理可能
  // 命中它的请求。all_sets/directory 不需要扫描反压。
  val aliasScanStatus = ysyx_26030103_VIPTAliasScan.icacheScan(
    aliasScanActive,
    valid,
    tag,
    refillAliasEvent,
    refillTag,
    refillIndexSafe,
    io.flush,
    scanWidth
  )
  val aliasScanBusy = aliasScanStatus.busy
  // directory 模式在 AR 握手拍更新目录并失效旧副本；同一拍不允许受理
  // 新取指，避免组合命中尚未失效的旧行。下一拍目录/valid 已同步。
  val aliasDirectoryInstallBusy =
    if (aliasDirectoryMode) refillAliasEvent else false.B
  // 扫描模式把新行 valid 推迟到本轮扫描完成：扫描窗口内不出现两份同时
  // 有效的副本。directory/all_sets 不生成这些状态寄存器，避免默认配置
  // 多出 valid 写口/read mux。
  private val deferredAliasInstall = aliasScanActive
  private val aliasDeferred =
    if (aliasScanActive) {
      Some(
        (
          Reg(UInt(IndexBits.W)),
          Reg(UInt(tagBits.W)),
          RegInit(false.B)
        )
      )
    } else {
      None
    }
  // 目录模式：refill 安装事件插入目录；store snoop 按物理行查目录；
  // flush 清空目录。
  aliasDirectory.foreach { dir =>
    dir.io.install := refillAliasEvent
    dir.io.installLine := refillTag
    dir.io.installSet := refillIndexSafe
    dir.io.invalidateLine :=
      (if (Snoop && Enable) io.SnoopValid else false.B)
    dir.io.invalidateLineAddress := io.SnoopAddress(AddressWidth - 1, BlockSizeLog2)
    dir.io.keepSet := false.B
    dir.io.keepSetIndex := 0.U
    dir.io.invalidateLine2 :=
      (if (Snoop && Enable) io.SnoopValid else false.B)
    dir.io.invalidateLine2Address :=
      io.SnoopAddressHi(AddressWidth - 1, BlockSizeLog2)
    // DMA 地址级 snoop 按物理行范围清目录项；Cache valid 由
    // DmaSnoopHitVec/refill 丢弃路径同步清除。
    dir.io.invalidateRange := DMASnoopActive.B && io.DMASnoopValid
    dir.io.invalidateRangeStart := DMAStartLine
    dir.io.invalidateRangeEnd := DMAEndLine
    // refill 自身出错时，Cache 已经不会安装该行；通知目录同步释放
    // 本次 refill 对应的 set，避免目录留下指向失败行的陈旧表项。
    dir.io.release := refillErrorNow
    dir.io.releaseSet := refillIndexSafe
    dir.io.clear := io.flush
  }
  // 扫描结束后补装被推迟的新行。中途 flush/同一行的 store snoop 必须
  // 取消补装，否则会把 store 之前取到的旧指令重新置为 valid。
  if (aliasScanActive) {
    val (deferredIndex, deferredTag, deferredValid) = aliasDeferred.get
    when(deferredValid && aliasScanStatus.installAllowed && !io.flush) {
      valid(deferredIndex) := true.B
      deferredValid := false.B
    }
    if (Snoop && Enable) {
      when(
        io.SnoopValid && deferredValid &&
          (deferredTag === io.SnoopAddress(AddressWidth - 1, BlockSizeLog2) ||
            deferredTag ===
              io.SnoopAddressHi(AddressWidth - 1, BlockSizeLog2))
      ) {
        // 若 snoop 恰好落在扫描最后一拍，上面刚把 deferred 行置为
        // valid；必须在同一沿压掉这次补装，store 之前的指令不能留下。
        valid(deferredIndex) := false.B
        deferredValid := false.B
      }
    }
  }
  // 半填行只在“同一 set”内可见：alias 几何下另一个 VA 可能用同一 PA
  // 命中不同 index，而 refill 只把数据写进 RefIndexSafe。若不比较 index，
  // 会把目标 set 的未完成数据当成另一个 set 的内容返回。
  val partialRefillHit =
    (refillState === refillRequest || refillState === refillResponse) &&
      refillCacheable && !discard && !refillError && !refillErrorNow &&
      refillLineMatch && (index === refillIndex) && requestedWordArrived &&
      !anySnoopLineHitNow
  val requestHit =
    incomingPhysicalAddressMatch && executable && cacheable && (hit || partialRefillHit)
  // VIPT 的“迟到的 PA”解析：s1 里保存的是受理时的 VA/index/offset，
  // 只有 PA 携带的 VA 与之一致时才允许把 PA 装入请求。注意这里不能使用
  // 当前 io.FetchVaddr（IFU 可能已经推进到下一 PC）。
  val stage1WaitTranslate = VIPT.B && stage1Valid && !stage1PhysicalAddressValid
  val resolveTranslate = stage1WaitTranslate && io.FetchPaddrValid &&
    io.FetchPaddrVaddr === fetchVirtualAddressReg
  val resolveTag = tagOf(io.FetchAddress)
  val resolveUnderRefill =
    refillBusy && refillCacheable && (fetchIndexReg === refillIndex)
  val resolveHit =
    valid(fetchIndexSafe) && tag(fetchIndexSafe) === resolveTag &&
      !resolveUnderRefill && !anySnoopLineHitNow
  val resolveLineMatch =
    io.FetchAddress(AddressWidth - 1, BlockSizeLog2) ===
      refillAddress(AddressWidth - 1, BlockSizeLog2)
  val resolveWordArrived =
    if (wordsPerBlock > 1) refillWordValid(fetchOffsetSafe)
    else refillWordValid(0)
  val resolvePartialRefillHit =
    (refillState === refillRequest || refillState === refillResponse) &&
      refillCacheable && !discard && !refillError && !refillErrorNow &&
      resolveLineMatch && (fetchIndexReg === refillIndex) &&
      resolveWordArrived && !anySnoopLineHitNow
  val resolveRequestHit =
    executable && cacheable && (resolveHit || resolvePartialRefillHit)
  io.AccessFaultResponse := accessFaultResponseReg
  io.AXI.AW.AWVALID := false.B
  io.AXI.AW.AWID := 0.U
  io.AXI.AW.AWADDR := 0.U
  io.AXI.AW.AWLEN := 0.U
  io.AXI.AW.AWSIZE := 2.U
  io.AXI.AW.AWBURST := 1.U
  io.AXI.AW.AWCACHE := 0.U
  io.AXI.AW.AWQOS := 0.U
  io.AXI.AW.AWPROT := 0.U
  io.AXI.W.WVALID := false.B
  io.AXI.W.WDATA := 0.U
  io.AXI.W.WSTRB := 0.U
  io.AXI.W.WLAST := false.B
  io.AXI.B.BREADY := false.B
  io.AXI.AR.ARVALID := false.B
  io.AXI.AR.ARID := 0.U
  io.AXI.AR.ARADDR := 0.U
  io.AXI.AR.ARLEN := 0.U
  io.AXI.AR.ARSIZE := beatSizeLog2.U
  io.AXI.AR.ARBURST := 1.U
  io.AXI.AR.ARPROT := 0.U
  io.AXI.AR.ARCACHE := 0.U
  io.AXI.AR.ARQOS := 0.U
  io.AXI.R.RREADY := true.B // 始终接收并排空 AXI 响应
  // 响应级: 命中按存储时序交付; 关键词返回/refill完成(s1_ready)后,
  // 可缓存的从阵列取/用锁存值, 不可缓存的用R拍锁存的数据; 错误一律给NOP
  val responding =
    stage1Valid && (storageHitResponding || stage1Ready) && !anySnoopStage1LineHit
  io.ResponseValid := responding
  // 响应携带架构 PC：VIPT 用受理时锁存的 VA；PIPT 的 VA==PA，保持原行为。
  dontTouch(io.ResponseAddress)
  io.ResponseAddress := Mux(VIPT.B, fetchVirtualAddressReg, fetchAddressReg)
  io.ResponseFault := accessFaultReg
  storageResponseData()
  // 受理级: 响应槽为空,或本拍响应正被接收。refill中可放行
  // 其他index的完整命中，以及同line已返回word的partial hit；两者的
  // 数据都在受理拍锁存/发起存储读，不会被后续R beat覆盖。
  val responseSlotAvailable =
    !stage1Valid || (responding && io.ResponseReady)
  io.FetchReady :=
    !io.flush &&
      !io.kill &&
      !aliasScanBusy &&
      !aliasDirectoryInstallBusy &&
      responseSlotAvailable &&
      (refillState === refillIdle || refillState === refillDrainFault || requestHit)
  val accept = io.FetchValid && io.FetchReady
  // VIPT 翻译请求：除了已受理但仍缺 PA 的 s1，还把“本拍正在受理且 PA
  // 尚未匹配”的请求组合地送给 MMU。这样 TLB 查询与 VA 选 index 在同一拍
  // 启动，而不是等受理后下一拍。该信号不参与 FetchReady 判定，无组合环。
  val stage1PendingTranslate = VIPT.B && stage1Valid && !stage1PhysicalAddressValid
  val acceptNeedsTranslate = VIPT.B && accept && !incomingPhysicalAddressMatch
  io.FetchXlatePending := stage1PendingTranslate || acceptNeedsTranslate
  io.FetchXlateVaddr :=
    Mux(stage1PendingTranslate, fetchVirtualAddressReg, io.FetchVaddr)
  // VIPT 的 PA 晚到时，命中/缺失要等 resolve 拍再计数，避免把“尚未翻译”
  // 误报成 miss。
  io.perf_hit := (accept && requestHit) || (resolveTranslate && resolveRequestHit)
  // 非缓存取指（ICACHE=n 或未来的可执行不可缓存区）不是 miss。
  io.perf_miss :=
    (accept && incomingPhysicalAddressMatch && executable && cacheable && !requestHit) ||
      (resolveTranslate && executable && cacheable && !resolveRequestHit)
  io.perf_refill_req := refillState === refillRequest
  io.perf_refill_resp := refillState === refillResponse
  val perfLineFill = WireDefault(false.B)
  io.perf_line_fill := perfLineFill
  dontTouch(io.perf_hit)
  dontTouch(io.perf_miss)
  dontTouch(io.perf_refill_req)
  dontTouch(io.perf_refill_resp)
  dontTouch(io.perf_line_fill)
  when(responding && io.ResponseReady) {
    stage1Valid := false.B
    stage1Waits := false.B
  }
  when(accept) {
    // 虚拟地址在受理拍就锁存并用于选 index；这是 VIPT 并行的关键：
    // 即使 PA 还没到，存储阵列的组选择也已经发生。
    fetchIndexReg := index
    fetchOffsetReg := blockOffset
    fetchVirtualAddressReg := io.FetchVaddr
    stage1PhysicalAddressValid := incomingPhysicalAddressMatch
    storageOnAccept()
    stage1Valid := true.B
    stage1Waits := false.B
    when(incomingPhysicalAddressMatch) {
      // TLB 命中/旁路：PA 同拍可用，走原来的完整命中/缺失路径。
      fetchAddressReg := io.FetchAddress
      storageOnAcceptPa()
      cacheableReg := cacheable
      stage1Hit := requestHit
      // PMA拒绝不需要总线往返；用DECERR作为调试响应码，并让带故障的
      // NOP响应沿正常流水线到达EXU，在提交点精确写入mepc/mcause。
      stage1Ready := !executable
      accessFaultReg := !executable
      accessFaultResponseReg := Mux(executable, 0.U, 3.U)
      // 总线已超时：不再发AR，直接以故障响应结束本次取指（命中仍可正常
      // 服务，数据在阵列里不需要总线）。
      when(busFault && executable && !requestHit) {
        stage1Ready := true.B
        accessFaultReg := true.B
        accessFaultResponseReg := 2.U
      }
    }.otherwise {
      // VIPT：先占住 s1 等物理地址。此时绝不能判定命中、发 AR 或提交
      // 故障；错误数据也不会在响应通道上出现。
      cacheableReg := false.B
      stage1Hit := false.B
      stage1Ready := false.B
      accessFaultReg := false.B
      accessFaultResponseReg := 0.U
    }
  }
  // PA 到达：用受理时锁存的 VA index + 当前 PA 完成 tag/PMA 判定。
  // 只有携带同一 VA 的 PA 才被接受，旧翻译结果会被忽略。
  when(resolveTranslate) {
    fetchAddressReg := io.FetchAddress
    stage1PhysicalAddressValid := true.B
    cacheableReg := cacheable
    stage1Hit := resolveRequestHit
    stage1Ready := !executable
    accessFaultReg := !executable
    accessFaultResponseReg := Mux(executable, 0.U, 3.U)
    storageOnResolve()
    when(busFault && executable && !resolveRequestHit) {
      stage1Ready := true.B
      accessFaultReg := true.B
      accessFaultResponseReg := 2.U
    }
  }
  // 排队缺失: refill空闲时启动; 启动前重查命中(在飞refill可能刚把这行填好)
  // VIPT 必须等 PA 解析完成，否则会把 0 地址或旧 PA 当成缺失发上总线。
  val queuedMiss =
    stage1Valid && (!VIPT.B || stage1PhysicalAddressValid) && !stage1Hit && !stage1Ready
  val recheckHit = cacheableReg &&
    valid(fetchIndexSafe) &&
    tag(fetchIndexSafe) === tagOf(fetchAddressReg)
  // DMA snoop 握手：如果事件到来时已有属于被改写行的 refill，必须等它
  // 把旧事务排空（discard 已置位）后再放行 DMA B。handled 保证同一个
  // snoop 事件只毒化一次：排空后自动重取的新 refill 读的是 DMA 完成后的内存。
  val DMASnoopActiveRefill = DMASnoopActive.B && (refillState =/= refillIdle) &&
    dmaLineMatch(refillAddress)
  val DMASnoopStaleRefill = RegInit(false.B)
  val DMASnoopHandled = RegInit(false.B)
  when(!io.DMASnoopValid) { DMASnoopHandled := false.B }
  when(io.DMASnoopValid && !DMASnoopHandled) {
    DMASnoopHandled := true.B
    when(DMASnoopActiveRefill) { DMASnoopStaleRefill := true.B }
  }
  when(DMASnoopStaleRefill && refillState === refillIdle) {
    DMASnoopStaleRefill := false.B
  }
  val DMASnoopFirstPoison =
    io.DMASnoopValid && !DMASnoopHandled && DMASnoopActiveRefill
  io.DMASnoopReady := (!DMASnoopActive).B ||
    (!DMASnoopStaleRefill && !DMASnoopFirstPoison)
  when(queuedMiss && refillState === refillIdle && !io.kill && !io.flush &&
      !busFault) {
    when(recheckHit) {
      stage1Hit := true.B
      storageOnRecheck()
    }.otherwise {
      refillState := refillRequest
      refillAddress := fetchAddressReg
      refillVirtualIndex := fetchIndexReg
      refillCacheable := cacheableReg
      refillCount := 0.U
      refillWordValid := 0.U
      ARExposed := false.B
      burstMode := cacheableReg
      // 每次新refill都从干净的事务状态开始，不能继承上一次flush/discard
      // 或RRESP错误；旧目标行在AR握手时才失效，避免取消的
      // 错路请求白白驱逐原有行。
      discard := false.B
      refillError := false.B
      stage1Waits := true.B
    }
  }
  // SRAM 同步读、旁路与锁存必须在 refill 数据写入之前，保证关键词拍的
  // 锁存优先级与原实现一致；Reg 的响应数据已在响应级组合读出。
  storageRespond()
  // 注意: refill启动刻意不早于s1_valid寄存后一拍——启动条件里的!io.kill
  // 能抑制错误路径取指的refill(误预测时kill比受理晚一拍到)。曾经试过受理即启动,
  // 结果误预测窗口内的错误路径取指把无用refill发上总线并驱逐有用行, microbench
  // 缺失+70%, 总周期+10.6%, 已回退。
  switch(refillState) {
    is(refillRequest) {
      // redirect/exception在AR尚未被时钟采样为pending时可以取消错路
      // refill。ARVALID若已被反压过，即使还没握手也必须保持到READY。
      val cancelUnaccepted =
        io.kill && stage1Waits && !ARExposed && !io.flush
      io.AXI.AR.ARVALID := !cancelUnaccepted
      io.AXI.AR.ARSIZE := Mux(refillCacheable, beatSizeLog2.U, 2.U)
      io.AXI.AR.ARLEN := Mux(
        refillCacheable && burstMode,
        (beatsPerBlock - 1).U,
        0.U
      )
      io.AXI.AR.ARADDR := Mux(
        refillCacheable,
        Mux(
          burstMode,
          Cat(refillAddress(AddressWidth - 1, BlockSizeLog2), 0.U(BlockSizeLog2.W)),
          if (BlockSizeLog2 > 2)
            Cat(refillAddress(AddressWidth - 1, BlockSizeLog2), refillOffsetSafe,
              0.U(2.W))
          else
            Cat(refillAddress(AddressWidth - 1, BlockSizeLog2), 0.U(BlockSizeLog2.W))
        ),
        refillAddress
      )
      when(cancelUnaccepted) {
        burstMode := false.B
        discard := false.B
        refillWordValid := 0.U
        ARExposed := false.B
        refillState := refillIdle
      }.elsewhen(io.AXI.AR.ARREADY) {
        // 延迟到AR真正接受后才失效被替换行；因此未握手的
        // 错路refill被取消时，不会白白驱逐原有有效行。
        // 已展示但在握手前被kill的owner AR必须完成协议，但其
        // discard事务既不写新line，也不应驱逐原victim。
        val killedOwnerNow = io.kill && stage1Waits
        when(refillCacheable && !discard && !killedOwnerNow) {
          valid(refillIndexSafe) := false.B
          invalidateAliasCopies(refillTag, refillIndexSafe)
        }
        ARExposed := false.B
        refillState := refillResponse
      }.otherwise {
        // 本拍ARVALID已对外呈现但被反压；之后即使kill到来也必须
        // 保持地址和VALID，等握手后再排空响应。
        ARExposed := true.B
      }
    }
    is(refillResponse) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RREADY) {
        val readError = io.AXI.R.RRESP =/= 0.U
        // 非缓存访问只有一个beat；缓存访问则只有请求所在的关键词beat
        // 能把错误归属给当前s1请求，其他beat的错误仅记录为line错误。
        val keywordBeat = !refillCacheable || (refillCount === refillKeywordBeat)

        // 错误必须在discard时也记录（事务仍在排空），但不能把被flush的
        // 响应重新写回s1。sticky状态保证后续成功beat不能重新置valid。
        when(readError) {
          refillError := true.B
          // burst中任一beat失败后，整条line的partial-hit许可立即
          // 撤销；不能再利用早先成功beat的半填数据。
          refillWordValid := 0.U
          when(!discard && stage1Waits && keywordBeat) {
            accessFaultReg := true.B
            accessFaultResponseReg := io.AXI.R.RRESP
            responseDataReg := "h00000013".U // NOP, 不让下游拿到非法指令
          }
        }.elsewhen(!discard) {
          // 关键词拍的数据来源按存储时序处理；Reg 下一次用阵列组合读，
          // SRAM 必须在此锁存。这也避免旧s1_waits覆盖新命中数据。
          when(stage1Waits && !refillCacheable) {
            responseDataReg := nonCacheableReadWord
          }
          storageOnRefillBeat(keywordBeat)
          when(refillCacheable) { // 阵列填充无条件进行, 数据是真实内存内容
            tag(refillIndexSafe) := refillTag
            for (k <- 0 until wordsPerBeat) {
              val wordOffset =
                if (k == 0) refillOffsetSafe
                else beatWordOffset(refillCount, k)
              writeDataWord(
                refillIndexSafe,
                wordOffset,
                io.AXI.R.RDATA(k * 32 + 31, k * 32)
              )
            }
            when(!refillError) {
              refillWordValid := refillWordValid |
                beatWordMask(refillCount)
            }
            // 只有整条line无任何错误时才允许变为valid；当前beat为成功
            // 响应，refill_error覆盖此前已经发生的错误。serial/parallel
            // 扫描尚未覆盖全部 set 时先挂起，扫描最后一拍再补置 valid。
            when(
              refillCount === (beatsPerBlock - 1).U &&
                io.AXI.R.RLAST && !refillError
            ) {
              perfLineFill := true.B
              when(aliasScanStatus.installAllowed) {
                valid(refillIndexSafe) := true.B
              }.otherwise {
                if (deferredAliasInstall) {
                  val (deferredIndex, deferredTag, deferredValid) =
                    aliasDeferred.get
                  // store snoop 与 RLAST 同拍时，这次 refill 的数据早于
                  // store；不能挂成 deferred，否则扫描结束后又会把旧行
                  // 置 valid（上面的 snoop 取消逻辑只看已寄存的 deferred）。
                  val deferredSnooped =
                    if (Snoop && Enable)
                      io.SnoopValid && snoopLineMatch(refillAddress)
                    else false.B
                  when(!deferredSnooped) {
                    deferredValid := true.B
                    deferredIndex := refillIndexSafe
                    deferredTag := refillTag
                  }
                }
              }
            }
          }
        }
        // early restart: 关键词所在拍到手即可响应, 无需等整行填完
        when(!discard && stage1Waits && keywordBeat) {
          stage1Ready := true.B
        }
        when(refillCacheable && burstMode) {
          refillCount := refillCount + 1.U
          when(io.AXI.R.RLAST) {
            // 突发结束(正常结束或被从机提前截断)
            when(discard) {
              discard := false.B
              refillState := refillIdle
            }.elsewhen(refillCount === (beatsPerBlock - 1).U) {
              when(stage1Waits) {
                stage1Ready := true.B
              }
              refillState := refillIdle
            }.otherwise {
              burstMode := false.B // 提前RLAST: 剩余的beat改单拍补取
              refillState := refillRequest
            }
          }.elsewhen(refillCount === (beatsPerBlock - 1).U) {
            // 已收到ARLEN指定的最后一拍却没有RLAST，该line绝不能安装。
            // 继续排空R通道直到迟到的RLAST，避免它被后续refill误收。
            valid(refillIndexSafe) := false.B
            refillError := true.B
            refillWordValid := 0.U
            when(!discard && stage1Waits && !stage1Ready) {
              accessFaultReg := true.B
              accessFaultResponseReg := Mux(readError, io.AXI.R.RRESP, 2.U)
              responseDataReg := "h00000013".U
              stage1Ready := true.B
            }
            refillState := refillDrain
            drainTimer := 0.U
          }
        }.elsewhen(refillCacheable) {
          // 提前RLAST后的补取是独立单拍事务，每一拍也必须以RLAST结束。
          when(io.AXI.R.RLAST) {
            when(discard) {
              discard := false.B
              refillState := refillIdle
            }.elsewhen(refillCount === (beatsPerBlock - 1).U) {
              when(stage1Waits) {
                stage1Ready := true.B
              }
              refillState := refillIdle
            }.otherwise {
              refillCount := refillCount + 1.U
              refillState := refillRequest
            }
          }.otherwise {
            valid(refillIndexSafe) := false.B
            refillError := true.B
            refillWordValid := 0.U
            when(!discard && stage1Waits && !stage1Ready) {
              accessFaultReg := true.B
              accessFaultResponseReg := Mux(readError, io.AXI.R.RRESP, 2.U)
              responseDataReg := "h00000013".U
              stage1Ready := true.B
            }
            refillState := refillDrain
            drainTimer := 0.U
          }
        }.otherwise {
          when(io.AXI.R.RLAST) {
            when(discard) {
              discard := false.B
              refillState := refillIdle
            }.otherwise {
              when(stage1Waits) {
                stage1Ready := true.B
              }
              refillState := refillIdle
            }
          }.otherwise {
            when(!discard && stage1Waits && !stage1Ready) {
              accessFaultReg := true.B
              accessFaultResponseReg := Mux(readError, io.AXI.R.RRESP, 2.U)
              responseDataReg := "h00000013".U
              stage1Ready := true.B
            }
            refillState := refillDrain
            drainTimer := 0.U
          }
        }
      }
    }
    is(refillDrain) {
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RLAST) {
        discard := false.B
        burstMode := false.B
        refillState := refillIdle
      }.elsewhen(drainTimer === DrainTimeout.U) {
        busFault := true.B
        drainTimer := 0.U
        drainRetryTimer := 0.U
        refillState := refillDrainFault
      }.otherwise {
        drainTimer := drainTimer + 1.U
      }
    }
    is(refillDrainFault) {
      // 继续以 RREADY 吸收违规从设备的迟到拍，直到 RLAST 使总线重新同步；
      // 期间新取指由受理路径直接以故障响应结束。通道安静时周期性重试
      // refill（清故障标记回空闲）：从设备恢复后系统自动复原，不必等复位。
      io.AXI.R.RREADY := true.B
      when(io.AXI.R.RVALID && io.AXI.R.RLAST) {
        busFault := false.B
        refillState := refillIdle
      }.elsewhen(drainRetryTimer === DrainTimeout.U && !io.AXI.R.RVALID) {
        busFault := false.B
        drainRetryTimer := 0.U
        refillState := refillIdle
      }.otherwise {
        drainRetryTimer := drainRetryTimer + 1.U
      }
    }
  }
  // kill(重定向/异常): 杀响应槽(s1)。未展示的owner AR可直接取消；
  // 已展示但未握手的owner AR必须排空但不安装，也不驱逐victim；
  // 已握手的refill继续填充。已脱离s1的后台fill不受无关kill影响。
  when(io.kill) {
    // 只有当前s1仍是这个refill的demand owner时，kill才能改变
    // refill。early response已消费后，s1_waits=false，之后无关分支的kill
    // 不能丢弃后台fill。只有已展示但未握手的AR需要sticky discard；
    // 已握手的rf_resp继续完成有效line，避免留下必然miss的空index。
    val KillOwnedExposedAR =
      stage1Waits && refillState === refillRequest && ARExposed
    stage1Valid := false.B
    stage1Waits := false.B
    stage1PhysicalAddressValid := false.B
    storageOnKill()
    when(KillOwnedExposedAR) {
      discard := true.B
      refillWordValid := 0.U
    }
  }
  // flush(fence.i): 代码可能已被改写,清cache行,在飞事务排空丢弃
  // 注意: 已发出的ARVALID绝不能在握手前撤回(否则仲裁器授权后等不到fire会死锁),
  // 必须保持请求直到AR完成,再转入排空丢弃数据
  when(io.flush) {
    stage1Valid := false.B
    stage1Waits := false.B
    stage1PhysicalAddressValid := false.B
    storageOnFlush()
    refillWordValid := 0.U
    if (aliasScanActive) { aliasDeferred.get._3 := false.B }
    valid.foreach(_ := false.B)
    when(refillState === refillRequest) {
      discard := true.B
    }.elsewhen(refillState === refillResponse && !discard) {
      discard := true.B
    }
  }
  // 自修改代码 HARDWARE_SNOOP：store B 完成时失效对应物理行。若该行还有
  // 在飞 refill，必须丢弃这次事务（它的数据可能来自 store 之前），并让
  // 当前 s1 保持未就绪；状态机回 idle 后由 queued_miss 自动重新取指。
  // 这条重取路径不需要 fence.i 的流水线重定向，因此不能直接复用 flush。
  if (Snoop && Enable) {
    val snoopHit = snoopHitVec(io.SnoopAddress) | snoopHitVec(io.SnoopAddressHi)
    for (set <- valid.indices) {
      when(io.SnoopValid && snoopHit(set)) {
        valid(set) := false.B
      }
    }
    val snoopActiveRefill =
      (refillState =/= refillIdle) && snoopLineMatch(refillAddress)
    val snoopLaunchRefill =
      queuedMiss && refillState === refillIdle && snoopLineMatch(fetchAddressReg)
    val snoopRefillMatch = snoopActiveRefill || snoopLaunchRefill
    when(io.SnoopValid && snoopRefillMatch) {
      discard := true.B
      refillWordValid := 0.U
      // 同拍 RLAST 可能刚把旧 refill 安装成 valid；最后写回必须压掉它。
      // 启动拍 ref_addr 尚未更新，目标 index 取当前 s1 的 index。
      valid(Mux(snoopActiveRefill, refillIndexSafe, fetchIndexSafe)) := false.B
    }
    when(io.SnoopValid && snoopStage1LineHit) {
      stage1Hit := false.B
      stage1Ready := false.B
      storageDropStage1Hit()
    }
  }
  // DMA 地址级 snoop：失效所有重叠行；匹配的 refill 丢弃并在排空后由
  // queued_miss 自动重取（此时内存已含 DMA 新数据）。s1 的旧命中同样
  // 撤回，不能把 DMA 之前的指令继续交付。
  if (DMASnoopActive) {
    val DMASnoopHit = DMASnoopHitVec
    for (set <- valid.indices) {
      when(io.DMASnoopValid && DMASnoopHit(set)) {
        valid(set) := false.B
      }
    }
    when(io.DMASnoopValid && !DMASnoopHandled &&
        DMASnoopActiveRefill) {
      discard := true.B
      refillWordValid := 0.U
      valid(refillIndexSafe) := false.B
    }
    // 放在 refill RLAST 之后：同拍 RLAST 可能刚把新行挂到 deferred，
    // 或被本拍 DMA snoop 丢弃，必须立刻取消，扫描结束后不得补装。
    if (aliasScanActive) {
      val (deferredIndex, deferredTag, deferredValid) = aliasDeferred.get
      when(io.DMASnoopValid && deferredValid &&
          dmaLineInRange(deferredTag)) {
        valid(deferredIndex) := false.B
        deferredValid := false.B
      }
      when(DMASnoopFirstPoison) {
        deferredValid := false.B
      }
    }
    when(io.DMASnoopValid && DMASnoopStage1LineHit) {
      stage1Hit := false.B
      stage1Ready := false.B
      storageDropStage1Hit()
    }
  }
  // 目录失效放在所有 valid 置位路径之后：store snoop / DMA 范围失效 /
  // 目录冲突挤出的旧行必须压掉同拍 refill 最后一拍的 valid 写回。
  aliasDirectory.foreach { dir =>
    for (set <- valid.indices) {
      when(dir.io.invalidate(set)) {
        valid(set) := false.B
      }
    }
  }
}
