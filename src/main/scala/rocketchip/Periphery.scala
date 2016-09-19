// See LICENSE for license details.

package rocketchip

import Chisel._
import cde.{Parameters, Field}
import junctions._
import junctions.NastiConstants._
import uncore.tilelink._
import uncore.tilelink2._
import uncore.converters._
import uncore.devices._
import uncore.util._
import rocket.Util._
import rocket.XLen
import scala.math.max
import coreplex._

/** Options for memory bus interface */
object BusType {
  sealed trait EnumVal
  case object AXI extends EnumVal
  case object AHB extends EnumVal
  case object TL  extends EnumVal
  val busTypes = Seq(AXI, AHB, TL)
}

/** Memory channel controls */
case object TMemoryChannels extends Field[BusType.EnumVal]
/** External MMIO controls */
case object NExtMMIOAXIChannels extends Field[Int]
case object NExtMMIOAHBChannels extends Field[Int]
case object NExtMMIOTLChannels  extends Field[Int]
/** External Bus controls */
case object NExtBusAXIChannels extends Field[Int]
/** Async configurations */
case object AsyncBusChannels extends Field[Boolean]
case object AsyncDebugBus extends Field[Boolean]
case object AsyncMemChannels extends Field[Boolean]
case object AsyncMMIOChannels extends Field[Boolean]
/** External address map settings */
case object ExtMMIOPorts extends Field[Seq[AddrMapEntry]]
/** Specifies the size of external memory */
case object ExtMemSize extends Field[Long]
/** Specifies the number of external interrupts */
case object NExtTopInterrupts extends Field[Int]
/** Source of RTC. First bundle is TopIO.extra, Second bundle is periphery.io.extra  **/
case object RTCPeriod extends Field[Int]

object PeripheryUtils {
  def addQueueAXI(source: NastiIO)(implicit p: Parameters) = {
    val sink = Wire(new NastiIO)
    sink.ar  <> Queue(source.ar, 1)
    sink.aw  <> Queue(source.aw, 1)
    sink.w   <> Queue(source.w)
    source.r <> Queue(sink.r)
    source.b <> Queue(sink.b, 1)
    sink
  }
  def convertTLtoAXI(tl: ClientUncachedTileLinkIO)(implicit p: Parameters) = {
    val bridge = Module(new NastiIOTileLinkIOConverter())
    bridge.io.tl <> tl
    addQueueAXI(bridge.io.nasti)
  }
  def convertTLtoAHB(tl: ClientUncachedTileLinkIO, atomics: Boolean)(implicit p: Parameters) = {
    val bridge = Module(new AHBBridge(atomics))
    bridge.io.tl <> tl
    bridge.io.ahb
  }
}

/** Utility trait for quick access to some relevant parameters */
trait HasPeripheryParameters {
  implicit val p: Parameters
  lazy val tMemChannels = p(TMemoryChannels)
  lazy val nMemChannels = p(NMemoryChannels)
  lazy val nMemAXIChannels = if (tMemChannels == BusType.AXI) nMemChannels else 0
  lazy val nMemAHBChannels = if (tMemChannels == BusType.AHB) nMemChannels else 0
  lazy val nMemTLChannels  = if (tMemChannels == BusType.TL)  nMemChannels else 0
  lazy val innerParams = p.alterPartial({ case TLId => "L1toL2" })
  lazy val innerMMIOParams = p.alterPartial({ case TLId => "L2toMMIO" })
  lazy val outermostParams = p.alterPartial({ case TLId => "Outermost" })
  lazy val outermostMMIOParams = p.alterPartial({ case TLId => "MMIO_Outermost" })
}

/////

trait PeripheryDebug extends LazyModule {
  implicit val p: Parameters
}

trait PeripheryDebugBundle {
  implicit val p: Parameters
  val debug_clk = (p(AsyncDebugBus) && !p(IncludeJtagDTM)).option(Clock(INPUT))
  val debug_rst = (p(AsyncDebugBus) && !p(IncludeJtagDTM)).option(Bool(INPUT))
  val debug = (!p(IncludeJtagDTM)).option(new DebugBusIO()(p).flip)
  val jtag = p(IncludeJtagDTM).option(new JTAGIO(true).flip)
}

trait PeripheryDebugModule {
  implicit val p: Parameters
  val outer: PeripheryDebug
  val io: PeripheryDebugBundle
  val coreplex: Coreplex

  if (p(IncludeJtagDTM)) {
    // JtagDTMWithSync is a wrapper which
    // handles the synchronization as well.
    val dtm = Module (new JtagDTMWithSync()(p))
    dtm.io.jtag <> io.jtag.get
    coreplex.io.debug <> dtm.io.debug
  } else {
    coreplex.io.debug <>
      (if (p(AsyncDebugBus)) AsyncDebugBusFrom(io.debug_clk.get, io.debug_rst.get, io.debug.get)
      else io.debug.get)
  }
}

/////

trait PeripheryExtInterrupts extends LazyModule {
  implicit val p: Parameters
  val pInterrupts: RangeManager

  pInterrupts.add("ext", p(NExtTopInterrupts))
}

