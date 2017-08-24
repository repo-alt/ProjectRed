package mrtjp.projectred.fabrication

import codechicken.lib.data.{MCDataInput, MCDataOutput}
import codechicken.lib.packet.PacketCustom
import mrtjp.core.vec.{Point, Size}
import mrtjp.projectred.ProjectRedCore.log
import net.minecraft.item.ItemStack
import net.minecraft.nbt.{NBTTagCompound, NBTTagList}
import net.minecraft.world.World

import scala.collection.mutable.{Map => MMap}

trait IICTileEditorNetwork
{
    def getIC:ICTileMapEditor
    def getEditorWorld:World

    def getICStreamOf(key:Int):MCDataOutput
    def getTileStream(pos:Point):MCDataOutput

    def isRemote:Boolean
    def markSave()
}

trait TICTileEditorNetwork extends IICTileEditorNetwork
{
    private var editorStream:PacketCustom = null
    private var tileStream:PacketCustom = null

    def createTileStream():PacketCustom
    def sendTileStream(out:PacketCustom)
    override def getTileStream(pos:Point):MCDataOutput =
    {
        if (tileStream == null) tileStream = createTileStream()

        val tile = getIC.getTile(pos)
        tileStream.writeByte(tile.id)
        tileStream.writeByte(pos.x).writeByte(pos.y)

        tileStream
    }
    def flushTileStream()
    {
        if (tileStream != null) {
            tileStream.writeByte(255)//terminator
            sendTileStream(tileStream.compress())
            tileStream = null
        }
    }
    def readTileStream(in:MCDataInput)
    {
        try {
            var id = in.readUByte()
            while (id != 255) {
                val p = Point(in.readUByte(), in.readUByte())
                var tile = getIC.getTile(p)
                if (tile == null || tile.id != id) {
                    log.error("client tile stream couldnt find tile "+p)
                    tile = ICTile.createTile(id)
                }
                tile.read(in)
                id = in.readUByte()
            }
        }
        catch {
            case ex:IndexOutOfBoundsException =>
                log.error("tile stream failed to be read.")
                ex.printStackTrace()
        }
    }

    def createEditorStream():PacketCustom
    def sendEditorStream(out:PacketCustom)

    override def getICStreamOf(key:Int):MCDataOutput =
    {
        if (editorStream == null) editorStream = createEditorStream()
        editorStream.writeByte(key)
        editorStream
    }
    def flushICStream()
    {
        if (editorStream != null) {
            editorStream.writeByte(255) //terminator
            sendEditorStream(editorStream.compress())
            editorStream = null
        }
    }
    def readICStream(in:MCDataInput)
    {
        try {
            var id = in.readUByte()
            while (id != 255) {
                getIC.read(in, id)
                id = in.readUByte()
            }
        } catch {
            case ex:IndexOutOfBoundsException =>
                log.error("Tile Map stream failed to be read")
        }
    }
}

class ICTileMapContainer extends ISETileMap
{
    override val tiles = MMap[(Int, Int), ICTile]()

    var tilesLoadedDelegate = {() => ()}

    var name = "untitled"

    var size = Size.zeroSize

    def isEmpty = size == Size.zeroSize

    def nonEmpty = !isEmpty

    def assertCoords(x:Int, y:Int)
    {
        if (!(0 until size.width contains x) || !(0 until size.height contains y))
            throw new IndexOutOfBoundsException("Tile Map does not contain "+Point(x, y))
    }

    def saveTiles(tag:NBTTagCompound)
    {
        tag.setString("name", name)
        tag.setByte("sw", size.width.toByte)
        tag.setByte("sh", size.height.toByte)

        val tagList = new NBTTagList
        for (tile <- tiles.values) {
            val tileTag = new NBTTagCompound
            tileTag.setByte("id", tile.id.toByte)
            tileTag.setByte("xpos", tile.pos.x.toByte)
            tileTag.setByte("ypos", tile.pos.y.toByte)
            tile.save(tileTag)
            tagList.appendTag(tileTag)
        }
        tag.setTag("tiles", tagList)
    }

    def loadTiles(tag:NBTTagCompound)
    {
        name = tag.getString("name")
        size = Size(tag.getByte("sw")&0xFF, tag.getByte("sh")&0xFF)

        val tileList = tag.getTagList("tiles", 10)
        for(i <- 0 until tileList.tagCount) {
            val tileTag = tileList.getCompoundTagAt(i)
            val tile = ICTile.createTile(tileTag.getByte("id")&0xFF)
            val x = tileTag.getByte("xpos")&0xFF
            val y = tileTag.getByte("ypos")&0xFF
            tile.bindTileMap(this)
            tile.bindPos(Point(x, y))
            tiles += (x, y) -> tile
            tile.load(tileTag)
        }

        tilesLoadedDelegate()
    }

    def getTile(x:Int, y:Int):ICTile = tiles.getOrElse((x, y), null)

    def getTile(p:Point):ICTile = getTile(p.x, p.y)
}

class ICTileMapEditor(val network:IICTileEditorNetwork) extends IICSimEngineContainerDelegate
{
    val tileMapContainer = new ICTileMapContainer

    var simEngineContainer = new ICSimEngineContainer
    var simNeedsRefresh = true

    var lastWorldTime = -1L

    private var scheduledTicks = MMap[Point, Long]()

    tileMapContainer.tilesLoadedDelegate = {() =>
        simNeedsRefresh = true
        for (tile <- tileMapContainer.tiles.values)
            tile.bindEditor(this)
    }

    def size = tileMapContainer.size
    def name = tileMapContainer.name

    def save(tag:NBTTagCompound)
    {
        tileMapContainer.saveTiles(tag)
        simEngineContainer.saveSimState(tag)
    }

    def load(tag:NBTTagCompound)
    {
        clear()
        tileMapContainer.loadTiles(tag)

        simEngineContainer.propagateSilently = true
        recompileSchematic()
        simEngineContainer.loadSimState(tag)
        simEngineContainer.propagateSilently = false
    }

    def writeDesc(out:MCDataOutput)
    {
        out.writeString(tileMapContainer.name)
        out.writeByte(tileMapContainer.size.width).writeByte(tileMapContainer.size.height)
        for (i <- 0 until 4) out.writeInt(simEngineContainer.iostate(i))
        simEngineContainer.logger.writeLog(out)

        for (((x, y), tile) <- tileMapContainer.tiles) {
            out.writeByte(tile.id)
            out.writeByte(x).writeByte(y)
            tile.writeDesc(out)
        }
        out.writeByte(255)
    }

    def readDesc(in:MCDataInput)
    {
        clear()
        tileMapContainer.name = in.readString()
        tileMapContainer.size = Size(in.readUByte(), in.readUByte())
        for (i <- 0 until 4) simEngineContainer.iostate(i) = in.readInt()
        simEngineContainer.logger.readLog(in)

        var id = in.readUByte()
        while (id != 255) {
            val tile = ICTile.createTile(id)
            setTile_do(Point(in.readUByte(), in.readUByte()), tile)
            tile.readDesc(in)
            id = in.readUByte()
        }
    }

    def read(in:MCDataInput, key:Int) = key match
    {
        case 0 => readDesc(in)
        case 1 =>
            val tile = ICTile.createTile(in.readUByte())
            setTile_do(Point(in.readUByte(), in.readUByte()), tile)
            tile.readDesc(in)
        case 2 => removeTile(Point(in.readUByte(), in.readUByte()))
        case 3 => TileEditorOp.getOperation(in.readUByte()).readOp(this, in)
        case 4 => getTile(Point(in.readUByte(), in.readUByte())) match {
            case g:TClientNetICTile => g.readClientPacket(in)
            case _ => log.error("Server IC stream received invalid client packet")
        }
        case 5 =>
            for (r <- 0 until 4)
                simEngineContainer.iostate(r) = in.readInt()
        case 6 => simEngineContainer.setInput(in.readUByte(), in.readShort())//TODO remove? not used...
        case 7 => simEngineContainer.setOutput(in.readUByte(), in.readShort()) //TODO remove? not used...
        case 8 => simEngineContainer.logger.readLog(in)
        case _ =>
    }

    def sendTileAdded(tile:ICTile)
    {
        val out = network.getICStreamOf(1)
        out.writeByte(tile.id)
        out.writeByte(tile.pos.x).writeByte(tile.pos.y)
        tile.writeDesc(out)
    }

    def sendRemoveTile(pos:Point)
    {
        network.getICStreamOf(2).writeByte(pos.x).writeByte(pos.y)
    }

    def sendOpUse(op:TileEditorOp, start:Point, end:Point) =
    {
        if (op.checkOp(this, start, end)) {
            op.writeOp(this, start, end, network.getICStreamOf(3).writeByte(op.id))
            true
        }
        else false
    }