trait PeripheryExtInterruptsBundle {
  implicit val p: Parameters
  val interrupts = Vec(p(NExtTopInterrupts), Bool()).asInput
}

trait PeripheryExtInterruptsModule {
  implicit val p: Parameters
  val outer: PeripheryExtInterrupts
  val io: PeripheryExtInterruptsBundle
  val coreplex: Coreplex

  {
    val r = outer.pInterrupts.range("ext")
    ((r._1 until r._2) zipWithIndex) foreach { case (c, i) =>
      coreplex.io.interrupts(c) := io.interrupts(i)
    }
  }
}

/////

trait PeripheryMasterMem extends LazyModule {
  implicit val p: Parameters
}

trait PeripheryMasterMemBundle extends HasPeripheryParameters {
  implicit val p: Parameters
  val mem_clk = p(AsyncMemChannels).option(Vec(nMemChannels, Clock(INPUT)))
  val mem_rst = p(AsyncMemChannels).option(Vec(nMemChannels, Bool (INPUT)))
  val mem_axi = Vec(nMemAXIChannels, new NastiIO)
  val mem_ahb = Vec(nMemAHBChannels, new HastiMasterIO)
  val mem_tl = Vec(nMemTLChannels, new ClientUncachedTileLinkIO()(outermostParams))
}

trait PeripheryMasterMemModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val outer: PeripheryMasterMem
  val io: PeripheryMasterMemBundle
  val coreplex: Coreplex

  // Abuse the fact that zip takes the shorter of the two lists
  (io.mem_axi zip coreplex.io.master.mem).zipWithIndex.foreach { case ((axi, mem), idx) =>
    val axi_sync = PeripheryUtils.convertTLtoAXI(mem)(outermostParams)
    axi_sync.ar.bits.cache := CACHE_NORMAL_NOCACHE_BUF
    axi_sync.aw.bits.cache := CACHE_NORMAL_NOCACHE_BUF
    axi <> (
      if (!p(AsyncMemChannels)) axi_sync
      else AsyncNastiTo(io.mem_clk.get(idx), io.mem_rst.get(idx), axi_sync)
    )
  }

  (io.mem_ahb zip coreplex.io.master.mem) foreach { case (ahb, mem) =>
    ahb <> PeripheryUtils.convertTLtoAHB(mem, atomics = false)(outermostParams)
  }

  (io.mem_tl zip coreplex.io.master.mem).zipWithIndex.foreach { case ((tl, mem), idx) =>
    val tl_sync = TileLinkEnqueuer(mem, 2)(outermostParams)
    tl <> (
      if (!p(AsyncMemChannels)) tl_sync
      else AsyncClientUncachedTileLinkTo(io.mem_clk.get(idx), io.mem_rst.get(idx), tl_sync))
  }
}

/////

trait PeripheryMasterMMIO extends LazyModule {
  implicit val p: Parameters
}

trait PeripheryMasterMMIOBundle extends HasPeripheryParameters {
  implicit val p: Parameters
  val mmio_clk = p(AsyncMMIOChannels).option(Vec(p(NExtMMIOAXIChannels), Clock(INPUT)))
  val mmio_rst = p(AsyncMMIOChannels).option(Vec(p(NExtMMIOAXIChannels), Bool (INPUT)))
  val mmio_axi = Vec(p(NExtMMIOAXIChannels), new NastiIO)
  val mmio_ahb = Vec(p(NExtMMIOAHBChannels), new HastiMasterIO)
  val mmio_tl = Vec(p(NExtMMIOTLChannels), new ClientUncachedTileLinkIO()(outermostMMIOParams))
}

trait PeripheryMasterMMIOModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val outer: PeripheryMasterMMIO
  val io: PeripheryMasterMMIOBundle
  val mmioNetwork: TileLinkRecursiveInterconnect

  val mmio_ports = p(ExtMMIOPorts) map { port =>
    TileLinkWidthAdapter(mmioNetwork.port(port.name), "MMIO_Outermost")
  }

  val mmio_axi_start = 0
  val mmio_axi_end   = mmio_axi_start + p(NExtMMIOAXIChannels)
  val mmio_ahb_start = mmio_axi_end
  val mmio_ahb_end   = mmio_ahb_start + p(NExtMMIOAHBChannels)
  val mmio_tl_start  = mmio_ahb_end
  val mmio_tl_end    = mmio_tl_start  + p(NExtMMIOTLChannels)
  require (mmio_tl_end == mmio_ports.size)

  for (i <- 0 until mmio_ports.size) {
    if (mmio_axi_start <= i && i < mmio_axi_end) {
      val idx = i-mmio_axi_start
      val axi_sync = PeripheryUtils.convertTLtoAXI(mmio_ports(i))(outermostMMIOParams)
      io.mmio_axi(idx) <> (
        if (!p(AsyncMMIOChannels)) axi_sync
        else AsyncNastiTo(io.mmio_clk.get(idx), io.mmio_rst.get(idx), axi_sync)
      )
    } else if (mmio_ahb_start <= i && i < mmio_ahb_end) {
      val idx = i-mmio_ahb_start
      io.mmio_ahb(idx) <> PeripheryUtils.convertTLtoAHB(mmio_ports(i), atomics = true)(outermostMMIOParams)
    } else if (mmio_tl_start <= i && i < mmio_tl_end) {
      val idx = i-mmio_tl_start
      val tl_sync = TileLinkEnqueuer(mmio_ports(i), 2)(outermostMMIOParams)
      io.mmio_tl(idx) <> (
        if (!p(AsyncMMIOChannels)) tl_sync
        else AsyncClientUncachedTileLinkTo(io.mmio_clk.get(idx), io.mmio_rst.get(idx), tl_sync))
    } else {
      require(false, "Unconnected external MMIO port")
    }
  }
}