    def sendClientPacket(tile:TClientNetICTile, writer:MCDataOutput => Unit)
    {
        val s = network.getICStreamOf(4).writeByte(tile.pos.x).writeByte(tile.pos.y)
        writer(s)
    }

    def sendIOUpdate()
    {
        val stream = network.getICStreamOf(5)
            for (r <- 0 until 4)
                stream.writeInt(simEngineContainer.iostate(r))
    }

    def sendInputUpdate(r:Int) //TODO Remove?
    {
        network.getICStreamOf(6).writeByte(r).writeShort(simEngineContainer.iostate(r)&0xFFFF)
    }

    def sendOutputUpdate(r:Int) //TODO Remove?
    {
        network.getICStreamOf(7).writeByte(r).writeShort(simEngineContainer.iostate(r)>>>16)
    }

    def sendCompileLog()
    {
        simEngineContainer.logger.writeLog(network.getICStreamOf(8))
    }

    def clear()
    {
        tileMapContainer.tiles.values.foreach{_.unbind()}//remove references
        tileMapContainer.tiles.clear()
        scheduledTicks = MMap()
        tileMapContainer.name = "untitled"
        tileMapContainer.size = Size.zeroSize
        for (i <- 0 until 4) simEngineContainer.iostate(i) = 0
        simNeedsRefresh = true
    }

    def isEmpty = tileMapContainer.isEmpty
    def nonEmpty = tileMapContainer.nonEmpty

    def tick()
    {
        //Update tiles as needed
        val t = network.getEditorWorld.getTotalWorldTime
        var rem = Seq.newBuilder[Point]
        for((p, st) <- scheduledTicks) if(st >= t) {
            getTile(p).scheduledTick()
            rem += p
        }
        rem.result().foreach(scheduledTicks.remove)

        //Tick tiles
        for(tile <- tileMapContainer.tiles.values) tile.update()

        //Rebuild circuit if needed
        if (simNeedsRefresh)
            recompileSchematic()

        //Tick Simulation time
        simEngineContainer.advanceTime(if (lastWorldTime >= 0) t-lastWorldTime else 1) //if first tick, advance 1 tick only
        simEngineContainer.repropagate()
        lastWorldTime = t
    }

    def setTile(pos:Point, tile:ICTile)
    {
        setTile_do(pos, tile)
        tile.onAdded()
        if (!network.isRemote) {
            sendTileAdded(tile)
            markSchematicChanged()
        }
    }
    private def setTile_do(pos:Point, tile:ICTile)
    {
        tileMapContainer.assertCoords(pos.x, pos.y)
        tile.bindEditor(this)
        tile.bindPos(pos)
        tileMapContainer.tiles += (pos.x, pos.y) -> tile
    }

    def getTile(pos:Point):ICTile = tileMapContainer.getTile(pos.x, pos.y)

    def removeTile(pos:Point)
    {
        tileMapContainer.assertCoords(pos.x, pos.y)
        val tile = getTile(pos)
        if (tile != null) {
            if (!network.isRemote) {
                sendRemoveTile(pos)
                markSchematicChanged()
            }
            tileMapContainer.tiles.remove((pos.x, pos.y))
            tile.onRemoved()
            tile.unbind()
        }
    }

    def notifyNeighbor(pos:Point)
    {
        val tile = getTile(pos)
        if (tile != null) tile.onNeighborChanged()
    }

    def notifyNeighbors(pos:Point, mask:Int)
    {
        for(r <- 0 until 4) if ((mask&1<<r) != 0) {
            val tile = getTile(pos.offset(r))
            if (tile != null) tile.onNeighborChanged()
        }
    }

    def scheduleTick(pos:Point, ticks:Int){scheduledTicks += pos -> (network.getEditorWorld.getTotalWorldTime+ticks)}

    def markSchematicChanged()
    {
        simNeedsRefresh = true
    }

    def recompileSchematic()
    {
        simNeedsRefresh = false
        simEngineContainer.delegate = this
        simEngineContainer.recompileSimulation(tileMapContainer)
        simEngineContainer.propagateAll()
    }


    override def registersDidChange(registers:Set[Int])
    {
        for (tile <- tileMapContainer.tiles.values)
            tile.onRegistersChanged(registers)
    }

    override def ioRegistersDidChange()
    {
        sendIOUpdate()
    }

    override def logDidChange()
    {
        sendCompileLog()
    }
}