/////

trait PeripherySlave extends LazyModule {
  implicit val p: Parameters
  val pBusMasters: RangeManager

  if (p(NExtBusAXIChannels) > 0) pBusMasters.add("ext", 1) // NExtBusAXIChannels are arbitrated into one TL port
}

trait PeripherySlaveBundle extends HasPeripheryParameters {
  implicit val p: Parameters
  val bus_clk = p(AsyncBusChannels).option(Vec(p(NExtBusAXIChannels), Clock(INPUT)))
  val bus_rst = p(AsyncBusChannels).option(Vec(p(NExtBusAXIChannels), Bool (INPUT)))
  val bus_axi = Vec(p(NExtBusAXIChannels), new NastiIO).flip
}

trait PeripherySlaveModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val outer: PeripherySlave
  val io: PeripherySlaveBundle
  val coreplex: Coreplex

  if (p(NExtBusAXIChannels) > 0) {
    val arb = Module(new NastiArbiter(p(NExtBusAXIChannels)))
    ((io.bus_axi zip arb.io.master) zipWithIndex) foreach { case ((bus, port), idx) =>
      port <> (
        if (!p(AsyncBusChannels)) bus
        else AsyncNastiFrom(io.bus_clk.get(idx), io.bus_rst.get(idx), bus)
      )
    }
    val conv = Module(new TileLinkIONastiIOConverter()(innerParams))
    conv.io.nasti <> arb.io.slave

    val r = outer.pBusMasters.range("ext")
    require(r._2 - r._1 == 1, "RangeManager should return 1 slot")
    coreplex.io.slave(r._1) <> conv.io.tl
  }
}

/////

trait PeripheryCoreplexLocalInterrupter extends LazyModule with HasPeripheryParameters {
  implicit val p: Parameters
  val peripheryBus: TLXbar

  // CoreplexLocalInterrupter must be at least 64b if XLen >= 64
  val beatBytes = (innerMMIOParams(XLen) min 64) / 8
  val clintConfig = CoreplexLocalInterrupterConfig(beatBytes)
  val clint = LazyModule(new CoreplexLocalInterrupter(clintConfig)(innerMMIOParams))
  // The periphery bus is 32-bit, so we may need to adapt its width to XLen
  clint.node := TLFragmenter(TLWidthWidget(peripheryBus.node, 4), beatBytes, 256)
}

trait PeripheryCoreplexLocalInterrupterBundle {
  implicit val p: Parameters
}

trait PeripheryCoreplexLocalInterrupterModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val outer: PeripheryCoreplexLocalInterrupter
  val io: PeripheryCoreplexLocalInterrupterBundle
  val coreplex: Coreplex

  outer.clint.module.io.rtcTick := Counter(p(RTCPeriod)).inc()
  coreplex.io.clint <> outer.clint.module.io.tiles
}

/////

trait PeripheryBootROM extends LazyModule {
  implicit val p: Parameters
  val peripheryBus: TLXbar

  val rom = LazyModule(new TLROM(0x1000, 0x1000, GenerateBootROM(p)) { override def name = "bootrom" })
  rom.node := TLFragmenter(peripheryBus.node, 4, 256)
}

trait PeripheryBootROMBundle {
  implicit val p: Parameters
}

trait PeripheryBootROMModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val outer: PeripheryBootROM
  val io: PeripheryBootROMBundle
}

/////

trait PeripheryTestRAM extends LazyModule {
  implicit val p: Parameters
  val peripheryBus: TLXbar

  val ramBase = 0x52000000
  val ramSize = 0x1000

  val sram = LazyModule(new TLRAM(AddressSet(ramBase, ramSize-1)) { override def name = "testram" })
  sram.node := TLFragmenter(peripheryBus.node, 4, 256)
}

trait PeripheryTestRAMBundle {
  implicit val p: Parameters
}

trait PeripheryTestRAMModule extends HasPeripheryParameters {
  implicit val p: Parameters
  val outer: PeripheryTestRAM
}

/////

trait PeripheryTestBusMaster extends LazyModule {
  implicit val p: Parameters
  val peripheryBus: TLXbar

  val fuzzer = LazyModule(new TLFuzzer(5000))
  peripheryBus.node := fuzzer.node
}

trait PeripheryTestBusMasterBundle {
  implicit val p: Parameters
}

trait PeripheryTestBusMasterModule {
  implicit val p: Parameters
  val outer: PeripheryTestBusMaster
}
